import CastHopProtocol
import Foundation
import Network
import Security

/// TLS settings shared by both ends (PROTOCOL.md §2): TLS 1.3 only, ALPN `yansit/1`.
public enum TLSSettings {
    public static let alpn = "yansit/1"

    static func base() -> NWProtocolTLS.Options {
        let tls = NWProtocolTLS.Options()
        let sec = tls.securityProtocolOptions
        sec_protocol_options_set_min_tls_protocol_version(sec, .TLSv13)
        sec_protocol_options_set_max_tls_protocol_version(sec, .TLSv13)
        sec_protocol_options_add_tls_application_protocol(sec, alpn)
        return tls
    }

    static func tcp(connectTimeoutSeconds: Int) -> NWProtocolTCP.Options {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = false
        if connectTimeoutSeconds > 0 { tcp.connectionTimeout = connectTimeoutSeconds }
        return tcp
    }

    /// Listener parameters for a receiver.
    public static func server(identity: SecIdentity) -> NWParameters {
        let tls = base()
        if let local = sec_identity_create(identity) {
            sec_protocol_options_set_local_identity(tls.securityProtocolOptions, local)
        }
        let params = NWParameters(tls: tls, tcp: tcp(connectTimeoutSeconds: 0))
        params.allowLocalEndpointReuse = true
        return params
    }

    /// True if the connection negotiated what the protocol requires.
    public static func negotiatedExpected(_ connection: NWConnection) -> Bool {
        guard let meta = connection.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata else {
            return false
        }
        let sec = meta.securityProtocolMetadata
        guard sec_protocol_metadata_get_negotiated_tls_protocol_version(sec) == .TLSv13,
              let protocolName = sec_protocol_metadata_get_negotiated_protocol(sec) else { return false }
        return String(cString: protocolName) == alpn
    }
}

/// An established TLS connection whose certificate passed the pin check.
public struct PinnedConnection: @unchecked Sendable {
    public let framed: FramedConnection
    /// SHA-256 of the certificate seen on this connection. It goes into the identity proof.
    public let fingerprint: Data
}

/// Client side of the pin check. CA, name and validity are not checked: receivers are self-signed. Only the fingerprint
/// decides. A nil `expected` means first contact over PIN: the caller stores what it saw.
public enum PinnedTLS {
    private final class Outcome: @unchecked Sendable {
        let lock = NSLock()
        var seen: Data?
        var mismatch: TransportError?
        var resumed = false

