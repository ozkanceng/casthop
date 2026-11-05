import AudioToolbox
import CastHopProtocol
@testable import CastHopReceiver
import CastHopSender
import CoreMedia
import CoreVideo
import ImageIO
import XCTest

private func makeImage(width: Int, height: Int, luma: (Int, Int) -> UInt8) throws -> CVPixelBuffer {
    var image: CVPixelBuffer?
    let attributes = [kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any]] as CFDictionary
    XCTAssertEqual(CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, attributes, &image),
                   kCVReturnSuccess)
    let buffer = try XCTUnwrap(image)
    CVPixelBufferLockBaseAddress(buffer, [])
    defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
    let y = try XCTUnwrap(CVPixelBufferGetBaseAddressOfPlane(buffer, 0)).assumingMemoryBound(to: UInt8.self)
    let stride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0)
    for row in 0..<height {
        for column in 0..<width { y[row * stride + column] = luma(column, row) }
    }
    let uv = try XCTUnwrap(CVPixelBufferGetBaseAddressOfPlane(buffer, 1)).assumingMemoryBound(to: UInt8.self)
    memset(uv, 128, CVPixelBufferGetBytesPerRowOfPlane(buffer, 1) * height / 2)
    return buffer
}

private func lumaAt(_ buffer: CVPixelBuffer, _ x: Int, _ y: Int) -> UInt8 {
    CVPixelBufferLockBaseAddress(buffer, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
    let base = CVPixelBufferGetBaseAddressOfPlane(buffer, 0)!.assumingMemoryBound(to: UInt8.self)
    return base[y * CVPixelBufferGetBytesPerRowOfPlane(buffer, 0) + x]
}

final class VideoRoundTripTests: XCTestCase {
    private func plan(_ codec: UInt8 = Codec.h264) -> StreamPlan {
        StreamPlan(encoderName: "test", codec: codec, hardware: true, width: 640, height: 360, fps: 30, startBps: 2_000_000,
                   minBps: 500_000, maxBps: 4_000_000)
    }

    /// Runs frames through encoder and decoder as the wire would carry them.
    private func roundTrip(codec: UInt8, frames count: Int) throws -> (VideoDecoder, [EncodedFrame]) {
        let encoder = VideoToolboxEncoder()
        let lock = NSLock()
        var frames: [EncodedFrame] = []
        try encoder.start(plan: plan(codec), sink: { f in
            lock.lock()
            frames.append(f)
            lock.unlock()
        }, onError: { XCTFail("encoder: \($0)") })
        var pts: UInt64 = 1_000_000
        for i in 0..<count {
            encoder.encode(try makeImage(width: 640, height: 360) { x, y in UInt8((x + y + i * 8) & 0xFF) }, ptsUs: pts)
            pts += 33_334
            Thread.sleep(forTimeInterval: 0.02)
        }
        Thread.sleep(forTimeInterval: 0.3)
        encoder.stop()

        let decoder = VideoDecoder()
        decoder.setClockOffset(0)
        var seq: UInt32 = 0
        lock.lock()
        let output = frames
        lock.unlock()
        for f in output {
            var payload = f.data
            if f.isConfig { payload = Data([codec]) + payload }
            decoder.push(header: FrameHeader(type: .video, flags: f.flags, ptsUs: f.ptsUs, seq: seq, length: payload.count),
                         payload: payload, recvUs: f.ptsUs + 1000)
            seq += 1
        }
        decoder.finish()
        return (decoder, output)
    }

    func testH264FramesSurviveTheWireFormat() throws {
        let (decoder, frames) = try roundTrip(codec: Codec.h264, frames: 12)
        XCTAssertEqual(frames.first?.isConfig, true, "CONFIG must come first")
        XCTAssertEqual(frames.dropFirst().first?.isKeyframe, true, "the stream starts with an IDR")
        let config = try XCTUnwrap(frames.first).data
        XCTAssertEqual(AnnexB.parameterSets(config: config, codec: Codec.h264)?.count, 2)
        let video = frames.filter { !$0.isConfig }
        XCTAssertGreaterThan(video.count, 0)
        for f in video {
            XCTAssertEqual(Array(f.data.prefix(4)), [0, 0, 0, 1], "Annex-B start code")
        }
        XCTAssertEqual(decoder.totalRendered, Int64(video.count))
        XCTAssertEqual(decoder.totalDropped, 0)
    }

    func testH265FramesSurviveTheWireFormatWhereHardwareExists() throws {
        try XCTSkipUnless(VideoDecoder.hardwareH265 && VideoToolboxCaps.available(allowH265: true).count == 2)
        let (decoder, frames) = try roundTrip(codec: Codec.h265, frames: 8)
        let config = try XCTUnwrap(frames.first).data
        XCTAssertEqual(AnnexB.parameterSets(config: config, codec: Codec.h265)?.count, 3)
        XCTAssertEqual(decoder.totalRendered, Int64(frames.filter { !$0.isConfig }.count))
    }

    func testRequestedKeyframeComesFromTheLastFrameOnAStillScreen() throws {
        let encoder = VideoToolboxEncoder()
        let keyframes = expectation(description: "second keyframe")
        let lock = NSLock()
        var seen = 0
        try encoder.start(plan: plan(), sink: { f in
            guard f.isKeyframe else { return }
            lock.lock()
            seen += 1
            let count = seen
            lock.unlock()
            if count == 2 { keyframes.fulfill() }
        }, onError: { XCTFail("encoder: \($0)") })
        encoder.encode(try makeImage(width: 640, height: 360) { x, _ in UInt8(x & 0xFF) }, ptsUs: 1_000_000)
        Thread.sleep(forTimeInterval: 0.2)
        encoder.requestKeyframe() // nothing else is captured
        wait(for: [keyframes], timeout: 3)
        encoder.stop()
    }

    func testDecoderDropsUntilKeyframeAfterAGap() throws {
        let (_, frames) = try roundTrip(codec: Codec.h264, frames: 12)
        let decoder = VideoDecoder()
        var reasons: [UInt8] = []
        decoder.onKeyframeNeeded = { reasons.append($0) }
        var seq: UInt32 = 0
        for (index, f) in frames.enumerated() {
            var payload = f.data
            if f.isConfig { payload = Data([Codec.h264]) + payload }
            if index == 3 { seq += 1 } // one frame lost on the way
            decoder.push(header: FrameHeader(type: .video, flags: f.flags, ptsUs: f.ptsUs, seq: seq, length: payload.count),
                         payload: payload, recvUs: f.ptsUs)
            seq += 1
        }
        decoder.finish()
        XCTAssertEqual(reasons.first, KeyframeReason.backlogDropped)
        XCTAssertEqual(decoder.totalRendered, 2)
        XCTAssertEqual(decoder.totalDropped, Int64(frames.count - 3))
    }

    func testPFrameWithoutConfigIsDropped() {
        let decoder = VideoDecoder()
        decoder.push(header: FrameHeader(type: .video, flags: 0, ptsUs: 1, seq: 0, length: 3), payload: Data([0, 0, 1]), recvUs: 1)
        XCTAssertEqual(decoder.totalDropped, 1)
        XCTAssertEqual(decoder.totalRendered, 0)
    }
}

final class RotationTests: XCTestCase {
    /// The bright corner of a frame is top left. Decides where it goes.
    private func rotated(_ rotation: FrameRotation) throws -> CVPixelBuffer {
        let source = try makeImage(width: 64, height: 32) { x, y in x < 16 && y < 16 ? 235 : 16 }
        let (w, h) = rotation.displaySize(width: 64, height: 32)
        return try XCTUnwrap(XCTUnwrap(FrameRotator(rotation: rotation, width: w, height: h)).process(source))
    }

    func testOrientationMapping() {
        XCTAssertEqual(FrameRotation(.up), .none)
        XCTAssertEqual(FrameRotation(.right), .clockwise90)
        XCTAssertEqual(FrameRotation(.left), .counterClockwise90)
        XCTAssertEqual(FrameRotation(.down), .half)
    }

    func testClockwiseMovesTopLeftToTopRight() throws {
        let out = try rotated(.clockwise90)
        XCTAssertEqual(CVPixelBufferGetWidth(out), 32)
        XCTAssertEqual(CVPixelBufferGetHeight(out), 64)
        XCTAssertGreaterThan(lumaAt(out, 28, 4), 200)
        XCTAssertLessThan(lumaAt(out, 4, 4), 50)
    }

    func testCounterClockwiseMovesTopLeftToBottomLeft() throws {
        let out = try rotated(.counterClockwise90)
        XCTAssertGreaterThan(lumaAt(out, 4, 60), 200)
        XCTAssertLessThan(lumaAt(out, 4, 4), 50)
    }

    func testHalfTurnMovesTopLeftToBottomRight() throws {
        let out = try rotated(.half)
        XCTAssertEqual(CVPixelBufferGetWidth(out), 64)
        XCTAssertGreaterThan(lumaAt(out, 60, 28), 200)
    }

    func testScalesDownBeforeRotating() throws {
        let source = try makeImage(width: 256, height: 128) { x, y in x < 64 && y < 64 ? 235 : 16 }
        let out = try XCTUnwrap(XCTUnwrap(FrameRotator(rotation: .clockwise90, width: 64, height: 128)).process(source))
        XCTAssertEqual(CVPixelBufferGetWidth(out), 64)
        XCTAssertEqual(CVPixelBufferGetHeight(out), 128)
        XCTAssertGreaterThan(lumaAt(out, 56, 8), 200)
    }

    func testPoolIsBounded() throws {
        let source = try makeImage(width: 64, height: 32) { _, _ in 16 }
        let rotator = try XCTUnwrap(FrameRotator(rotation: .half, width: 64, height: 32))
        var held: [CVPixelBuffer] = []
        for _ in 0..<10 {
            if let f = rotator.process(source) { held.append(f) }
        }
        XCTAssertLessThanOrEqual(held.count, 4)
        XCTAssertGreaterThan(held.count, 0)
    }

    func testPipelineReportsATurnOnce() throws {
        let pipeline = ScreenPipeline(encoder: VideoToolboxEncoder())
        var sizes: [String] = []
        pipeline.onSourceChanged = { sizes.append("\($0)x\($1)") }
        let frame = try makeImage(width: 64, height: 32) { _, _ in 16 }
        pipeline.push(frame, rotation: .none, ptsUs: 1)
        pipeline.push(frame, rotation: .none, ptsUs: 2)
        pipeline.push(frame, rotation: .clockwise90, ptsUs: 3)
        pipeline.push(frame, rotation: .clockwise90, ptsUs: 4)
        pipeline.push(frame, rotation: .counterClockwise90, ptsUs: 5)
        XCTAssertEqual(sizes, ["64x32", "32x64"])
    }
}

final class PacerTests: XCTestCase {
    func testFullRatePassesWithJitter() {
        var pacer = FramePacer()
        var passed = 0
        for i in 0..<600 {
            let jitter = (i % 3 == 0) ? 4_000 : 0
            let pts = UInt64(1_000_000 + i * 16_667 - jitter)
            if pacer.admits(ptsUs: pts, fps: 60) {
                pacer.accept(ptsUs: pts, fps: 60)
                passed += 1
            }
        }
        XCTAssertGreaterThanOrEqual(passed, 595)
    }

    func testSixtyDownToThirtyTakesEveryOtherFrame() {
        var pacer = FramePacer()
        var passed = 0
        for i in 0..<600 {
            let pts = UInt64(1_000_000 + i * 16_667)
            if pacer.admits(ptsUs: pts, fps: 30) {
                pacer.accept(ptsUs: pts, fps: 30)
                passed += 1
            }
        }
        XCTAssertEqual(passed, 300, accuracy: 3)
    }
}

final class AudioRoundTripTests: XCTestCase {
    /// Stereo 16 bit interleaved, 440 Hz.
    private func tone(frames: Int, rate: Double, start: Int) -> Data {
        var out = Data(capacity: frames * 4)
        for i in 0..<frames {
            let v = Int16(sin(2 * .pi * 440 * Double(start + i) / rate) * 12_000)
            withUnsafeBytes(of: v.littleEndian) { out.append(contentsOf: Array($0) + Array($0)) }
        }
        return out
    }

    private func pcmFormat(rate: Double) -> AudioStreamBasicDescription {
        AudioStreamBasicDescription(mSampleRate: rate, mFormatID: kAudioFormatLinearPCM,
                                    mFormatFlags: kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked,
                                    mBytesPerPacket: 4, mFramesPerPacket: 1, mBytesPerFrame: 4, mChannelsPerFrame: 2,
                                    mBitsPerChannel: 16, mReserved: 0)
    }

    private func encodeAac(rate: Double) throws -> [AudioPacket] {
        let encoder = AacEncoder()
        var packets: [AudioPacket] = []
        try encoder.start(sink: { packets.append($0) }, onError: { XCTFail("encoder: \($0)") })
        var start = 0
        for i in 0..<50 {
            encoder.push(pcm: [tone(frames: 960, rate: rate, start: start)], format: pcmFormat(rate: rate),
                         ptsUs: 1_000_000 + Int64(i) * Int64(960.0 / rate * 1e6))
            start += 960
        }
        return packets
    }

    func testAacRoundTrip() throws {
        let packets = try encodeAac(rate: 48_000)
        XCTAssertGreaterThan(packets.count, 40, "1024 samples per packet")
        let decoder = try XCTUnwrap(PacketDecoder.aac(config: AacEncoder().config))
        var frames = 0
        var peak: Float = 0
        for p in packets {
            guard let out = decoder.decode(p.data) else { continue }
            frames += Int(out.frameLength)
            for i in 0..<Int(out.frameLength) { peak = max(peak, abs(out.floatChannelData![0][i])) }
        }
        XCTAssertGreaterThan(frames, 1024 * 30)
        XCTAssertGreaterThan(peak, 0.2, "the tone must survive")
        XCTAssertTrue(packets.indices.dropFirst().allSatisfy { packets[$0].ptsUs >= packets[$0 - 1].ptsUs })
    }

    func testAacFollowsAnotherInputRate() throws {
        let packets = try encodeAac(rate: 44_100)
        XCTAssertGreaterThan(packets.count, 30)
        XCTAssertNotNil(PacketDecoder.aac(config: AacEncoder().config)?.decode(packets[5].data))
    }

    func testAudioSpecificConfigIsTheStandardTwoBytes() {
        XCTAssertEqual([UInt8](AacEncoder().config), [0x11, 0x90])
        XCTAssertEqual(AudioSpecificConfig.parse(Data([0x11, 0x90])), AudioSpecificConfig(sampleRate: 48_000, channels: 2))
        XCTAssertEqual(AudioSpecificConfig.parse(Data([0x12, 0x10])), AudioSpecificConfig(sampleRate: 44_100, channels: 2))
        XCTAssertNil(AudioSpecificConfig.parse(Data([0x11])))
    }

    /// Opus packets made by AudioToolbox's own encoder, read back by the receiver's decoder.
    func testOpusDecoderReadsRealPackets() throws {
        try XCTSkipUnless(AudioPlayer.opusAvailable)
        var source = pcmFormat(rate: 48_000)
        var target = AudioStreamBasicDescription(mSampleRate: 48_000, mFormatID: kAudioFormatOpus, mFormatFlags: 0, mBytesPerPacket: 0,
                                                 mFramesPerPacket: 960, mBytesPerFrame: 0, mChannelsPerFrame: 2, mBitsPerChannel: 0,
                                                 mReserved: 0)
        var made: AudioConverterRef?
        XCTAssertEqual(AudioConverterNew(&source, &target, &made), noErr)
        let converter = try XCTUnwrap(made)
        defer { AudioConverterDispose(converter) }

        final class Feed {
            var pcm: Data
            var handed: UnsafeMutableRawPointer?
            init(_ pcm: Data) { self.pcm = pcm }
            deinit { handed?.deallocate() }
        }
        let feed = Feed(tone(frames: 48_000, rate: 48_000, start: 0))
        var packets: [Data] = []
        let output = UnsafeMutableRawPointer.allocate(byteCount: 4000, alignment: 16)
        defer { output.deallocate() }
        for _ in 0..<40 {
            var count: UInt32 = 1
            var description = AudioStreamPacketDescription()
            var list = AudioBufferList(mNumberBuffers: 1, mBuffers: AudioBuffer(mNumberChannels: 2, mDataByteSize: 4000, mData: output))
            let status = AudioConverterFillComplexBuffer(converter, { _, n, data, _, user in
                let feed = Unmanaged<Feed>.fromOpaque(user!).takeUnretainedValue()
                let frames = min(Int(n.pointee), feed.pcm.count / 4)
                guard frames > 0 else {
                    n.pointee = 0
                    return 1
                }
                feed.handed?.deallocate()
                let copy = UnsafeMutableRawPointer.allocate(byteCount: frames * 4, alignment: 16)
                feed.pcm.copyBytes(to: copy.assumingMemoryBound(to: UInt8.self), count: frames * 4)
                feed.handed = copy
                feed.pcm = Data(feed.pcm.dropFirst(frames * 4))
                data.pointee.mBuffers.mData = copy
                data.pointee.mBuffers.mDataByteSize = UInt32(frames * 4)
                data.pointee.mBuffers.mNumberChannels = 2
                n.pointee = UInt32(frames)
                return noErr
            }, Unmanaged.passUnretained(feed).toOpaque(), &count, &list, &description)
            XCTAssertEqual(status, noErr)
            guard count > 0 else { continue }
            packets.append(Data(bytes: output + Int(description.mStartOffset), count: Int(description.mDataByteSize)))
        }
        XCTAssertGreaterThan(packets.count, 20)

        var head = Data("OpusHead".utf8)
        head.append(contentsOf: [1, 2, 0x38, 0x01, 0x80, 0xBB, 0, 0, 0, 0, 0])
        let decoder = try XCTUnwrap(PacketDecoder.opus(head: head))
        var frames = 0
        for p in packets { frames += Int(decoder.decode(p)?.frameLength ?? 0) }
        XCTAssertGreaterThan(frames, 960 * 15)
    }

    func testBadConfigGivesNoDecoder() {
        XCTAssertNil(PacketDecoder.opus(head: Data("NotOpus!".utf8)))
        XCTAssertNil(PacketDecoder.aac(config: Data()))
    }

    private func header(_ pts: UInt64, config: Bool = false, count: Int) -> FrameHeader {
        FrameHeader(type: .audio, flags: config ? FrameFlags.config : 0, ptsUs: pts, seq: 0, length: count)
    }

    func testLatePacketsAreDroppedAndCounted() throws {
        let packets = try encodeAac(rate: 48_000)
        let player = AudioPlayer(headless: true)
        var config = Data([Codec.aacLc])
        config.append(AacEncoder().config)
        player.push(header: header(0, config: true, count: config.count), payload: config, recvUs: 0)
        player.setClockOffset(0)
        // Target 40 ms: a packet that arrives 100 ms after capture is late, one at 10 ms is not.
        player.push(header: header(1_000_000, count: packets[3].data.count), payload: packets[3].data, recvUs: 1_100_000)
        XCTAssertEqual(player.totalLate, 1)
        player.push(header: header(2_000_000, count: packets[4].data.count), payload: packets[4].data, recvUs: 2_010_000)
        XCTAssertEqual(player.totalLate, 1)
        XCTAssertEqual(player.totalReceived, 2)
    }

    func testTargetLatencyStaysInItsBounds() {
        let player = AudioPlayer(headless: true)
        player.setTargetLatency(videoUs: 10)
        XCTAssertEqual(player.currentTargetUs, AudioPlayer.minTargetUs)
        player.setTargetLatency(videoUs: 1_000_000)
        XCTAssertEqual(player.currentTargetUs, AudioPlayer.maxTargetUs)
    }
}

final class AnnexBTests: XCTestCase {
    func testFindsThreeAndFourByteStartCodes() {
        let stream = Data([0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4, 5])
        XCTAssertEqual(AnnexB.nalRanges(stream), [4..<7, 10..<12, 16..<19])
    }

    func testParameterSetsNeedBothForH264() {
        let both = Data([0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2])
        XCTAssertEqual(AnnexB.parameterSets(config: both, codec: Codec.h264), [Data([0x67, 1]), Data([0x68, 2])])
        XCTAssertNil(AnnexB.parameterSets(config: Data([0, 0, 0, 1, 0x67, 1]), codec: Codec.h264))
    }

    func testLengthPrefixRoundTrip() {
        var bytes: [UInt8] = [0, 0, 0, 2, 0x65, 1, 0, 0, 0, 1, 0x41]
        let ok = bytes.withUnsafeMutableBytes { AnnexB.lengthPrefixedToStartCodes($0) }
        XCTAssertTrue(ok)
        XCTAssertEqual(bytes, [0, 0, 0, 1, 0x65, 1, 0, 0, 0, 1, 0x41])
        var broken: [UInt8] = [0, 0, 0, 9, 0x65]
        XCTAssertFalse(broken.withUnsafeMutableBytes { AnnexB.lengthPrefixedToStartCodes($0) })
    }

    func testOutOfBandTypes() {
        XCTAssertTrue(AnnexB.isOutOfBand(nalType: 7, codec: Codec.h264))
        XCTAssertTrue(AnnexB.isOutOfBand(nalType: 9, codec: Codec.h264))
        XCTAssertFalse(AnnexB.isOutOfBand(nalType: 5, codec: Codec.h264))
        XCTAssertTrue(AnnexB.isOutOfBand(nalType: 33, codec: Codec.h265))
        XCTAssertFalse(AnnexB.isOutOfBand(nalType: 19, codec: Codec.h265))
    }
}
