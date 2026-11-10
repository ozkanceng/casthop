import CastHopProtocol
import Foundation

/// Unique per TCP connection.
public typealias ConnId = UInt64

/// Abilities announced in WELCOME.
public struct ReceiverInfo: Equatable, Sendable {
    public var name: String
    public var displayWidth: UInt16 = 1920
    public var displayHeight: UInt16 = 1080
    public var displayRefresh: UInt8 = 60
    public var videoDecoders: [CodecSupport] = []
    public var audioDecoders: [CodecSupport] = []
    public var audioEnabled = false
    /// Turned frames (ROTATED) are shown turned back (PROTOCOL.md §11).
    public var rotatedFrames = false

    public init(name: String) { self.name = name }
}

/// Work for the network layer: send in order, then close.
public struct Actions: Sendable {
    public var send: [(ConnId, ControlMessage)] = []
    public var close: [ConnId] = []

    public init() {}

    public mutating func merge(_ other: Actions) {
        send += other.send
        close += other.close
    }

    public var isEmpty: Bool { send.isEmpty && close.isEmpty }
}

/// Single-sender session state machine (PROTOCOL.md §4, §7.1, §11): HELLO, takeover approval, ATTACH, the RESUME window
/// and session end. No network and no clock of its own: each call returns the work to do.
public final class SessionManager {
    public struct Policy: Sendable {
        public var approvalTimeoutMs: Int64
        public var resumeWindowMs: Int64

        public init(approvalTimeoutMs: Int64 = 20_000, resumeWindowMs: Int64 = 10_000) {
            self.approvalTimeoutMs = approvalTimeoutMs
            self.resumeWindowMs = resumeWindowMs
        }
    }

    public enum MediaSlot: Sendable {
        case none
        case video
        case audio
    }

    public struct Session: Sendable {
        public var sessionId: Data
        public var deviceName: String
        public var deviceId: Data
        public var attachKey: Data
        public var resumeToken: Data
        public var control: ConnId?
        public var video: ConnId?
        public var audio: ConnId?
        /// When CONTROL was lost, the start of the RESUME window.
        public var controlLostAtMs: Int64?
    }

    public struct Pending: Sendable {
        public var conn: ConnId
        public var hello: Hello
        public var deadlineMs: Int64
    }

    private let pairing: PairingManager
    public var receiverInfo: ReceiverInfo
    public let policy: Policy
    public private(set) var session: Session?
    public private(set) var pending: Pending?

    public init(pairing: PairingManager, info: ReceiverInfo, policy: Policy = Policy()) {
        self.pairing = pairing
        receiverInfo = info
        self.policy = policy
    }

    public func mediaSlot(of conn: ConnId) -> MediaSlot {
        guard let s = session else { return .none }
        if s.video == conn { return .video }
        if s.audio == conn { return .audio }
        return .none
    }

    public func isControl(_ conn: ConnId) -> Bool { session?.control == conn }

    private static func reject(_ reason: UInt8, retryMs: Int64? = nil) -> ControlMessage {
        let retry = retryMs.flatMap { $0 > 0 ? UInt32(min($0, Int64(UInt32.max))) : nil }
        return .reject(reason: reason, retryAfterMs: retry)
    }

    private func makeWelcome(_ s: Session) -> Welcome {
        Welcome(receiverName: receiverInfo.name, sessionId: s.sessionId, displayWidth: receiverInfo.displayWidth,
                displayHeight: receiverInfo.displayHeight, displayRefresh: receiverInfo.displayRefresh,
                videoDecoders: receiverInfo.videoDecoders, audioDecoders: receiverInfo.audioDecoders,
                attachKey: s.attachKey, resumeToken: s.resumeToken, audioEnabled: receiverInfo.audioEnabled,
                rotatedFrames: receiverInfo.rotatedFrames)
    }

    public func onHello(conn: ConnId, hello: Hello, serverNonce: Data, certFingerprint: Data, nowMs: Int64) -> Actions {
        var a = Actions()
        func refuse(_ m: ControlMessage) -> Actions {
            a.send.append((conn, m))
            a.close.append(conn)
            return a
        }

        if hello.version != 1 { return refuse(Self.reject(RejectReason.versionUnsupported)) }
        // Someone already waits for approval: refuse before spending a credential.
        if pending != nil { return refuse(Self.reject(RejectReason.busyDenied)) }

        let verdict = pairing.verifyHello(hello, serverNonce: serverNonce, certFingerprint: certFingerprint, nowMs: nowMs)
        switch verdict.outcome {
        case .accepted: break
        case .rateLimited: return refuse(Self.reject(RejectReason.rateLimited, retryMs: verdict.retryAfterMs))
        case .lockedOut: return refuse(Self.reject(RejectReason.lockedOut, retryMs: verdict.retryAfterMs))
        case .authFailed, .unsupportedMethod: return refuse(Self.reject(RejectReason.authFailed))
        }

        let byPairKey = verdict.trusted != nil
        if let s = session {
            // The same paired device takes over its own session without approval, for example after an app restart.
            if byPairKey && s.deviceId == hello.deviceId {
                a = closeSession(bye: ByeReason.takeover)
                a.merge(startSession(conn: conn, hello: hello, byPairKey: byPairKey))
                return a
            }
            pending = Pending(conn: conn, hello: hello, deadlineMs: nowMs + policy.approvalTimeoutMs)
            a.send.append((conn, .pendingApproval(timeoutMs: UInt32(policy.approvalTimeoutMs))))
            return a
        }
        return startSession(conn: conn, hello: hello, byPairKey: byPairKey)
    }

