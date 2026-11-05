package io.github.ozkanceng.yansit.receiver

import io.github.ozkanceng.yansit.protocol.Auth
import io.github.ozkanceng.yansit.protocol.AuthMethod
import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.ControlCodec
import io.github.ozkanceng.yansit.protocol.ControlMessage

/** A sender paired for good (auth_method = 3). [toString] leaves the key out: it must never reach a log. */
public data class TrustedSender(
    val keyId: Bytes,
    val pairKey: Bytes,
    val deviceId: Bytes,
    val deviceName: String,
) {
    override fun toString(): String = "TrustedSender(deviceName=$deviceName)"
}

/**
 * Receiver authentication policy (PROTOCOL.md §7.1, §11): single-use QR token and PIN, PIN brute-force lockout,
 * global HELLO rate limit and pairing keys. Port of `receiver/src/session/PairingManager`.
 *
 * Time is passed in, so nothing here touches a clock or the network. Not thread-safe: the engine holds its lock.
 */
public class PairingManager(nowMs: Long, private val policy: Policy = Policy()) {
    public data class Policy(
        val tokenLifetimeMs: Long = 10 * 60 * 1000L,
        val maxPinFailures: Int = 5,
        val pinLockoutMs: Long = 30_000L,
        val maxHellosPerWindow: Int = 10,
        val helloWindowMs: Long = 10_000L,
    )

    public enum class Outcome { ACCEPTED, AUTH_FAILED, RATE_LIMITED, LOCKED_OUT, UNSUPPORTED_METHOD }

    public data class Verdict(
        val outcome: Outcome,
        val retryAfterMs: Long? = null,
        /** On acceptance through a pairing key, the sender it belongs to. */
        val trusted: TrustedSender? = null,
    )

    public var qrToken: Bytes = Auth.randomBytes(QR_TOKEN_SIZE)
        private set

    /** Six digits, leading zeros included. */
    public var pin: String = generatePin()
        private set

    private var tokenIssuedMs = nowMs
    private var pinFailures = 0
    private var pinLockedUntilMs: Long? = null
    private val recentHellos = ArrayDeque<Long>()
    private val trusted = ArrayList<TrustedSender>()

    /** Called when the QR token or PIN changes, so the waiting screen can redraw. */
    public var onCredentialsChanged: (() -> Unit)? = null

    /** Called when the trusted list changes, so it can be saved. */
    public var onTrustedChanged: (() -> Unit)? = null

    public val trustedSenders: List<TrustedSender> get() = trusted.toList()

    public fun setTrustedSenders(list: List<TrustedSender>) {
        trusted.clear()
        trusted.addAll(list)
    }

    /** Renews an expired token. True when the QR has to be redrawn. */
    public fun tick(nowMs: Long): Boolean {
        if (nowMs - tokenIssuedMs < policy.tokenLifetimeMs) return false
        rotateToken(nowMs)
        onCredentialsChanged?.invoke()
        return true
    }

    /** Checks a HELLO. An accepted QR token or PIN is single-use and renewed at once. */
    public fun verifyHello(hello: ControlMessage.Hello, serverNonce: Bytes, certFingerprint: Bytes, nowMs: Long): Verdict {
        while (recentHellos.isNotEmpty() && nowMs - recentHellos.first() >= policy.helloWindowMs) {
            recentHellos.removeFirst()
        }
        if (recentHellos.size >= policy.maxHellosPerWindow) {
            return Verdict(Outcome.RATE_LIMITED, recentHellos.first() + policy.helloWindowMs - nowMs)
        }
        recentHellos.addLast(nowMs)

        fun expected(secret: ByteArray): Bytes =
            Auth.authProof(secret, hello.authMethod, serverNonce, hello.clientNonce, certFingerprint, hello.deviceId)

        return when (hello.authMethod) {
            AuthMethod.QR_TOKEN -> verifyToken(hello, ::expected, nowMs)
            AuthMethod.PIN -> verifyPin(hello, ::expected, nowMs)
            AuthMethod.PAIRED -> verifyPaired(hello, ::expected)
            else -> Verdict(Outcome.UNSUPPORTED_METHOD)
        }
    }

