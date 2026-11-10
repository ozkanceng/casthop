import Foundation

/// Frame types (PROTOCOL.md §3).
public enum FrameType: UInt8, Sendable {
    case video = 0x01
    case audio = 0x02
    case control = 0x03

    public var maxPayload: Int {
        switch self {
        case .video: return 8 * 1024 * 1024
        case .audio, .control: return 64 * 1024
        }
    }
}

public enum FrameFlags {
    public static let config: UInt16 = 0x0001
    public static let keyframe: UInt16 = 0x0002
    public static let discontinuity: UInt16 = 0x0004
    /// VIDEO only: the picture is turned 90° clockwise (top edge on the frame's right); the receiver turns it back.
    public static let rotated: UInt16 = 0x0008
}

public struct FrameHeader: Equatable, Sendable {
    public var type: FrameType
    public var flags: UInt16
    public var ptsUs: UInt64
    public var seq: UInt32
    public var length: Int

    public init(type: FrameType, flags: UInt16, ptsUs: UInt64, seq: UInt32, length: Int) {
        self.type = type
        self.flags = flags
        self.ptsUs = ptsUs
        self.seq = seq
        self.length = length
    }

    public var isConfig: Bool { flags & FrameFlags.config != 0 }
    public var isKeyframe: Bool { flags & FrameFlags.keyframe != 0 }
    public var isDiscontinuity: Bool { flags & FrameFlags.discontinuity != 0 }
    public var isRotated: Bool { flags & FrameFlags.rotated != 0 }
}

public struct Frame: Sendable {
    public var header: FrameHeader
    public var payload: Data

    public init(header: FrameHeader, payload: Data) {
        self.header = header
        self.payload = payload
    }
}

public enum Frames {
    public static let magic: UInt16 = 0x5954
    public static let version: UInt8 = 1
    public static let headerSize = 24

    public static func encodeHeader(_ h: FrameHeader) -> Data {
        var out = Data(capacity: headerSize)
        out.appendU16(magic)
        out.append(version)
        out.append(h.type.rawValue)
        out.appendU16(h.flags)
        out.appendU16(0)
        out.appendU64(h.ptsUs)
        out.appendU32(h.seq)
        out.appendU32(UInt32(h.length))
        return out
    }

    public static func encode(type: FrameType, flags: UInt16, ptsUs: UInt64, seq: UInt32, payload: Data) -> Data {
        var out = encodeHeader(FrameHeader(type: type, flags: flags, ptsUs: ptsUs, seq: seq, length: payload.count))
        out.append(payload)
        return out
    }

    public static func decodeHeader(_ bytes: UnsafeRawBufferPointer) throws -> FrameHeader {
        guard bytes.count >= headerSize else { throw ProtocolError.truncated }
        guard readU16(bytes, 0) == magic else { throw ProtocolError.badMagic }
        guard bytes[2] == version else { throw ProtocolError.unsupportedVersion }
        guard let type = FrameType(rawValue: bytes[3]) else { throw ProtocolError.unknownFrameType }
        let length = readU32(bytes, 20)
        guard Int(length) <= type.maxPayload else { throw ProtocolError.frameTooLarge }
        return FrameHeader(type: type, flags: readU16(bytes, 4), ptsUs: readU64(bytes, 8), seq: readU32(bytes, 16),
                           length: Int(length))
    }

    public static func decodeHeader(_ data: Data) throws -> FrameHeader {
        try data.withUnsafeBytes { try decodeHeader($0) }
    }

    static func readU16(_ b: UnsafeRawBufferPointer, _ o: Int) -> UInt16 { UInt16(b[o]) << 8 | UInt16(b[o + 1]) }
    static func readU32(_ b: UnsafeRawBufferPointer, _ o: Int) -> UInt32 {
        UInt32(b[o]) << 24 | UInt32(b[o + 1]) << 16 | UInt32(b[o + 2]) << 8 | UInt32(b[o + 3])
    }
    static func readU64(_ b: UnsafeRawBufferPointer, _ o: Int) -> UInt64 {
        (0..<8).reduce(UInt64(0)) { $0 << 8 | UInt64(b[o + $1]) }
    }
}

/// Incremental frame parser. Feed bytes as they arrive and take complete frames.
/// A bad header throws as soon as all 24 bytes are in, before any payload memory is allocated.
public struct FrameParser {
    private var header = Data(capacity: Frames.headerSize)
    private var current: FrameHeader?
    private var payload = Data()
    private var ready: [Frame] = []

    public init() {}

    public mutating func feed(_ chunk: Data) throws {
        var offset = chunk.startIndex
        while offset < chunk.endIndex {
            if let h = current {
                let take = min(h.length - payload.count, chunk.endIndex - offset)
                payload.append(chunk[offset..<(offset + take)])
                offset += take
            } else {
                let take = min(Frames.headerSize - header.count, chunk.endIndex - offset)
                header.append(chunk[offset..<(offset + take)])
                offset += take
                guard header.count == Frames.headerSize else { break }
                let h = try Frames.decodeHeader(header)
                header.removeAll(keepingCapacity: true)
                current = h
                payload = Data(capacity: h.length)
            }
            finishIfComplete()
        }
        finishIfComplete()
    }

    private mutating func finishIfComplete() {
        guard let h = current, payload.count == h.length else { return }
        ready.append(Frame(header: h, payload: payload))
        current = nil
        payload = Data()
    }

    public mutating func next() -> Frame? { ready.isEmpty ? nil : ready.removeFirst() }

    /// True if the stream stopped at a frame boundary.
    public var atBoundary: Bool { current == nil && header.isEmpty }
}

public extension Data {
    mutating func appendU16(_ v: UInt16) {
        append(UInt8(v >> 8))
        append(UInt8(v & 0xFF))
    }

    mutating func appendU32(_ v: UInt32) {
        for shift in stride(from: 24, through: 0, by: -8) { append(UInt8((v >> UInt32(shift)) & 0xFF)) }
    }

    mutating func appendU64(_ v: UInt64) {
        for shift in stride(from: 56, through: 0, by: -8) { append(UInt8((v >> UInt64(shift)) & 0xFF)) }
    }

    /// Lower-case hex.
    var hex: String {
        var out = ""
        out.reserveCapacity(count * 2)
        let digits = Array("0123456789abcdef")
        for b in self {
            out.append(digits[Int(b >> 4)])
            out.append(digits[Int(b & 0x0F)])
        }
        return out
    }

    init?(hex: String) {
        guard hex.utf8.count % 2 == 0 else { return nil }
        var out = Data(capacity: hex.utf8.count / 2)
        var high: UInt8?
        for c in hex.utf8 {
            let v: UInt8
            switch c {
            case 0x30...0x39: v = c - 0x30
            case 0x61...0x66: v = c - 0x61 + 10
            case 0x41...0x46: v = c - 0x41 + 10
            default: return nil
            }
            if let h = high {
                out.append(h << 4 | v)
                high = nil
            } else {
                high = v
            }
        }
        self = out
    }
}
