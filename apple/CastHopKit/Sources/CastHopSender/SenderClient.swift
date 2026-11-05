import CastHopProtocol
import CastHopTransport
import Foundation
import Network

/// Where a receiver is. `fingerprint` comes from the QR or a stored pairing. A receiver found by Bonjour only gives
/// `fingerprintPrefix`, compared against the certificate seen in TLS.
public struct ReceiverEndpoint: Sendable {
    public var endpoint: NWEndpoint
    public var fingerprint: Data?
    public var fingerprintPrefix: Data?

    public init(endpoint: NWEndpoint, fingerprint: Data?, fingerprintPrefix: Data? = nil) {
        self.endpoint = endpoint
        self.fingerprint = fingerprint
        self.fingerprintPrefix = fingerprintPrefix
    }

    public init(host: String, port: UInt16, fingerprint: Data?, fingerprintPrefix: Data? = nil) {
        self.init(endpoint: .hostPort(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port) ?? 47800),
                  fingerprint: fingerprint, fingerprintPrefix: fingerprintPrefix)
    }
}

public enum Credentials: Sendable {
    case qrToken(Data)
    case pin(String)
    case paired(keyId: Data, pairKey: Data)

    var method: UInt8 {
        switch self {
        case .qrToken: return AuthMethod.qrToken
        case .pin: return AuthMethod.pin
        case .paired: return AuthMethod.paired
        }
    }

    var keyId: Data? {
        if case .paired(let keyId, _) = self { return keyId }
        return nil
    }
}

/// What HELLO says about this sender.
public struct DeviceInfo: Sendable {
    public var name: String
    public var deviceId: Data
    public var appVersion: String
    public var videoCodecs: [UInt8]
    public var audioCodecs: [UInt8]?
    public var maxWidth: Int
    public var maxHeight: Int
    public var maxFps: Int

    public init(name: String, deviceId: Data, appVersion: String, videoCodecs: [UInt8], audioCodecs: [UInt8]? = nil,
                maxWidth: Int, maxHeight: Int, maxFps: Int) {
        self.name = name
        self.deviceId = deviceId
        self.appVersion = appVersion
        self.videoCodecs = videoCodecs
        self.audioCodecs = audioCodecs
        self.maxWidth = maxWidth
        self.maxHeight = maxHeight
        self.maxFps = maxFps
    }
}

public enum ClientError: Error, Equatable, Sendable {
    case unexpectedMessage
    case unsupportedVersion
    case attachRejected(status: UInt8)
    case invalidPin
}

/// A connection used for control messages. During a handshake `next` hands out messages; afterwards `onMessage` does.
public final class ControlChannel: @unchecked Sendable {
    public let framed: FramedConnection
    public let fingerprint: Data
    private let lock = NSLock()
    private var inbox: [ControlMessage] = []
    private var closedError: Error?
    private var isClosed = false
    private var waiter: CheckedContinuation<ControlMessage, Error>?
    private var waiterGeneration = 0
    private var handler: ((ControlMessage) -> Void)?
    private var closeHandler: ((Error?) -> Void)?

    init(_ pinned: PinnedConnection) {
        framed = pinned.framed
        fingerprint = pinned.fingerprint
        framed.onFrame = { [weak self] frame, _ in self?.deliver(frame) }
        framed.onClosed = { [weak self] error in self?.closed(error) }
    }

    private func deliver(_ frame: Frame) {
        guard frame.header.type == .control else {
            framed.abort(TransportError.malformed(.unknownFrameType))
            return
        }
        let message: ControlMessage
        do {
            message = try ControlCodec.decode(frame.payload)
        } catch {
            framed.abort(error)
            return
        }
        if case .unknown = message { return }
        lock.lock()
        if let w = waiter {
            waiter = nil
            lock.unlock()
            w.resume(returning: message)
        } else if let h = handler {
            lock.unlock()
            h(message)
        } else {
            inbox.append(message)
            lock.unlock()
        }
    }

    private func closed(_ error: Error?) {
        lock.lock()
        isClosed = true
        closedError = error
        let w = waiter
        waiter = nil
        let h = closeHandler
        closeHandler = nil
        lock.unlock()
        w?.resume(throwing: error ?? TransportError.closed)
        h?(error)
    }

