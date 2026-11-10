import Foundation
import XCTest
@testable import CastHopProtocol

final class QrPayloadTests: XCTestCase {
    private let token = Data(hex: "e0e1e2e3e4e5e6e7e8e9eaebecedeeef")!
    private let fp = Auth.sha256(Data("x".utf8))

    func testRoundTripWithUnicodeAndReservedCharacters() throws {
        let p = QrPayload(hosts: ["192.168.43.1"], port: 47800, token: token, certFingerprint: fp,
                          name: "Çağrı'nın tahtası #3 & 100%")
        XCTAssertEqual(p, try QrPayload.parse(p.toUri()))
    }

    func testUnknownParametersAreIgnoredAndNameIsOptional() throws {
        let uri = QrPayload(hosts: ["10.0.0.1"], port: 1, token: token, certFingerprint: fp, name: "").toUri()
            .replacingOccurrences(of: "&n=", with: "&x=future&n=")
        XCTAssertEqual("", try QrPayload.parse(uri).name)
        XCTAssertEqual("", try QrPayload.parse(uri.components(separatedBy: "&n=")[0]).name)
    }

    func testIPv4ValidationIsStrict() {
        XCTAssertTrue(QrPayload.isIPv4("0.0.0.0"))
        XCTAssertTrue(QrPayload.isIPv4("255.255.255.255"))
        for bad in ["01.2.3.4", "1.2.3", "1.2.3.4.5", "a.b.c.d", "1.2.3.-4", "256.1.1.1"] {
            XCTAssertFalse(QrPayload.isIPv4(bad), bad)
        }
    }

    func testBadPercentEncodingIsABadField() {
        let uri = QrPayload(hosts: ["10.0.0.1"], port: 1, token: token, certFingerprint: fp, name: "a").toUri() + "%G1"
        XCTAssertThrowsError(try QrPayload.parse(uri)) { XCTAssertEqual($0 as? QrError, .badField) }
    }
}

final class DiscoveryTxtTests: XCTestCase {
    func testParsesNameAndFingerprintPrefix() {
        let fp = Data((0..<16).map { UInt8($0) })
        let r = DiscoveryTxt.parse(["v": "1", "name": "7-A Sınıfı", "fp": Base64Url.encode(fp)])
        XCTAssertEqual(r?.name, "7-A Sınıfı")
        XCTAssertEqual(r?.fingerprintPrefix, fp)
    }

    func testRejectsOtherVersionsAndBadPrefix() {
        XCTAssertNil(DiscoveryTxt.parse(["v": "2", "fp": "AAAAAAAAAAAAAAAAAAAAAA"]))
        XCTAssertNil(DiscoveryTxt.parse(["v": "1", "fp": "kısa"]))
        XCTAssertNil(DiscoveryTxt.parse(["v": "1"]))
    }

    func testRecordUsesTheFirstSixteenBytes() {
        let fp = Data((0..<32).map { UInt8($0) })
        let record = DiscoveryTxt.record(name: "Tahta", fingerprint: fp)
        XCTAssertEqual(DiscoveryTxt.parse(record)?.fingerprintPrefix, fp.prefix(16))
    }

    func testKindIsOptionalAndUnknownValuesAreIgnored() {
        let fp = Base64Url.encode(Data(count: 16))
        XCTAssertEqual(DiscoveryTxt.parse(["v": "1", "fp": fp, "kind": "tv"])?.kind, "tv")
        XCTAssertNil(DiscoveryTxt.parse(["v": "1", "fp": fp])?.kind)
        XCTAssertNil(DiscoveryTxt.parse(["v": "1", "fp": fp, "kind": "toaster"])?.kind)
    }

    func testRecordCarriesTheKind() {
        let record = DiscoveryTxt.record(name: "Tahta", fingerprint: Data(count: 32), kind: "pc")
        XCTAssertEqual(DiscoveryTxt.parse(record)?.kind, "pc")
        XCTAssertTrue(DiscoveryTxt.kinds.contains(DiscoveryTxt.localKind))
    }

