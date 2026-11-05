package io.github.ozkanceng.yansit.receiver

import io.github.ozkanceng.yansit.protocol.Auth
import io.github.ozkanceng.yansit.protocol.AuthMethod
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.Codec
import io.github.ozkanceng.yansit.protocol.ControlMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Derived from receiver/tests/test_pairing.cpp. */
class PairingManagerTest {
    private val t0 = 0L
    private val nonce = id(1, 2, 3)
    private val certFp = Auth.sha256("cert".toByteArray())

    private fun hello(method: Int, secret: ByteArray, keyId: Bytes? = null): ControlMessage.Hello {
        val deviceId = id(9, 9, 9)
        val clientNonce = id(7, 7)
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
        )
    }

    @Test
    fun qrTokenIsSingleUse() {
        val pm = PairingManager(t0)
        var changes = 0
        pm.onCredentialsChanged = { changes++ }
        val token = pm.qrToken
        val h = hello(AuthMethod.QR_TOKEN, token.toByteArray())
        assertEquals(PairingManager.Outcome.ACCEPTED, pm.verifyHello(h, nonce, certFp, t0).outcome)
        assertNotEquals(token, pm.qrToken)
        assertEquals(1, changes)
        assertEquals(PairingManager.Outcome.AUTH_FAILED, pm.verifyHello(h, nonce, certFp, t0 + 1000).outcome)
    }

    @Test
    fun proofIsBoundToCertificateAndNonce() {
        val pm = PairingManager(t0)
        val h = hello(AuthMethod.QR_TOKEN, pm.qrToken.toByteArray())
        val otherFp = Auth.sha256("other".toByteArray())
        assertEquals(PairingManager.Outcome.AUTH_FAILED, pm.verifyHello(h, nonce, otherFp, t0).outcome)
        assertEquals(PairingManager.Outcome.AUTH_FAILED, pm.verifyHello(h, id(4), certFp, t0).outcome)
        assertEquals(PairingManager.Outcome.ACCEPTED, pm.verifyHello(h, nonce, certFp, t0).outcome)
    }

    @Test
    fun tokenExpires() {
        val pm = PairingManager(t0)
        val token = pm.qrToken
        assertFalse(pm.tick(t0 + 9 * 60_000))
        assertTrue(pm.tick(t0 + 10 * 60_000))
        assertNotEquals(token, pm.qrToken)
    }

    @Test
    fun pinLockoutAfterFiveFailures() {
        val pm = PairingManager(t0)
        val pin = pm.pin
        val wrong = if (pin == "000000") "000001" else "000000"
        var t = t0
        repeat(4) {
            t += 2000 // stays under the rate limit
            val v = pm.verifyHello(hello(AuthMethod.PIN, Auth.pinSecret(wrong)), nonce, certFp, t)
            assertEquals(PairingManager.Outcome.AUTH_FAILED, v.outcome)
        }
        t += 2000
        var v = pm.verifyHello(hello(AuthMethod.PIN, Auth.pinSecret(wrong)), nonce, certFp, t)
        assertEquals(PairingManager.Outcome.LOCKED_OUT, v.outcome)
        assertEquals(30_000L, v.retryAfterMs)
        assertNotEquals(pin, pm.pin, "the PIN is renewed on lockout")

        // During the lockout even the right PIN is refused.
        val fresh = pm.pin
        v = pm.verifyHello(hello(AuthMethod.PIN, Auth.pinSecret(fresh)), nonce, certFp, t + 10_000)
        assertEquals(PairingManager.Outcome.LOCKED_OUT, v.outcome)
        assertEquals(20_000L, v.retryAfterMs)

        v = pm.verifyHello(hello(AuthMethod.PIN, Auth.pinSecret(fresh)), nonce, certFp, t + 31_000)
        assertEquals(PairingManager.Outcome.ACCEPTED, v.outcome)
        assertNotEquals(fresh, pm.pin, "the PIN is single-use")
    }

    @Test
    fun globalRateLimit() {
        val pm = PairingManager(t0)
        val bad = hello(AuthMethod.QR_TOKEN, ByteArray(16))
        for (i in 0 until 10) {
            assertEquals(PairingManager.Outcome.AUTH_FAILED, pm.verifyHello(bad, nonce, certFp, t0 + i * 100L).outcome)
        }
        var v = pm.verifyHello(hello(AuthMethod.QR_TOKEN, pm.qrToken.toByteArray()), nonce, certFp, t0 + 1000)
        assertEquals(PairingManager.Outcome.RATE_LIMITED, v.outcome)
        assertEquals(9000L, v.retryAfterMs)
        v = pm.verifyHello(hello(AuthMethod.QR_TOKEN, pm.qrToken.toByteArray()), nonce, certFp, t0 + 10_000)
        assertEquals(PairingManager.Outcome.ACCEPTED, v.outcome)
    }

    @Test
    fun pairedSenderReconnectsWithKey() {
        val pm = PairingManager(t0)
        var trustedChanges = 0
        pm.onTrustedChanged = { trustedChanges++ }
        val first = hello(AuthMethod.QR_TOKEN, pm.qrToken.toByteArray())
        assertEquals(PairingManager.Outcome.ACCEPTED, pm.verifyHello(first, nonce, certFp, t0).outcome)
        val t = pm.createPairing(first)
        assertEquals(1, trustedChanges)

        val again = hello(AuthMethod.PAIRED, t.pairKey.toByteArray(), t.keyId)
        val v = pm.verifyHello(again, nonce, certFp, t0 + 1000)
        assertEquals(PairingManager.Outcome.ACCEPTED, v.outcome)
        assertNotNull(v.trusted)
        assertEquals(first.deviceId, v.trusted?.deviceId)

        // Another device cannot use the same key.
        val otherDevice = id(1)
        val stolen = again.copy(
            deviceId = otherDevice,
            authProof = Auth.authProof(t.pairKey.toByteArray(), 3, nonce, again.clientNonce, certFp, otherDevice),
        )
        assertEquals(PairingManager.Outcome.AUTH_FAILED, pm.verifyHello(stolen, nonce, certFp, t0 + 2000).outcome)

        // Pairing again replaces the old key, and a removed key is refused.
        val t2 = pm.createPairing(first)
        assertEquals(1, pm.trustedSenders.size)
        assertEquals(PairingManager.Outcome.AUTH_FAILED, pm.verifyHello(again, nonce, certFp, t0 + 3000).outcome)
        assertTrue(pm.removeTrusted(t2.keyId))
        assertTrue(pm.trustedSenders.isEmpty())
    }

    @Test
    fun pairedRenameIsSaved() {
        val pm = PairingManager(t0)
        val first = hello(AuthMethod.QR_TOKEN, pm.qrToken.toByteArray())
        val t = pm.createPairing(first)
        var trustedChanges = 0
        pm.onTrustedChanged = { trustedChanges++ }
        val renamed = hello(AuthMethod.PAIRED, t.pairKey.toByteArray(), t.keyId).copy(deviceName = "New name")
        assertEquals(PairingManager.Outcome.ACCEPTED, pm.verifyHello(renamed, nonce, certFp, t0).outcome)
        assertEquals("New name", pm.trustedSenders.single().deviceName)
        assertEquals(1, trustedChanges)
    }

    @Test
    fun unsupportedMethod() {
        val pm = PairingManager(t0)
        assertEquals(
            PairingManager.Outcome.UNSUPPORTED_METHOD,
            pm.verifyHello(hello(9, byteArrayOf(1)), nonce, certFp, t0).outcome,
        )
    }

    @Test
    fun pinHasSixDigitsAndVaries() {
        val seen = HashSet<String>()
        repeat(200) {
            val p = PairingManager.generatePin()
            assertEquals(6, p.length)
            assertTrue(p.all { it in '0'..'9' })
            seen += p
        }
        assertTrue(seen.size > 190)
    }

    @Test
    fun trustedSenderToStringHidesTheKey() {
        val t = TrustedSender(id(1), key(0xAB), id(2), "Tablet")
        assertFalse(t.toString().contains("abab"))
    }
}
