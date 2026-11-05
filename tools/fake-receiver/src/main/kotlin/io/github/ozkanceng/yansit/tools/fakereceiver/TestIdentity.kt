package io.github.ozkanceng.yansit.tools.fakereceiver

import io.github.ozkanceng.yansit.protocol.Auth
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The self-signed ECDSA P-256 identity of PROTOCOL.md §2, made by the JDK's `keytool` so the test tool needs no
 * certificate library. The store password sits next to the store: this identity protects nothing but a test run.
 */
internal class TestIdentity private constructor(val context: SSLContext, val certificateDer: ByteArray) {
    companion object {
        private const val ALIAS = "casthop"
        private const val STORE_NAME = "identity.p12"
        private const val PASSWORD_NAME = "identity.pass"
        private const val VALIDITY_DAYS = 3650
        private const val KEYTOOL_TIMEOUT_S = 60L

        @Throws(IOException::class)
        fun loadOrCreate(dataDir: File): TestIdentity {
            dataDir.mkdirs()
            val store = File(dataDir, STORE_NAME)
            val passwordFile = File(dataDir, PASSWORD_NAME)
            if (store.isFile && passwordFile.isFile) {
                val loaded = load(store, passwordFile.readText())
                if (loaded != null) return loaded
                // §11: an expired certificate is replaced.
                store.delete()
            }
            val password = Auth.randomBytes(16).toHex()
            passwordFile.delete()
            passwordFile.createNewFile()
            passwordFile.setReadable(false, false)
            passwordFile.setReadable(true, true)
            passwordFile.writeText(password)
            runKeytool(store, password)
            return load(store, password) ?: throw IOException("keytool made an unusable certificate")
        }

        private fun load(store: File, password: String): TestIdentity? {
            val keyStore = KeyStore.getInstance("PKCS12")
            store.inputStream().use { keyStore.load(it, password.toCharArray()) }
            val certificate = keyStore.getCertificate(ALIAS) as? X509Certificate ?: return null
            try {
                certificate.checkValidity()
            } catch (_: CertificateExpiredException) {
                return null
            } catch (_: CertificateNotYetValidException) {
                return null
            }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keys.init(keyStore, password.toCharArray())
            val context = SSLContext.getInstance("TLS")
            context.init(keys.keyManagers, null, null)
            return TestIdentity(context, certificate.encoded)
        }

        private fun runKeytool(store: File, password: String) {
            val keytool = File(System.getProperty("java.home"), "bin/keytool").takeIf { it.canExecute() }?.path ?: "keytool"
            val process = ProcessBuilder(
                keytool, "-genkeypair", "-alias", ALIAS, "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-validity", VALIDITY_DAYS.toString(), "-dname", "CN=CastHop test receiver",
                "-storetype", "PKCS12", "-keystore", store.path,
                // Through the environment, so the password is not on a command line anyone can list.
                "-storepass:env", "CASTHOP_STORE_PASS",
            ).redirectErrorStream(true).apply {
                environment()["CASTHOP_STORE_PASS"] = password
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(KEYTOOL_TIMEOUT_S, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw IOException("keytool failed: $output")
            }
            store.setReadable(false, false)
            store.setReadable(true, true)
        }
    }
}
