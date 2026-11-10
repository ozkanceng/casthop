import CastHopProtocol
import CoreMedia
import CoreVideo
import Foundation
import VideoToolbox

/// Where decoded frames go. The app shows them on an AVSampleBufferDisplayLayer. Without one, decoded counts as rendered.
public protocol VideoOutput: AnyObject {
    func enqueue(_ sampleBuffer: CMSampleBuffer)
    func flush()
    /// The frames from the next `enqueue` on carry a picture turned 90° clockwise (PROTOCOL.md §11, ROTATED): show it
    /// turned back counter-clockwise. Called on the decoder's thread only when the turn changes, and with `false` on reset.
    func setRotated(_ rotated: Bool)
}

public extension VideoOutput {
    func setRotated(_ rotated: Bool) {}
}

/// VideoToolbox receive path (PROTOCOL.md §5, §11): CONFIG first, Annex-B in, frames dropped up to the next IDR when the
/// decoder falls behind. `push`, `reset` and `finish` run on the receiver queue. Decoder callbacks arrive on their own
/// thread, so counters are behind a lock.
public final class VideoDecoder: @unchecked Sendable {
    public struct Stats: Sendable {
        public var framesReceived: UInt32 = 0
        public var framesRendered: UInt32 = 0
        public var framesDropped: UInt32 = 0
        public var bytesReceived: UInt64 = 0
        public var c2rSumUs: UInt64 = 0
        public var c2rCount: UInt32 = 0
        public var c2rMinUs: UInt32 = 0
        public var c2rMaxUs: UInt32 = 0
        public var c2dSumUs: UInt64 = 0
        public var c2dCount: UInt32 = 0
        public var backlog: UInt16 = 0
    }

    static let maxInFlight = 4
    static let hardMaxInFlight = 30
    static let startupMaxInFlight = 120
    static let backlogGraceUs: UInt64 = 250_000
    static let keyframeRequestIntervalUs: UInt64 = 200_000

