import CastHopProtocol
@testable import CastHopSender
import XCTest

final class SendQueueTests: XCTestCase {
    private func frame(_ pts: UInt64, _ flags: UInt16 = 0, at: UInt64? = nil) -> EncodedFrame {
        EncodedFrame(data: Data(count: 10), ptsUs: pts, flags: flags, enqueuedUs: at ?? pts)
    }

    private func key(_ pts: UInt64, at: UInt64? = nil) -> EncodedFrame { frame(pts, FrameFlags.keyframe, at: at) }
    private func config(at: UInt64) -> EncodedFrame { frame(0, FrameFlags.config, at: at) }

    func testPassesFramesInOrder() {
        let q = SendQueue(maxFrames: 3)
        XCTAssertTrue(q.offer(key(1), nowUs: 1))
        XCTAssertTrue(q.offer(frame(2), nowUs: 2))
        XCTAssertEqual(q.take(timeoutMs: 0)?.0.ptsUs, 1)
        XCTAssertEqual(q.take(timeoutMs: 0)?.0.ptsUs, 2)
        XCTAssertNil(q.take(timeoutMs: 0))
    }

    func testOverflowDropsPFramesUntilNextKeyframe() {
        var drops = 0
        let q = SendQueue(maxFrames: 2, onDrop: { drops += $0 })
        q.offer(key(1), nowUs: 1)
        q.offer(frame(2), nowUs: 2)
        XCTAssertFalse(q.offer(frame(3), nowUs: 3))
        XCTAssertFalse(q.offer(frame(4), nowUs: 4))
        XCTAssertGreaterThanOrEqual(drops, 3)
        XCTAssertTrue(q.offer(key(5), nowUs: 5))
        let first = q.take(timeoutMs: 0)
        XCTAssertEqual(first?.0.ptsUs, 5)
        XCTAssertEqual(first?.1, true)
        XCTAssertTrue(q.offer(frame(6), nowUs: 6))
        XCTAssertEqual(q.take(timeoutMs: 0)?.1, false)
    }

    func testStaleFramesTriggerDropEvenIfCountIsLow() {
        let q = SendQueue(maxFrames: 10, maxAgeUs: 50_000)
        q.offer(key(1, at: 0), nowUs: 0)
        XCTAssertFalse(q.offer(frame(2, at: 60_000), nowUs: 60_000))
        XCTAssertGreaterThanOrEqual(q.droppedTotal, 1)
    }

    func testKeyframeIsAcceptedOnOverflowAndConfigIsNeverDropped() {
        let q = SendQueue(maxFrames: 1)
        q.offer(config(at: 0), nowUs: 0)
        q.offer(key(1), nowUs: 1)
        XCTAssertTrue(q.offer(key(2), nowUs: 2))
        XCTAssertEqual(q.take(timeoutMs: 0)?.0.isConfig, true)
        XCTAssertEqual(q.take(timeoutMs: 0)?.0.ptsUs, 2)
    }

    func testTakeBlocksUntilFrameOrClose() {
        let q = SendQueue()
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.05) { q.offer(self.key(7), nowUs: 7) }
        XCTAssertEqual(q.take(timeoutMs: 2000)?.0.ptsUs, 7)
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.05) { q.close() }
        XCTAssertNil(q.take(timeoutMs: 2000))
        XCTAssertFalse(q.offer(key(8), nowUs: 8))
    }

    func testNewConfigClearsStaleFrames() {
        let q = SendQueue(maxFrames: 4)
        q.offer(key(1), nowUs: 1)
        q.offer(frame(2), nowUs: 2)
        XCTAssertTrue(q.offer(frame(3, FrameFlags.config), nowUs: 3))
        XCTAssertTrue(q.offer(key(4), nowUs: 4))
        XCTAssertEqual(q.take(timeoutMs: 0)?.0.isConfig, true)
        XCTAssertEqual(q.take(timeoutMs: 0)?.0.ptsUs, 4)
        XCTAssertNil(q.take(timeoutMs: 0))
    }

    func testDropUntilKeyframeAfterReconnect() {
        let q = SendQueue(maxFrames: 4)
        q.offer(key(1), nowUs: 1)
        q.offer(frame(2), nowUs: 2)
        q.dropUntilKeyframe()
        XCTAssertFalse(q.offer(frame(3), nowUs: 3))
        XCTAssertTrue(q.offer(key(4), nowUs: 4))
        let first = q.take(timeoutMs: 0)
        XCTAssertEqual(first?.0.ptsUs, 4)
        XCTAssertEqual(first?.1, true)
    }

    func testAudioQueueDropsOldestWhenFull() {
        let q = AudioQueue(capacity: 2)
        for i in 0..<3 { q.offer(AudioPacket(data: Data(), ptsUs: UInt64(i))) }
        XCTAssertEqual(q.dropped, 1)
        XCTAssertEqual(q.take(timeoutMs: 0)?.ptsUs, 1)
        XCTAssertEqual(q.take(timeoutMs: 0)?.ptsUs, 2)
        XCTAssertNil(q.take(timeoutMs: 0))
    }
}

