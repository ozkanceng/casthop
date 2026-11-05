import CastHopProtocol
@testable import CastHopReceiver
import XCTest

private let serverNonce = Data(repeating: 5, count: 16)
private let certFp = Auth.sha256(Data("fp".utf8))

private func id(_ byte: UInt8) -> Data {
    var d = Data(count: 16)
    d[0] = byte
    return d
}

private func hello(_ method: UInt8, _ secret: Data, device: Data, keyId: Data? = nil, requestPairing: Bool = false,
                   nonce: Data = serverNonce, fingerprint: Data = certFp) -> Hello {
    let client = id(1)
    return Hello(deviceName: "Tablet", deviceId: device, appVersion: "0.1.0", authMethod: method, clientNonce: client,
                 authProof: Auth.authProof(secret: secret, authMethod: method, serverNonce: nonce, clientNonce: client,
                                           receiverCertFp: fingerprint, deviceId: device),
                 keyId: keyId, videoCodecs: [Codec.h264], audioCodecs: nil, maxWidth: 1920, maxHeight: 1080, maxFps: 60,
                 requestPairing: requestPairing ? true : nil)
}

private func pinData(_ pin: String) -> Data { Data(pin.utf8) }

final class PairingTests: XCTestCase {
    private let device = Data(repeating: 9, count: 16)

    private func verify(_ pm: PairingManager, _ h: Hello, at: Int64, nonce: Data = serverNonce,
                        fingerprint: Data = certFp) -> PairingManager.Verdict {
        pm.verifyHello(h, serverNonce: nonce, certFingerprint: fingerprint, nowMs: at)
    }

    func testQrTokenIsSingleUse() {
        let pm = PairingManager(nowMs: 0)
        var changes = 0
        pm.onCredentialsChanged = { changes += 1 }
        let token = pm.qrToken
        let h = hello(AuthMethod.qrToken, token, device: device)
        XCTAssertEqual(verify(pm, h, at: 0).outcome, .accepted)
        XCTAssertNotEqual(pm.qrToken, token)
        XCTAssertEqual(changes, 1)
        XCTAssertEqual(verify(pm, h, at: 1000).outcome, .authFailed)
    }

    func testProofIsBoundToCertificateAndNonce() {
        let pm = PairingManager(nowMs: 0)
        let h = hello(AuthMethod.qrToken, pm.qrToken, device: device)
        var other = certFp
        other[0] ^= 1
        XCTAssertEqual(verify(pm, h, at: 0, fingerprint: other).outcome, .authFailed)
        XCTAssertEqual(verify(pm, h, at: 0, nonce: id(4)).outcome, .authFailed)
        XCTAssertEqual(verify(pm, h, at: 0).outcome, .accepted)
    }

    func testTokenExpires() {
        let pm = PairingManager(nowMs: 0)
        let token = pm.qrToken
        XCTAssertFalse(pm.tick(nowMs: 9 * 60_000))
        XCTAssertTrue(pm.tick(nowMs: 10 * 60_000))
        XCTAssertNotEqual(pm.qrToken, token)
    }

    func testPinLockoutAfterFiveFailures() {
        let pm = PairingManager(nowMs: 0)
        let pin = pm.pin
        let wrong = pin == "000000" ? "000001" : "000000"
        var t: Int64 = 0
        for _ in 0..<4 {
            t += 2000
            XCTAssertEqual(verify(pm, hello(AuthMethod.pin, pinData(wrong), device: device), at: t).outcome, .authFailed)
        }
        t += 2000
        var v = verify(pm, hello(AuthMethod.pin, pinData(wrong), device: device), at: t)
        XCTAssertEqual(v.outcome, .lockedOut)
        XCTAssertEqual(v.retryAfterMs, 30_000)
        XCTAssertNotEqual(pm.pin, pin)

        let fresh = pm.pin
        v = verify(pm, hello(AuthMethod.pin, pinData(fresh), device: device), at: t + 10_000)
        XCTAssertEqual(v.outcome, .lockedOut)
        XCTAssertEqual(v.retryAfterMs, 20_000)

        v = verify(pm, hello(AuthMethod.pin, pinData(fresh), device: device), at: t + 31_000)
        XCTAssertEqual(v.outcome, .accepted)
        XCTAssertNotEqual(pm.pin, fresh)
    }

