import CastHopProtocol
import CoreMedia
import CoreVideo
import Foundation
import VideoToolbox

/// What VideoToolbox can encode here, answered by VideoToolbox itself.
public final class VideoToolboxCaps: VideoEncoderCaps {
    public let codec: UInt8
    public let hardware: Bool
    public let widthAlignment = 2
    public let heightAlignment = 2
    public let bitrateRange: ClosedRange<Int64> = 200_000...100_000_000
    private var cache: [Int: Bool] = [:]
    private let lock = NSLock()

    public var name: String { codec == Codec.h265 ? "VideoToolbox HEVC" : "VideoToolbox H.264" }

    private init(codec: UInt8, hardware: Bool) {
        self.codec = codec
        self.hardware = hardware
    }

    private var codecType: CMVideoCodecType { codec == Codec.h265 ? kCMVideoCodecType_HEVC : kCMVideoCodecType_H264 }

    private static func probe(_ type: CMVideoCodecType, width: Int, height: Int, hardware: Bool) -> Bool {
        var spec: [String: Any] = [:]
        if hardware { spec[kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder as String] = true }
        var encoderId: CFString?
        var properties: CFDictionary?
        return VTCopySupportedPropertyDictionaryForEncoder(width: Int32(width), height: Int32(height), codecType: type,
                                                           encoderSpecification: spec as CFDictionary,
                                                           encoderIDOut: &encoderId, supportedPropertiesOut: &properties) == noErr
    }

    /// H.264 always, hardware when the device has it. H.265 only in hardware.
    public static func available(allowH265: Bool) -> [VideoToolboxCaps] {
        var out: [VideoToolboxCaps] = []
        if allowH265 && probe(kCMVideoCodecType_HEVC, width: 1920, height: 1080, hardware: true) {
            out.append(VideoToolboxCaps(codec: Codec.h265, hardware: true))
        }
        let hardware = probe(kCMVideoCodecType_H264, width: 1920, height: 1080, hardware: true)
        out.append(VideoToolboxCaps(codec: Codec.h264, hardware: hardware))
        return out
    }

    public func isSupported(width: Int, height: Int, fps: Int) -> Bool {
        guard width % widthAlignment == 0, height % heightAlignment == 0, width > 0, height > 0 else { return false }
        // VideoToolbox answers for size only. Its hardware encoders manage 4K30, so that is the pixel rate bound.
        guard width * height * fps <= 3840 * 2160 * 30 else { return false }
        let key = width << 16 | height
        lock.lock()
        defer { lock.unlock() }
        if let known = cache[key] { return known }
        let result = Self.probe(codecType, width: width, height: height, hardware: hardware)
        cache[key] = result
        return result
    }
}

/// Holds capture to the plan's frame rate. It works on a schedule, not on the gap between frames, so that a frame that is
/// a few milliseconds early still passes at full rate.
public struct FramePacer: Sendable {
    private var nextDueUs: UInt64 = 0

    public init() {}

    public func admits(ptsUs: UInt64, fps: Int) -> Bool {
        let interval = UInt64(1_000_000 / max(fps, 1))
        return ptsUs + interval / 3 >= nextDueUs
    }

    public mutating func accept(ptsUs: UInt64, fps: Int) {
        let interval = UInt64(1_000_000 / max(fps, 1))
        nextDueUs = max(nextDueUs, ptsUs &- min(ptsUs, interval)) + interval
    }
}

public enum EncoderError: Error, Equatable {
    case createFailed(OSStatus)
    case encodeFailed(OSStatus)
}

/// One VTCompressionSession at a time. Frames are not copied: the sample's memory is rewritten from length prefixes to
/// start codes and handed on, and the frame holds the block buffer.
public final class VideoToolboxEncoder: VideoSource, @unchecked Sendable {
    /// Frames allowed inside the encoder. More would hold capture buffers and grow memory, so they are skipped.
    private static let maxInFlight = 3
    private static let keyframeIntervalSeconds = 10

    private let lock = NSLock()
    private let resendQueue = DispatchQueue(label: "casthop.encoder.resend", qos: .userInteractive)
    private var session: VTCompressionSession?
    private var plan: StreamPlan?
    private var sink: ((EncodedFrame) -> Void)?
    private var errorHandler: ((Error) -> Void)?
    private var forceKeyframe = false
    private var inFlight = 0
    private var parameterSets: [Data] = []
    private var lastPixelBuffer: CVPixelBuffer?
    private var pacer = FramePacer()
    private var generation = 0
    public private(set) var usesHardware = false
    /// Frames skipped because the encoder was behind.
    public private(set) var skippedBusy = 0
    /// Frames skipped because they came faster than the plan's frame rate.
    public private(set) var skippedRate = 0
    public private(set) var submitted = 0

    public init() {}

    deinit { stop() }