    /// The next message, or an error after `timeoutMs` or when the connection ends.
    public func next(timeoutMs: Int) async throws -> ControlMessage {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<ControlMessage, Error>) in
            lock.lock()
            if !inbox.isEmpty {
                let m = inbox.removeFirst()
                lock.unlock()
                c.resume(returning: m)
                return
            }
            if isClosed {
                let e = closedError
                lock.unlock()
                c.resume(throwing: e ?? TransportError.closed)
                return
            }
            waiter = c
            waiterGeneration += 1
            let generation = waiterGeneration
            lock.unlock()
            framed.queue.asyncAfter(deadline: .now() + .milliseconds(timeoutMs)) { [weak self] in
                guard let self else { return }
                self.lock.lock()
                guard self.waiterGeneration == generation, let w = self.waiter else {
                    self.lock.unlock()
                    return
                }
                self.waiter = nil
                self.lock.unlock()
                w.resume(throwing: TransportError.timeout)
            }
        }
    }

    /// Moves from handshake to steady state. Messages that came early are delivered first.
    public func listen(onMessage: @escaping (ControlMessage) -> Void, onClosed: @escaping (Error?) -> Void) {
        lock.lock()
        let early = inbox
        inbox = []
        handler = onMessage
        let alreadyClosed = isClosed
        let error = closedError
        if !alreadyClosed { closeHandler = onClosed }
        lock.unlock()
        early.forEach(onMessage)
        if alreadyClosed { onClosed(error) }
    }

    public func send(_ msg: ControlMessage) { framed.send(control: msg) }

    /// Sends the last message and closes when it has left.
    public func close() {
        lock.lock()
        handler = nil
        closeHandler = nil
        lock.unlock()
        framed.closeGracefully()
    }

    /// Closes at once, with no flush.
    public func abort() {
        lock.lock()
        handler = nil
        closeHandler = nil
        lock.unlock()
        framed.abort()
    }
}

/// A VIDEO or AUDIO connection.
public final class MediaChannel: @unchecked Sendable {
    private let framed: FramedConnection
    public let kind: UInt8
    private static let writeTimeoutSeconds = 3.0

    init(framed: FramedConnection, kind: UInt8) {
        self.framed = framed
        self.kind = kind
    }

    /// Sends a frame and returns when the network stack has taken it. This wait is the back-pressure: while it lasts,
    /// the send queue fills and starts dropping frames instead of building delay.
    public func send(flags: UInt16, ptsUs: UInt64, payload: Data) throws {
        let done = DispatchSemaphore(value: 0)
        var failure: Error?
        framed.send(type: kind == StreamKind.audio ? .audio : .video, flags: flags, ptsUs: ptsUs, payload: payload) { error in
            failure = error
            done.signal()
        }
        guard done.wait(timeout: .now() + Self.writeTimeoutSeconds) == .success else { throw TransportError.timeout }
        if let failure { throw failure }
    }

    public func close() { framed.abort() }
}

/// An accepted session: the CONTROL connection and what WELCOME said.
public final class Session: @unchecked Sendable {
    public let endpoint: ReceiverEndpoint
    public let control: ControlChannel
    public let welcome: Welcome

    init(endpoint: ReceiverEndpoint, control: ControlChannel, welcome: Welcome) {
        self.endpoint = endpoint
        self.control = control
        self.welcome = welcome
    }

    /// The fingerprint verified in this session, stored after a PIN pairing.
    public var fingerprint: Data { control.fingerprint }
}

public enum HelloOutcome: Sendable {
    case accepted(Session)
    case rejected(reason: UInt8, retryAfterMs: UInt32?)
}

/// Sender side handshakes: HELLO, ATTACH, RESUME (PROTOCOL.md §4, §7.1).
public enum SenderClient {
    private static let firstMessageTimeoutMs = 10_000
    private static let queue = DispatchQueue(label: "casthop.sender.network", qos: .userInteractive)

