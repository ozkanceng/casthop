import Foundation

/// Control message types (PROTOCOL.md §7).
public enum MsgType {
    public static let challenge: UInt8 = 0x00
    public static let hello: UInt8 = 0x01
    public static let welcome: UInt8 = 0x02
    public static let reject: UInt8 = 0x03
    public static let pendingApproval: UInt8 = 0x04
    public static let streamConfig: UInt8 = 0x10
    public static let keyframeRequest: UInt8 = 0x11
    public static let ping: UInt8 = 0x20
    public static let pong: UInt8 = 0x21
    public static let receiverReport: UInt8 = 0x30
    public static let senderStats: UInt8 = 0x31
    public static let pause: UInt8 = 0x40
    public static let unpause: UInt8 = 0x41
    public static let attach: UInt8 = 0x50
    public static let attachResult: UInt8 = 0x51
    public static let resume: UInt8 = 0x60
    public static let bye: UInt8 = 0x70
    /// msg_flags bit 0: a receiver that does not know the type must close.
    public static let flagCritical: UInt8 = 0x01
}

public enum Codec {
    public static let h264: UInt8 = 0x01
    public static let h265: UInt8 = 0x02
    public static let opus: UInt8 = 0x10
    public static let aacLc: UInt8 = 0x11
}

/// Decoder support: `flags` bit 0 hardware, bit 1 preferred.
public struct CodecSupport: Equatable, Sendable {
    public static let flagHardware: UInt8 = 0x01
    public static let flagPreferred: UInt8 = 0x02

    public var codec: UInt8
    public var flags: UInt8

    public init(codec: UInt8, flags: UInt8) {
        self.codec = codec
        self.flags = flags
    }

    public var hardware: Bool { flags & Self.flagHardware != 0 }
    public var preferred: Bool { flags & Self.flagPreferred != 0 }
}

public enum AuthMethod {
    public static let qrToken: UInt8 = 1
    public static let pin: UInt8 = 2
    public static let paired: UInt8 = 3
}

public enum StreamKind {
    public static let video: UInt8 = 1
    public static let audio: UInt8 = 2
}

public enum RejectReason {
    public static let versionUnsupported: UInt8 = 1
    public static let authFailed: UInt8 = 2
    public static let busyDenied: UInt8 = 3
    public static let busyTimeout: UInt8 = 4
    public static let rateLimited: UInt8 = 5
    public static let malformed: UInt8 = 6
    public static let `internal`: UInt8 = 7
    public static let sessionUnknown: UInt8 = 8
    public static let lockedOut: UInt8 = 9
}

public enum KeyframeReason {
    public static let decoderReset: UInt8 = 1
    public static let backlogDropped: UInt8 = 2
    public static let decodeError: UInt8 = 3
    public static let user: UInt8 = 4
}

public enum ByeReason {
    public static let userStop: UInt8 = 1
    public static let projectionStopped: UInt8 = 2
    public static let takeover: UInt8 = 3
    public static let receiverClosing: UInt8 = 4
    public static let error: UInt8 = 5
    public static let timeout: UInt8 = 6
    public static let malformed: UInt8 = 7
}

public struct Challenge: Equatable, Sendable {
    public var minVersion: UInt16
    public var maxVersion: UInt16
    public var serverNonce: Data
    public var receiverName: String?

    public init(minVersion: UInt16, maxVersion: UInt16, serverNonce: Data, receiverName: String? = nil) {
        self.minVersion = minVersion
        self.maxVersion = maxVersion
        self.serverNonce = serverNonce
        self.receiverName = receiverName
    }
}

public struct Hello: Equatable, Sendable {
    public var version: UInt16
    public var deviceName: String
    public var deviceId: Data
    public var appVersion: String
    public var authMethod: UInt8
    public var clientNonce: Data
    public var authProof: Data
    public var keyId: Data?
    public var videoCodecs: [UInt8]
    public var audioCodecs: [UInt8]?
    public var maxWidth: UInt16
    public var maxHeight: UInt16
    public var maxFps: UInt8
    public var requestPairing: Bool?

