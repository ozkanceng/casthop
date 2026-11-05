import Foundation

let maxStringBytes = 255

/// Writes a control payload. Tags must rise (canonical encoding, PROTOCOL.md §7).
struct TlvWriter {
    private(set) var out = Data()
    private var lastTag = -1

    init(msgType: UInt8, msgFlags: UInt8 = 0) {
        out.append(msgType)
        out.append(msgFlags)
    }

    private mutating func header(_ tag: UInt8, _ length: Int) {
        precondition(Int(tag) > lastTag, "TLV tags must rise")
        lastTag = Int(tag)
        out.append(tag)
        out.appendU16(UInt16(length))
    }

    mutating func u8(_ tag: UInt8, _ v: UInt8) { header(tag, 1); out.append(v) }
    mutating func bool(_ tag: UInt8, _ v: Bool) { u8(tag, v ? 1 : 0) }
    mutating func u16(_ tag: UInt8, _ v: UInt16) { header(tag, 2); out.appendU16(v) }
    mutating func u32(_ tag: UInt8, _ v: UInt32) { header(tag, 4); out.appendU32(v) }
    mutating func u64(_ tag: UInt8, _ v: UInt64) { header(tag, 8); out.appendU64(v) }
    mutating func bytes(_ tag: UInt8, _ v: Data, size: Int) {
        precondition(v.count == size, "field must be \(size) bytes")
        header(tag, size)
        out.append(v)
    }
    mutating func str(_ tag: UInt8, _ v: String) {
        let b = Data(v.utf8)
        precondition(b.count <= maxStringBytes, "string longer than 255 bytes")
        header(tag, b.count)
        out.append(b)
    }
    mutating func list(_ tag: UInt8, _ v: [UInt8]) { header(tag, v.count); out.append(contentsOf: v) }
    mutating func pairs(_ tag: UInt8, _ v: [CodecSupport]) {
        header(tag, v.count * 2)
        for p in v {
            out.append(p.codec)
            out.append(p.flags)
        }
    }
}

/// Parses a control payload, accepting each tag at most once.
struct TlvReader {
    let msgType: UInt8
    let msgFlags: UInt8
    private let fields: [UInt8: [UInt8]]

    init(payload: Data) throws {
        let p = [UInt8](payload)
        if p.isEmpty { throw ProtocolError.emptyControl }
        if p.count < 2 { throw ProtocolError.truncated }
        var fields: [UInt8: [UInt8]] = [:]
        var o = 2
        while o < p.count {
            if p.count - o < 3 { throw ProtocolError.malformedTlv }
            let tag = p[o]
            let length = Int(p[o + 1]) << 8 | Int(p[o + 2])
            o += 3
            if p.count - o < length { throw ProtocolError.malformedTlv }
            if fields[tag] != nil { throw ProtocolError.duplicateTag }
            fields[tag] = Array(p[o..<(o + length)])
            o += length
        }
        msgType = p[0]
        msgFlags = p[1]
        self.fields = fields
    }

    private func fixed(_ tag: UInt8, _ size: Int) throws -> [UInt8]? {
        guard let v = fields[tag] else { return nil }
        if v.count != size { throw ProtocolError.badFieldLength }
        return v
    }

    private func required<T>(_ v: T?) throws -> T {
        guard let v else { throw ProtocolError.missingTag }
        return v
    }

    func u8Opt(_ tag: UInt8) throws -> UInt8? { try fixed(tag, 1)?[0] }
    func u8(_ tag: UInt8) throws -> UInt8 { try required(u8Opt(tag)) }
    func boolOpt(_ tag: UInt8) throws -> Bool? { try u8Opt(tag).map { $0 != 0 } }
    func bool(_ tag: UInt8) throws -> Bool { try u8(tag) != 0 }
    func u16Opt(_ tag: UInt8) throws -> UInt16? {
        try fixed(tag, 2).map { UInt16($0[0]) << 8 | UInt16($0[1]) }
    }
    func u16(_ tag: UInt8) throws -> UInt16 { try required(u16Opt(tag)) }
    func u32Opt(_ tag: UInt8) throws -> UInt32? {
        try fixed(tag, 4).map { $0.reduce(UInt32(0)) { $0 << 8 | UInt32($1) } }
    }
    func u32(_ tag: UInt8) throws -> UInt32 { try required(u32Opt(tag)) }
    func u64Opt(_ tag: UInt8) throws -> UInt64? {
        try fixed(tag, 8).map { $0.reduce(UInt64(0)) { $0 << 8 | UInt64($1) } }
    }
    func u64(_ tag: UInt8) throws -> UInt64 { try required(u64Opt(tag)) }
    func bytesOpt(_ tag: UInt8, _ size: Int) throws -> Data? { try fixed(tag, size).map { Data($0) } }
    func bytes(_ tag: UInt8, _ size: Int) throws -> Data { try required(bytesOpt(tag, size)) }
    func strOpt(_ tag: UInt8) throws -> String? {
        guard let v = fields[tag] else { return nil }
        if v.count > maxStringBytes { throw ProtocolError.stringTooLong }
        guard isValidUtf8(v) else { throw ProtocolError.badUtf8 }
        return String(decoding: v, as: UTF8.self)
    }
    func str(_ tag: UInt8) throws -> String { try required(strOpt(tag)) }
    func listOpt(_ tag: UInt8) -> [UInt8]? { fields[tag] }
    func list(_ tag: UInt8) throws -> [UInt8] { try required(listOpt(tag)) }
    func pairs(_ tag: UInt8) throws -> [CodecSupport] {
        let v = try required(fields[tag])
        if v.count % 2 != 0 { throw ProtocolError.badFieldLength }
        return stride(from: 0, to: v.count, by: 2).map { CodecSupport(codec: v[$0], flags: v[$0 + 1]) }
    }
}

/// Strict UTF-8: no overlong forms, no surrogates, nothing above U+10FFFF.
func isValidUtf8(_ b: [UInt8]) -> Bool {
    var i = 0
    while i < b.count {
        let c = b[i]
        let extra: Int
        var low: UInt8 = 0x80
        var high: UInt8 = 0xBF
        switch c {
        case 0x00...0x7F: extra = 0
        case 0xC2...0xDF: extra = 1
        case 0xE0: extra = 2; low = 0xA0
        case 0xE1...0xEC, 0xEE...0xEF: extra = 2
        case 0xED: extra = 2; high = 0x9F
        case 0xF0: extra = 3; low = 0x90
        case 0xF1...0xF3: extra = 3
        case 0xF4: extra = 3; high = 0x8F
        default: return false
        }
        guard i + extra < b.count else { return false }
        if extra > 0 {
            guard b[i + 1] >= low, b[i + 1] <= high else { return false }
            for k in stride(from: 2, through: extra, by: 1) where b[i + k] & 0xC0 != 0x80 { return false }
        }
        i += extra + 1
    }
    return true
}
