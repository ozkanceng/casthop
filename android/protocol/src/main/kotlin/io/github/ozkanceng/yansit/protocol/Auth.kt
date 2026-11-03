package io.github.ozkanceng.yansit.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Kimlik kanıtları (PROTOCOL.md §7.1.1). Tüm karşılaştırmalar sabit zamanlıdır. */
public object Auth {
    private val AUTH_LABEL = "yansit-auth-v1".toByteArray(Charsets.US_ASCII)
    private val ATTACH_LABEL = "yansit-attach-v1".toByteArray(Charsets.US_ASCII)
    private val RESUME_LABEL = "yansit-resume-v1".toByteArray(Charsets.US_ASCII)

    public const val PIN_LENGTH: Int = 6
    public const val FINGERPRINT_SIZE: Int = 32

    private val random = SecureRandom()

    public fun authProof(
        secret: ByteArray,
        authMethod: Int,
        serverNonce: Bytes,
        clientNonce: Bytes,
        receiverCertFp: Bytes,
        deviceId: Bytes,
    ): Bytes {
        require(authMethod in 0..0xFF)
        require(serverNonce.size == ControlCodec.ID_SIZE && clientNonce.size == ControlCodec.ID_SIZE)
        require(receiverCertFp.size == FINGERPRINT_SIZE && deviceId.size == ControlCodec.ID_SIZE)
        return hmac(secret, AUTH_LABEL, byteArrayOf(authMethod.toByte()), serverNonce.unsafeArray(),
            clientNonce.unsafeArray(), receiverCertFp.unsafeArray(), deviceId.unsafeArray())
    }

    public fun attachProof(attachKey: Bytes, sessionId: Bytes, streamKind: Int, serverNonce: Bytes): Bytes {
        require(attachKey.size == ControlCodec.KEY_SIZE && sessionId.size == ControlCodec.ID_SIZE)
        require(serverNonce.size == ControlCodec.ID_SIZE && streamKind in 0..0xFF)
        return hmac(attachKey.unsafeArray(), ATTACH_LABEL, sessionId.unsafeArray(), byteArrayOf(streamKind.toByte()),
            serverNonce.unsafeArray())
    }

    public fun resumeProof(resumeToken: Bytes, sessionId: Bytes, serverNonce: Bytes): Bytes {
        require(resumeToken.size == ControlCodec.KEY_SIZE && sessionId.size == ControlCodec.ID_SIZE)
        require(serverNonce.size == ControlCodec.ID_SIZE)
        return hmac(resumeToken.unsafeArray(), RESUME_LABEL, sessionId.unsafeArray(), serverNonce.unsafeArray())
    }

    /** PIN'in HMAC anahtarı: 6 ASCII rakam. */
    public fun pinSecret(pin: String): ByteArray {
        require(pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }) { "PIN 6 rakam olmalı" }
        return pin.toByteArray(Charsets.US_ASCII)
    }

    public fun sha256(data: ByteArray): Bytes = Bytes.wrap(MessageDigest.getInstance("SHA-256").digest(data))

    public fun constantTimeEquals(a: Bytes, b: Bytes): Boolean = MessageDigest.isEqual(a.unsafeArray(), b.unsafeArray())

    public fun randomBytes(size: Int): Bytes {
        val b = ByteArray(size)
        random.nextBytes(b)
        return Bytes.wrap(b)
    }

    private fun hmac(key: ByteArray, vararg parts: ByteArray): Bytes {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return Bytes.wrap(mac.doFinal())
    }
}
