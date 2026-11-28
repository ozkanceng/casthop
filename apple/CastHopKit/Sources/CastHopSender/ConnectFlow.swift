import CastHopProtocol
import CastHopTransport
import Foundation
import Network

/// A receiver found on the network (PROTOCOL.md §8). It gives only a prefix of its fingerprint: the full one is pinned at
/// pairing.
public struct DiscoveredReceiver: Identifiable, Equatable, Sendable {
    public var name: String
    public var endpoint: NWEndpoint
    public var fingerprintPrefix: Data
    public var kind: String?
    /// Optional IPv4 addresses published in the mDNS TXT `ip=` field (immune to `.local` hostname collisions).
    public var hosts: [String]

    public var id: String { "\(endpoint)" }
    public var shortId: String { BoardIdentity.shortId(fingerprintPrefix) }

    public init(name: String, endpoint: NWEndpoint, fingerprintPrefix: Data, kind: String? = nil, hosts: [String] = []) {
        self.name = name
        self.endpoint = endpoint
        self.fingerprintPrefix = fingerprintPrefix
        self.kind = kind
        self.hosts = hosts
    }
}

/// Finds receivers with Bonjour. Callbacks run on `queue`.
public final class ReceiverBrowser: @unchecked Sendable {
    private let browser: NWBrowser
    private let queue = DispatchQueue(label: "casthop.browser")

    /// The current list after each change.
    public var onChange: (([DiscoveredReceiver]) -> Void)?
    /// Called if Bonjour browsing fails (e.g. local network permission denied or multicast unavailable).
    public var onFailed: (() -> Void)?

    public init() {
        let parameters = NWParameters()
        parameters.includePeerToPeer = false
        browser = NWBrowser(for: .bonjourWithTXTRecord(type: DiscoveryTxt.serviceType, domain: nil), using: parameters)
    }

    public func start() {
        browser.stateUpdateHandler = { [weak self] state in
            if case .failed = state {
                self?.onFailed?()
            }
        }
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            let found = results.compactMap(Self.parse).sorted { $0.name < $1.name }
            self?.onChange?(found)
        }
        browser.start(queue: queue)
    }

    public func stop() { browser.cancel() }

    private static func parse(_ result: NWBrowser.Result) -> DiscoveredReceiver? {
        guard case .bonjour(let record) = result.metadata else { return nil }
        guard let txt = DiscoveryTxt.parse(record.dictionary) else { return nil }
        return DiscoveredReceiver(name: txt.name ?? instanceName(result.endpoint), endpoint: result.endpoint,
                                  fingerprintPrefix: txt.fingerprintPrefix, kind: txt.kind, hosts: txt.hosts)
    }

    private static func instanceName(_ endpoint: NWEndpoint) -> String {
        if case .service(let name, _, _, _) = endpoint { return name }
        return "CastHop"
    }

    /// The receiver whose certificate starts with `fingerprint`, if it appears within `timeoutMs`.
    public static func find(fingerprint: Data, timeoutMs: Int) async -> DiscoveredReceiver? {
        let browser = ReceiverBrowser()
        let once = Once<DiscoveredReceiver?>()
        return await withCheckedContinuation { continuation in
            once.continuation = continuation
            browser.onChange = { found in
                if let hit = found.first(where: { fingerprint.starts(with: $0.fingerprintPrefix) }) { once.finish(hit) }
            }
            browser.queue.asyncAfter(deadline: .now() + .milliseconds(timeoutMs)) { once.finish(nil) }
            browser.start()
        }.also { _ in browser.stop() }
    }
}

private final class Once<T>: @unchecked Sendable {
    private let lock = NSLock()
    var continuation: CheckedContinuation<T, Never>?

    func finish(_ value: T) {
        lock.lock()
        let c = continuation
        continuation = nil
        lock.unlock()
        c?.resume(returning: value)
    }
}

private extension Optional {
    func also(_ body: (Self) -> Void) -> Self {
        body(self)
        return self
    }
}

public enum ConnectTarget: Sendable {
    case qr(QrPayload)
    case discovered(DiscoveredReceiver, pin: String)
    case manual(host: String, port: UInt16, pin: String)
    case remembered(RememberedReceiver)
}