final class KeyframeGateTests: XCTestCase {
    func testRequestsPassWhileNothingIsBeingWritten() {
        let g = KeyframeGate()
        XCTAssertTrue(g.onRequest(nowUs: 0))
        XCTAssertTrue(g.onRequest(nowUs: 1))
    }

    func testRequestsDuringAWriteLeaveAsOneWhenItEnds() {
        let g = KeyframeGate()
        g.onWriteStarted(nowUs: 0)
        XCTAssertFalse(g.onRequest(nowUs: 10))
        XCTAssertFalse(g.onRequest(nowUs: 20))
        XCTAssertTrue(g.onWriteEnded())
        XCTAssertFalse(g.onWriteEnded())
    }

    func testAWriteWithoutRequestsAsksForNothing() {
        let g = KeyframeGate()
        g.onWriteStarted(nowUs: 0)
        XCTAssertFalse(g.onWriteEnded())
    }

    func testAStalledWriteStopsHoldingRequests() {
        let g = KeyframeGate(timeoutUs: 1_000)
        g.onWriteStarted(nowUs: 0)
        XCTAssertFalse(g.onRequest(nowUs: 999))
        XCTAssertTrue(g.onRequest(nowUs: 1_000))
    }
}

final class QualityGovernorTests: XCTestCase {
    private let lo: Int64 = 4_000_000
    private let hi: Int64 = 24_000_000
    private let middle: Int64 = 12_000_000

    /// Ticks every 250 ms and returns the first step and when it came.
    private func run(_ g: QualityGovernor, _ bps: Int64, from: Int64, forMs: Int64) -> (QualityStep, Int64)? {
        var t = from
        while t <= from + forMs {
            if let s = g.tick(nowMs: t, bitrateBps: bps, minBps: lo, maxBps: hi) { return (s, t) }
            t += 250
        }
        return nil
    }

    func testEightSecondsAtTheFloorStepsDown() {
        let g = QualityGovernor()
        XCTAssertNil(run(g, lo, from: 0, forMs: 7_750))
        XCTAssertEqual(g.tick(nowMs: 8_000, bitrateBps: lo, minBps: lo, maxBps: hi), .down)
    }

    func testLeavingTheFloorRestartsTheWait() {
        let g = QualityGovernor()
        XCTAssertNil(run(g, lo, from: 0, forMs: 7_000))
        XCTAssertNil(g.tick(nowMs: 7_250, bitrateBps: middle, minBps: lo, maxBps: hi))
        XCTAssertNil(run(g, lo, from: 7_500, forMs: 7_750))
    }

    func testAMinuteNearTheCeilingStepsUp() {
        let r = run(QualityGovernor(), hi, from: 0, forMs: 61_000)
        XCTAssertEqual(r?.0, .up)
        XCTAssertEqual(r?.1, 60_000)
    }

    func testAMiddleRateNeverSteps() {
        XCTAssertNil(run(QualityGovernor(), middle, from: 0, forMs: 600_000))
    }

    func testNoSecondStepWhileTheFirstIsPending() {
        let g = QualityGovernor()
        _ = run(g, lo, from: 0, forMs: 8_000)
        XCTAssertNil(run(g, lo, from: 8_250, forMs: 60_000))
    }

    func testSwitchesKeepAMinimumGap() throws {
        let g = QualityGovernor()
        let at = try XCTUnwrap(run(g, lo, from: 0, forMs: 8_000)).1
        g.onSwitched(nowMs: at, step: .down)
        let next = try XCTUnwrap(run(g, lo, from: at + 250, forMs: 20_000))
        XCTAssertEqual(next.0, .down)
        XCTAssertEqual(next.1, at + 15_000)
    }

    func testAFailedRiseDoublesTheNextWaitUpToTheLimit() throws {
        let g = QualityGovernor()
        var t: Int64 = 0
        for wait in [60_000, 120_000, 240_000, 480_000, 480_000] as [Int64] {
            t += 20_000
            let (step, at) = try XCTUnwrap(run(g, hi, from: t, forMs: wait))
            XCTAssertEqual(step, .up)
            XCTAssertEqual(at, t + wait)
            g.onSwitched(nowMs: at, step: .up)
            t = at + 20_000
            g.onSwitched(nowMs: t, step: .down)
        }
    }

