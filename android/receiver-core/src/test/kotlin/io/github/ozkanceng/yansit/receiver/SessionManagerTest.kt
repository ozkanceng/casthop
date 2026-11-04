package io.github.ozkanceng.yansit.receiver

import io.github.ozkanceng.yansit.protocol.Auth
import io.github.ozkanceng.yansit.protocol.AuthMethod
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.ByeReason
import io.github.ozkanceng.yansit.protocol.Codec
import io.github.ozkanceng.yansit.protocol.CodecSupport
import io.github.ozkanceng.yansit.protocol.ControlMessage
import io.github.ozkanceng.yansit.protocol.RejectReason
import io.github.ozkanceng.yansit.protocol.StreamKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Derived from receiver/tests/test_session.cpp. */
class SessionManagerTest {
    private val t0 = 0L
    private val nonce = id(5, 5, 5)
    private val certFp = Auth.sha256("fp".toByteArray())

    private val pairing = PairingManager(t0)
    private val sm = SessionManager(
        pairing,
        ReceiverInfo(
            name = "Tahta",
            videoDecoders = listOf(CodecSupport(Codec.H264, CodecSupport.FLAG_HARDWARE or CodecSupport.FLAG_PREFERRED)),
        ),
        SessionManager.Policy(20_000, 10_000),
    )

    private fun hello(
        method: Int,
        secret: ByteArray,
        deviceId: Bytes,
        keyId: Bytes? = null,
        requestPairing: Boolean = false,
    ): ControlMessage.Hello {
        val clientNonce = id(1)
        return ControlMessage.Hello(
            version = 1,
            deviceName = "Tablet",
            deviceId = deviceId,
            appVersion = "0.1.0",
            authMethod = method,
            clientNonce = clientNonce,
            authProof = Auth.authProof(secret, method, nonce, clientNonce, certFp, deviceId),
            keyId = keyId,
            videoCodecs = listOf(Codec.H264),
            maxWidth = 1920,
            maxHeight = 1080,
            maxFps = 60,
            requestPairing = if (requestPairing) true else null,
        )
    }

    private fun pinHello(deviceId: Bytes) = hello(AuthMethod.PIN, Auth.pinSecret(pairing.pin), deviceId)

    private inline fun <reified T : ControlMessage> Actions.sentTo(conn: ConnId): T? =
        send.firstOrNull { it.first == conn && it.second is T }?.second as T?

    private fun Actions.closes(conn: ConnId) = conn in close

    /** Opens a session by QR and returns its WELCOME. */
    private fun open(conn: ConnId, device: Bytes = id(0xA), requestPairing: Boolean = false): ControlMessage.Welcome {
        val h = hello(AuthMethod.QR_TOKEN, pairing.qrToken.toByteArray(), device, null, requestPairing)
        val a = sm.onHello(conn, h, nonce, certFp, t0)
        assertTrue(a.close.isEmpty())
        return assertNotNull(a.sentTo<ControlMessage.Welcome>(conn))
    }

    private fun assertNotNull(w: ControlMessage.Welcome?): ControlMessage.Welcome {
        org.junit.jupiter.api.Assertions.assertNotNull(w)
        return w!!
    }

    private fun attach(conn: ConnId, w: ControlMessage.Welcome, kind: Int): Actions = sm.onAttach(
        conn,
        ControlMessage.Attach(w.sessionId, kind, Auth.attachProof(w.attachKey, w.sessionId, kind, nonce)),
        nonce,
    )

    private fun resumeOf(w: ControlMessage.Welcome) =
        ControlMessage.Resume(w.sessionId, Auth.resumeProof(w.resumeToken, w.sessionId, nonce))

    @Test
    fun qrHelloStartsSessionWithFreshKeys() {
        val w = open(1)
        assertEquals("Tahta", w.receiverName)
        assertEquals(1, w.videoDecoders.size)
        assertNull(w.pairKey)
        assertEquals(w.sessionId, sm.session?.sessionId)
        assertTrue(sm.isControl(1))
        assertNotEquals(w.attachKey, w.resumeToken)
    }

    @Test
    fun badProofIsRejectedAndClosed() {
        val good = hello(AuthMethod.QR_TOKEN, pairing.qrToken.toByteArray(), id(0xA))
        val a = sm.onHello(1, good.copy(authProof = key(0)), nonce, certFp, t0)
        assertEquals(RejectReason.AUTH_FAILED, a.sentTo<ControlMessage.Reject>(1)?.reason)
        assertTrue(a.closes(1))
        assertNull(sm.session)
    }

    @Test
    fun unsupportedVersionIsRejected() {
        val h = hello(AuthMethod.QR_TOKEN, pairing.qrToken.toByteArray(), id(0xA)).copy(version = 2)
        val a = sm.onHello(1, h, nonce, certFp, t0)
        assertEquals(RejectReason.VERSION_UNSUPPORTED, a.sentTo<ControlMessage.Reject>(1)?.reason)
    }