        /// True for the first caller only.
        func claim() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            if resumed { return false }
            resumed = true
            return true
        }
    }

    public static func connect(to endpoint: NWEndpoint, expected: Data?, queue: DispatchQueue,
                               interactiveVideo: Bool = false, timeoutMs: Int = 8000) async throws -> PinnedConnection {
        let outcome = Outcome()
        let tls = TLSSettings.base()
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, trust, complete in
            let chain = SecTrustCopyCertificateChain(sec_trust_copy_ref(trust).takeRetainedValue()) as? [SecCertificate]
            guard let leaf = chain?.first else {
                complete(false)
                return
            }
            let fp = Auth.sha256(SecCertificateCopyData(leaf) as Data)
            outcome.lock.lock()
            outcome.seen = fp
            if let expected, !Auth.constantTimeEquals(expected, fp) {
                outcome.mismatch = .fingerprintMismatch(expected: expected, actual: fp)
            }
            let accepted = outcome.mismatch == nil
            outcome.lock.unlock()
            complete(accepted)
        }, queue)

        let params = NWParameters(tls: tls, tcp: TLSSettings.tcp(connectTimeoutSeconds: 4))
        if interactiveVideo { params.serviceClass = .interactiveVideo }
        let connection = NWConnection(to: endpoint, using: params)

        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<PinnedConnection, Error>) in
            let fail: @Sendable (Error) -> Void = { error in
                guard outcome.claim() else { return }
                connection.stateUpdateHandler = nil
                connection.forceCancel()
                continuation.resume(throwing: error)
            }
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    guard TLSSettings.negotiatedExpected(connection) else {
                        fail(TransportError.incompatibleReceiver)
                        return
                    }
                    outcome.lock.lock()
                    let fp = outcome.seen
                    outcome.lock.unlock()
                    guard let fp else {
                        fail(TransportError.incompatibleReceiver)
                        return
                    }
                    guard outcome.claim() else { return }
                    let framed = FramedConnection(connection: connection, queue: queue)
                    framed.startReading()
                    continuation.resume(returning: PinnedConnection(framed: framed, fingerprint: fp))
                case .failed(let error):
                    outcome.lock.lock()
                    let mismatch = outcome.mismatch
                    outcome.lock.unlock()
                    fail(mismatch ?? map(error))
                case .waiting(let error):
                    // Transient `.waiting` (ENETUNREACH/EHOSTUNREACH/ARP wake on iPad or cold Broadcast Extension)
                    // recovers automatically as soon as Wi-Fi wakes. Only fail fast on a real peer RST or pin mismatch.
                    outcome.lock.lock()
                    let mismatch = outcome.mismatch
                    outcome.lock.unlock()
                    if let mismatch {
                        fail(mismatch)
                    } else if case .posix(let code) = error, code == .ECONNREFUSED {
                        fail(TransportError.connectFailed(refused: true))
                    }
                case .cancelled:
                    fail(TransportError.closed)
                default:
                    break
                }
            }
            connection.start(queue: queue)
            queue.asyncAfter(deadline: .now() + .milliseconds(timeoutMs)) { fail(TransportError.timeout) }
        }
    }

    public enum ConnectFirstResult: @unchecked Sendable {
        case connected(host: String, connection: PinnedConnection)
        case failed(refused: Bool, fingerprintMismatch: TransportError?, incompatible: Bool)
    }

    private final class FirstRace: @unchecked Sendable {
        let lock = NSLock()
        var pending: Int
        var refused = false
        var mismatch: TransportError?
        var incompatible = false
        var done = false
        var tasks: [Task<Void, Never>] = []
        var continuation: CheckedContinuation<ConnectFirstResult, Never>?

        init(pending: Int) { self.pending = pending }

        func win(host: String, connection: PinnedConnection) -> Bool {
            lock.lock()
            guard !done else {
                lock.unlock()
                return false
            }
            done = true
            let c = continuation
            let running = tasks
            continuation = nil
            lock.unlock()
            running.forEach { $0.cancel() }
            c?.resume(returning: .connected(host: host, connection: connection))
            return true
        }

        func failOne(_ error: Error) {
            lock.lock()
            if case TransportError.fingerprintMismatch = error, mismatch == nil {
                mismatch = error as? TransportError
            } else if case TransportError.incompatibleReceiver = error {
                incompatible = true
            } else if case TransportError.connectFailed(refused: true) = error {
                refused = true
            }
            pending -= 1
            let shouldFinish = pending <= 0 && !done
            let r = refused
            let m = mismatch
            let inc = incompatible
            let c = shouldFinish ? continuation : nil
            if shouldFinish {
                done = true
                continuation = nil
            }
            lock.unlock()
            c?.resume(returning: .failed(refused: r, fingerprintMismatch: m, incompatible: inc))
        }
    }

    /// Races direct TLS 1.3 + fingerprint-pinned connections across all candidate hosts (no raw TCP probe).
    public static func connectFirst(
        hosts: [String],
        port: UInt16,
        expected: Data?,
        expectedPrefix: Data? = nil,
        queue: DispatchQueue,
        timeoutMs: Int = 5000
    ) async -> ConnectFirstResult {
        var unique: [String] = []
        var seen = Set<String>()
        for h in hosts {
            let trimmed = h.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty && seen.insert(trimmed).inserted { unique.append(trimmed) }
        }
        guard !unique.isEmpty, let nwPort = NWEndpoint.Port(rawValue: port) else {
            return .failed(refused: false, fingerprintMismatch: nil, incompatible: false)
        }
        let race = FirstRace(pending: unique.count)
        return await withCheckedContinuation { continuation in
            race.lock.lock()
            race.continuation = continuation
            for host in unique {
                let endpoint = NWEndpoint.hostPort(host: NWEndpoint.Host(host), port: nwPort)
                let t = Task {
                    do {
                        let pinned = try await connect(to: endpoint, expected: expected, queue: queue, timeoutMs: timeoutMs)
                        if let prefix = expectedPrefix, !pinned.fingerprint.starts(with: prefix) {
                            pinned.framed.abort()
                            race.failOne(TransportError.fingerprintMismatch(expected: prefix, actual: pinned.fingerprint))
                            return
                        }
                        if !race.win(host: host, connection: pinned) {
                            pinned.framed.abort()
                        }
                    } catch {
                        race.failOne(error)
                    }
                }
                race.tasks.append(t)
            }
            race.lock.unlock()
        }
    }

    /// Orders candidate IPv4 hosts so addresses on any active local Wi-Fi/Ethernet subnet come first.
    public static func prioritizeHosts(_ hosts: [String]) -> [String] {
        var unique: [String] = []
        var seen = Set<String>()
        for h in hosts {
            let trimmed = h.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty && seen.insert(trimmed).inserted { unique.append(trimmed) }
        }
        guard unique.count > 1 else { return unique }
        let subnets = localIpv4Subnets()
        guard !subnets.isEmpty else { return unique }
        var same: [String] = []
        var rest: [String] = []
        for host in unique {
            if let ip = ipv4Value(host), subnets.contains(where: { ($0.address & $0.mask) == (ip & $0.mask) }) {
                same.append(host)
            } else {
                rest.append(host)
            }
        }
        return same + rest
    }

    private static func localIpv4Subnets() -> [(address: UInt32, mask: UInt32)] {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0 else { return [] }
        defer { freeifaddrs(head) }
        var out: [(UInt32, UInt32)] = []
        var cursor = head
        while let entry = cursor {
            defer { cursor = entry.pointee.ifa_next }
            let flags = Int32(entry.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_RUNNING != 0, flags & IFF_LOOPBACK == 0,
                  let sa = entry.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET),
                  let nm = entry.pointee.ifa_netmask, nm.pointee.sa_family == UInt8(AF_INET) else { continue }
            let ip = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            let mask = nm.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            out.append((ip, mask))
        }
        return out
    }

    private static func ipv4Value(_ text: String) -> UInt32? {
        let parts = text.split(separator: ".")
        guard parts.count == 4 else { return nil }
        var v: UInt32 = 0
        for p in parts {
            guard let n = UInt32(p), n <= 255 else { return nil }
            v = (v << 8) | n
        }
        return v
    }

    private static func map(_ error: NWError) -> TransportError {
        if case .posix(let code) = error, code == .ECONNREFUSED { return .connectFailed(refused: true) }
        return .connectFailed(refused: false)
    }
}

