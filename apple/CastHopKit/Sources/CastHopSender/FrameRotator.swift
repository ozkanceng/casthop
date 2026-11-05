import CoreMedia
import CoreVideo
import Foundation
import ImageIO
import VideoToolbox

/// How a captured frame must turn to look right. ReplayKit delivers the screen in the device's native orientation and
/// tells the rotation in `RPVideoSampleOrientationKey`, a CGImagePropertyOrientation.
public enum FrameRotation: Sendable, Equatable {
    case none
    case clockwise90
    case counterClockwise90
    case half

    public init(_ orientation: CGImagePropertyOrientation) {
        switch orientation {
        case .right, .rightMirrored: self = .clockwise90
        case .left, .leftMirrored: self = .counterClockwise90
        case .down, .downMirrored: self = .half
        default: self = .none
        }
    }

    public var swapsSides: Bool { self == .clockwise90 || self == .counterClockwise90 }

    /// The size on screen of a frame captured at `width` × `height`.
    public func displaySize(width: Int, height: Int) -> (Int, Int) {
        swapsSides ? (height, width) : (width, height)
    }

    fileprivate var key: CFString? {
        switch self {
        case .none: return nil
        case .clockwise90: return kVTRotation_CW90
        case .counterClockwise90: return kVTRotation_CCW90
        case .half: return kVTRotation_180
        }
    }
}

/// Brings frames upright at the size being encoded. Scaling comes first: a rotated iPad screen at full size would not fit
/// in a Broadcast Upload Extension's memory, a frame at stream size does. Both pools are bounded.
public final class FrameRotator {
    private static let maxBuffers = 3

    private let rotation: FrameRotation
    private let width: Int
    private let height: Int
    private var rotationSession: VTPixelRotationSession?
    private var transferSession: VTPixelTransferSession?
    private var scaledPool: CVPixelBufferPool?
    private var outputPool: CVPixelBufferPool?
    private var format: OSType = 0

    /// `width` and `height` are the upright size of the output.
    public init?(rotation: FrameRotation, width: Int, height: Int) {
        guard let key = rotation.key else { return nil }
        self.rotation = rotation
        self.width = width
        self.height = height
        var made: VTPixelRotationSession?
        guard VTPixelRotationSessionCreate(nil, &made) == noErr, let made else { return nil }
        VTSessionSetProperty(made, key: kVTPixelRotationPropertyKey_Rotation, value: key)
        rotationSession = made
        var transfer: VTPixelTransferSession?
        guard VTPixelTransferSessionCreate(allocator: nil, pixelTransferSessionOut: &transfer) == noErr else { return nil }
        transferSession = transfer
    }

    deinit {
        if let rotationSession { VTPixelRotationSessionInvalidate(rotationSession) }
        if let transferSession { VTPixelTransferSessionInvalidate(transferSession) }
    }

    private static func makePool(width: Int, height: Int, format: OSType) -> CVPixelBufferPool? {
        let attributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: format,
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any],
        ]
        var pool: CVPixelBufferPool?
        CVPixelBufferPoolCreate(nil, [kCVPixelBufferPoolMinimumBufferCountKey as String: 1] as CFDictionary,
                                attributes as CFDictionary, &pool)
        return pool
    }

    private static func take(_ pool: CVPixelBufferPool?) -> CVPixelBuffer? {
        guard let pool else { return nil }
        var out: CVPixelBuffer?
        let aux = [kCVPixelBufferPoolAllocationThresholdKey as String: maxBuffers] as CFDictionary
        // Over the threshold there is no buffer: the frame is skipped instead of memory growing.
        guard CVPixelBufferPoolCreatePixelBufferWithAuxAttributes(nil, pool, aux, &out) == kCVReturnSuccess else { return nil }
        return out
    }

    /// The upright frame. Nil if the pools are exhausted or a session failed: the caller skips the frame.
    public func process(_ input: CVPixelBuffer) -> CVPixelBuffer? {
        guard let rotationSession, let transferSession else { return nil }
        let inputFormat = CVPixelBufferGetPixelFormatType(input)
        // Before rotation the sides are still swapped.
        let (preWidth, preHeight) = rotation.swapsSides ? (height, width) : (width, height)
        if inputFormat != format {
            format = inputFormat
            scaledPool = Self.makePool(width: preWidth, height: preHeight, format: inputFormat)
            outputPool = Self.makePool(width: width, height: height, format: inputFormat)
        }
        var source = input
        if CVPixelBufferGetWidth(input) != preWidth || CVPixelBufferGetHeight(input) != preHeight {
            guard let scaled = Self.take(scaledPool),
                  VTPixelTransferSessionTransferImage(transferSession, from: input, to: scaled) == noErr else { return nil }
            source = scaled
        }
        guard let output = Self.take(outputPool),
              VTPixelRotationSessionRotateImage(rotationSession, source, output) == noErr else { return nil }
        return output
    }
}

/// Connects capture to the encoder: follows the orientation, turns frames upright and passes them on without copies when
/// they are already upright. `ScreenPipeline.push` runs on the capture thread.
public final class ScreenPipeline: @unchecked Sendable {
    public let encoder: VideoToolboxEncoder
    private let lock = NSLock()
    private var rotation = FrameRotation.none
    private var rotator: FrameRotator?
    private var planWidth = 0
    private var planHeight = 0
    private var sourceSize: (Int, Int)?

    /// The upright source size changed (the device turned). The caller plans again and calls `applied`.
    public var onSourceChanged: ((Int, Int) -> Void)?

    public init(encoder: VideoToolboxEncoder) { self.encoder = encoder }

    /// The output size is now known, or changed.
    public func applied(width: Int, height: Int) {
        lock.lock()
        defer { lock.unlock() }
        planWidth = width
        planHeight = height
        rotator = FrameRotator(rotation: rotation, width: width, height: height)
    }

    public func push(_ image: CVPixelBuffer, rotation newRotation: FrameRotation, ptsUs: UInt64) {
        let upright = newRotation.displaySize(width: CVPixelBufferGetWidth(image), height: CVPixelBufferGetHeight(image))
        lock.lock()
        let changed = sourceSize.map { $0 != upright } ?? true
        let turned = newRotation != rotation
        if changed || turned {
            rotation = newRotation
            sourceSize = upright
            if planWidth != 0 { rotator = FrameRotator(rotation: newRotation, width: planWidth, height: planHeight) }
        }
        let current = rotator
        let currentRotation = rotation
        lock.unlock()
        if changed { onSourceChanged?(upright.0, upright.1) }

        if currentRotation == .none {
            encoder.encode(image, ptsUs: ptsUs)
        } else if let turnedFrame = current?.process(image) {
            encoder.encode(turnedFrame, ptsUs: ptsUs)
        }
    }

    public var currentSourceSize: (Int, Int)? { lock.withLock { sourceSize } }
}
