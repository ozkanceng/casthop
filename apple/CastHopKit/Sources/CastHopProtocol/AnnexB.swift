import Foundation

/// Annex-B byte streams (PROTOCOL.md §5) against the length prefixed form VideoToolbox uses.
public enum AnnexB {
    /// Offsets of each NAL unit, start codes excluded.
    public static func nalRanges(_ bytes: UnsafeRawBufferPointer) -> [Range<Int>] {
        var ranges: [Range<Int>] = []
        let n = bytes.count
        var i = 0
        var start: Int?
        while i + 2 < n {
            if bytes[i + 2] > 1 {
                i += 3
            } else if bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 1 {
                if let s = start {
                    var end = i
                    while end > s && bytes[end - 1] == 0 { end -= 1 } // a 4 byte start code, or trailing zeros
                    ranges.append(s..<end)
                }
                start = i + 3
                i += 3
            } else {
                i += 1
            }
        }
        if let s = start, s < n { ranges.append(s..<n) }
        return ranges
    }

    public static func nalRanges(_ data: Data) -> [Range<Int>] {
        data.withUnsafeBytes { nalRanges($0) }
    }

    /// The NAL unit type. `codec` is a protocol codec id.
    public static func nalType(firstByte: UInt8, codec: UInt8) -> UInt8 {
        codec == Codec.h265 ? (firstByte >> 1) & 0x3F : firstByte & 0x1F
    }

    /// True for parameter sets and access unit delimiters, which do not belong in a length prefixed sample.
    public static func isOutOfBand(nalType: UInt8, codec: UInt8) -> Bool {
        if codec == Codec.h265 { return (32...35).contains(nalType) }
        return (7...9).contains(nalType)
    }

    /// The parameter sets in a CONFIG payload, in decoder order: VPS, SPS, PPS. Nil if one is missing.
    public static func parameterSets(config: Data, codec: UInt8) -> [Data]? {
        let wanted: [UInt8] = codec == Codec.h265 ? [32, 33, 34] : [7, 8]
        var found: [UInt8: [Data]] = [:]
        for r in nalRanges(config) where !r.isEmpty {
            let start = config.startIndex + r.lowerBound
            let nal = Data(config[start..<(config.startIndex + r.upperBound)])
            found[nalType(firstByte: nal[nal.startIndex], codec: codec), default: []].append(nal)
        }
        var sets: [Data] = []
        for type in wanted {
            guard let list = found[type] else { return nil }
            sets += list
        }
        return sets
    }

    /// Size of the length prefixed form of `ranges`.
    public static func lengthPrefixedSize(_ ranges: [Range<Int>]) -> Int {
        ranges.reduce(0) { $0 + $1.count + 4 }
    }

    /// Writes each range with a 4 byte length. `output` must hold `lengthPrefixedSize(ranges)` bytes.
    public static func writeLengthPrefixed(_ source: UnsafeRawBufferPointer, ranges: [Range<Int>],
                                           to output: UnsafeMutableRawPointer) {
        var at = 0
        for r in ranges {
            output.storeBytes(of: UInt32(r.count).bigEndian, toByteOffset: at, as: UInt32.self)
            (output + at + 4).copyMemory(from: source.baseAddress! + r.lowerBound, byteCount: r.count)
            at += 4 + r.count
        }
    }

    /// Replaces 4 byte lengths with start codes, in place. False if the lengths do not add up.
    @discardableResult
    public static func lengthPrefixedToStartCodes(_ bytes: UnsafeMutableRawBufferPointer) -> Bool {
        var at = 0
        while at + 4 <= bytes.count {
            let length = (Int(bytes[at]) << 24) | (Int(bytes[at + 1]) << 16) | (Int(bytes[at + 2]) << 8) | Int(bytes[at + 3])
            guard length >= 0, at + 4 + length <= bytes.count else { return false }
            bytes[at] = 0
            bytes[at + 1] = 0
            bytes[at + 2] = 0
            bytes[at + 3] = 1
            at += 4 + length
        }
        return at == bytes.count
    }
}

/// AAC AudioSpecificConfig (ISO 14496-3 §1.6.2.1), what CONFIG carries.
public struct AudioSpecificConfig: Equatable, Sendable {
    public static let aacLc: UInt8 = 2
    private static let rates = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350]

    public var objectType: UInt8
    public var sampleRate: Int
    public var channels: Int

    public init(objectType: UInt8 = aacLc, sampleRate: Int, channels: Int) {
        self.objectType = objectType
        self.sampleRate = sampleRate
        self.channels = channels
    }

    public static func parse(_ data: Data) -> AudioSpecificConfig? {
        var bitsRead = 0
        let bytes = [UInt8](data)
        func take(_ n: Int) -> Int? {
            guard bitsRead + n <= bytes.count * 8 else { return nil }
            var v = 0
            for _ in 0..<n {
                v = v << 1 | Int((bytes[bitsRead / 8] >> (7 - UInt8(bitsRead % 8))) & 1)
                bitsRead += 1
            }
            return v
        }
        guard var object = take(5), let index = take(4) else { return nil }
        if object == 31 {
            guard let extended = take(6) else { return nil }
            object = 32 + extended
        }
        var rate = 0
        if index == 15 {
            guard let explicit = take(24) else { return nil }
            rate = explicit
        } else if index < rates.count {
            rate = rates[index]
        } else {
            return nil
        }
        guard let channels = take(4), channels >= 1, channels <= 7, rate > 0 else { return nil }
        return AudioSpecificConfig(objectType: UInt8(object), sampleRate: rate, channels: channels)
    }

    /// Two bytes for a standard rate, five otherwise.
    public func encoded() -> Data {
        var bits: [(Int, Int)] = [(Int(objectType), 5)]
        if let index = Self.rates.firstIndex(of: sampleRate) {
            bits.append((index, 4))
        } else {
            bits += [(15, 4), (sampleRate, 24)]
        }
        bits += [(channels, 4), (0, 3)]
        var out = Data()
        var acc = 0
        var count = 0
        for (value, width) in bits {
            for shift in stride(from: width - 1, through: 0, by: -1) {
                acc = acc << 1 | ((value >> shift) & 1)
                count += 1
                if count == 8 {
                    out.append(UInt8(acc))
                    acc = 0
                    count = 0
                }
            }
        }
        if count > 0 { out.append(UInt8(acc << (8 - count))) }
        return out
    }
}