    public init(version: UInt16 = 1, deviceName: String, deviceId: Data, appVersion: String, authMethod: UInt8,
                clientNonce: Data, authProof: Data, keyId: Data? = nil, videoCodecs: [UInt8],
                audioCodecs: [UInt8]? = nil, maxWidth: UInt16, maxHeight: UInt16, maxFps: UInt8,
                requestPairing: Bool? = nil) {
        self.version = version
        self.deviceName = deviceName
        self.deviceId = deviceId
        self.appVersion = appVersion
        self.authMethod = authMethod
        self.clientNonce = clientNonce
        self.authProof = authProof
        self.keyId = keyId
        self.videoCodecs = videoCodecs
        self.audioCodecs = audioCodecs
        self.maxWidth = maxWidth
        self.maxHeight = maxHeight
        self.maxFps = maxFps
        self.requestPairing = requestPairing
    }
}

public struct Welcome: Equatable, Sendable {
    public var version: UInt16
    public var receiverName: String
    public var sessionId: Data
    public var displayWidth: UInt16
    public var displayHeight: UInt16
    public var displayRefresh: UInt8
    public var videoDecoders: [CodecSupport]
    public var audioDecoders: [CodecSupport]
    public var attachKey: Data
    public var resumeToken: Data
    public var pairKey: Data?
    public var keyId: Data?
    public var audioEnabled: Bool
    /// The receiver turns `FrameFlags.rotated` frames back; written only when true.
    public var rotatedFrames: Bool

    public init(version: UInt16 = 1, receiverName: String, sessionId: Data, displayWidth: UInt16, displayHeight: UInt16,
                displayRefresh: UInt8, videoDecoders: [CodecSupport], audioDecoders: [CodecSupport], attachKey: Data,
                resumeToken: Data, pairKey: Data? = nil, keyId: Data? = nil, audioEnabled: Bool,
                rotatedFrames: Bool = false) {
        self.version = version
        self.receiverName = receiverName
        self.sessionId = sessionId
        self.displayWidth = displayWidth
        self.displayHeight = displayHeight
        self.displayRefresh = displayRefresh
        self.videoDecoders = videoDecoders
        self.audioDecoders = audioDecoders
        self.attachKey = attachKey
        self.resumeToken = resumeToken
        self.pairKey = pairKey
        self.keyId = keyId
        self.audioEnabled = audioEnabled
        self.rotatedFrames = rotatedFrames
    }
}

public struct StreamConfig: Equatable, Sendable {
    public var streamKind: UInt8
    public var codec: UInt8
    public var width: UInt16?
    public var height: UInt16?
    public var fps: UInt8?
    public var bitrateBps: UInt32?
    public var sampleRate: UInt32?
    public var channels: UInt8?
    public var rotationDeg: UInt16?

    public init(streamKind: UInt8, codec: UInt8, width: UInt16? = nil, height: UInt16? = nil, fps: UInt8? = nil,
                bitrateBps: UInt32? = nil, sampleRate: UInt32? = nil, channels: UInt8? = nil,
                rotationDeg: UInt16? = nil) {
        self.streamKind = streamKind
        self.codec = codec
        self.width = width
        self.height = height
        self.fps = fps
        self.bitrateBps = bitrateBps
        self.sampleRate = sampleRate
        self.channels = channels
        self.rotationDeg = rotationDeg
    }
}

public struct ReceiverReport: Equatable, Sendable {
    public var intervalMs: UInt16
    public var framesReceived: UInt32
    public var framesRendered: UInt32
    public var framesDropped: UInt32
    public var bytesReceived: UInt32
    public var c2rAvgUs: UInt32
    public var c2rMinUs: UInt32
    public var c2rMaxUs: UInt32
    public var c2dAvgUs: UInt32
    public var decodeBacklog: UInt16
    public var audioLate: UInt16

    public init(intervalMs: UInt16, framesReceived: UInt32, framesRendered: UInt32, framesDropped: UInt32,
                bytesReceived: UInt32, c2rAvgUs: UInt32, c2rMinUs: UInt32, c2rMaxUs: UInt32, c2dAvgUs: UInt32,
                decodeBacklog: UInt16, audioLate: UInt16) {
        self.intervalMs = intervalMs
        self.framesReceived = framesReceived
        self.framesRendered = framesRendered
        self.framesDropped = framesDropped
        self.bytesReceived = bytesReceived
        self.c2rAvgUs = c2rAvgUs
        self.c2rMinUs = c2rMinUs
        self.c2rMaxUs = c2rMaxUs
        self.c2dAvgUs = c2dAvgUs
        self.decodeBacklog = decodeBacklog
        self.audioLate = audioLate
    }
}

