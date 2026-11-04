package io.github.ozkanceng.yansit.receiver

import io.github.ozkanceng.yansit.protocol.client.PinnedTls
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.SocketException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** An accepted connection. The engine runs [handshake] on the connection's own thread. */
public interface ReceiverSocket : Closeable {
    public val input: InputStream
    public val output: OutputStream

    /** Finishes TLS. @throws IOException when it fails or the peer did not agree on TLS 1.3 and ALPN `yansit/1`. */
    @Throws(IOException::class)
    public fun handshake()

    /** Closes at once, dropping unsent data. Unblocks a pending read. */
    public fun abort()
}

/** The listening socket. TLS comes with it, so the core does not depend on where the identity lives. */
public interface ServerTransport : Closeable {
    public val port: Int

    /** Blocks until a connection arrives. @throws IOException once closed. */
    @Throws(IOException::class)
    public fun accept(): ReceiverSocket
}

/**
 * TLS 1.3 server on JSSE (PROTOCOL.md §2, §11). Works on the JVM and on Android; only the [context] differs:
 * a PKCS#12 test identity in tools/fake-receiver, an AndroidKeyStore key in android/tv.
 */
public class JsseServerTransport(context: SSLContext, port: Int) : ServerTransport {
    private val server = context.serverSocketFactory.createServerSocket() as SSLServerSocket

    init {
        server.reuseAddress = true
        server.bind(InetSocketAddress(port), BACKLOG)
    }

    override val port: Int get() = server.localPort

    override fun accept(): ReceiverSocket = JsseSocket(server.accept() as SSLSocket)

    override fun close() {
        server.close()
    }

    private class JsseSocket(private val socket: SSLSocket) : ReceiverSocket {
        override val input: InputStream get() = socket.inputStream
        override val output: OutputStream get() = socket.outputStream

        override fun handshake() {
            socket.tcpNoDelay = true
            socket.useClientMode = false
            socket.enabledProtocols = arrayOf(TLS13)
            // ALPN goes on the accepted socket: not every provider carries it over from the server socket.
            socket.sslParameters = socket.sslParameters.also { it.applicationProtocols = arrayOf(PinnedTls.ALPN) }
            socket.startHandshake()
            if (socket.session.protocol != TLS13 || socket.applicationProtocol != PinnedTls.ALPN) {
                throw IOException("peer did not agree on TLS 1.3 and ALPN")
            }
        }

        override fun abort() {
            try {
                socket.setSoLinger(true, 0)
            } catch (_: SocketException) {
                // Already closed.
            }
            close()
        }

        override fun close() {
            try {
                socket.close()
            } catch (_: IOException) {
                // Nothing left to release.
            }
        }
    }

    private companion object {
        const val TLS13 = "TLSv1.3"
        const val BACKLOG = 32
    }
}