    private func startSession(conn: ConnId, hello: Hello, byPairKey: Bool) -> Actions {
        let s = Session(sessionId: Auth.randomBytes(ControlCodec.idSize), deviceName: hello.deviceName,
                        deviceId: hello.deviceId, attachKey: Auth.randomBytes(ControlCodec.keySize),
                        resumeToken: Auth.randomBytes(ControlCodec.keySize), control: conn)
        var w = makeWelcome(s)
        if hello.requestPairing == true && !byPairKey {
            let t = pairing.createPairing(hello)
            w.pairKey = t.pairKey
            w.keyId = t.keyId
        }
        session = s
        var a = Actions()
        a.send.append((conn, .welcome(w)))
        return a
    }

    private func closeSession(bye: UInt8?) -> Actions {
        var a = Actions()
        guard let s = session else { return a }
        session = nil
        if let c = s.control {
            if let bye { a.send.append((c, .bye(reason: bye))) }
            a.close.append(c)
        }
        if let v = s.video { a.close.append(v) }
        if let u = s.audio { a.close.append(u) }
        return a
    }

    /// The result of the approval dialog.
    public func resolvePending(accept: Bool) -> Actions {
        var a = Actions()
        guard let p = pending else { return a }
        pending = nil
        guard accept else {
            a.send.append((p.conn, Self.reject(RejectReason.busyDenied)))
            a.close.append(p.conn)
            return a
        }
        a = closeSession(bye: ByeReason.takeover)
        a.merge(startSession(conn: p.conn, hello: p.hello, byPairKey: p.hello.authMethod == AuthMethod.paired))
        return a
    }

    public func onAttach(conn: ConnId, attach: Attach, serverNonce: Data) -> Actions {
        var a = Actions()
        func result(_ status: UInt8) -> Actions {
            a.send.append((conn, .attachResult(status: status)))
            if status != 0 { a.close.append(conn) }
            return a
        }

        // Without CONTROL (RESUME window) the old attach_key is void: RESUME must come first.
        guard let s = session, s.sessionId == attach.sessionId, s.control != nil else {
            return result(RejectReason.sessionUnknown)
        }
        let expected = Auth.attachProof(attachKey: s.attachKey, sessionId: attach.sessionId,
                                        streamKind: attach.streamKind, serverNonce: serverNonce)
        guard Auth.constantTimeEquals(expected, attach.proof) else { return result(RejectReason.authFailed) }

        if attach.streamKind == StreamKind.video {
            if let old = s.video, old != conn { a.close.append(old) }
            session?.video = conn
        } else if attach.streamKind == StreamKind.audio && receiverInfo.audioEnabled {
            if let old = s.audio, old != conn { a.close.append(old) }
            session?.audio = conn
        } else {
            return result(RejectReason.malformed)
        }
        return result(0)
    }

    public func onResume(conn: ConnId, resume: Resume, serverNonce: Data) -> Actions {
        var a = Actions()
        guard var s = session, s.sessionId == resume.sessionId,
              Auth.constantTimeEquals(Auth.resumeProof(resumeToken: s.resumeToken, sessionId: resume.sessionId,
                                                       serverNonce: serverNonce), resume.proof) else {
            a.send.append((conn, Self.reject(RejectReason.sessionUnknown)))
            a.close.append(conn)
            return a
        }
        // The sender may notice a break before the receiver does: the old CONTROL can still be open.
        if let c = s.control, c != conn { a.close.append(c) }
        if let v = s.video { a.close.append(v) }
        if let u = s.audio { a.close.append(u) }
        s.video = nil
        s.audio = nil
        s.control = conn
        s.controlLostAtMs = nil
        s.attachKey = Auth.randomBytes(ControlCodec.keySize)
        s.resumeToken = Auth.randomBytes(ControlCodec.keySize)
        session = s
        a.send.append((conn, .welcome(makeWelcome(s))))
        return a
    }

    public func onBye(conn: ConnId) -> Actions {
        if isControl(conn) { return closeSession(bye: nil) }
        var a = Actions()
        if pending?.conn == conn { pending = nil }
        a.close.append(conn)
        return a
    }

    public func onConnectionClosed(conn: ConnId, nowMs: Int64) -> Actions {
        var a = Actions()
        if pending?.conn == conn {
            pending = nil
            return a
        }
        guard var s = session else { return a }
        if s.control == conn {
            s.control = nil
            s.controlLostAtMs = nowMs
            if let v = s.video { a.close.append(v) }
            if let u = s.audio { a.close.append(u) }
            s.video = nil
            s.audio = nil
        } else if s.video == conn {
            s.video = nil
        } else if s.audio == conn {
            s.audio = nil
        }
        session = s
        return a
    }

    /// Ends the session from the receiver side.
    public func endSession(bye: UInt8) -> Actions {
        var a = closeSession(bye: bye)
        if let p = pending {
            a.send.append((p.conn, Self.reject(RejectReason.busyDenied)))
            a.close.append(p.conn)
            pending = nil
        }
        return a
    }

    /// Timeouts: approval and the RESUME window.
    public func tick(nowMs: Int64) -> Actions {
        var a = Actions()
        if let p = pending, nowMs >= p.deadlineMs {
            a.send.append((p.conn, Self.reject(RejectReason.busyTimeout)))
            a.close.append(p.conn)
            pending = nil
        }
        if let lost = session?.controlLostAtMs, nowMs - lost >= policy.resumeWindowMs {
            a.merge(closeSession(bye: nil))
        }
        return a
    }
}
