package io.github.ozkanceng.yansit.protocol

import io.github.ozkanceng.yansit.protocol.client.ReachResult
import io.github.ozkanceng.yansit.protocol.client.Reachability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket

class ReachabilityTest {
    private fun closedPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    @Test
    fun findsListeningHostAmongOthers() {
        ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { server ->
            val port = server.localPort
            // 127.0.0.2 Linux'ta loopback'tir ama dinlenmez → ret; 127.0.0.1 açık.
            val r = Reachability.firstReachable(listOf("127.0.0.2", "127.0.0.1"), port, 1000)
            assertEquals(ReachResult.Reachable("127.0.0.1"), r)
        }
    }

    @Test
    fun refusedWhenNothingListens() {
        val r = Reachability.firstReachable(listOf("127.0.0.1"), closedPort(), 1000)
        assertEquals(ReachResult.Unreachable(refused = true), r)
    }
}
