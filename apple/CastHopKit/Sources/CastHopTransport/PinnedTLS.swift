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

        let params = NWParameters(tls: tls, tcp: TLSSettings.tcp(connectTimeoutSeconds: 3))
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
                case .failed(let error), .waiting(let error):
                    outcome.lock.lock()
                    let mismatch = outcome.mismatch
                    outcome.lock.unlock()
                    fail(mismatch ?? map(error))
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

    public static func firstReachable(hosts: [String], port: UInt16, timeoutMs: Int = 2000) async -> Result {
        guard !hosts.isEmpty, let nwPort = NWEndpoint.Port(rawValue: port) else {
            return .unreachable(refused: false)
        }
        let queue = DispatchQueue(label: "casthop.reachability")
        let race = Race(pending: hosts.count)
        return await withCheckedContinuation { continuation in
            race.continuation = continuation
            for host in hosts {
                let params = NWParameters(tls: nil, tcp: TLSSettings.tcp(connectTimeoutSeconds: 2))
                let c = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: params)
                race.connections.append(c)
                var failed = false
                c.stateUpdateHandler = { state in
                    switch state {
                    case .ready:
                        race.finish(.reachable(host))
                    case .failed(let e), .waiting(let e):
                        guard !failed else { return }
                        failed = true
                        var refused = false
                        if case .posix(let code) = e, code == .ECONNREFUSED { refused = true }
                        race.failed(refused: refused)
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