    func testInstanceNameNeverSplitsACharacter() {
        XCTAssertEqual(DiscoveryTxt.instanceName(""), "CastHop")
        XCTAssertEqual(DiscoveryTxt.instanceName("Tahta"), "Tahta")
        let long = String(repeating: "a", count: 62) + "ğğ"
        let cut = DiscoveryTxt.instanceName(long)
        XCTAssertEqual(cut, String(repeating: "a", count: 62))
        XCTAssertLessThanOrEqual(cut.utf8.count, 63)
    }
}

final class ClockSyncTests: XCTestCase {
    /// Responder clock = initiator + offset, with separate delays each way.
    @discardableResult
    private func exchange(_ cs: inout ClockSync, t0: Int64, offset: Int64, up: Int64, down: Int64,
                          proc: Int64 = 100) -> ClockSync.Sample? {
        cs.onPong(t0Us: t0, t1Us: t0 + up + offset, t2Us: t0 + up + proc + offset, t3Us: t0 + up + proc + down)
    }

    func testSymmetricPathGivesExactOffsetAndRtt() {
        var cs = ClockSync()
        let s = exchange(&cs, t0: 1_000_000, offset: 5_000_000, up: 2_000, down: 2_000)
        XCTAssertEqual(s?.offsetUs, 5_000_000)
        XCTAssertEqual(s?.rttUs, 4_000)
    }

    func testMinimumRttSampleWins() {
        var cs = ClockSync()
        exchange(&cs, t0: 0, offset: -300_000, up: 40_000, down: 2_000)
        exchange(&cs, t0: 1_000_000, offset: -300_000, up: 1_000, down: 1_000)
        exchange(&cs, t0: 2_000_000, offset: -300_000, up: 2_000, down: 30_000)
        XCTAssertEqual(cs.offsetUs, -300_000)
        XCTAssertEqual(cs.rttUs, 2_000)
    }

    func testWindowEvictsOldSamples() {
        var cs = ClockSync(window: 2)
        exchange(&cs, t0: 0, offset: 10, up: 100, down: 100)
        exchange(&cs, t0: 1_000, offset: 10, up: 5_000, down: 5_000)
        exchange(&cs, t0: 2_000, offset: 10, up: 3_000, down: 3_000)
        XCTAssertEqual(cs.rttUs, 6_000)
    }

    func testNegativeRttIsIgnored() {
        var cs = ClockSync()
        XCTAssertNil(cs.onPong(t0Us: 100, t1Us: 50, t2Us: 1_000, t3Us: 200))
        XCTAssertNil(cs.offsetUs)
    }
}

final class FrameParserTests: XCTestCase {
    func testRoundTripOverATricklingStream() throws {
        let big = Data((0..<200_000).map { UInt8(truncatingIfNeeded: $0 * 31) })
        var stream = Frames.encode(type: .video, flags: FrameFlags.config, ptsUs: 10, seq: 0, payload: Data([1, 2, 3]))
        stream += Frames.encode(type: .video, flags: FrameFlags.keyframe, ptsUs: 20, seq: 1, payload: big)
        stream += Frames.encode(type: .control, flags: 0, ptsUs: 30, seq: 0, payload: ControlCodec.encode(.pause))

        var parser = FrameParser()
        var frames: [Frame] = []
        var i = 0
        while i < stream.count {
            // One byte through the first frames, larger chunks after that keep the test quick.
            let n = i < 200 ? 1 : 4096
            try parser.feed(stream.subdata(in: i..<min(i + n, stream.count)))
            while let f = parser.next() { frames.append(f) }
            i += n
        }
        XCTAssertEqual(frames.count, 3)
        XCTAssertTrue(frames[0].header.isConfig)
        XCTAssertEqual(frames[0].payload, Data([1, 2, 3]))
        XCTAssertTrue(frames[1].header.isKeyframe)
        XCTAssertEqual(frames[1].header.seq, 1)
        XCTAssertEqual(frames[1].payload, big)
        XCTAssertEqual(try ControlCodec.decode(frames[2].payload), .pause)
        XCTAssertTrue(parser.atBoundary)
    }

    func testStreamEndingInsideAFrameIsNotAtABoundary() throws {
        let frame = Frames.encode(type: .audio, flags: 0, ptsUs: 1, seq: 2, payload: Data(count: 100))
        var parser = FrameParser()
        try parser.feed(frame.prefix(frame.count - 1))
        XCTAssertNil(parser.next())
        XCTAssertFalse(parser.atBoundary)
    }

