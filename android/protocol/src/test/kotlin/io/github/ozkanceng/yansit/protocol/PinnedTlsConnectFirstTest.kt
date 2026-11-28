package io.github.ozkanceng.yansit.protocol

import io.github.ozkanceng.yansit.protocol.client.ConnectFirstResult
import io.github.ozkanceng.yansit.protocol.client.PinnedTls
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Live loopback TLS 1.3 tests for direct PinnedTls.connectFirst racing and DHCP IP-swap detection. */
@Timeout(20)
class PinnedTlsConnectFirstTest {
    companion object {
        private lateinit var tempDir: File
        private lateinit var boardA: TestTlsReceiver
        private lateinit var boardB: TestTlsReceiver

        private class TestTlsReceiver(cn: String, dir: File) : AutoCloseable {
            val fingerprint: Bytes
            val server: SSLServerSocket
            private val running = AtomicBoolean(true)
            private val acceptThread: Thread

            init {
                val p12 = File(dir, "$cn.p12")
                val pass = "changeit"
                val proc = ProcessBuilder(
                    "keytool", "-genkeypair", "-alias", "srv", "-keyalg", "EC", "-groupname", "secp256r1",
                    "-sigalg", "SHA256withECDSA", "-dname", "CN=$cn", "-validity", "30",
                    "-storetype", "PKCS12", "-keystore", p12.absolutePath, "-storepass", pass, "-keypass", pass,
                ).redirectErrorStream(true).start()
                check(proc.waitFor() == 0) { "keytool failed: ${proc.inputStream.bufferedReader().readText()}" }

                val ks = KeyStore.getInstance("PKCS12").apply { FileInputStream(p12).use { load(it, pass.toCharArray()) } }
                fingerprint = Auth.sha256(ks.getCertificate("srv").encoded)
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass.toCharArray()) }
                val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
                server = (ctx.serverSocketFactory.createServerSocket(0, 16, InetAddress.getLoopbackAddress()) as SSLServerSocket).apply {
                    enabledProtocols = arrayOf("TLSv1.3")
                    sslParameters = sslParameters.also { it.applicationProtocols = arrayOf(PinnedTls.ALPN) }
                }
                acceptThread = Thread({
                    while (running.get()) {
                        val sock = try {
                            server.accept() as SSLSocket
                        } catch (_: IOException) {
                            break
                        }
                        Thread({
                            try {
                                sock.soTimeout = 3000
                                sock.startHandshake()
                                // Keep socket alive briefly so client verifies handshake cleanly.
                                sock.inputStream.read()
                            } catch (_: IOException) {
                            } finally {
                                runCatching { sock.close() }
                            }
                        }, "test-tls-peer-$cn").apply { isDaemon = true; start() }
                    }
                }, "test-tls-accept-$cn").apply { isDaemon = true; start() }
            }

            val port: Int get() = server.localPort

            override fun close() {
                running.set(false)
                runCatching { server.close() }
            }
        }

        @BeforeAll
        @JvmStatic
        fun setUp() {
            tempDir = Files.createTempDirectory("casthop-tls-race").toFile()
            boardA = TestTlsReceiver("boardA", tempDir)
            boardB = TestTlsReceiver("boardB", tempDir)
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            boardA.close()
            boardB.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun connectsDirectlyToPinnedReceiverAndVerifiesFingerprint() {
        val result = PinnedTls.connectFirst(
            hosts = listOf("127.0.0.1"),
            port = boardA.port,
            expectedFingerprint = boardA.fingerprint,
            connectTimeoutMs = 2000,
            handshakeTimeoutMs = 3000,
        )
        assertTrue(result is ConnectFirstResult.Connected, "expected Connected, got $result")
        val connected = result as ConnectFirstResult.Connected
        assertEquals("127.0.0.1", connected.host)
        assertEquals(boardA.fingerprint, connected.socket.fingerprint)
        connected.socket.close()
    }

    @Test
    fun reportsFingerprintMismatchWhenAnotherBoardHoldsOldIp() {
        // Simulates school DHCP recycling Board A's old IP to Board B.
        val result = PinnedTls.connectFirst(
            hosts = listOf("127.0.0.1"),
            port = boardB.port,
            expectedFingerprint = boardA.fingerprint,
            connectTimeoutMs = 2000,
            handshakeTimeoutMs = 3000,
        )
        assertTrue(result is ConnectFirstResult.Failed, "expected Failed, got $result")
        val failed = result as ConnectFirstResult.Failed
        val mismatch = checkNotNull(failed.fingerprintMismatch)
        assertEquals(boardA.fingerprint, mismatch.expected)
        assertEquals(boardB.fingerprint, mismatch.actual)
    }

    @Test
    fun reportsRefusedWhenPortIsClosed() {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val result = PinnedTls.connectFirst(
            hosts = listOf("127.0.0.1"),
            port = closedPort,
            expectedFingerprint = boardA.fingerprint,
            connectTimeoutMs = 1500,
            handshakeTimeoutMs = 1500,
        )
        assertTrue(result is ConnectFirstResult.Failed)
        assertTrue((result as ConnectFirstResult.Failed).refused)
    }
}