    public static var hardwareH264: Bool { VTIsHardwareDecodeSupported(kCMVideoCodecType_H264) }
    public static var hardwareH265: Bool { VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC) }

    /// Asks the sender for an IDR. Runs on the caller of `push` or on the decoder thread.
    public var onKeyframeNeeded: ((UInt8) -> Void)?
    public var onFirstFrame: (() -> Void)?
    public var onSizeChanged: ((Int, Int) -> Void)?
    public weak var output: VideoOutput?

    private let lock = NSLock()
    private var stats = Stats()
    private var offsetUs: Int64?
    private var inFlight = 0
    private var firstRendered = false
    private var generation = 0
    private var totalReceivedCount: Int64 = 0
    private var totalRenderedCount: Int64 = 0
    private var totalDroppedCount: Int64 = 0
    private var usesHardware = false
    private var lastKeyframeRequestUs: UInt64 = 0
    private var shownRotated = false
    private var rotatedSeenFlag = false

    private var codec: UInt8 = 0
    private var config = Data()
    private var configPending = false
    private var waitKeyframe = false
    private var lastSeq: UInt32?
    private var backlogSinceUs: UInt64 = 0
    private var session: VTDecompressionSession?
    private var format: CMVideoFormatDescription?
    private var imageFormat: CMVideoFormatDescription?

    public init() {}

    deinit { destroySession() }

    public func setClockOffset(_ offset: Int64?) {
        lock.lock()
        offsetUs = offset
        lock.unlock()
    }

    public func takeStats() -> Stats {
        lock.lock()
        defer { lock.unlock() }
        var s = stats
        s.backlog = UInt16(clamping: inFlight)
        stats = Stats()
        return s
    }

    public var totalReceived: Int64 { locked { totalReceivedCount } }
    public var totalRendered: Int64 { locked { totalRenderedCount } }
    public var totalDropped: Int64 { locked { totalDroppedCount } }
    public var hardwareDecoding: Bool { locked { usesHardware } }
    /// A turned frame (ROTATED) was shown in this session.
    public var rotatedSeen: Bool { locked { rotatedSeenFlag } }
    public var decoderName: String { "VideoToolbox" }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    /// Waits for frames in the decoder, so the totals are final.
    public func finish() {
        if let session { VTDecompressionSessionWaitForAsynchronousFrames(session) }
    }

    public func reset() {
        destroySession()
        let wasRotated: Bool = locked {
            defer { shownRotated = false }
            return shownRotated
        }
        if wasRotated { output?.setRotated(false) }
        output?.flush()
        lock.lock()
        rotatedSeenFlag = false
        generation += 1
        stats = Stats()
        offsetUs = nil
        inFlight = 0
        firstRendered = false
        totalReceivedCount = 0
        totalRenderedCount = 0
        totalDroppedCount = 0
        usesHardware = false
        lastKeyframeRequestUs = 0
        lock.unlock()
        codec = 0
        config = Data()
        configPending = false
        waitKeyframe = false
        lastSeq = nil
        backlogSinceUs = 0
        format = nil
    }

    private func destroySession() {
        guard let s = session else { return }
        VTDecompressionSessionWaitForAsynchronousFrames(s)
        VTDecompressionSessionInvalidate(s)
        session = nil
    }

    private func requestKeyframe(_ reason: UInt8) {
        let now = MonotonicClock.nowUs()
        let allowed: Bool = locked {
            if lastKeyframeRequestUs != 0 && now - lastKeyframeRequestUs < Self.keyframeRequestIntervalUs { return false }
            lastKeyframeRequestUs = now
            return true
        }
        if allowed { onKeyframeNeeded?(reason) }
    }

    private func dropFrame() {
        lock.lock()
        totalDroppedCount += 1
        stats.framesDropped += 1
        lock.unlock()
    }

    public func push(header: FrameHeader, payload: Data, recvUs: UInt64) {
        if header.isConfig {
            guard let first = payload.first, first == Codec.h264 || first == Codec.h265 else { return }
            if first != codec {
                destroySession()
                codec = first
            }
            config = Data(payload.dropFirst())
            configPending = true
            waitKeyframe = true
            return
        }

        let (inFlightNow, rendered): (Int, Bool) = locked {
            totalReceivedCount += 1
            stats.framesReceived += 1
            stats.bytesReceived += UInt64(payload.count)
            if let offsetUs {
                let c2r = Int64(recvUs) - (Int64(header.ptsUs) + offsetUs)
                let v = UInt32(clamping: max(c2r, 0))
                stats.c2rSumUs += UInt64(v)
                stats.c2rMinUs = stats.c2rCount == 0 ? v : min(stats.c2rMinUs, v)
                stats.c2rMaxUs = max(stats.c2rMaxUs, v)
                stats.c2rCount += 1
            }
            return (inFlight, firstRendered)
        }

        let gap = lastSeq.map { header.seq != $0 &+ 1 } ?? false
        lastSeq = header.seq
        let key = header.isKeyframe
        if !key && (gap || header.isDiscontinuity) && !waitKeyframe {
            waitKeyframe = true
            requestKeyframe(KeyframeReason.backlogDropped)
        }
        if config.isEmpty {
            dropFrame()
            return
        }
        if waitKeyframe && !key {
            dropFrame()
            requestKeyframe(KeyframeReason.decoderReset)
            return
        }

        // A short pile-up melts by itself. Above the limit for the grace time, or at the hard limit, the decoder cannot
        // keep up.
        if inFlightNow < Self.maxInFlight {
            backlogSinceUs = 0
        } else if backlogSinceUs == 0 {
            backlogSinceUs = recvUs
        }
        let sustained = backlogSinceUs != 0 && rendered && recvUs - backlogSinceUs >= Self.backlogGraceUs
        let hardLimit = rendered ? Self.hardMaxInFlight : Self.startupMaxInFlight
        if !key && (sustained || inFlightNow >= hardLimit) {
            backlogSinceUs = 0
            dropFrame()
            waitKeyframe = true
            requestKeyframe(KeyframeReason.backlogDropped)
            return
        }
        waitKeyframe = false

        if configPending {
            guard key else {
                dropFrame()
                return
            }
            configPending = false
            guard rebuild() else {
                dropFrame()
                waitKeyframe = true
                requestKeyframe(KeyframeReason.decodeError)
                return
            }
        }
        guard let session, let format, let sample = makeSample(payload, ptsUs: header.ptsUs, format: format) else {
            dropFrame()
            return
        }
        let current: Int = locked {
            inFlight += 1
            return generation
        }
        let rotated = header.isRotated
        let status = VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample, flags: [._EnableAsynchronousDecompression],
                                                       infoFlagsOut: nil) { [weak self] status, _, image, pts, _ in
            self?.decoded(status: status, image: image, pts: pts, rotated: rotated, generation: current)
        }
        if status != noErr {
            locked { inFlight = max(inFlight - 1, 0) }
            onDecodeFailure(status)
        }
    }

    /// A decoder that the system invalidated (app in background) or that rejected a frame restarts from CONFIG.
    private func onDecodeFailure(_ status: OSStatus) {
        dropFrame()
        waitKeyframe = true
        if status == kVTInvalidSessionErr {
            destroySession()
            configPending = true
        }
        requestKeyframe(KeyframeReason.decodeError)
    }

    private func rebuild() -> Bool {
        guard let sets = AnnexB.parameterSets(config: config, codec: codec),
              let description = Self.formatDescription(sets: sets, codec: codec) else { return false }
        if let session, VTDecompressionSessionCanAcceptFormatDescription(session, formatDescription: description) {
            format = description
            reportSize(description)
            return true
        }
        destroySession()
        let attributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any],
            kCVPixelBufferMetalCompatibilityKey as String: true,
        ]
        var created: VTDecompressionSession?
        let status = VTDecompressionSessionCreate(allocator: nil, formatDescription: description, decoderSpecification: nil,
                                                  imageBufferAttributes: attributes as CFDictionary, outputCallback: nil,
                                                  decompressionSessionOut: &created)
        guard status == noErr, let created else { return false }
        VTSessionSetProperty(created, key: kVTDecompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        var hardware: CFTypeRef?
        VTSessionCopyProperty(created, key: kVTDecompressionPropertyKey_UsingHardwareAcceleratedVideoDecoder,
                              allocator: nil, valueOut: &hardware)
        let isHardware = (hardware as? Bool) ?? false
        locked { usesHardware = isHardware }
        session = created
        format = description
        reportSize(description)
        return true
    }

    private func reportSize(_ description: CMVideoFormatDescription) {
        let d = CMVideoFormatDescriptionGetDimensions(description)
        onSizeChanged?(Int(d.width), Int(d.height))
    }

    static func formatDescription(sets: [Data], codec: UInt8) -> CMVideoFormatDescription? {
        let buffers = sets.map { [UInt8]($0) }
        let sizes = buffers.map(\.count)
        func withPointers<R>(_ i: Int, _ pointers: [UnsafePointer<UInt8>], _ body: ([UnsafePointer<UInt8>]) -> R) -> R {
            if i == buffers.count { return body(pointers) }
            return buffers[i].withUnsafeBufferPointer { withPointers(i + 1, pointers + [$0.baseAddress!], body) }
        }
        var out: CMVideoFormatDescription?
        let status: OSStatus = withPointers(0, []) { pointers in
            if codec == Codec.h265 {
                return CMVideoFormatDescriptionCreateFromHEVCParameterSets(
                    allocator: nil, parameterSetCount: pointers.count, parameterSetPointers: pointers,
                    parameterSetSizes: sizes, nalUnitHeaderLength: 4, extensions: nil, formatDescriptionOut: &out)
            }
            return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                allocator: nil, parameterSetCount: pointers.count, parameterSetPointers: pointers,
                parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &out)
        }
        return status == noErr ? out : nil
    }

    private func makeSample(_ payload: Data, ptsUs: UInt64, format: CMVideoFormatDescription) -> CMSampleBuffer? {
        let codec = self.codec
        return payload.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> CMSampleBuffer? in
            let ranges = AnnexB.nalRanges(raw).filter { r in
                !r.isEmpty && !AnnexB.isOutOfBand(nalType: AnnexB.nalType(firstByte: raw[r.lowerBound], codec: codec), codec: codec)
            }
            guard !ranges.isEmpty else { return nil }
            let size = AnnexB.lengthPrefixedSize(ranges)
            var block: CMBlockBuffer?
            guard CMBlockBufferCreateWithMemoryBlock(allocator: nil, memoryBlock: nil, blockLength: size, blockAllocator: nil,
                                                     customBlockSource: nil, offsetToData: 0, dataLength: size,
                                                     flags: kCMBlockBufferAssureMemoryNowFlag, blockBufferOut: &block) == noErr,
                  let block else { return nil }
            var pointer: UnsafeMutablePointer<CChar>?
            guard CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: nil,
                                              dataPointerOut: &pointer) == noErr, let pointer else { return nil }
            AnnexB.writeLengthPrefixed(raw, ranges: ranges, to: UnsafeMutableRawPointer(pointer))
            var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: CMTime(value: Int64(ptsUs), timescale: 1_000_000),
                                            decodeTimeStamp: .invalid)
            var sampleSize = size
            var sample: CMSampleBuffer?
            guard CMSampleBufferCreateReady(allocator: nil, dataBuffer: block, formatDescription: format, sampleCount: 1,
                                            sampleTimingEntryCount: 1, sampleTimingArray: &timing, sampleSizeEntryCount: 1,
                                            sampleSizeArray: &sampleSize, sampleBufferOut: &sample) == noErr else { return nil }
            return sample
        }
    }

    private func decoded(status: OSStatus, image: CVImageBuffer?, pts: CMTime, rotated: Bool, generation current: Int) {
        lock.lock()
        guard current == generation else {
            lock.unlock()
            return
        }
        inFlight = max(inFlight - 1, 0)
        guard status == noErr, let image else {
            totalDroppedCount += 1
            stats.framesDropped += 1
            lock.unlock()
            if status != noErr { requestKeyframe(KeyframeReason.decodeError) }
            return
        }
        // The turn goes with the picture: the output learns of a change right before the first frame that has it.
        let turned = rotated != shownRotated
        shownRotated = rotated
        if rotated { rotatedSeenFlag = true }
        lock.unlock()

        if turned { output?.setRotated(rotated) }
        if let output, let sample = displaySample(image, pts: pts) { output.enqueue(sample) }

        let now = MonotonicClock.nowUs()
        lock.lock()
        totalRenderedCount += 1
        stats.framesRendered += 1
        if let offsetUs {
            let captured = Int64(CMTimeConvertScale(pts, timescale: 1_000_000, method: .default).value) + offsetUs
            let c2d = Int64(now) - captured
            if c2d >= 0 {
                stats.c2dSumUs += UInt64(c2d)
                stats.c2dCount += 1
            }
        }
        let first = !firstRendered
        firstRendered = true
        lock.unlock()
        if first { onFirstFrame?() }
    }

    private func displaySample(_ image: CVImageBuffer, pts: CMTime) -> CMSampleBuffer? {
        var description: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: image,
                                                           formatDescriptionOut: &description) == noErr,
              let description else { return nil }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: pts, decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(allocator: nil, imageBuffer: image, formatDescription: description,
                                                       sampleTiming: &timing, sampleBufferOut: &sample) == noErr,
              let sample else { return nil }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return sample
    }
}
