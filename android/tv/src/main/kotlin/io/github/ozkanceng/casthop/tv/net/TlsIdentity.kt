package io.github.ozkanceng.casthop.tv.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * The receiver's identity (PROTOCOL.md §2, §11): a self-signed ECDSA P-256 certificate whose private key never leaves
 * AndroidKeyStore. An expired certificate is replaced, after which paired devices have to scan the QR again.
 */
internal class TlsIdentity private constructor(val context: SSLContext, val certificateDer: ByteArray) {
    /** Hands the one key to TLS, whatever the provider asks for. */
    private class FixedKeyManager(private val key: PrivateKey, private val chain: Array<X509Certificate>) :
        X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
            null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String =
            ALIAS
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain
        override fun getPrivateKey(alias: String?): PrivateKey = key
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "casthop-receiver-identity"
        private const val VALIDITY_YEARS = 10

        fun loadOrCreate(): TlsIdentity {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            var certificate = store.getCertificate(ALIAS) as? X509Certificate
            if (certificate == null || !isValid(certificate)) {
                store.deleteEntry(ALIAS)
                generate()
                certificate = store.getCertificate(ALIAS) as X509Certificate
            }
            val key = store.getKey(ALIAS, null) as PrivateKey
            val context = SSLContext.getInstance("TLS")
            val keyManagers: Array<KeyManager> = arrayOf(FixedKeyManager(key, arrayOf(certificate)))
            context.init(keyManagers, null, null)
            return TlsIdentity(context, certificate.encoded)
        }

        private fun isValid(certificate: X509Certificate): Boolean = try {
            certificate.checkValidity()
            true
        } catch (_: CertificateExpiredException) {
            false
        } catch (_: CertificateNotYetValidException) {
            false
        }

        private fun generate() {
            val notBefore = Date()
            val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, VALIDITY_YEARS) }.time
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // TLS 1.3 signs SHA-256; the TLS stack may hash first and ask for a raw signature.
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                .setCertificateSubject(X500Principal("CN=CastHop"))
                .setCertificateSerialNumber(BigInteger.valueOf(notBefore.time))
                .setCertificateNotBefore(notBefore)
                .setCertificateNotAfter(notAfter)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
                initialize(spec)
                generateKeyPair()
            }
        }
    }
}