/// Finding the receiver and authenticating, with the failure already put in the teacher's terms.
public enum ConnectFlow {
    private static let lookupTimeoutMs = 3500
    private static let connectTimeoutMs = 5000
    private static let transientRetryDelayNs: UInt64 = 350_000_000
    private static let connectQueue = DispatchQueue(label: "casthop.connect.flow", qos: .userInteractive)

    private struct EstablishedLink {
        var endpoint: ReceiverEndpoint
        var connection: PinnedConnection?
        var connectedHost: String?
        var freshHosts: [String]
    }

    private static func connectDirect(
        hosts: [String],
        port: UInt16,
        fingerprint: Data?,
        fingerprintPrefix: Data? = nil
    ) async -> PinnedTLS.ConnectFirstResult {
        let ordered = PinnedTLS.prioritizeHosts(hosts)
        return await PinnedTLS.connectFirst(
            hosts: ordered,
            port: port,
            expected: fingerprint,
            expectedPrefix: fingerprintPrefix,
            queue: connectQueue,
            timeoutMs: connectTimeoutMs
        )
    }

    private static func mapConnectFailure(
        refused: Bool,
        fingerprintMismatch: TransportError?,
        incompatible: Bool,
        manual: Bool = false
    ) -> MirrorFailure {
        if fingerprintMismatch != nil { return .wrongReceiver }
        if incompatible { return .incompatible }
        if refused { return .refused }
        return manual ? .clientIsolation : .unreachable
    }

    /// Establishes a verified TLS link directly (no separate raw TCP probe), falling back to Bonjour on DHCP IP changes
    /// (including `ECONNREFUSED` and `fingerprintMismatch`) and retrying once on cold ARP / Wi-Fi wake timeouts.
    private static func establish(for target: ConnectTarget, store: SharedStore) async -> Result<EstablishedLink, MirrorFailure> {
        switch target {
        case .qr(let qr):
            var first = await connectDirect(hosts: qr.hosts, port: qr.port, fingerprint: qr.certFingerprint)
            if case .failed(let refused, let mismatch, let incompatible) = first,
               !refused, mismatch == nil, !incompatible {
                try? await Task.sleep(nanoseconds: transientRetryDelayNs)
                first = await connectDirect(hosts: qr.hosts, port: qr.port, fingerprint: qr.certFingerprint)
            }
            switch first {
            case .connected(let host, let conn):
                if store.receivers().contains(where: { $0.fingerprint == qr.certFingerprint }) {
                    store.updateHosts(qr.hosts, port: qr.port, fingerprint: qr.certFingerprint, preferredHost: host)
                }
                return .success(EstablishedLink(
                    endpoint: ReceiverEndpoint(host: host, port: qr.port, fingerprint: qr.certFingerprint),
                    connection: conn,
                    connectedHost: host,
                    freshHosts: qr.hosts
                ))
            case .failed(let refused, let mismatch, let incompatible):
                return .failure(mapConnectFailure(refused: refused, fingerprintMismatch: mismatch, incompatible: incompatible))
            }

        case .discovered(let found, _):
            return .success(EstablishedLink(
                endpoint: ReceiverEndpoint(endpoint: found.endpoint, fingerprint: nil, fingerprintPrefix: found.fingerprintPrefix),
                connection: nil,
                connectedHost: nil,
                freshHosts: found.hosts
            ))

        case .manual(let host, let port, _):
            var first = await connectDirect(hosts: [host], port: port, fingerprint: nil)
            if case .failed(let refused, let mismatch, let incompatible) = first,
               !refused, mismatch == nil, !incompatible {
                try? await Task.sleep(nanoseconds: transientRetryDelayNs)
                first = await connectDirect(hosts: [host], port: port, fingerprint: nil)
            }
            switch first {
            case .connected(let connectedHost, let conn):
                return .success(EstablishedLink(
                    endpoint: ReceiverEndpoint(host: connectedHost, port: port, fingerprint: nil),
                    connection: conn,
                    connectedHost: connectedHost,
                    freshHosts: [connectedHost]
                ))
            case .failed(let refused, let mismatch, let incompatible):
                return .failure(mapConnectFailure(refused: refused, fingerprintMismatch: mismatch, incompatible: incompatible, manual: true))
            }

        case .remembered(let r):
            var lastRefused = false
            var lastMismatch: TransportError?
            var lastIncompatible = false

            if !r.hosts.isEmpty {
                switch await connectDirect(hosts: r.hosts, port: r.port, fingerprint: r.fingerprint) {
                case .connected(let host, let conn):
                    return .success(EstablishedLink(
                        endpoint: ReceiverEndpoint(host: host, port: r.port, fingerprint: r.fingerprint),
                        connection: conn,
                        connectedHost: host,
                        freshHosts: r.hosts
                    ))
                case .failed(let refused, let mismatch, let incompatible):
                    lastRefused = refused
                    lastMismatch = mismatch
                    lastIncompatible = incompatible
                    if incompatible { return .failure(.incompatible) }
                }
            }

            // The address may have changed since the pairing (timeout, REFUSED, or another board took the old DHCP IP).
            if let found = await ReceiverBrowser.find(fingerprint: r.fingerprint, timeoutMs: lookupTimeoutMs) {
                if !found.hosts.isEmpty {
                    if case .connected(let host, let conn) = await connectDirect(hosts: found.hosts, port: r.port, fingerprint: r.fingerprint) {
                        store.updateHosts(found.hosts, port: r.port, fingerprint: r.fingerprint, preferredHost: host)
                        return .success(EstablishedLink(
                            endpoint: ReceiverEndpoint(host: host, port: r.port, fingerprint: r.fingerprint),
                            connection: conn,
                            connectedHost: host,
                            freshHosts: found.hosts
                        ))
                    }
                }
                return .success(EstablishedLink(
                    endpoint: ReceiverEndpoint(endpoint: found.endpoint, fingerprint: r.fingerprint),
                    connection: nil,
                    connectedHost: nil,
                    freshHosts: found.hosts
                ))
            }

            // Cold ARP / Wi-Fi power-save wake-up retry once on transient failure.
            if !r.hosts.isEmpty, !lastRefused, lastMismatch == nil, !lastIncompatible {
                try? await Task.sleep(nanoseconds: transientRetryDelayNs)
                if case .connected(let host, let conn) = await connectDirect(hosts: r.hosts, port: r.port, fingerprint: r.fingerprint) {
                    return .success(EstablishedLink(
                        endpoint: ReceiverEndpoint(host: host, port: r.port, fingerprint: r.fingerprint),
                        connection: conn,
                        connectedHost: host,
                        freshHosts: r.hosts
                    ))
                }
            }

            return .failure(mapConnectFailure(refused: lastRefused, fingerprintMismatch: lastMismatch, incompatible: lastIncompatible))
        }
    }