    func testGlobalRateLimit() {
        let pm = PairingManager(nowMs: 0)
        let bad = hello(AuthMethod.qrToken, Data(count: 16), device: device)
        for i in 0..<10 { XCTAssertEqual(verify(pm, bad, at: Int64(i) * 100).outcome, .authFailed) }
        var v = verify(pm, hello(AuthMethod.qrToken, pm.qrToken, device: device), at: 1000)
        XCTAssertEqual(v.outcome, .rateLimited)
        XCTAssertEqual(v.retryAfterMs, 9000)
        v = verify(pm, hello(AuthMethod.qrToken, pm.qrToken, device: device), at: 10_000)
        XCTAssertEqual(v.outcome, .accepted)
    }

    func testPairedSenderReconnectsWithKey() {
        let pm = PairingManager(nowMs: 0)
        var trustedChanges = 0
        pm.onTrustedChanged = { trustedChanges += 1 }
        let first = hello(AuthMethod.qrToken, pm.qrToken, device: device)
        XCTAssertEqual(verify(pm, first, at: 0).outcome, .accepted)
        let t = pm.createPairing(first)
        XCTAssertEqual(trustedChanges, 1)

        let again = hello(AuthMethod.paired, t.pairKey, device: device, keyId: t.keyId)
        let v = verify(pm, again, at: 1000)
        XCTAssertEqual(v.outcome, .accepted)
        XCTAssertEqual(v.trusted?.deviceId, first.deviceId)

        // Another device cannot use the same key.
        let stolen = hello(AuthMethod.paired, t.pairKey, device: id(1), keyId: t.keyId)
        XCTAssertEqual(verify(pm, stolen, at: 2000).outcome, .authFailed)

        // A new pairing replaces the old one; a removed key is refused.
        let t2 = pm.createPairing(first)
        XCTAssertEqual(pm.trustedSenders.count, 1)
        XCTAssertEqual(verify(pm, again, at: 3000).outcome, .authFailed)
        XCTAssertTrue(pm.removeTrusted(keyId: t2.keyId))
        XCTAssertTrue(pm.trustedSenders.isEmpty)
    }

    func testUnsupportedMethod() {
        let pm = PairingManager(nowMs: 0)
        XCTAssertEqual(verify(pm, hello(9, Data([1]), device: device), at: 0).outcome, .unsupportedMethod)
    }

    func testPinIsSixDigitsAndVaried() {
        var seen = Set<String>()
        for _ in 0..<200 {
            let p = PairingManager.generatePin()
            XCTAssertEqual(p.count, 6)
            XCTAssertTrue(p.allSatisfy { $0.isASCII && $0.isNumber })
            seen.insert(p)
        }
        XCTAssertGreaterThan(seen.count, 190)
    }
}

final class SessionManagerTests: XCTestCase {
    private var pairing: PairingManager!
    private var sm: SessionManager!

    private static func info(audio: Bool = false) -> ReceiverInfo {
        var i = ReceiverInfo(name: "Tahta")
        i.videoDecoders = [CodecSupport(codec: Codec.h264, flags: CodecSupport.flagHardware | CodecSupport.flagPreferred)]
        i.audioEnabled = audio
        return i
    }

    override func setUp() {
        pairing = PairingManager(nowMs: 0)
        sm = SessionManager(pairing: pairing, info: Self.info(), policy: .init(approvalTimeoutMs: 20_000, resumeWindowMs: 10_000))
    }

    private func onHello(_ conn: ConnId, _ h: Hello, at: Int64 = 0) -> Actions {
        sm.onHello(conn: conn, hello: h, serverNonce: serverNonce, certFingerprint: certFp, nowMs: at)
    }

    private func welcome(_ a: Actions, _ conn: ConnId) -> Welcome? {
        for (c, m) in a.send where c == conn {
            if case .welcome(let w) = m { return w }
        }
        return nil
    }

    private func rejectReason(_ a: Actions, _ conn: ConnId) -> (UInt8, UInt32?)? {
        for (c, m) in a.send where c == conn {
            if case .reject(let reason, let retry) = m { return (reason, retry) }
        }
        return nil
    }

    private func bye(_ a: Actions, _ conn: ConnId) -> UInt8? {
        for (c, m) in a.send where c == conn {
            if case .bye(let reason) = m { return reason }
        }
        return nil
    }

    private func attachStatus(_ a: Actions, _ conn: ConnId) -> UInt8? {
        for (c, m) in a.send where c == conn {
            if case .attachResult(let status) = m { return status }
        }
        return nil
    }

    private func open(_ conn: ConnId, device: Data = id(0xA), requestPairing: Bool = false) throws -> Welcome {
        let a = onHello(conn, hello(AuthMethod.qrToken, pairing.qrToken, device: device, requestPairing: requestPairing))
        XCTAssertTrue(a.close.isEmpty)
        return try XCTUnwrap(welcome(a, conn))
    }

