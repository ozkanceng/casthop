package io.github.ozkanceng.yansit.protocol.client

import io.github.ozkanceng.yansit.protocol.Auth
import io.github.ozkanceng.yansit.protocol.Bytes
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
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

/** Birden çok aday adrese paralel Pinned-TLS denemesinin sonucu. */
public sealed interface ConnectFirstResult {
    public data class Connected(val host: String, val socket: PinnedSocket) : ConnectFirstResult

    public data class Failed(
        val refused: Boolean,
        val fingerprintMismatch: FingerprintMismatchException?,
        val incompatible: IncompatibleReceiverException?,
        val lastError: IOException?,
    ) : ConnectFirstResult
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
        connectTimeoutMs: Int = 4000,
        handshakeTimeoutMs: Int = 5000,
        sendBufferBytes: Int = 128 * 1024,
        socketFactory: SocketFactory? = null,
        onSocketCreated: ((Socket) -> Unit)? = null,
    ): PinnedSocket {
        val trust = PinningTrustManager(expectedFingerprint)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trust), random)

        val raw = try {
            socketFactory?.createSocket() ?: Socket()
        } catch (_: IOException) {
            Socket()
        }
        onSocketCreated?.invoke(raw)
        try {
            raw.tcpNoDelay = true
            raw.sendBufferSize = sendBufferBytes.coerceIn(16 * 1024, 512 * 1024)
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
            runCatching { raw.close() }
            throw e
        }
    }

    private sealed interface AttemptOutcome {
        data class Ok(val host: String, val socket: PinnedSocket) : AttemptOutcome
        data class Err(val error: IOException) : AttemptOutcome
    }

    /**
     * Ham TCP probe yerine tüm aday adreslere doğrudan TLS 1.3 + parmak izi doğrulamalı bağlantı yarışır.
     * İlk doğrulanan bağlantı döner, diğer bekleyen soketler anında kapatılır.
     */
    public fun connectFirst(
        hosts: List<String>,
        port: Int,
        expectedFingerprint: Bytes?,
        expectedPrefix: Bytes? = null,
        connectTimeoutMs: Int = 4000,
        handshakeTimeoutMs: Int = 5000,
        sendBufferBytes: Int = 32 * 1024,
        socketFactoryProvider: (String) -> SocketFactory? = { null },
        cancelSignal: AtomicBoolean = AtomicBoolean(false),
        onRegisterSocket: ((Socket) -> Unit)? = null,
    ): ConnectFirstResult {
        val distinctHosts = hosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (distinctHosts.isEmpty()) return ConnectFirstResult.Failed(false, null, null, null)
        if (cancelSignal.get()) return ConnectFirstResult.Failed(false, null, null, IOException("iptal edildi"))

        val outcomes = ArrayBlockingQueue<AttemptOutcome>(distinctHosts.size)
        val openSockets = CopyOnWriteArrayList<Socket>()
        val winner = AtomicReference<PinnedSocket?>(null)

        fun closeLosers(except: Socket? = null) {
            for (s in openSockets) {
                if (s !== except) runCatching { s.close() }
            }
        }

        for (host in distinctHosts) {
            Thread({
                var created: Socket? = null
                try {
                    if (cancelSignal.get() || winner.get() != null) {
                        outcomes.offer(AttemptOutcome.Err(IOException("iptal edildi")))
                        return@Thread
                    }
                    val pinned = connect(
                        host = host,
                        port = port,
                        expectedFingerprint = expectedFingerprint,
                        connectTimeoutMs = connectTimeoutMs,
                        handshakeTimeoutMs = handshakeTimeoutMs,
                        sendBufferBytes = sendBufferBytes,
                        socketFactory = socketFactoryProvider(host),
                        onSocketCreated = { sock ->
                            created = sock
                            openSockets.add(sock)
                            onRegisterSocket?.invoke(sock)
                            if (cancelSignal.get() || winner.get() != null) runCatching { sock.close() }
                        },
                    )
                    if (expectedPrefix != null && !matchesPrefix(pinned.fingerprint, expectedPrefix)) {
                        pinned.close()
                        outcomes.offer(AttemptOutcome.Err(FingerprintMismatchException(expectedPrefix, pinned.fingerprint)))
                        return@Thread
                    }
                    if (!cancelSignal.get() && winner.compareAndSet(null, pinned)) {
                        closeLosers(except = created)
                        outcomes.offer(AttemptOutcome.Ok(host, pinned))
                    } else {
                        pinned.close()
                        outcomes.offer(AttemptOutcome.Err(IOException("diğer adres kazandı")))
                    }
                } catch (e: IOException) {
                    outcomes.offer(AttemptOutcome.Err(e))
                }
            }, "tls-race-$host").apply { isDaemon = true; start() }
        }

        var refused = false
        var mismatch: FingerprintMismatchException? = null
        var incompatible: IncompatibleReceiverException? = null
        var lastError: IOException? = null
        val totalWaitMs = connectTimeoutMs.toLong() + handshakeTimeoutMs.toLong() + 500L
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalWaitMs)

        repeat(distinctHosts.size) {
            val remainingNs = maxOf(0L, deadline - System.nanoTime())
            val result = try {
                outcomes.poll(remainingNs, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                closeLosers()
                return ConnectFirstResult.Failed(refused, mismatch, incompatible, IOException("iptal edildi"))
            } ?: run {
                closeLosers()
                return ConnectFirstResult.Failed(refused, mismatch, incompatible, lastError)
            }
            when (result) {
                is AttemptOutcome.Ok -> return ConnectFirstResult.Connected(result.host, result.socket)
                is AttemptOutcome.Err -> {
                    val e = result.error
                    when {
                        e is FingerprintMismatchException -> if (mismatch == null) mismatch = e
                        e is IncompatibleReceiverException -> if (incompatible == null) incompatible = e
                        e is ConnectException && e.message?.contains("refused", ignoreCase = true) == true -> refused = true
                    }
                    lastError = e
                }
            }
        }
        closeLosers()
        return ConnectFirstResult.Failed(refused, mismatch, incompatible, lastError)
    }

    private fun matchesPrefix(fingerprint: Bytes, prefix: Bytes): Boolean =
        prefix.size > 0 && prefix.size <= fingerprint.size && (0 until prefix.size).all { fingerprint[it] == prefix[it] }

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