/// Finds which address of a receiver answers (PROTOCOL.md §8: a receiver may have several).
public enum Reachability {
    public enum Result: Equatable, Sendable {
        case reachable(String)
        /// `refused`: at least one address answered with a refusal, so the device is on but the app is not listening.
        case unreachable(refused: Bool)
    }

    private final class Race: @unchecked Sendable {
        let lock = NSLock()
        var pending: Int
        var refused = false
        var done = false
        var connections: [NWConnection] = []
        var continuation: CheckedContinuation<Result, Never>?

        init(pending: Int) { self.pending = pending }

        func finish(_ result: Result) {
            lock.lock()
            guard !done else {
                lock.unlock()
                return
            }
            done = true
            let c = continuation
            let open = connections
            lock.unlock()
            open.forEach { $0.forceCancel() }
            c?.resume(returning: result)
        }

        func failed(refused wasRefused: Bool) {
            lock.lock()
            pending -= 1
            refused = refused || wasRefused
            let last = pending == 0
            let anyRefused = refused
            lock.unlock()
            if last { finish(.unreachable(refused: anyRefused)) }
        }
    }

    public static func firstReachable(hosts: [String], port: UInt16, timeoutMs: Int = 4000) async -> Result {
        guard !hosts.isEmpty, let nwPort = NWEndpoint.Port(rawValue: port) else {
            return .unreachable(refused: false)
        }
        let queue = DispatchQueue(label: "casthop.reachability")
        let race = Race(pending: hosts.count)
        return await withCheckedContinuation { continuation in
            race.continuation = continuation
            for host in hosts {
                let params = NWParameters(tls: nil, tcp: TLSSettings.tcp(connectTimeoutSeconds: 4))
                let c = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: params)
                race.connections.append(c)
                let failedLock = NSLock()
                final class OnceFlag: @unchecked Sendable {
                    var value = false
                }
                let failed = OnceFlag()
                c.stateUpdateHandler = { state in
                    switch state {
                    case .ready:
                        race.finish(.reachable(host))
                    case .failed(let e):
                        failedLock.lock()
                        let already = failed.value
                        failed.value = true
                        failedLock.unlock()
                        guard !already else { return }
                        var refused = false
                        if case .posix(let code) = e, code == .ECONNREFUSED { refused = true }
                        race.failed(refused: refused)
                    case .waiting(let e):
                        if case .posix(let code) = e, code == .ECONNREFUSED {
                            failedLock.lock()
                            let already = failed.value
                            failed.value = true
                            failedLock.unlock()
                            guard !already else { return }
                            race.failed(refused: true)
                        }
                    default:
                        break
                    }
                }
                c.start(queue: queue)
            }
            queue.asyncAfter(deadline: .now() + .milliseconds(timeoutMs + 500)) {
                race.lock.lock()
                let refused = race.refused
                race.lock.unlock()
                race.finish(.unreachable(refused: refused))
            }
        }
    }
}