    @Test
    fun pinLockoutCarriesRetryAfter() {
        val wrong = "000000x".toByteArray()
        for (i in 0 until 4) {
            val a = sm.onHello(i + 1L, hello(AuthMethod.PIN, wrong, id(0xA)), nonce, certFp, t0 + i * 3000L)
            assertEquals(RejectReason.AUTH_FAILED, a.sentTo<ControlMessage.Reject>(i + 1L)?.reason)
        }
        val a = sm.onHello(9, hello(AuthMethod.PIN, wrong, id(0xA)), nonce, certFp, t0 + 15_000)
        val r = a.sentTo<ControlMessage.Reject>(9)
        assertEquals(RejectReason.LOCKED_OUT, r?.reason)
        assertEquals(30_000L, r?.retryAfterMs)
    }

    @Test
    fun secondSenderWaitsForApprovalAndTimesOut() {
        open(1)
        val a = sm.onHello(2, pinHello(id(0xB)), nonce, certFp, t0)
        assertEquals(20_000L, a.sentTo<ControlMessage.PendingApproval>(2)?.timeoutMs)
        assertFalse(a.closes(2))
        assertEquals(id(0xB), sm.pending?.hello?.deviceId)

        // Third sender: someone is already waiting, and the token is not spent.
        val token = pairing.qrToken
        val third = sm.onHello(3, hello(AuthMethod.QR_TOKEN, token.toByteArray(), id(0xC)), nonce, certFp, t0 + 1000)
        assertEquals(RejectReason.BUSY_DENIED, third.sentTo<ControlMessage.Reject>(3)?.reason)
        assertEquals(token, pairing.qrToken)

        assertTrue(sm.tick(t0 + 19_000).isEmpty)
        val t = sm.tick(t0 + 20_000)
        assertEquals(RejectReason.BUSY_TIMEOUT, t.sentTo<ControlMessage.Reject>(2)?.reason)
        assertTrue(t.closes(2))
        assertNull(sm.pending)
        assertTrue(sm.isControl(1))
    }

    @Test
    fun approvedTakeoverEndsOldSession() {
        val old = open(1)
        attach(10, old, StreamKind.VIDEO)
        sm.onHello(2, pinHello(id(0xB)), nonce, certFp, t0)

        val a = sm.resolvePending(true)
        assertEquals(ByeReason.TAKEOVER, a.sentTo<ControlMessage.Bye>(1)?.reason)
        assertTrue(a.closes(1))
        assertTrue(a.closes(10))
        val w = assertNotNull(a.sentTo<ControlMessage.Welcome>(2))
        assertNotEquals(old.sessionId, w.sessionId)
        assertTrue(sm.isControl(2))
        assertEquals(id(0xB), sm.session?.sender?.deviceId)
    }

    @Test
    fun deniedTakeoverKeepsOldSession() {
        open(1)
        sm.onHello(2, pinHello(id(0xB)), nonce, certFp, t0)
        val a = sm.resolvePending(false)
        assertEquals(RejectReason.BUSY_DENIED, a.sentTo<ControlMessage.Reject>(2)?.reason)
        assertTrue(a.closes(2))
        assertFalse(a.closes(1))
        assertTrue(sm.isControl(1))
    }

    @Test
    fun pairedSameDeviceTakesOverWithoutApproval() {
        val first = open(1, id(0xA), requestPairing = true)
        val pairKey = first.pairKey!!
        assertEquals(1, pairing.trustedSenders.size)

        val h = hello(AuthMethod.PAIRED, pairKey.toByteArray(), id(0xA), first.keyId, requestPairing = true)
        val a = sm.onHello(2, h, nonce, certFp, t0 + 1000)
        assertEquals(ByeReason.TAKEOVER, a.sentTo<ControlMessage.Bye>(1)?.reason)
        val w = assertNotNull(a.sentTo<ControlMessage.Welcome>(2))
        // A request made with a pairing key creates no new key.
        assertNull(w.pairKey)
        assertEquals(1, pairing.trustedSenders.size)
    }

    @Test
    fun pairedOtherDeviceStillNeedsApproval() {
        val first = open(1, id(0xA), requestPairing = true)
        sm.endSession(ByeReason.RECEIVER_CLOSING)
        open(5, id(0xB))
        val h = hello(AuthMethod.PAIRED, first.pairKey!!.toByteArray(), id(0xA), first.keyId)
        val a = sm.onHello(2, h, nonce, certFp, t0 + 1000)
        org.junit.jupiter.api.Assertions.assertNotNull(a.sentTo<ControlMessage.PendingApproval>(2))
    }

