package io.github.ozkanceng.yansit.protocol.client

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Bağlanılabilirlik denetiminin sonucu. */
public sealed interface ReachResult {
    public data class Reachable(val host: String) : ReachResult

    /**
     * Hiçbir adrese bağlanılamadı.
     * [refused]: en az bir adres bağlantıyı reddetti (tahta açık ama Yansıt çalışmıyor/port kapalı);
     * aksi halde zaman aşımı veya ağ hatası (farklı ağ, istemci yalıtımı — RISKS R1).
     */
    public data class Unreachable(val refused: Boolean) : ReachResult
}

/** QR'daki adreslerden ulaşılabilir olanı bulur (PROTOCOL.md §8: tahtanın birden çok IP'si olabilir). */
public object Reachability {
    /**
     * Tüm adreslere aynı anda TCP bağlantısı dener ve ilk başarılı olanı döndürür (hepsi aynı tahtadır).
     * Bağlantılar hemen kapatılır; kimlik doğrulama ayrıca yapılır.
     */
    public fun firstReachable(hosts: List<String>, port: Int, timeoutMs: Int = 2000): ReachResult {
        require(hosts.isNotEmpty())
        val outcomes = ArrayBlockingQueue<Pair<String, Outcome>>(hosts.size)
        for (host in hosts) {
            Thread({ outcomes.put(host to probe(host, port, timeoutMs)) }, "reach-$host")
                .apply { isDaemon = true; start() }
        }
        var refused = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs + 500L)
        repeat(hosts.size) {
            val (host, outcome) = outcomes.poll(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                ?: return ReachResult.Unreachable(refused)
            when (outcome) {
                Outcome.OK -> return ReachResult.Reachable(host)
                Outcome.REFUSED -> refused = true
                Outcome.FAILED -> {}
            }
        }
        return ReachResult.Unreachable(refused)
    }

    private enum class Outcome { OK, REFUSED, FAILED }

    private fun probe(host: String, port: Int, timeoutMs: Int): Outcome = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        Outcome.OK
    } catch (e: ConnectException) {
        // "Network is unreachable" de ConnectException olabilir; yalnız gerçek ret REFUSED sayılır.
        if (e.message?.contains("refused", ignoreCase = true) == true) Outcome.REFUSED else Outcome.FAILED
    } catch (_: IOException) {
        Outcome.FAILED
    }
}
