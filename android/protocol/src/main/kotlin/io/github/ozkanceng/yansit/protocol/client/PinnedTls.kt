package io.github.ozkanceng.yansit.protocol.client

import io.github.ozkanceng.yansit.protocol.Auth
import io.github.ozkanceng.yansit.protocol.Bytes
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Tahtanın sertifika parmak izi beklenenle (QR veya kayıtlı eşleştirme) uyuşmadı: olası araya girme. */
public class FingerprintMismatchException(public val expected: Bytes, public val actual: Bytes) :
    IOException("sertifika parmak izi uyuşmuyor")

/** Sunucu ALPN `yansit/1` seçmedi veya TLS 1.3 kurulamadı. */
public class IncompatibleReceiverException(message: String) : IOException(message)

/** Kurulmuş, parmak izi doğrulanmış TLS bağlantısı. */
public class PinnedSocket internal constructor(
    public val socket: SSLSocket,
    /** Bu bağlantıda görülen sertifikanın SHA-256'sı (kimlik kanıtına girer). */
    public val fingerprint: Bytes,
) : AutoCloseable {
    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}

/**
 * Alıcıya TLS 1.3 bağlantısı (PROTOCOL.md §2). CA zinciri yerine parmak izi sabitleme kullanılır:
 * [expectedFingerprint] verilirse sertifika onunla eşleşmeli; `null` ise (PIN ile ilk bağlantı) görülen
 * parmak izi döner ve çağıran kaydeder.
 */
public object PinnedTls {
    public const val ALPN: String = "yansit/1"
    private const val TLS13 = "TLSv1.3"
    private val random = SecureRandom()

    @Throws(IOException::class)
    public fun connect(
        host: String,
        port: Int,
        expectedFingerprint: Bytes?,
        connectTimeoutMs: Int = 3000,
        handshakeTimeoutMs: Int = 5000,
    ): PinnedSocket {
        val trust = PinningTrustManager(expectedFingerprint)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trust), random)

        val raw = Socket()
        try {
            raw.tcpNoDelay = true
            raw.sendBufferSize = 256 * 1024
            raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
            val ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            ssl.enabledProtocols = arrayOf(TLS13)
            ssl.sslParameters = ssl.sslParameters.also { it.applicationProtocols = arrayOf(ALPN) }
            ssl.soTimeout = handshakeTimeoutMs
            try {
                ssl.startHandshake()
            } catch (e: IOException) {
                trust.mismatch?.let { throw it }
                throw e
            }
            if (ssl.session.protocol != TLS13 || ssl.applicationProtocol != ALPN) {
                ssl.close()
                throw IncompatibleReceiverException("protokol=${ssl.session.protocol} alpn=${ssl.applicationProtocol}")
            }
            ssl.soTimeout = 0
            return PinnedSocket(ssl, checkNotNull(trust.observed))
        } catch (e: IOException) {
            raw.close()
            throw e
        }
    }

    /** Yalnız parmak izi eşleşen sertifikayı kabul eder; CA, ad ve süre denetimi yapılmaz (kendinden imzalı). */
    private class PinningTrustManager(private val expected: Bytes?) : X509TrustManager {
        @Volatile var observed: Bytes? = null
        @Volatile var mismatch: FingerprintMismatchException? = null

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("sertifika yok")
            val fp = Auth.sha256(leaf.encoded)
            if (expected != null && !Auth.constantTimeEquals(expected, fp)) {
                mismatch = FingerprintMismatchException(expected, fp)
                throw CertificateException("parmak izi uyuşmuyor")
            }
            observed = fp
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("istemci sertifikası beklenmiyor")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