    private func attach(_ conn: ConnId, _ w: Welcome, _ kind: UInt8) -> Actions {
        sm.onAttach(conn: conn, attach: Attach(sessionId: w.sessionId, streamKind: kind,
                                               proof: Auth.attachProof(attachKey: w.attachKey, sessionId: w.sessionId,
                                                                       streamKind: kind, serverNonce: serverNonce)),
                    serverNonce: serverNonce)
    }

    private func resume(_ w: Welcome) -> Resume {
        Resume(sessionId: w.sessionId, proof: Auth.resumeProof(resumeToken: w.resumeToken, sessionId: w.sessionId,
                                                               serverNonce: serverNonce))
    }

    private func pinHello(device: Data = id(0xB)) -> Hello { hello(AuthMethod.pin, pinData(pairing.pin), device: device) }

    func testQrHelloStartsSessionWithFreshKeys() throws {
        let w = try open(1)
        XCTAssertEqual(w.receiverName, "Tahta")
        XCTAssertEqual(w.videoDecoders.count, 1)
        XCTAssertNil(w.pairKey)
        XCTAssertEqual(sm.session?.sessionId, w.sessionId)
        XCTAssertTrue(sm.isControl(1))
        XCTAssertNotEqual(w.attachKey, w.resumeToken)
    }

    func testBadProofIsRejectedAndClosed() {
        var h = hello(AuthMethod.qrToken, pairing.qrToken, device: id(0xA))
        h.authProof[0] ^= 1
        let a = onHello(1, h)
        XCTAssertEqual(rejectReason(a, 1)?.0, RejectReason.authFailed)
        XCTAssertTrue(a.close.contains(1))
        XCTAssertNil(sm.session)
    }

    func testUnsupportedVersionIsRejected() {
        var h = hello(AuthMethod.qrToken, pairing.qrToken, device: id(0xA))
        h.version = 2
        XCTAssertEqual(rejectReason(onHello(1, h), 1)?.0, RejectReason.versionUnsupported)
    }

    func testPinLockoutCarriesRetryAfter() {
        let bad = hello(AuthMethod.pin, Data("000000x".utf8), device: id(0xA))
        for i in 0..<4 {
            let conn = ConnId(i + 1)
            XCTAssertEqual(rejectReason(onHello(conn, bad, at: Int64(i) * 3000), conn)?.0, RejectReason.authFailed)
        }
        let r = rejectReason(onHello(9, bad, at: 15_000), 9)
        XCTAssertEqual(r?.0, RejectReason.lockedOut)
        XCTAssertEqual(r?.1, 30_000)
    }

    func testSecondSenderWaitsForApprovalAndTimesOut() throws {
        _ = try open(1)
        let a = onHello(2, pinHello())
        guard case .pendingApproval(let ms)? = a.send.first(where: { $0.0 == 2 })?.1 else { return XCTFail("no pending") }
        XCTAssertEqual(ms, 20_000)
        XCTAssertFalse(a.close.contains(2))
        XCTAssertEqual(sm.pending?.hello.deviceId, id(0xB))

        // A third sender: someone is already waiting.
        let third = onHello(3, hello(AuthMethod.qrToken, pairing.qrToken, device: id(0xC)), at: 1000)
        XCTAssertEqual(rejectReason(third, 3)?.0, RejectReason.busyDenied)

        XCTAssertTrue(sm.tick(nowMs: 19_000).isEmpty)
        let t = sm.tick(nowMs: 20_000)
        XCTAssertEqual(rejectReason(t, 2)?.0, RejectReason.busyTimeout)
        XCTAssertTrue(t.close.contains(2))
        XCTAssertNil(sm.pending)
        XCTAssertTrue(sm.isControl(1))
    }

    func testApprovedTakeoverEndsOldSession() throws {
        let old = try open(1)
        _ = attach(10, old, StreamKind.video)
        _ = onHello(2, pinHello())
        let a = sm.resolvePending(accept: true)
        XCTAssertEqual(bye(a, 1), ByeReason.takeover)
        XCTAssertTrue(a.close.contains(1))
        XCTAssertTrue(a.close.contains(10))
        let w = try XCTUnwrap(welcome(a, 2))
        XCTAssertNotEqual(w.sessionId, old.sessionId)
        XCTAssertTrue(sm.isControl(2))
        XCTAssertEqual(sm.session?.deviceId, id(0xB))
    }