    func testOversizedLengthIsRejectedBeforeAllocating() {
        var header = Frames.encode(type: .control, flags: 0, ptsUs: 0, seq: 0, payload: Data())
        header[20] = 0x7F
        var parser = FrameParser()
        XCTAssertThrowsError(try parser.feed(header)) { XCTAssertEqual($0 as? ProtocolError, .frameTooLarge) }
    }

    func testRandomInputNeverCrashes() {
        var generator = SystemRandomNumberGenerator()
        for _ in 0..<2000 {
            let bytes = Data((0..<Int.random(in: 0...64, using: &generator)).map { _ in UInt8.random(in: 0...255) })
            var parser = FrameParser()
            try? parser.feed(bytes)
            _ = try? ControlCodec.decode(bytes)
            _ = try? QrPayload.parse(String(decoding: bytes, as: UTF8.self))
        }
    }
}

final class BitrateControllerTests: XCTestCase {
    private let config = BitrateController.Config(initialBps: 16_000_000, minBps: 4_000_000, maxBps: 24_000_000)
    private func make() -> BitrateController { BitrateController(config: config) }

    private func report(_ c2r: UInt32, received: UInt32 = 30, dropped: UInt32 = 0) -> ReceiverReport {
        ReceiverReport(intervalMs: 500, framesReceived: received, framesRendered: received - dropped,
                       framesDropped: dropped, bytesReceived: 1_000_000, c2rAvgUs: c2r, c2rMinUs: c2r, c2rMaxUs: c2r,
                       c2dAvgUs: c2r, decodeBacklog: 0, audioLate: 0)
    }

    func testStableNetworkIncreasesSlowlyAfterTwoSeconds() {
        let c = make()
        var t: Int64 = 0
        for _ in 0..<7 {
            XCTAssertNil(c.tick(nowMs: t))
            t += 250
        }
        var applied = 0
        while t <= 60_000 {
            c.onReceiverReport(nowMs: t, report: report(20_000))
            if c.tick(nowMs: t) != nil { applied += 1 }
            t += 250
        }
        XCTAssertEqual(c.appliedBps, 24_000_000)
        XCTAssertTrue((3...20).contains(applied), "small increases must merge: \(applied)")
    }

    func testQueueDelayTriggersMultiplicativeDecreaseWithHoldTime() {
        let c = make()
        c.onSenderQueueDelay(80_000)
        XCTAssertEqual(c.tick(nowMs: 0), 11_200_000)
        XCTAssertNil(c.tick(nowMs: 250))
        XCTAssertEqual(c.tick(nowMs: 500), 7_840_000)
        XCTAssertEqual(c.tick(nowMs: 1_000), 5_488_000)
        XCTAssertEqual(c.tick(nowMs: 1_500), 4_000_000)
        XCTAssertNil(c.tick(nowMs: 2_000))
        XCTAssertEqual(c.appliedBps, 4_000_000)
    }

    func testRisingReceiverDelayIsCongestion() {
        let c = make()
        _ = c.tick(nowMs: 0)
        c.onReceiverReport(nowMs: 0, report: report(20_000))
        _ = c.tick(nowMs: 250)
        c.onReceiverReport(nowMs: 500, report: report(24_000))
        XCTAssertNil(c.tick(nowMs: 500))
        c.onReceiverReport(nowMs: 1_000, report: report(45_000))
        XCTAssertEqual(c.tick(nowMs: 1_000), 11_200_000)
    }

    func testSteadyHighButFlatDelayIsNotCongestion() {
        let c = make()
        var t: Int64 = 0
        while t <= 10_000 {
            c.onReceiverReport(nowMs: t, report: report(70_000))
            _ = c.tick(nowMs: t)
            t += 250
        }
        XCTAssertGreaterThan(c.appliedBps, 16_000_000)
    }

    func testStillScreenRedrawsAreNotCongestion() {
        let c = make()
        _ = c.tick(nowMs: 0)
        // Two redraws a second: one arrives late and one is dropped.
        c.onReceiverReport(nowMs: 0, report: report(20_000, received: 1))
        c.onReceiverReport(nowMs: 500, report: report(120_000, received: 1, dropped: 1))
        XCTAssertNil(c.tick(nowMs: 500))
        c.onReceiverReport(nowMs: 1_000, report: report(30_000, received: 1))
        XCTAssertNil(c.tick(nowMs: 1_000))
    }