    public func start(plan: StreamPlan, sink: @escaping (EncodedFrame) -> Void, onError: @escaping (Error) -> Void) throws {
        let created = try Self.makeSession(plan: plan)
        var hardware: CFTypeRef?
        VTSessionCopyProperty(created, key: kVTCompressionPropertyKey_UsingHardwareAcceleratedVideoEncoder, allocator: nil,
                              valueOut: &hardware)
        lock.lock()
        session = created
        self.plan = plan
        self.sink = sink
        errorHandler = onError
        forceKeyframe = true
        inFlight = 0
        parameterSets = []
        pacer = FramePacer()
        generation += 1
        usesHardware = (hardware as? Bool) ?? false
        let resend = lastPixelBuffer != nil
        lock.unlock()
        // A static screen delivers nothing, and a new encoder must still show the picture.
        if resend { scheduleResend() }
    }

    private static func makeSession(plan: StreamPlan) throws -> VTCompressionSession {
        let type = plan.codec == Codec.h265 ? kCMVideoCodecType_HEVC : kCMVideoCodecType_H264
        func create(lowLatency: Bool) -> (OSStatus, VTCompressionSession?) {
            var spec: [String: Any] = [:]
            if lowLatency { spec[kVTVideoEncoderSpecification_EnableLowLatencyRateControl as String] = true }
            var out: VTCompressionSession?
            let status = VTCompressionSessionCreate(allocator: nil, width: Int32(plan.width), height: Int32(plan.height),
                                                    codecType: type, encoderSpecification: spec as CFDictionary,
                                                    imageBufferAttributes: nil, compressedDataAllocator: nil,
                                                    outputCallback: nil, refcon: nil, compressionSessionOut: &out)
            return (status, out)
        }
        var (status, made) = create(lowLatency: true)
        if status != noErr || made == nil { (status, made) = create(lowLatency: false) }
        guard status == noErr, let made else { throw EncoderError.createFailed(status) }

        func set(_ key: CFString, _ value: CFTypeRef) { VTSessionSetProperty(made, key: key, value: value) }
        set(kVTCompressionPropertyKey_RealTime, kCFBooleanTrue)
        // No B-frames (PROTOCOL.md §5).
        set(kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse)
        set(kVTCompressionPropertyKey_MaxFrameDelayCount, 0 as CFNumber)
        set(kVTCompressionPropertyKey_ExpectedFrameRate, plan.fps as CFNumber)
        set(kVTCompressionPropertyKey_AverageBitRate, plan.startBps as CFNumber)
        set(kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, keyframeIntervalSeconds as CFNumber)
        set(kVTCompressionPropertyKey_ProfileLevel, plan.codec == Codec.h265
            ? kVTProfileLevel_HEVC_Main_AutoLevel : kVTProfileLevel_H264_High_AutoLevel)
        VTCompressionSessionPrepareToEncodeFrames(made)
        return made
    }

    public func stop() {
        lock.lock()
        let old = session
        session = nil
        sink = nil
        errorHandler = nil
        generation += 1
        lock.unlock()
        if let old {
            VTCompressionSessionCompleteFrames(old, untilPresentationTimeStamp: .invalid)
            VTCompressionSessionInvalidate(old)
        }
    }

