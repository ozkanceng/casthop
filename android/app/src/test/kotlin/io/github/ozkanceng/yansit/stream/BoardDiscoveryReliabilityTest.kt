package io.github.ozkanceng.yansit.stream

import android.net.nsd.NsdManager
import io.github.ozkanceng.yansit.data.BoardStore
import io.github.ozkanceng.yansit.protocol.Bytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class BoardDiscoveryReliabilityTest {
    @Test
    fun serialResolveQueueResolvesAllBoardsWithoutConcurrencyAndRetriesBusy() {
        val active = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val resolved = ArrayList<String>()
        val seenOnce = HashSet<String>()
        val scheduled = ArrayList<() -> Unit>()

        val queue = SerialResolveQueue<String>(
            scheduleDelay = { _, r -> scheduled += r },
            resolveOne = { name, onOk, onFail ->
                val cur = active.incrementAndGet()
                maxConcurrent.accumulateAndGet(cur, ::maxOf)
                // Simulate Android 10-13 FAILURE_ALREADY_ACTIVE on the first try for every 3rd board.
                if (name.endsWith("3") && seenOnce.add(name)) {
                    active.decrementAndGet()
                    onFail(NsdManager.FAILURE_ALREADY_ACTIVE)
                } else {
                    resolved += name
                    active.decrementAndGet()
                    onOk()
                }
            },
        )

        for (i in 1..15) queue.enqueue("etap-$i")
        while (scheduled.isNotEmpty()) {
            val batch = scheduled.toList()
            scheduled.clear()
            batch.forEach { it() }
        }

        assertEquals(1, maxConcurrent.get(), "Android <14 must never invoke concurrent resolveService calls")
        assertEquals(15, resolved.size)
        assertTrue(resolved.contains("etap-3"))
        assertTrue(resolved.contains("etap-13"))
    }

    @Test
    fun buildBoardRecoversIpv4FromTxtWhenHostIsIpv6Only() {
        val fpPrefix = Bytes.fromHex("a4f2" + "11".repeat(14))
        val fpB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(fpPrefix.toByteArray())
        val attrs = mapOf(
            "v" to "1".toByteArray(),
            "name" to "etap-A4F2".toByteArray(),
            "fp" to fpB64.toByteArray(),
            "kind" to "board".toByteArray(),
            "ip" to "10.0.1.24,192.168.43.10".toByteArray(),
        )
        // Android 10-13 NsdServiceInfo.getHost() can return IPv6 fe80:: only; TXT `ip=` must save it.
        val ipv6Only = listOf(InetAddress.getByName("fe80::1"))
        val board = BoardDiscovery.buildBoard("etap-A4F2", 47800, attrs, ipv6Only)
        assertNotNull(board)
        assertEquals("etap-A4F2", board!!.name)
        assertEquals(listOf("10.0.1.24", "192.168.43.10"), board.hosts)
        assertEquals("board", board.kind)
    }

    @Test
    fun mergeHostsPreservesSchoolWifiAndHotspotAddressesWithWinnerFirst() {
        val merged = BoardStore.mergeHosts(
            primaryHost = "192.168.43.88",
            freshHosts = listOf("192.168.43.88", "10.0.5.9"),
            existingHosts = listOf("10.0.1.24", "192.168.1.50"),
        )
        assertEquals(listOf("192.168.43.88", "10.0.5.9", "10.0.1.24", "192.168.1.50"), merged)
    }
}