public struct SenderStats: Equatable, Sendable {
    public var bitrateBps: UInt32
    public var fpsX100: UInt16
    public var framesDropped: UInt32
    public var encoderName: String?

    public init(bitrateBps: UInt32, fpsX100: UInt16, framesDropped: UInt32, encoderName: String? = nil) {
        self.bitrateBps = bitrateBps
        self.fpsX100 = fpsX100
        self.framesDropped = framesDropped
        self.encoderName = encoderName
    }
}

public struct Attach: Equatable, Sendable {
    public var sessionId: Data
    public var streamKind: UInt8
    public var proof: Data

    public init(sessionId: Data, streamKind: UInt8, proof: Data) {
        self.sessionId = sessionId
        self.streamKind = streamKind
        self.proof = proof
    }
}

public struct Resume: Equatable, Sendable {
    public var sessionId: Data
    public var proof: Data

    public init(sessionId: Data, proof: Data) {
        self.sessionId = sessionId
        self.proof = proof
    }
}

public enum ControlMessage: Equatable, Sendable {
    case challenge(Challenge)
    case hello(Hello)
    case welcome(Welcome)
    case reject(reason: UInt8, retryAfterMs: UInt32?)
    case pendingApproval(timeoutMs: UInt32)
    case streamConfig(StreamConfig)
    case keyframeRequest(reason: UInt8)
    case ping(pingId: UInt32, t0Us: UInt64, offsetUs: Int64?)
    case pong(pingId: UInt32, t0Us: UInt64, t1Us: UInt64, t2Us: UInt64)
    case receiverReport(ReceiverReport)
    case senderStats(SenderStats)
    case pause
    case unpause
    case attach(Attach)
    case attachResult(status: UInt8)
    case resume(Resume)
    case bye(reason: UInt8)
    /// Unknown and not critical: must be ignored.
    case unknown(msgType: UInt8, msgFlags: UInt8)
}

/// Encodes and decodes control messages as canonical (rising tag) TLV.
public enum ControlCodec {
    public static let idSize = 16
    public static let keySize = 32