    @Test
    fun attachValidatesProofAndKind() {
        val w = open(1)
        val bad = ControlMessage.Attach(
            w.sessionId,
            StreamKind.VIDEO,
            Auth.attachProof(w.attachKey, w.sessionId, StreamKind.VIDEO, id(9)),
        )
        var a = sm.onAttach(10, bad, nonce)
        assertEquals(RejectReason.AUTH_FAILED, a.sentTo<ControlMessage.AttachResult>(10)?.status)
        assertTrue(a.closes(10))

        a = attach(11, w, StreamKind.AUDIO) // audio is off
        assertEquals(RejectReason.MALFORMED, a.sentTo<ControlMessage.AttachResult>(11)?.status)

        a = attach(12, w, StreamKind.VIDEO)
        assertEquals(0, a.sentTo<ControlMessage.AttachResult>(12)?.status)
        assertTrue(a.close.isEmpty())
        assertEquals(SessionManager.MediaSlot.VIDEO, sm.mediaSlotOf(12))

        // A second connection of the same kind replaces the first.
        a = attach(13, w, StreamKind.VIDEO)
        assertTrue(a.closes(12))
        assertEquals(SessionManager.MediaSlot.VIDEO, sm.mediaSlotOf(13))
        assertEquals(SessionManager.MediaSlot.NONE, sm.mediaSlotOf(12))
    }

    @Test
    fun attachToUnknownSessionFails() {
        val w = open(1)
        val a = attach(10, w.copy(sessionId = id(0xFF)), StreamKind.VIDEO)
        assertEquals(RejectReason.SESSION_UNKNOWN, a.sentTo<ControlMessage.AttachResult>(10)?.status)
    }

    @Test
    fun controlLossClosesMediaAndResumeRestores() {
        val w = open(1)
        attach(10, w, StreamKind.VIDEO)

        var a = sm.onConnectionClosed(1, t0 + 1000)
        assertTrue(a.closes(10))
        assertNull(sm.session?.control)
        org.junit.jupiter.api.Assertions.assertNotNull(sm.session)

        // In the RESUME window the old attach_key is refused.
        assertEquals(
            RejectReason.SESSION_UNKNOWN,
            attach(11, w, StreamKind.VIDEO).sentTo<ControlMessage.AttachResult>(11)?.status,
        )

        val r = resumeOf(w)
        a = sm.onResume(2, r, nonce)
        val w2 = assertNotNull(a.sentTo<ControlMessage.Welcome>(2))
        assertEquals(w.sessionId, w2.sessionId)
        assertNotEquals(w.resumeToken, w2.resumeToken)
        assertNotEquals(w.attachKey, w2.attachKey)
        assertTrue(sm.isControl(2))

        // The old resume token is single-use.
        assertEquals(RejectReason.SESSION_UNKNOWN, sm.onResume(3, r, nonce).sentTo<ControlMessage.Reject>(3)?.reason)

        assertEquals(0, attach(12, w2, StreamKind.VIDEO).sentTo<ControlMessage.AttachResult>(12)?.status)
        assertTrue(sm.tick(t0 + 60_000).isEmpty)
    }

    @Test
    fun resumeWhileOldControlStillOpenReplacesIt() {
        val w = open(1)
        attach(10, w, StreamKind.VIDEO)
        val a = sm.onResume(2, resumeOf(w), nonce)
        assertTrue(a.closes(1))
        assertTrue(a.closes(10))
        org.junit.jupiter.api.Assertions.assertNotNull(a.sentTo<ControlMessage.Welcome>(2))
        // The network layer reports the old connections closing; the session must not care.
        assertTrue(sm.onConnectionClosed(1, t0).isEmpty)
        assertTrue(sm.isControl(2))
    }

    @Test
    fun resumeWindowExpiryEndsSession() {
        open(1)
        sm.onConnectionClosed(1, t0)
        assertTrue(sm.tick(t0 + 9000).isEmpty)
        org.junit.jupiter.api.Assertions.assertNotNull(sm.session)
        sm.tick(t0 + 10_000)
        assertNull(sm.session)
    }

    @Test
    fun byeFromSenderEndsSessionSilently() {
        val w = open(1)
        attach(10, w, StreamKind.VIDEO)
        val a = sm.onBye(1)
        assertTrue(a.closes(1))
        assertTrue(a.closes(10))
        assertNull(a.sentTo<ControlMessage.Bye>(1))
        assertNull(sm.session)
    }

    @Test
    fun receiverEndSessionSendsByeAndRejectsPending() {
        open(1)
        sm.onHello(2, pinHello(id(0xB)), nonce, certFp, t0)
        val a = sm.endSession(ByeReason.RECEIVER_CLOSING)
        assertEquals(ByeReason.RECEIVER_CLOSING, a.sentTo<ControlMessage.Bye>(1)?.reason)
        assertEquals(RejectReason.BUSY_DENIED, a.sentTo<ControlMessage.Reject>(2)?.reason)
        assertNull(sm.session)
        assertNull(sm.pending)
    }

    @Test
    fun pendingSenderDisconnectClearsPending() {
        open(1)
        sm.onHello(2, pinHello(id(0xB)), nonce, certFp, t0)
        sm.onConnectionClosed(2, t0 + 1000)
        assertNull(sm.pending)
        assertTrue(sm.resolvePending(true).isEmpty)
        assertTrue(sm.isControl(1))
    }
}