    public func setBitrate(_ bps: Int64) {
        lock.lock()
        defer { lock.unlock() }
        guard let session else { return }
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate, value: bps as CFNumber)
    }

    public func requestKeyframe() {
        lock.lock()
        forceKeyframe = true
        lock.unlock()
        scheduleResend()
    }

    /// A screen that stands still delivers no frames, so a request would wait for ever. The last frame goes again.
    private func scheduleResend() {
        let frameMs = 1000 / max(lock.withLock { plan?.fps ?? 30 }, 1)
        resendQueue.asyncAfter(deadline: .now() + .milliseconds(frameMs * 2)) { [weak self] in
            guard let self else { return }
            self.lock.lock()
            let image = self.forceKeyframe ? self.lastPixelBuffer : nil
            self.lock.unlock()
            if let image { self.encode(image, ptsUs: MonotonicClock.nowUs()) }
        }
    }

    /// Takes a frame in capture time. Skips it if the encoder is behind or the frame comes before the plan's frame time.
    public func encode(_ image: CVPixelBuffer, ptsUs: UInt64) {
        lock.lock()
        guard let session, let plan else {
            lastPixelBuffer = image
            lock.unlock()
            return
        }
        lastPixelBuffer = image
        let tooSoon = !pacer.admits(ptsUs: ptsUs, fps: plan.fps)
        if (tooSoon && !forceKeyframe) || inFlight >= Self.maxInFlight {
            if inFlight >= Self.maxInFlight { skippedBusy += 1 } else { skippedRate += 1 }
            lock.unlock()
            return
        }
        submitted += 1
        let wantKey = forceKeyframe
        forceKeyframe = false
        pacer.accept(ptsUs: ptsUs, fps: plan.fps)
        inFlight += 1
        let current = generation
        lock.unlock()

        let properties = wantKey ? [kVTEncodeFrameOptionKey_ForceKeyFrame as String: true] as CFDictionary : nil
        let pts = CMTime(value: Int64(ptsUs), timescale: 1_000_000)
        let status = VTCompressionSessionEncodeFrame(session, imageBuffer: image, presentationTimeStamp: pts, duration: .invalid,
                                                     frameProperties: properties, infoFlagsOut: nil) { [weak self] status, _, sample in
            self?.encoded(status: status, sample: sample, ptsUs: ptsUs, generation: current)
        }
        if status != noErr {
            fail(status, generation: current)
        }
    }

    private func fail(_ status: OSStatus, generation current: Int) {
        lock.lock()
        guard current == generation else {
            lock.unlock()
            return
        }
        inFlight = max(inFlight - 1, 0)
        let handler = errorHandler
        lock.unlock()
        handler?(EncoderError.encodeFailed(status))
    }

    private func encoded(status: OSStatus, sample: CMSampleBuffer?, ptsUs: UInt64, generation current: Int) {
        guard status == noErr else {
            fail(status, generation: current)
            return
        }
        lock.lock()
        guard current == generation else {
            lock.unlock()
            return
        }
        inFlight = max(inFlight - 1, 0)
        let emit = sink
        lock.unlock()
        // A nil sample is a frame the encoder chose to drop.
        guard let sample, let emit, let block = contiguousBlock(sample) else { return }

        let keyframe = isKeyframe(sample)
        if keyframe, let sets = Self.parameterSets(of: sample), sets != lock.withLock({ parameterSets }) {
            lock.withLock { parameterSets = sets }
            var config = Data()
            for set in sets {
                config.append(contentsOf: [0, 0, 0, 1])
                config.append(set)
            }
            emit(EncodedFrame(data: config, ptsUs: ptsUs, flags: FrameFlags.config, enqueuedUs: MonotonicClock.nowUs()))
        }
        guard let data = Self.annexB(block) else { return }
        emit(EncodedFrame(data: data, ptsUs: ptsUs, flags: keyframe ? FrameFlags.keyframe : 0,
                          enqueuedUs: MonotonicClock.nowUs()))
    }

    private func contiguousBlock(_ sample: CMSampleBuffer) -> CMBlockBuffer? {
        guard let block = CMSampleBufferGetDataBuffer(sample) else { return nil }
        let length = CMBlockBufferGetDataLength(block)
        if CMBlockBufferIsRangeContiguous(block, atOffset: 0, length: length) { return block }
        var flat: CMBlockBuffer?
        guard CMBlockBufferCreateContiguous(allocator: nil, sourceBuffer: block, blockAllocator: nil, customBlockSource: nil,
                                            offsetToData: 0, dataLength: length, flags: 0, blockBufferOut: &flat) == noErr else {
            return nil
        }
        return flat
    }

    private func isKeyframe(_ sample: CMSampleBuffer) -> Bool {
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]],
              let first = attachments.first else { return true }
        return (first[kCMSampleAttachmentKey_NotSync] as? Bool) != true
    }

    /// The Data keeps the block buffer alive and points into it.
    private static func annexB(_ block: CMBlockBuffer) -> Data? {
        var length = 0
        var pointer: UnsafeMutablePointer<CChar>?
        guard CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: &length, totalLengthOut: nil,
                                          dataPointerOut: &pointer) == noErr, let pointer else { return nil }
        let raw = UnsafeMutableRawBufferPointer(start: pointer, count: length)
        guard AnnexB.lengthPrefixedToStartCodes(raw) else { return nil }
        return Data(bytesNoCopy: pointer, count: length, deallocator: .custom { _, _ in _ = block })
    }

    private static func parameterSets(of sample: CMSampleBuffer) -> [Data]? {
        guard let format = CMSampleBufferGetFormatDescription(sample) else { return nil }
        let isHevc = CMFormatDescriptionGetMediaSubType(format) == kCMVideoCodecType_HEVC
        func set(_ index: Int, _ count: inout Int) -> Data? {
            var pointer: UnsafePointer<UInt8>?
            var size = 0
            var total = 0
            let status = isHevc
                ? CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(format, parameterSetIndex: index, parameterSetPointerOut: &pointer,
                                                                     parameterSetSizeOut: &size, parameterSetCountOut: &total,
                                                                     nalUnitHeaderLengthOut: nil)
                : CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, parameterSetIndex: index, parameterSetPointerOut: &pointer,
                                                                     parameterSetSizeOut: &size, parameterSetCountOut: &total,
                                                                     nalUnitHeaderLengthOut: nil)
            guard status == noErr, let pointer else { return nil }
            count = total
            return Data(bytes: pointer, count: size)
        }
        var count = 0
        guard let first = set(0, &count) else { return nil }
        var sets = [first]
        var index = 1
        while index < count {
            guard let next = set(index, &count) else { return nil }
            sets.append(next)
            index += 1
        }
        return sets
    }
}

extension NSLock {
    func withLock<T>(_ body: () -> T) -> T {
        lock()
        defer { unlock() }
        return body()
    }
}