    public static func encode(_ msg: ControlMessage) -> Data {
        switch msg {
        case .challenge(let m):
            var w = TlvWriter(msgType: MsgType.challenge)
            w.u16(0x01, m.minVersion)
            w.u16(0x02, m.maxVersion)
            w.bytes(0x03, m.serverNonce, size: idSize)
            if let n = m.receiverName { w.str(0x04, n) }
            return w.out
        case .hello(let m):
            var w = TlvWriter(msgType: MsgType.hello)
            w.u16(0x01, m.version)
            w.str(0x02, m.deviceName)
            w.bytes(0x03, m.deviceId, size: idSize)
            w.str(0x04, m.appVersion)
            w.u8(0x05, m.authMethod)
            w.bytes(0x06, m.clientNonce, size: idSize)
            w.bytes(0x07, m.authProof, size: keySize)
            if let k = m.keyId { w.bytes(0x08, k, size: idSize) }
            w.list(0x09, m.videoCodecs)
            if let a = m.audioCodecs { w.list(0x0A, a) }
            w.u16(0x0B, m.maxWidth)
            w.u16(0x0C, m.maxHeight)
            w.u8(0x0D, m.maxFps)
            if let r = m.requestPairing { w.bool(0x0E, r) }
            return w.out
        case .welcome(let m):
            var w = TlvWriter(msgType: MsgType.welcome)
            w.u16(0x01, m.version)
            w.str(0x02, m.receiverName)
            w.bytes(0x03, m.sessionId, size: idSize)
            w.u16(0x04, m.displayWidth)
            w.u16(0x05, m.displayHeight)
            w.u8(0x06, m.displayRefresh)
            w.pairs(0x07, m.videoDecoders)
            w.pairs(0x08, m.audioDecoders)
            w.bytes(0x09, m.attachKey, size: keySize)
            w.bytes(0x0A, m.resumeToken, size: keySize)
            if let p = m.pairKey { w.bytes(0x0B, p, size: keySize) }
            if let k = m.keyId { w.bytes(0x0C, k, size: idSize) }
            w.bool(0x0D, m.audioEnabled)
            if m.rotatedFrames { w.bool(0x0E, true) }
            return w.out
        case .reject(let reason, let retry):
            var w = TlvWriter(msgType: MsgType.reject)
            w.u8(0x01, reason)
            if let r = retry { w.u32(0x02, r) }
            return w.out
        case .pendingApproval(let timeoutMs):
            var w = TlvWriter(msgType: MsgType.pendingApproval)
            w.u32(0x01, timeoutMs)
            return w.out
        case .streamConfig(let m):
            var w = TlvWriter(msgType: MsgType.streamConfig)
            w.u8(0x01, m.streamKind)
            w.u8(0x02, m.codec)
            if let v = m.width { w.u16(0x03, v) }
            if let v = m.height { w.u16(0x04, v) }
            if let v = m.fps { w.u8(0x05, v) }
            if let v = m.bitrateBps { w.u32(0x06, v) }
            if let v = m.sampleRate { w.u32(0x07, v) }
            if let v = m.channels { w.u8(0x08, v) }
            if let v = m.rotationDeg { w.u16(0x09, v) }
            return w.out
        case .keyframeRequest(let reason):
            var w = TlvWriter(msgType: MsgType.keyframeRequest)
            w.u8(0x01, reason)
            return w.out
        case .ping(let pingId, let t0Us, let offsetUs):
            var w = TlvWriter(msgType: MsgType.ping)
            w.u32(0x01, pingId)
            w.u64(0x02, t0Us)
            if let o = offsetUs { w.u64(0x03, UInt64(bitPattern: o)) }
            return w.out
        case .pong(let pingId, let t0Us, let t1Us, let t2Us):
            var w = TlvWriter(msgType: MsgType.pong)
            w.u32(0x01, pingId)
            w.u64(0x02, t0Us)
            w.u64(0x03, t1Us)
            w.u64(0x04, t2Us)
            return w.out
        case .receiverReport(let m):
            var w = TlvWriter(msgType: MsgType.receiverReport)
            w.u16(0x01, m.intervalMs)
            w.u32(0x02, m.framesReceived)
            w.u32(0x03, m.framesRendered)
            w.u32(0x04, m.framesDropped)
            w.u32(0x05, m.bytesReceived)
            w.u32(0x06, m.c2rAvgUs)
            w.u32(0x07, m.c2rMinUs)
            w.u32(0x08, m.c2rMaxUs)
            w.u32(0x09, m.c2dAvgUs)
            w.u16(0x0A, m.decodeBacklog)
            w.u16(0x0B, m.audioLate)
            return w.out
        case .senderStats(let m):
            var w = TlvWriter(msgType: MsgType.senderStats)
            w.u32(0x01, m.bitrateBps)
            w.u16(0x02, m.fpsX100)
            w.u32(0x03, m.framesDropped)
            if let n = m.encoderName { w.str(0x04, n) }
            return w.out
        case .pause:
            return TlvWriter(msgType: MsgType.pause).out
        case .unpause:
            return TlvWriter(msgType: MsgType.unpause).out
        case .attach(let m):
            var w = TlvWriter(msgType: MsgType.attach)
            w.bytes(0x01, m.sessionId, size: idSize)
            w.u8(0x02, m.streamKind)
            w.bytes(0x03, m.proof, size: keySize)
            return w.out
        case .attachResult(let status):
            var w = TlvWriter(msgType: MsgType.attachResult)
            w.u8(0x01, status)
            return w.out
        case .resume(let m):
            var w = TlvWriter(msgType: MsgType.resume)
            w.bytes(0x01, m.sessionId, size: idSize)
            w.bytes(0x02, m.proof, size: keySize)
            return w.out
        case .bye(let reason):
            var w = TlvWriter(msgType: MsgType.bye)
            w.u8(0x01, reason)
            return w.out
        case .unknown:
            preconditionFailure("an unknown message cannot be encoded")
        }
    }