    func testALateFallDoesNotLengthenTheWait() throws {
        let g = QualityGovernor()
        let at = try XCTUnwrap(run(g, hi, from: 0, forMs: 60_000)).1
        g.onSwitched(nowMs: at, step: .up)
        let later = at + 40_000
        g.onSwitched(nowMs: later, step: .down)
        let start = later + 20_000
        let r = run(g, hi, from: start, forMs: 61_000)
        XCTAssertEqual(r?.0, .up)
        XCTAssertEqual(r?.1, start + 60_000)
    }

    func testARejectedStepWaitsAFullPeriod() {
        let g = QualityGovernor()
        _ = run(g, lo, from: 0, forMs: 8_000)
        g.onRejected()
        XCTAssertNil(run(g, lo, from: 8_250, forMs: 7_750))
        XCTAssertEqual(g.tick(nowMs: 16_250, bitrateBps: lo, minBps: lo, maxBps: hi), .down)
    }

    func testClearedTimersForgetTimeSpentPaused() {
        let g = QualityGovernor()
        XCTAssertNil(run(g, lo, from: 0, forMs: 7_000))
        g.clearTimers()
        XCTAssertNil(run(g, lo, from: 7_250, forMs: 7_750))
    }
}

final class StreamPlannerTests: XCTestCase {
    /// A typical hardware encoder: 16 pixel alignment and a pixel rate limit.
    private struct FakeEncoder: VideoEncoderCaps {
        var name = "fake.avc.encoder"
        var hardware = true
        var maxW = 1920
        var maxH = 1088
        var maxPixelsPerSec = 1920 * 1088 * 60
        var widthAlignment = 16
        var heightAlignment = 16
        var bitrateRange: ClosedRange<Int64> = 64_000...40_000_000
        var codec: UInt8 { Codec.h264 }

        func isSupported(width: Int, height: Int, fps: Int) -> Bool {
            let landscape = width <= maxW && height <= maxH
            let portrait = width <= maxH && height <= maxW
            return (landscape || portrait) && width % widthAlignment == 0 && height % heightAlignment == 0
                && width * height * fps <= maxPixelsPerSec
        }
    }

    private let board = ReceiverDisplay(width: 1920, height: 1080, refreshHz: 60)

    private func plan(_ w: Int, _ h: Int, _ preset: Preset, _ receiver: ReceiverDisplay? = nil, _ enc: [VideoEncoderCaps]? = nil,
                      _ o: StreamOverrides = StreamOverrides()) -> StreamPlan? {
        StreamPlanner.plan(sourceWidth: w, sourceHeight: h, preset: preset, receiver: receiver ?? board,
                           encoders: enc ?? [FakeEncoder()], overrides: o)
    }

    func testBestPresetScales16by10TabletToFit1080pBoard() throws {
        let p = try XCTUnwrap(plan(2560, 1600, .best))
        XCTAssertEqual(p.width, 1728)
        XCTAssertEqual(p.height, 1072)
        XCTAssertEqual(p.fps, 60)
        XCTAssertEqual(p.startBps, 16_000_000)
        XCTAssertEqual(p.maxBps, 24_000_000)
        XCTAssertTrue(p.hardware)
    }

    func testPortraitSourceFitsBoardHeight() throws {
        let p = try XCTUnwrap(plan(1200, 1920, .balanced))
        XCTAssertGreaterThan(p.height, p.width)
        XCTAssertEqual(p.height, 1072)
        XCTAssertEqual(Double(p.width) / Double(p.height), 0.625, accuracy: 0.02)
    }

    func testNeverUpscalesSmallSource() throws {
        let p = try XCTUnwrap(plan(1280, 800, .best))
        XCTAssertEqual(p.width, 1280)
        XCTAssertEqual(p.height, 800)
    }

    func testWeakNetworkPresetUses30FpsAndLowBitrate() throws {
        let p = try XCTUnwrap(plan(2560, 1600, .weakNetwork))
        XCTAssertEqual(p.fps, 30)
        XCTAssertEqual(p.width, 1280)
        XCTAssertEqual(p.height, 800)
        XCTAssertEqual(p.startBps, 3_000_000)
        XCTAssertEqual(p.minBps, 800_000)
    }