    /// `store` loses a pairing the receiver no longer knows.
    public static func open(target: ConnectTarget, device: DeviceInfo, requestPairing: Bool, store: SharedStore,
                            onPending: @escaping @Sendable (UInt32) -> Void = { _ in }) async -> Result<Session, MirrorFailure> {
        let link: EstablishedLink
        switch await establish(for: target, store: store) {
        case .success(let l): link = l
        case .failure(let failure): return .failure(failure)
        }
        let credentials: Credentials
        switch target {
        case .qr(let qr): credentials = .qrToken(qr.token)
        case .discovered(_, let pin), .manual(_, _, let pin): credentials = .pin(pin)
        case .remembered(let r): credentials = .paired(keyId: r.keyId, pairKey: r.pairKey)
        }
        do {
            var outcome: HelloOutcome
            if let connection = link.connection {
                outcome = try await SenderClient.hello(connection: connection, endpoint: link.endpoint,
                                                       credentials: credentials, device: device,
                                                       requestPairing: requestPairing, onPending: onPending)
            } else {
                outcome = try await SenderClient.hello(endpoint: link.endpoint, credentials: credentials, device: device,
                                                       requestPairing: requestPairing, onPending: onPending)
            }

            // If a scanned QR token rotated or was already spent on an already-remembered board whose TLS fingerprint
            // was just cryptographically verified, seamlessly fall back once to its stored pairing key.
            if case .rejected(let reason, _) = outcome,
               reason == RejectReason.authFailed,
               case .qr(let qr) = target,
               let saved = store.receivers().first(where: { $0.fingerprint == qr.certFingerprint }) {
                if let fallback = try? await SenderClient.hello(
                    endpoint: link.endpoint,
                    credentials: .paired(keyId: saved.keyId, pairKey: saved.pairKey),
                    device: device,
                    requestPairing: false,
                    onPending: onPending
                ), case .accepted = fallback {
                    outcome = fallback
                }
            }

            switch outcome {
            case .accepted(let session):
                if case .remembered(let r) = target {
                    let remote = session.remoteAddress
                    let preferred = remote?.host ?? link.connectedHost
                    let port = remote?.port ?? r.port
                    let freshHosts = link.freshHosts.isEmpty ? (preferred.map { [$0] } ?? []) : link.freshHosts
                    store.updateHosts(freshHosts, port: port, fingerprint: r.fingerprint, preferredHost: preferred)
                }
                return .success(session)
            case .rejected(let reason, _):
                guard reason == RejectReason.authFailed else { return .failure(.from(rejectReason: reason)) }
                switch target {
                case .qr: return .failure(.authFailed)
                case .discovered, .manual: return .failure(.wrongPin)
                case .remembered(let r):
                    store.remove(fingerprint: r.fingerprint)
                    return .failure(.receiverForgot)
                }
            }
        } catch TransportError.fingerprintMismatch {
            return .failure(.wrongReceiver)
        } catch TransportError.incompatibleReceiver {
            return .failure(.incompatible)
        } catch TransportError.malformed, ClientError.unexpectedMessage, ClientError.unsupportedVersion {
            return .failure(.incompatible)
        } catch ClientError.invalidPin {
            return .failure(.wrongPin)
        } catch {
            return .failure(.unreachable)
        }
    }