    func testDelayTrendStartsAgainWhenThePictureMoves() {
        let c = make()
        _ = c.tick(nowMs: 0)
        c.onReceiverReport(nowMs: 0, report: report(20_000, received: 1))
        c.onReceiverReport(nowMs: 500, report: report(60_000))
        XCTAssertNil(c.tick(nowMs: 500))
        c.onReceiverReport(nowMs: 1_000, report: report(61_000))
        XCTAssertNil(c.tick(nowMs: 1_000))
        c.onReceiverReport(nowMs: 1_500, report: report(90_000))
        XCTAssertEqual(c.tick(nowMs: 1_500), 11_200_000)
    }

    func testReceiverAndSenderDropsAreCongestion() {
        let c = make()
        c.onReceiverReport(nowMs: 0, report: report(20_000, received: 30, dropped: 3))
        XCTAssertEqual(c.tick(nowMs: 0), 11_200_000)
        c.onSenderDrop()
        XCTAssertEqual(c.tick(nowMs: 600), 7_840_000)
    }

    func testNoIncreaseWithinTwoSecondsAfterCongestion() {
        let c = make()
        c.onSenderDrop()
        _ = c.tick(nowMs: 0)
        let after = c.appliedBps
        var t: Int64 = 250
        while t < 2_000 {
            XCTAssertNil(c.tick(nowMs: t))
            t += 250
        }
        XCTAssertEqual(c.appliedBps, after)
    }

    private func fallToFloor(_ c: BitrateController) -> Int64 {
        var t: Int64 = 0
        while c.appliedBps > config.minBps {
            c.onSenderDrop()
            _ = c.tick(nowMs: t)
            t += 500
        }
        return t
    }

    func testShortCongestionRegainsMostOfTheEarlierRate() {
        let c = make()
        var t = fallToFloor(c)
        let end = t + 7_000
        while t <= end {
            _ = c.tick(nowMs: t)
            t += 250
        }
        XCTAssertGreaterThanOrEqual(c.appliedBps, 13_000_000)
    }

    func testGrowthIsSlowAgainAboveTheRecoveryTarget() {
        let c = make()
        var t = fallToFloor(c)
        let recovered = t + 7_000
        while t <= recovered {
            _ = c.tick(nowMs: t)
            t += 250
        }
        let before = c.targetBps
        XCTAssertGreaterThanOrEqual(before, 13_600_000)
        let end = t + 4_000
        while t < end {
            _ = c.tick(nowMs: t)
            t += 250
        }
        XCTAssertLessThanOrEqual(Double(c.targetBps), Double(before) * 1.4)
    }

    func testCongestionDuringRecoveryLowersTheRecoveryTarget() {
        let c = make()
        var t = fallToFloor(c)
        let end = t + 3_500
        while t <= end {
            _ = c.tick(nowMs: t)
            t += 250
        }
        let atSecondCongestion = c.targetBps
        XCTAssertLessThan(atSecondCongestion, 13_600_000)
        c.onSenderDrop()
        _ = c.tick(nowMs: t)
        let stop = t + 3_500
        while t <= stop {
            t += 250
            _ = c.tick(nowMs: t)
        }
        XCTAssertLessThanOrEqual(c.targetBps, atSecondCongestion)
    }

    func testResetForgetsTheRecoveryTarget() {
        let c = make()
        var t = fallToFloor(c)
        c.reset(initialBps: 4_000_000)
        let end = t + 7_000
        while t <= end {
            _ = c.tick(nowMs: t)
            t += 250
        }
        XCTAssertLessThan(c.appliedBps, 8_000_000)
    }

    func testResetRestoresTheInitialState() {
        let c = make()
        c.onSenderDrop()
        _ = c.tick(nowMs: 0)
        c.reset(initialBps: 8_000_000)
        XCTAssertEqual(c.appliedBps, 8_000_000)
        XCTAssertNil(c.tick(nowMs: 10_000))
    }
}