    func testReceiverRefreshLimitsFps() throws {
        let p = try XCTUnwrap(plan(2560, 1600, .best, ReceiverDisplay(width: 1920, height: 1080, refreshHz: 30)))
        XCTAssertEqual(p.fps, 30)
    }

    func testHighFpsOnlyWhenResolutionStaysCloseToFull() throws {
        var enc45 = FakeEncoder()
        enc45.maxPixelsPerSec = 1920 * 1088 * 45
        let p = try XCTUnwrap(plan(2560, 1600, .best, nil, [enc45]))
        XCTAssertEqual(p.fps, 60)
        XCTAssertEqual(p.width, 1552)
        XCTAssertLessThanOrEqual(p.width * p.height * 60, enc45.maxPixelsPerSec)

        var enc30 = FakeEncoder()
        enc30.maxPixelsPerSec = 1920 * 1088 * 30
        let p2 = try XCTUnwrap(plan(2560, 1600, .best, nil, [enc30]))
        XCTAssertEqual(p2.fps, 30)
        XCTAssertEqual(p2.width, 1728)
    }

    func testPrefersHardwareEncoder() {
        var sw = FakeEncoder()
        sw.name = "software"
        sw.hardware = false
        var hw = FakeEncoder()
        hw.name = "hardware"
        XCTAssertEqual(plan(1920, 1200, .best, nil, [sw, hw])?.encoderName, "hardware")
    }

    func testBitrateIsClampedToEncoderRange() throws {
        var enc = FakeEncoder()
        enc.bitrateRange = 100_000...10_000_000
        let p = try XCTUnwrap(plan(1920, 1200, .best, nil, [enc]))
        XCTAssertEqual(p.maxBps, 10_000_000)
        XCTAssertEqual(p.startBps, 10_000_000)
        XCTAssertEqual(p.minBps, 4_000_000)
    }

    func testOverridesApply() throws {
        let p = try XCTUnwrap(plan(2560, 1600, .best, nil, nil, StreamOverrides(longSide: 1280, fps: 30, maxBitrateBps: 6_000_000)))
        XCTAssertEqual(p.width, 1280)
        XCTAssertEqual(p.fps, 30)
        XCTAssertEqual(p.maxBps, 6_000_000)
        XCTAssertEqual(p.startBps, 6_000_000)
    }

    func testUses2560OnlyFor4kBoardWithCapableEncoder() throws {
        var big = FakeEncoder()
        big.maxW = 4096
        big.maxH = 2304
        big.maxPixelsPerSec = 4096 * 2304 * 30
        let board4k = ReceiverDisplay(width: 3840, height: 2160, refreshHz: 60)
        XCTAssertEqual(plan(2560, 1600, .best, board4k, [big])?.width, 2560)
        XCTAssertEqual(plan(2560, 1600, .best, board4k)?.width, 1728)
    }

    func testNoEncoderMeansNoPlan() {
        XCTAssertNil(plan(1920, 1200, .best, nil, []))
        var useless = FakeEncoder()
        useless.maxW = 176
        useless.maxH = 144
        XCTAssertNil(plan(1920, 1200, .best, nil, [useless]))
    }

    func testSettingsOnlyOfferSupportedOptions() {
        var enc720 = FakeEncoder()
        enc720.maxW = 1280
        enc720.maxH = 720
        enc720.maxPixelsPerSec = 1280 * 720 * 30
        XCTAssertEqual(StreamPlanner.supportedFps(encoders: [enc720], sourceWidth: 2560, sourceHeight: 1600), [30])
        XCTAssertEqual(StreamPlanner.supportedFps(encoders: [FakeEncoder()], sourceWidth: 2560, sourceHeight: 1600), [30, 60])
        XCTAssertEqual(StreamPlanner.supportedLongSides(encoders: [FakeEncoder()], sourceWidth: 2560, sourceHeight: 1600), [1280, 1600])
        XCTAssertEqual(StreamPlanner.supportedLongSides(encoders: [FakeEncoder()], sourceWidth: 1920, sourceHeight: 1080), [1280, 1600, 1920])
        XCTAssertTrue(StreamPlanner.supportedLongSides(encoders: [enc720], sourceWidth: 2560, sourceHeight: 1600).allSatisfy { $0 <= 1280 })
    }

    func testPresetsStepOneAtATimeAndStopAtTheEnds() {
        XCTAssertEqual(Preset.best.lower, .balanced)
        XCTAssertEqual(Preset.balanced.lower, .weakNetwork)
        XCTAssertNil(Preset.weakNetwork.lower)
        XCTAssertEqual(Preset.weakNetwork.higher, .balanced)
        XCTAssertNil(Preset.best.higher)
    }
}