    /// First contact from the app: authenticates once, keeps the pairing and leaves. The extension then connects with the
    /// pairing key, which is how a broadcast can start without the single-use QR token or PIN.
    public static func pair(target: ConnectTarget, device: DeviceInfo, store: SharedStore, nowMs: Int64,
                            onPending: @escaping @Sendable (UInt32) -> Void = { _ in }) async -> Result<RememberedReceiver, MirrorFailure> {
        let session: Session
        switch await open(target: target, device: device, requestPairing: true, store: store, onPending: onPending) {
        case .success(let s): session = s
        case .failure(let failure): return .failure(failure)
        }
        let welcome = session.welcome
        let remote = session.remoteAddress
        // Wait briefly for BYE to flush so the receiver closes this pairing session cleanly before ReplayKit connects.
        await session.control.sendAndWait(.bye(reason: ByeReason.userStop), timeoutMs: 250)
        session.control.close()

        let existing = store.receivers().first { $0.fingerprint == session.fingerprint }
        let keyId = welcome.keyId ?? existing?.keyId
        let pairKey = welcome.pairKey ?? existing?.pairKey
        guard let keyId, let pairKey else { return .failure(.incompatible) }

        let hosts: [String]
        let port: UInt16
        let kind: String?
        switch target {
        case .qr(let qr):
            hosts = qr.hosts
            port = qr.port
            kind = nil
        case .manual(let host, let p, _):
            hosts = [host]
            port = p
            kind = nil
        case .discovered(let found, _):
            hosts = BoardIdentity.mergeHosts(primary: remote?.host, fresh: found.hosts, existing: [])
            port = remote?.port ?? 47800
            kind = found.kind
        case .remembered(let existingTarget):
            hosts = remote.map { [$0.host] } ?? existingTarget.hosts
            port = remote?.port ?? existingTarget.port
            kind = existingTarget.kind
        }
        let receiver = RememberedReceiver(name: welcome.receiverName, hosts: hosts, port: port, fingerprint: session.fingerprint,
                                          keyId: keyId, pairKey: pairKey, lastUsedMs: nowMs, kind: kind)
        store.save(receiver, preferredHost: remote?.host)
        return .success(store.receivers().first { $0.fingerprint == session.fingerprint } ?? receiver)
    }
}

extension Session {
    /// The IPv4 address the control connection reached.
    public var remoteAddress: (host: String, port: UInt16)? {
        guard case .hostPort(let host, let port)? = control.framed.connection.currentPath?.remoteEndpoint,
              case .ipv4(let address) = host else { return nil }
        let text = "\(address)".split(separator: "%").first.map(String.init) ?? "\(address)"
        return (text, port.rawValue)
    }
}
