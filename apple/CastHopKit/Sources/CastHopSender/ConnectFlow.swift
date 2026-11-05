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

    public var id: String { "\(endpoint)" }

    public init(name: String, endpoint: NWEndpoint, fingerprintPrefix: Data, kind: String? = nil) {
        self.name = name
        self.endpoint = endpoint
        self.fingerprintPrefix = fingerprintPrefix
        self.kind = kind
    }
}

/// Finds receivers with Bonjour. Callbacks run on `queue`.
public final class ReceiverBrowser: @unchecked Sendable {
    private let browser: NWBrowser
    private let queue = DispatchQueue(label: "casthop.browser")

    /// The current list after each change.
    public var onChange: (([DiscoveredReceiver]) -> Void)?

    public init() {
        let parameters = NWParameters()
        parameters.includePeerToPeer = false
        browser = NWBrowser(for: .bonjourWithTXTRecord(type: DiscoveryTxt.serviceType, domain: nil), using: parameters)
    }

    public func start() {
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
        return DiscoveredReceiver(name: txt.name ?? instanceName(result.endpoint), endpoint: result.endpoint, fingerprintPrefix: txt.fingerprintPrefix,
                                  kind: txt.kind)
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
    case remembered(RememberedReceiver)
}

/// Finding the receiver and authenticating, with the failure already put in the teacher's terms.
public enum ConnectFlow {
    private static let lookupTimeoutMs = 3000

    private static func endpoint(for target: ConnectTarget) async -> Result<ReceiverEndpoint, MirrorFailure> {
        func direct(hosts: [String], port: UInt16, fingerprint: Data) async -> Result<ReceiverEndpoint, MirrorFailure>? {
            guard !hosts.isEmpty else { return nil }
            switch await Reachability.firstReachable(hosts: hosts, port: port) {
            case .reachable(let host): return .success(ReceiverEndpoint(host: host, port: port, fingerprint: fingerprint))
            case .unreachable(let refused): return refused ? .failure(.refused) : nil
            }
        }
        switch target {
        case .qr(let qr):
            return await direct(hosts: qr.hosts, port: qr.port, fingerprint: qr.certFingerprint) ?? .failure(.unreachable)
        case .discovered(let found, _):
            return .success(ReceiverEndpoint(endpoint: found.endpoint, fingerprint: nil, fingerprintPrefix: found.fingerprintPrefix))
        case .remembered(let r):
            if let result = await direct(hosts: r.hosts, port: r.port, fingerprint: r.fingerprint) {
                if case .success = result { return result }
            }
            // The address may have changed since the pairing.
            if let found = await ReceiverBrowser.find(fingerprint: r.fingerprint, timeoutMs: lookupTimeoutMs) {
                return .success(ReceiverEndpoint(endpoint: found.endpoint, fingerprint: r.fingerprint))
            }
            return .failure(.unreachable)
        }
    }

    /// `store` loses a pairing the receiver no longer knows.
    public static func open(target: ConnectTarget, device: DeviceInfo, requestPairing: Bool, store: SharedStore,
                            onPending: @escaping @Sendable (UInt32) -> Void = { _ in }) async -> Result<Session, MirrorFailure> {
        let endpoint: ReceiverEndpoint
        switch await self.endpoint(for: target) {
        case .success(let e): endpoint = e
        case .failure(let failure): return .failure(failure)
        }
        let credentials: Credentials
        switch target {
        case .qr(let qr): credentials = .qrToken(qr.token)
        case .discovered(_, let pin): credentials = .pin(pin)
        case .remembered(let r): credentials = .paired(keyId: r.keyId, pairKey: r.pairKey)
        }
        do {
            let outcome = try await SenderClient.hello(endpoint: endpoint, credentials: credentials, device: device,
                                                       requestPairing: requestPairing, onPending: onPending)
            switch outcome {
            case .accepted(let session):
                return .success(session)
            case .rejected(let reason, _):
                guard reason == RejectReason.authFailed else { return .failure(.from(rejectReason: reason)) }
                switch target {
                case .qr: return .failure(.authFailed)
                case .discovered: return .failure(.wrongPin)
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
        session.control.send(.bye(reason: ByeReason.userStop))
        session.control.close()
        guard let keyId = welcome.keyId, let pairKey = welcome.pairKey else { return .failure(.incompatible) }
        let hosts: [String]
        let port: UInt16
        if case .qr(let qr) = target {
            hosts = qr.hosts
            port = qr.port
        } else {
            hosts = remote.map { [$0.host] } ?? []
            port = remote?.port ?? 47800
        }
        let receiver = RememberedReceiver(name: welcome.receiverName, hosts: hosts, port: port, fingerprint: session.fingerprint,
                                          keyId: keyId, pairKey: pairKey, lastUsedMs: nowMs)
        store.save(receiver)
        return .success(receiver)
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
