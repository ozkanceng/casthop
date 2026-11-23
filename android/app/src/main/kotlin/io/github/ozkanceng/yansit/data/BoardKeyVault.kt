package io.github.ozkanceng.yansit.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import io.github.ozkanceng.yansit.protocol.Bytes
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "YansitVault"

/**
 * Kalıcı eşleştirme anahtarlarını (`pairKey`) Android Keystore (AES-256-GCM) ile şifreler.
 * Ek bağımlılık gerektirmez (minSdk 29+). Keystore erişilemezse (ör. özel emülatör/test) güvenli geri dönüş sunar.
 */
internal object BoardKeyVault {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "casthop_board_pair_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val PREFIX = "v1:"

    fun encrypt(secret: Bytes): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = Bytes.of(cipher.iv)
        val ct = Bytes.of(cipher.doFinal(secret.toByteArray()))
        "$PREFIX${iv.toHex()}:${ct.toHex()}"
    }.onFailure { e ->
        Log.w(TAG, "Keystore encrypt unavailable", e)
    }.getOrNull()

    fun decrypt(encoded: String): Bytes? {
        if (!encoded.startsWith(PREFIX)) return null
        val parts = encoded.removePrefix(PREFIX).split(':')
        if (parts.size != 2) return null
        return runCatching {
            val iv = Bytes.fromHex(parts[0]).toByteArray()
            val ct = Bytes.fromHex(parts[1]).toByteArray()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            Bytes.of(cipher.doFinal(ct))
        }.onFailure { e ->
            Log.w(TAG, "Keystore decrypt failed", e)
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        gen.init(spec)
        return gen.generateKey()
    }
}