    func testDeniedTakeoverKeepsOldSession() throws {
        _ = try open(1)
        _ = onHello(2, pinHello())
        let a = sm.resolvePending(accept: false)
        XCTAssertEqual(rejectReason(a, 2)?.0, RejectReason.busyDenied)
        XCTAssertTrue(a.close.contains(2))
        XCTAssertFalse(a.close.contains(1))
        XCTAssertTrue(sm.isControl(1))
    }

    func testPairedSameDeviceTakesOverWithoutApproval() throws {
        let first = try open(1, requestPairing: true)
        let pairKey = try XCTUnwrap(first.pairKey)
        let keyId = try XCTUnwrap(first.keyId)
        XCTAssertEqual(pairing.trustedSenders.count, 1)
        let a = onHello(2, hello(AuthMethod.paired, pairKey, device: id(0xA), keyId: keyId, requestPairing: true), at: 1000)
        XCTAssertEqual(bye(a, 1), ByeReason.takeover)
        let w = try XCTUnwrap(welcome(a, 2))
        XCTAssertNil(w.pairKey)
        XCTAssertEqual(pairing.trustedSenders.count, 1)
    }

    func testPairedOtherDeviceStillNeedsApproval() throws {
        let first = try open(1, requestPairing: true)
        _ = sm.endSession(bye: ByeReason.receiverClosing)
        _ = try open(5, device: id(0xB))
        let a = onHello(2, hello(AuthMethod.paired, try XCTUnwrap(first.pairKey), device: id(0xA), keyId: first.keyId), at: 1000)
        guard case .pendingApproval? = a.send.first(where: { $0.0 == 2 })?.1 else { return XCTFail("no pending") }
    }

    func testAttachValidatesProofAndKind() throws {
        let w = try open(1)
        let bad = Attach(sessionId: w.sessionId, streamKind: StreamKind.video,
                         proof: Auth.attachProof(attachKey: w.attachKey, sessionId: w.sessionId, streamKind: StreamKind.video,
                                                 serverNonce: id(9)))
        var a = sm.onAttach(conn: 10, attach: bad, serverNonce: serverNonce)
        XCTAssertEqual(attachStatus(a, 10), RejectReason.authFailed)
        XCTAssertTrue(a.close.contains(10))

        a = attach(11, w, StreamKind.audio) // audio is off
        XCTAssertEqual(attachStatus(a, 11), RejectReason.malformed)

        a = attach(12, w, StreamKind.video)
        XCTAssertEqual(attachStatus(a, 12), 0)
        XCTAssertTrue(a.close.isEmpty)
        XCTAssertEqual(sm.mediaSlot(of: 12), .video)

        // A second connection of the same kind replaces the first.
        a = attach(13, w, StreamKind.video)
        XCTAssertTrue(a.close.contains(12))
        XCTAssertEqual(sm.mediaSlot(of: 13), .video)
        XCTAssertEqual(sm.mediaSlot(of: 12), .none)
    }

    func testAttachToUnknownSessionFails() throws {
        var w = try open(1)
        w.sessionId[0] ^= 1
        XCTAssertEqual(attachStatus(attach(10, w, StreamKind.video), 10), RejectReason.sessionUnknown)
    }

    func testControlLossClosesMediaAndResumeRestores() throws {
        let w = try open(1)
        _ = attach(10, w, StreamKind.video)

        var a = sm.onConnectionClosed(conn: 1, nowMs: 1000)
        XCTAssertTrue(a.close.contains(10))
        XCTAssertNotNil(sm.session)
        XCTAssertNil(sm.session?.control)

        // In the RESUME window, ATTACH with the old key is refused.
        XCTAssertEqual(attachStatus(attach(11, w, StreamKind.video), 11), RejectReason.sessionUnknown)

        let r = resume(w)
        a = sm.onResume(conn: 2, resume: r, serverNonce: serverNonce)
        let w2 = try XCTUnwrap(welcome(a, 2))
        XCTAssertEqual(w2.sessionId, w.sessionId)
        XCTAssertNotEqual(w2.resumeToken, w.resumeToken)
        XCTAssertNotEqual(w2.attachKey, w.attachKey)
        XCTAssertTrue(sm.isControl(2))

        // The old resume token is single use.
        XCTAssertEqual(rejectReason(sm.onResume(conn: 3, resume: r, serverNonce: serverNonce), 3)?.0, RejectReason.sessionUnknown)

        XCTAssertEqual(attachStatus(attach(12, w2, StreamKind.video), 12), 0)
        XCTAssertTrue(sm.tick(nowMs: 60_000).isEmpty)
    }

