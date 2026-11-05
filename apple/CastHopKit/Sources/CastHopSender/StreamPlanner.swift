import Foundation

/// A platform neutral view of what a video encoder can do. VideoToolbox implements it; tests fake it.
public protocol VideoEncoderCaps {
    var name: String { get }
    /// Protocol codec id.
    var codec: UInt8 { get }
    var hardware: Bool { get }
    var widthAlignment: Int { get }
    var heightAlignment: Int { get }
    var bitrateRange: ClosedRange<Int64> { get }
    func isSupported(width: Int, height: Int, fps: Int) -> Bool
}

/// Presets (ARCHITECTURE.md §2.1), trimmed to what the device and receiver can do.
public enum Preset: String, CaseIterable, Codable, Sendable {
    case best
    case balanced
    case weakNetwork

    public static let best4kLongSide = 2560

    public var longSideCap: Int { [1920, 1600, 1280][index] }
    public var fps: Int { [60, 60, 30][index] }
    public var startBps: Int64 { [16_000_000, 8_000_000, 3_000_000][index] }
    public var maxBps: Int64 { [24_000_000, 12_000_000, 5_000_000][index] }
    public var minBps: Int64 { [4_000_000, 2_000_000, 800_000][index] }

    var index: Int { Self.allCases.firstIndex(of: self)! }

    /// The next preset towards a weaker network.
    public var lower: Preset? { index + 1 < Self.allCases.count ? Self.allCases[index + 1] : nil }
    /// The next preset towards better quality.
    public var higher: Preset? { index > 0 ? Self.allCases[index - 1] : nil }
}

/// Advanced settings. Nil means the preset's value.
public struct StreamOverrides: Equatable, Codable, Sendable {
    public var longSide: Int?
    public var fps: Int?
    public var maxBitrateBps: Int64?

    public init(longSide: Int? = nil, fps: Int? = nil, maxBitrateBps: Int64? = nil) {
        self.longSide = longSide
        self.fps = fps
        self.maxBitrateBps = maxBitrateBps
    }
}

/// The display a receiver announced in WELCOME.
public struct ReceiverDisplay: Equatable, Sendable {
    public var width: Int
    public var height: Int
    public var refreshHz: Int

    public init(width: Int, height: Int, refreshHz: Int) {
        self.width = width
        self.height = height
        self.refreshHz = refreshHz
    }
}

public struct StreamPlan: Equatable, Sendable {
    public var encoderName: String
    public var codec: UInt8
    public var hardware: Bool
    public var width: Int
    public var height: Int
    public var fps: Int
    public var startBps: Int64
    public var minBps: Int64
    public var maxBps: Int64

    public init(encoderName: String, codec: UInt8, hardware: Bool, width: Int, height: Int, fps: Int, startBps: Int64,
                minBps: Int64, maxBps: Int64) {
        self.encoderName = encoderName
        self.codec = codec
        self.hardware = hardware
        self.width = width
        self.height = height
        self.fps = fps
        self.startBps = startBps
        self.minBps = minBps
        self.maxBps = maxBps
    }
}

/// Picks the stream format from source size, preset, receiver and encoder.
public enum StreamPlanner {
    private static let minShortSide = 360
    private static let scaleStep = 0.9
    private static let highFpsMinRatio = 0.75
    private static let usefulLongSide = 1024