    public static func decode(_ payload: Data) throws -> ControlMessage {
        let r = try TlvReader(payload: payload)
        switch r.msgType {
        case MsgType.challenge:
            return .challenge(Challenge(minVersion: try r.u16(0x01), maxVersion: try r.u16(0x02),
                                        serverNonce: try r.bytes(0x03, idSize), receiverName: try r.strOpt(0x04)))
        case MsgType.hello:
            return .hello(Hello(version: try r.u16(0x01), deviceName: try r.str(0x02),
                                deviceId: try r.bytes(0x03, idSize), appVersion: try r.str(0x04),
                                authMethod: try r.u8(0x05), clientNonce: try r.bytes(0x06, idSize),
                                authProof: try r.bytes(0x07, keySize), keyId: try r.bytesOpt(0x08, idSize),
                                videoCodecs: try r.list(0x09), audioCodecs: r.listOpt(0x0A),
                                maxWidth: try r.u16(0x0B), maxHeight: try r.u16(0x0C), maxFps: try r.u8(0x0D),
                                requestPairing: try r.boolOpt(0x0E)))
        case MsgType.welcome:
            return .welcome(Welcome(version: try r.u16(0x01), receiverName: try r.str(0x02),
                                    sessionId: try r.bytes(0x03, idSize), displayWidth: try r.u16(0x04),
                                    displayHeight: try r.u16(0x05), displayRefresh: try r.u8(0x06),
                                    videoDecoders: try r.pairs(0x07), audioDecoders: try r.pairs(0x08),
                                    attachKey: try r.bytes(0x09, keySize), resumeToken: try r.bytes(0x0A, keySize),
                                    pairKey: try r.bytesOpt(0x0B, keySize), keyId: try r.bytesOpt(0x0C, idSize),
                                    audioEnabled: try r.bool(0x0D),
                                    rotatedFrames: try r.boolOpt(0x0E) ?? false))
        case MsgType.reject:
            return .reject(reason: try r.u8(0x01), retryAfterMs: try r.u32Opt(0x02))
        case MsgType.pendingApproval:
            return .pendingApproval(timeoutMs: try r.u32(0x01))
        case MsgType.streamConfig:
            return .streamConfig(StreamConfig(streamKind: try r.u8(0x01), codec: try r.u8(0x02),
                                              width: try r.u16Opt(0x03), height: try r.u16Opt(0x04),
                                              fps: try r.u8Opt(0x05), bitrateBps: try r.u32Opt(0x06),
                                              sampleRate: try r.u32Opt(0x07), channels: try r.u8Opt(0x08),
                                              rotationDeg: try r.u16Opt(0x09)))
        case MsgType.keyframeRequest:
            return .keyframeRequest(reason: try r.u8(0x01))
        case MsgType.ping:
            return .ping(pingId: try r.u32(0x01), t0Us: try r.u64(0x02),
                         offsetUs: try r.u64Opt(0x03).map { Int64(bitPattern: $0) })
        case MsgType.pong:
            return .pong(pingId: try r.u32(0x01), t0Us: try r.u64(0x02), t1Us: try r.u64(0x03), t2Us: try r.u64(0x04))
        case MsgType.receiverReport:
            return .receiverReport(ReceiverReport(intervalMs: try r.u16(0x01), framesReceived: try r.u32(0x02),
                                                  framesRendered: try r.u32(0x03), framesDropped: try r.u32(0x04),
                                                  bytesReceived: try r.u32(0x05), c2rAvgUs: try r.u32(0x06),
                                                  c2rMinUs: try r.u32(0x07), c2rMaxUs: try r.u32(0x08),
                                                  c2dAvgUs: try r.u32(0x09), decodeBacklog: try r.u16(0x0A),
                                                  audioLate: try r.u16(0x0B)))
        case MsgType.senderStats:
            return .senderStats(SenderStats(bitrateBps: try r.u32(0x01), fpsX100: try r.u16(0x02),
                                            framesDropped: try r.u32(0x03), encoderName: try r.strOpt(0x04)))
        case MsgType.pause:
            return .pause
        case MsgType.unpause:
            return .unpause
        case MsgType.attach:
            return .attach(Attach(sessionId: try r.bytes(0x01, idSize), streamKind: try r.u8(0x02),
                                  proof: try r.bytes(0x03, keySize)))
        case MsgType.attachResult:
            return .attachResult(status: try r.u8(0x01))
        case MsgType.resume:
            return .resume(Resume(sessionId: try r.bytes(0x01, idSize), proof: try r.bytes(0x02, keySize)))
        case MsgType.bye:
            return .bye(reason: try r.u8(0x01))
        default:
            if r.msgFlags & MsgType.flagCritical != 0 { throw ProtocolError.unknownCriticalMessage }
            return .unknown(msgType: r.msgType, msgFlags: r.msgFlags)
        }
    }
}
