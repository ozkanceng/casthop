package io.github.ozkanceng.yansit.data

import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.stream.DiscoveredBoard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BoardIdentityTest {
    private fun fp(prefixHex: String) = Bytes.fromHex(prefixHex + "00".repeat(32 - prefixHex.length / 2))
    private fun prefix(prefixHex: String) = Bytes.fromHex(prefixHex + "00".repeat(16 - prefixHex.length / 2))

    private fun remembered(
        name: String,
        ip: String,
        fpHex: String,
        lastUsedMs: Long,
        nickname: String? = null,
        favorite: Boolean = false,
    ) = RememberedBoard(
        name = name,
        hosts = listOf(ip),
        port = 47800,
        fingerprint = fp(fpHex),
        keyId = Bytes.fromHex("11".repeat(16)),
        pairKey = Bytes.fromHex("22".repeat(32)),
        lastUsedMs = lastUsedMs,
        nickname = nickname,
        favorite = favorite,
        kind = "board",
    )

    @Test
    fun shortIdMatchesAcrossFullFingerprintAndMdnsPrefix() {
        val full = fp("a4f29988")
        val mdns = prefix("a4f29988")
        assertEquals("A4F2", BoardIdentity.shortId(full))
        assertEquals("A4F2", BoardIdentity.shortId(mdns))
        assertTrue(BoardIdentity.matchesPrefix(full, mdns))
        assertFalse(BoardIdentity.matchesPrefix(full, prefix("a4f39988")))
    }

    @Test
    fun subtitleDisambiguatesMultipleEtapBoards() {
        val b1 = BoardIdentity.subtitle(listOf("192.168.43.102"), prefix("a4f2"), "etap", nickname = null)
        val b2 = BoardIdentity.subtitle(listOf("192.168.43.118"), prefix("7c01"), "etap", nickname = null)
        assertEquals("IP .102 · #A4F2", b1)
        assertEquals("IP .118 · #7C01", b2)

        val nicknamed = BoardIdentity.subtitle(listOf("192.168.43.102"), prefix("a4f2"), "etap", nickname = "9-B Matematik")
        assertEquals("etap · IP .102 · #A4F2", nicknamed)
    }

    @Test
    fun ipLastOctetIgnoresNonIpv4Addresses() {
        assertEquals(".42", BoardIdentity.ipLastOctet(listOf("fe80::1", "192.168.43.42")))
        assertNull(BoardIdentity.ipLastOctet(listOf("fe80::1", "not-an-ip")))
    }

    @Test
    fun sortRememberedPutsFavoritesFirstThenMostRecent() {
        val recent = remembered("etap", "192.168.1.10", "aaaa", lastUsedMs = 500, favorite = false)
        val oldFav = remembered("etap", "192.168.1.11", "bbbb", lastUsedMs = 100, nickname = "10-A", favorite = true)
        val newFav = remembered("etap", "192.168.1.12", "cccc", lastUsedMs = 300, nickname = "9-B", favorite = true)

        val sorted = BoardIdentity.sortRemembered(listOf(recent, oldFav, newFav))
        assertEquals(listOf("CCCC", "BBBB", "AAAA"), sorted.map { it.shortId })
    }

    @Test
    fun sortDiscoveredElevatesRememberedFavoritesAndRecentBoards() {
        val dUnknown = DiscoveredBoard("etap", listOf("192.168.1.5"), 47800, prefix("1111"), "board")
        val dRecent = DiscoveredBoard("etap", listOf("192.168.1.6"), 47800, prefix("2222"), "board")
        val dFav = DiscoveredBoard("etap", listOf("192.168.1.7"), 47800, prefix("3333"), "board")
        val saved = listOf(
            remembered("etap", "192.168.1.6", "2222", lastUsedMs = 900, favorite = false),
            remembered("etap", "192.168.1.7", "3333", lastUsedMs = 100, favorite = true),
        )

        val sorted = BoardIdentity.sortDiscovered(listOf(dUnknown, dRecent, dFav), saved)
        assertEquals(listOf("3333", "2222", "1111"), sorted.map { BoardIdentity.shortId(it.fingerprintPrefix) })
    }

    @Test
    fun sanitizeOtpStripsSpacesDashesAndExtraCharacters() {
        assertEquals("482917", BoardIdentity.sanitizeOtp("482 917"))
        assertEquals("482917", BoardIdentity.sanitizeOtp("482-917"))
        assertEquals("123456", BoardIdentity.sanitizeOtp("Kod: 123456789"))
        assertEquals("", BoardIdentity.sanitizeOtp("abc"))
    }
}