    /// Asks for a session. If someone else is mirroring, `onPending` runs and the receiver's approval is awaited, for up
    /// to the PENDING_APPROVAL time plus 5 s.
    public static func hello(endpoint: ReceiverEndpoint, credentials: Credentials, device: DeviceInfo,
                             requestPairing: Bool = false,
                             onPending: @Sendable (UInt32) -> Void = { _ in }) async throws -> HelloOutcome {
        let channel = try await open(endpoint)
        do {
            let challenge = try await expectChallenge(channel)
            let clientNonce = Auth.randomBytes(ControlCodec.idSize)
            let secret: Data
            switch credentials {
            case .qrToken(let token): secret = token
            case .pin(let pin):
                guard let s = Auth.pinSecret(pin) else { throw ClientError.invalidPin }
                secret = s
            case .paired(_, let pairKey): secret = pairKey
            }
            let proof = Auth.authProof(secret: secret, authMethod: credentials.method, serverNonce: challenge.serverNonce,
                                       clientNonce: clientNonce, receiverCertFp: channel.fingerprint,
                                       deviceId: device.deviceId)
            channel.send(.hello(Hello(deviceName: device.name, deviceId: device.deviceId, appVersion: device.appVersion,
                                      authMethod: credentials.method, clientNonce: clientNonce, authProof: proof,
                                      keyId: credentials.keyId, videoCodecs: device.videoCodecs,
                                      audioCodecs: device.audioCodecs, maxWidth: UInt16(clamping: device.maxWidth),
                                      maxHeight: UInt16(clamping: device.maxHeight), maxFps: UInt8(clamping: device.maxFps),
                                      requestPairing: requestPairing ? true : nil)))
            var timeoutMs = firstMessageTimeoutMs
            while true {
                switch try await channel.next(timeoutMs: timeoutMs) {
                case .welcome(let w):
                    return .accepted(Session(endpoint: endpoint, control: channel, welcome: w))
                case .reject(let reason, let retry):
                    channel.abort()
                    return .rejected(reason: reason, retryAfterMs: retry)
                case .pendingApproval(let ms):
                    timeoutMs = Int(min(ms, UInt32(Int32.max) - 5_000)) + 5_000
                    onPending(ms)
                default:
                    throw ClientError.unexpectedMessage
                }
            }
        } catch {
            channel.abort()
            throw error
        }
    }

    /// Adds a VIDEO or AUDIO connection to a session.
    public static func attach(session: Session, kind: UInt8, interactiveVideo: Bool = true) async throws -> MediaChannel {
        let pinned = try await PinnedTLS.connect(to: session.endpoint.endpoint, expected: session.fingerprint, queue: queue,
                                                 interactiveVideo: interactiveVideo)
        let channel = ControlChannel(pinned)
        do {
            let challenge = try await expectChallenge(channel)
            let w = session.welcome
            channel.send(.attach(Attach(sessionId: w.sessionId, streamKind: kind,
                                        proof: Auth.attachProof(attachKey: w.attachKey, sessionId: w.sessionId,
                                                                streamKind: kind, serverNonce: challenge.serverNonce))))
            guard case .attachResult(let status) = try await channel.next(timeoutMs: firstMessageTimeoutMs) else {
                throw ClientError.unexpectedMessage
            }
            guard status == 0 else { throw ClientError.attachRejected(status: status) }
            channel.listen(onMessage: { _ in }, onClosed: { _ in })
            return MediaChannel(framed: channel.framed, kind: kind)
        } catch {
            channel.abort()
            throw error
        }
    }

    /// Renews a lost CONTROL connection without losing the session (the RESUME window is 10 s).
    public static func resume(session: Session) async throws -> HelloOutcome {
        var endpoint = session.endpoint
        endpoint.fingerprint = session.fingerprint
        let channel = try await open(endpoint)
        do {
            let challenge = try await expectChallenge(channel)
            let w = session.welcome
            channel.send(.resume(Resume(sessionId: w.sessionId,
                                        proof: Auth.resumeProof(resumeToken: w.resumeToken, sessionId: w.sessionId,
                                                                serverNonce: challenge.serverNonce))))
            switch try await channel.next(timeoutMs: firstMessageTimeoutMs) {
            case .welcome(let welcome):
                return .accepted(Session(endpoint: endpoint, control: channel, welcome: welcome))
            case .reject(let reason, let retry):
                channel.abort()
                return .rejected(reason: reason, retryAfterMs: retry)
            default:
                throw ClientError.unexpectedMessage
            }
        } catch {
            channel.abort()
            throw error
        }
    }

    private static func open(_ endpoint: ReceiverEndpoint) async throws -> ControlChannel {
        let pinned = try await PinnedTLS.connect(to: endpoint.endpoint, expected: endpoint.fingerprint, queue: queue)
        if let prefix = endpoint.fingerprintPrefix, !pinned.fingerprint.starts(with: prefix) {
            pinned.framed.abort()
            throw TransportError.fingerprintMismatch(expected: prefix, actual: pinned.fingerprint)
        }
        return ControlChannel(pinned)
    }

    private static func expectChallenge(_ channel: ControlChannel) async throws -> Challenge {
        guard case .challenge(let c) = try await channel.next(timeoutMs: firstMessageTimeoutMs) else {
            throw ClientError.unexpectedMessage
        }
        guard c.minVersion <= 1, c.maxVersion >= 1 else { throw ClientError.unsupportedVersion }
        return c
    }
}
