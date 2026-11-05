import CastHopProtocol
import XCTest
@testable import CastHopSender

final class SharedStateTests: XCTestCase {
    private func store() -> SharedStore {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        return SharedStore(directory: dir)
    }

    private func receiver(_ tag: UInt8, usedAt: Int64) -> RememberedReceiver {
        RememberedReceiver(name: "r\(tag)", hosts: ["192.168.1.\(tag)"], port: 47800, fingerprint: Data(repeating: tag, count: 32),
                           keyId: Data(repeating: 1, count: 16), pairKey: Data(repeating: 2, count: 32), lastUsedMs: usedAt)
    }

    func testDeviceIdIsStable() {
        let s = store()
        let first = s.deviceId
        XCTAssertEqual(first.count, ControlCodec.idSize)
        XCTAssertEqual(s.deviceId, first)
    }

    func testReceiversAreLatestFirstAndReplaced() {
        let s = store()
        s.save(receiver(1, usedAt: 10))
        s.save(receiver(2, usedAt: 20))
        XCTAssertEqual(s.receivers().map(\.name), ["r2", "r1"])
        var renamed = receiver(1, usedAt: 30)
        renamed.name = "new"
        s.save(renamed)
        XCTAssertEqual(s.receivers().map(\.name), ["new", "r2"])
        s.touch(fingerprint: receiver(2, usedAt: 0).fingerprint, nowMs: 40)
        XCTAssertEqual(s.receivers().map(\.name), ["r2", "new"])
    }

    func testListIsBounded() {
        let s = store()
        for i in 1...25 { s.save(receiver(UInt8(i), usedAt: Int64(i))) }
        XCTAssertEqual(s.receivers().count, 20)
        XCTAssertEqual(s.receivers().first?.name, "r25")
    }

    func testRemovingTheSelectedReceiverClearsTheSelection() {
        let s = store()
        let r = receiver(1, usedAt: 1)
        s.save(r)
        s.selectedFingerprint = r.fingerprint
        XCTAssertEqual(s.selectedReceiver, r)
        s.remove(fingerprint: r.fingerprint)
        XCTAssertNil(s.selectedFingerprint)
        XCTAssertNil(s.selectedReceiver)
    }

    func testSettingsAndStatusSurviveAReload() {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        let first = SharedStore(directory: dir)
        var settings = SenderSettings()
        settings.preset = .weakNetwork
        settings.overrides = StreamOverrides(longSide: 1280, fps: 30, maxBitrateBps: 8_000_000)
        first.settings = settings
        var status = BroadcastStatus()
        status.phase = .ended
        status.failure = .takenOver
        first.status = status

        let second = SharedStore(directory: dir)
        XCTAssertEqual(second.settings, settings)
        XCTAssertEqual(second.status, status)
    }

    func testRejectReasons() {
        XCTAssertEqual(MirrorFailure.from(rejectReason: RejectReason.authFailed), .authFailed)
        XCTAssertEqual(MirrorFailure.from(rejectReason: RejectReason.sessionUnknown), .authFailed)
        XCTAssertEqual(MirrorFailure.from(rejectReason: RejectReason.busyDenied), .busyDenied)
        XCTAssertEqual(MirrorFailure.from(rejectReason: RejectReason.busyTimeout), .busyTimeout)
        XCTAssertEqual(MirrorFailure.from(rejectReason: RejectReason.rateLimited), .rateLimited)
        XCTAssertEqual(MirrorFailure.from(rejectReason: RejectReason.lockedOut), .lockedOut)
        XCTAssertEqual(MirrorFailure.from(rejectReason: RejectReason.versionUnsupported), .incompatible)
        XCTAssertEqual(MirrorFailure.from(rejectReason: RejectReason.malformed), .incompatible)
        XCTAssertEqual(MirrorFailure.from(rejectReason: 200), .internalError)
    }

    func testSessionEnds() {
        XCTAssertNil(MirrorFailure.from(end: .local(byeReason: ByeReason.userStop)))
        XCTAssertEqual(MirrorFailure.from(end: .local(byeReason: ByeReason.projectionStopped)), .broadcastEnded)
        XCTAssertEqual(MirrorFailure.from(end: .remote(byeReason: ByeReason.userStop)), .stoppedByReceiver)
        XCTAssertEqual(MirrorFailure.from(end: .remote(byeReason: ByeReason.takeover)), .takenOver)
        XCTAssertEqual(MirrorFailure.from(end: .remote(byeReason: ByeReason.receiverClosing)), .receiverClosed)
        XCTAssertEqual(MirrorFailure.from(end: .connectionLost(sessionExpired: true)), .connectionLost)
        XCTAssertEqual(MirrorFailure.from(end: .encoderFailed), .encoderFailed)
    }

    func testDarwinNotificationReachesAnObserver() {
        let arrived = expectation(description: "notification")
        let observation = DarwinNotifier.observe(.pause) { arrived.fulfill() }
        DarwinNotifier.post(.pause)
        wait(for: [arrived], timeout: 5)
        withExtendedLifetime(observation) {}
    }
}