    /// `sourceWidth` and `sourceHeight` follow the orientation. Encoders are in order of preference. Nil if nothing fits.
    public static func plan(sourceWidth: Int, sourceHeight: Int, preset: Preset, receiver: ReceiverDisplay?,
                            encoders: [VideoEncoderCaps], overrides: StreamOverrides = StreamOverrides()) -> StreamPlan? {
        precondition(sourceWidth > 0 && sourceHeight > 0)
        // Hardware first, otherwise the given order.
        let ordered = encoders.filter(\.hardware) + encoders.filter { !$0.hardware }
        let fpsWanted = min(overrides.fps ?? preset.fps, receiver.map { max($0.refreshHz, 30) } ?? 60)
        for enc in ordered {
            let longCap = longSideCap(preset, receiver, enc, overrides)
            let high = largestSupportedSize(sourceWidth, sourceHeight, longCap, receiver, enc, fpsWanted)
            let low = fpsWanted > 30 ? largestSupportedSize(sourceWidth, sourceHeight, longCap, receiver, enc, 30) : nil
            // A higher frame rate wins only if little resolution is lost: readable text comes first.
            let size: (Int, Int)
            let fps: Int
            if let high, low == nil || Double(max(high.0, high.1)) >= highFpsMinRatio * Double(max(low!.0, low!.1)) {
                size = high
                fps = fpsWanted
            } else if let low {
                size = low
                fps = 30
            } else {
                continue
            }
            let maxBps = max(min(overrides.maxBitrateBps ?? preset.maxBps, enc.bitrateRange.upperBound),
                             enc.bitrateRange.lowerBound)
            let minBps = min(max(preset.minBps, enc.bitrateRange.lowerBound), maxBps)
            let startBps = min(max(preset.startBps, minBps), maxBps)
            return StreamPlan(encoderName: enc.name, codec: enc.codec, hardware: enc.hardware, width: size.0,
                              height: size.1, fps: fps, startBps: startBps, minBps: minBps, maxBps: maxBps)
        }
        return nil
    }

    /// Frame rates for the settings screen: those supported at a useful resolution.
    public static func supportedFps(encoders: [VideoEncoderCaps], sourceWidth: Int, sourceHeight: Int) -> [Int] {
        [30, 60].filter { fps in
            encoders.contains { enc in
                guard let s = largestSupportedSize(sourceWidth, sourceHeight, 1280, nil, enc, fps) else { return false }
                return max(s.0, s.1) >= usefulLongSide
            }
        }
    }

    /// Long side choices for the settings screen: those the encoder supports at 30 fps.
    public static func supportedLongSides(encoders: [VideoEncoderCaps], sourceWidth: Int, sourceHeight: Int) -> [Int] {
        [1280, 1600, 1920, 2560].filter { side in
            let (w, h) = fit(sourceWidth, sourceHeight, side, nil)
            return encoders.contains { enc in
                let aw = alignDown(w, enc.widthAlignment)
                let ah = alignDown(h, enc.heightAlignment)
                return Double(max(aw, ah)) >= Double(side) * 0.95 && enc.isSupported(width: aw, height: ah, fps: 30)
            }
        }
    }

    private static func longSideCap(_ preset: Preset, _ receiver: ReceiverDisplay?, _ enc: VideoEncoderCaps,
                                    _ o: StreamOverrides) -> Int {
        if let side = o.longSide { return side }
        if preset == .best, let receiver, max(receiver.width, receiver.height) >= 3840,
           enc.isSupported(width: Preset.best4kLongSide, height: Preset.best4kLongSide * 9 / 16, fps: 30) {
            return Preset.best4kLongSide
        }
        return preset.longSideCap
    }

    private static func largestSupportedSize(_ sw: Int, _ sh: Int, _ longCap: Int, _ receiver: ReceiverDisplay?,
                                             _ enc: VideoEncoderCaps, _ fps: Int) -> (Int, Int)? {
        var (w, h) = fit(sw, sh, longCap, receiver)
        while min(w, h) >= minShortSide {
            let aw = alignDown(w, enc.widthAlignment)
            let ah = alignDown(h, enc.heightAlignment)
            if enc.isSupported(width: aw, height: ah, fps: fps) { return (aw, ah) }
            w = Int((Double(w) * scaleStep).rounded())
            h = Int((Double(h) * scaleStep).rounded())
        }
        return nil
    }

    /// Fits the cap and the receiver display, keeping the aspect ratio, never scaling up. A board does not rotate: a
    /// portrait source is shown with bars, so the receiver's width and height are used as they are.
    static func fit(_ sw: Int, _ sh: Int, _ longCap: Int, _ receiver: ReceiverDisplay?) -> (Int, Int) {
        var scale = min(1.0, Double(longCap) / Double(max(sw, sh)))
        if let receiver {
            scale = min(scale, min(Double(receiver.width) / Double(sw), Double(receiver.height) / Double(sh)))
        }
        return (Int((Double(sw) * scale).rounded()), Int((Double(sh) * scale).rounded()))
    }

    static func alignDown(_ v: Int, _ alignment: Int) -> Int {
        let a = max(2, alignment)
        return v / a * a
    }
}