    func testResumeWhileOldControlStillOpenReplacesIt() throws {
        let w = try open(1)
        _ = attach(10, w, StreamKind.video)
        let a = sm.onResume(conn: 2, resume: resume(w), serverNonce: serverNonce)
        XCTAssertTrue(a.close.contains(1))
        XCTAssertTrue(a.close.contains(10))
        XCTAssertNotNil(welcome(a, 2))
        // The network layer reports the old connections closing: the session must not care.
        XCTAssertTrue(sm.onConnectionClosed(conn: 1, nowMs: 0).isEmpty)
        XCTAssertTrue(sm.isControl(2))
    }

    func testResumeWindowExpiryEndsSession() throws {
        _ = try open(1)
        _ = sm.onConnectionClosed(conn: 1, nowMs: 0)
        XCTAssertTrue(sm.tick(nowMs: 9000).isEmpty)
        XCTAssertNotNil(sm.session)
        _ = sm.tick(nowMs: 10_000)
        XCTAssertNil(sm.session)
    }

    func testByeFromSenderEndsSessionSilently() throws {
        let w = try open(1)
        _ = attach(10, w, StreamKind.video)
        let a = sm.onBye(conn: 1)
        XCTAssertTrue(a.close.contains(1))
        XCTAssertTrue(a.close.contains(10))
        XCTAssertNil(bye(a, 1))
        XCTAssertNil(sm.session)
    }

    func testReceiverEndSessionSendsByeAndRejectsPending() throws {
        _ = try open(1)
        _ = onHello(2, pinHello())
        let a = sm.endSession(bye: ByeReason.receiverClosing)
        XCTAssertEqual(bye(a, 1), ByeReason.receiverClosing)
        XCTAssertEqual(rejectReason(a, 2)?.0, RejectReason.busyDenied)
        XCTAssertNil(sm.session)
        XCTAssertNil(sm.pending)
    }

    func testPendingSenderDisconnectClearsPending() throws {
        _ = try open(1)
        _ = onHello(2, pinHello())
        _ = sm.onConnectionClosed(conn: 2, nowMs: 1000)
        XCTAssertNil(sm.pending)
        XCTAssertTrue(sm.resolvePending(accept: true).isEmpty)
        XCTAssertTrue(sm.isControl(1))
    }
}

final class AddressSelectionTests: XCTestCase {
    func testKeepsLocalIpv4WiredFirstAndNoVirtual() {
        let list: [AddressSelection.Candidate] = [
            .init(name: "en0", address: "192.168.1.5", wired: false),
            .init(name: "en1", address: "10.0.0.7", wired: true),
            .init(name: "utun3", address: "10.8.0.2", wired: false),
            .init(name: "en2", address: "8.8.8.8", wired: false),
            .init(name: "en3", address: "100.64.1.1", wired: false),
            .init(name: "en4", address: "172.32.0.1", wired: false),
        ]
        XCTAssertEqual(AddressSelection.select(list), ["10.0.0.7", "192.168.1.5", "100.64.1.1"])
    }

    func testAtMostFourAddresses() {
        let list = (1...6).map { AddressSelection.Candidate(name: "en\($0)", address: "192.168.1.\($0)", wired: false) }
        XCTAssertEqual(AddressSelection.select(list).count, 4)
    }
}

final class IdentityTests: XCTestCase {
    func testCertificateIsWellFormedAndStable() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("casthop-identity-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let first = try IdentityStore.loadOrCreate(directory: dir)
        XCTAssertEqual(first.fingerprint, Auth.sha256(first.certificateDER))
        let parsed = try XCTUnwrap(SecCertificateCreateWithData(nil, first.certificateDER as CFData))
        XCTAssertEqual(SecCertificateCopySubjectSummary(parsed) as String?, "CastHop")
        XCTAssertNotNil(SecCertificateCopyKey(parsed))
        let second = try IdentityStore.loadOrCreate(directory: dir)
        XCTAssertEqual(first.fingerprint, second.fingerprint)
    }

    func testExpiredCertificateIsReplaced() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("casthop-identity-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let now = Date()
        let first = try IdentityStore.loadOrCreate(directory: dir, now: now)
        let later = now.addingTimeInterval(TimeInterval(SelfSignedCertificate.validityYears) * 366 * 86_400)
        let second = try IdentityStore.loadOrCreate(directory: dir, now: later)
        XCTAssertNotEqual(first.fingerprint, second.fingerprint)
    }
}