    private fun verifyToken(hello: ControlMessage.Hello, expected: (ByteArray) -> Bytes, nowMs: Long): Verdict {
        if (!Auth.constantTimeEquals(expected(qrToken.toByteArray()), hello.authProof)) return Verdict(Outcome.AUTH_FAILED)
        rotateToken(nowMs)
        onCredentialsChanged?.invoke()
        return Verdict(Outcome.ACCEPTED)
    }

    private fun verifyPin(hello: ControlMessage.Hello, expected: (ByteArray) -> Bytes, nowMs: Long): Verdict {
        val lockedUntil = pinLockedUntilMs
        if (lockedUntil != null && nowMs < lockedUntil) return Verdict(Outcome.LOCKED_OUT, lockedUntil - nowMs)
        pinLockedUntilMs = null
        if (!Auth.constantTimeEquals(expected(Auth.pinSecret(pin)), hello.authProof)) {
            if (++pinFailures >= policy.maxPinFailures) {
                rotatePin()
                pinLockedUntilMs = nowMs + policy.pinLockoutMs
                onCredentialsChanged?.invoke()
                return Verdict(Outcome.LOCKED_OUT, policy.pinLockoutMs)
            }
            return Verdict(Outcome.AUTH_FAILED)
        }
        rotatePin()
        onCredentialsChanged?.invoke()
        return Verdict(Outcome.ACCEPTED)
    }

    private fun verifyPaired(hello: ControlMessage.Hello, expected: (ByteArray) -> Bytes): Verdict {
        val keyId = hello.keyId ?: return Verdict(Outcome.AUTH_FAILED)
        val index = trusted.indexOfFirst { it.keyId == keyId }
        val found = trusted.getOrNull(index)
        if (found == null || found.deviceId != hello.deviceId ||
            !Auth.constantTimeEquals(expected(found.pairKey.toByteArray()), hello.authProof)
        ) {
            return Verdict(Outcome.AUTH_FAILED)
        }
        if (found.deviceName == hello.deviceName) return Verdict(Outcome.ACCEPTED, trusted = found)
        val renamed = found.copy(deviceName = hello.deviceName)
        trusted[index] = renamed
        onTrustedChanged?.invoke()
        return Verdict(Outcome.ACCEPTED, trusted = renamed)
    }

    /** Creates a pairing key for an accepted HELLO. One key per device: older ones are dropped. */
    public fun createPairing(hello: ControlMessage.Hello): TrustedSender {
        trusted.removeAll { it.deviceId == hello.deviceId }
        val sender = TrustedSender(
            keyId = Auth.randomBytes(ControlCodec.ID_SIZE),
            pairKey = Auth.randomBytes(ControlCodec.KEY_SIZE),
            deviceId = hello.deviceId,
            deviceName = hello.deviceName,
        )
        trusted.add(sender)
        onTrustedChanged?.invoke()
        return sender
    }

    public fun removeTrusted(keyId: Bytes): Boolean {
        val removed = trusted.removeAll { it.keyId == keyId }
        if (removed) onTrustedChanged?.invoke()
        return removed
    }

    private fun rotateToken(nowMs: Long) {
        qrToken = Auth.randomBytes(QR_TOKEN_SIZE)
        tokenIssuedMs = nowMs
    }

    private fun rotatePin() {
        pin = generatePin()
        pinFailures = 0
    }

    public companion object {
        private const val QR_TOKEN_SIZE = 16
        private const val PIN_RANGE = 1_000_000L
        private const val U32_MAX = 0xFFFF_FFFFL

        /** Uniform over [0, 999999]: rejects the top of the u32 range that does not divide evenly. */
        public fun generatePin(): String {
            val limit = U32_MAX - U32_MAX % PIN_RANGE
            while (true) {
                val b = Auth.randomBytes(4)
                var v = 0L
                for (i in 0 until 4) v = (v shl 8) or (b[i].toLong() and 0xFF)
                if (v < limit) return (v % PIN_RANGE).toString().padStart(Auth.PIN_LENGTH, '0')
            }
        }
    }
}
