package io.github.ozkanceng.yansit.data

import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.stream.DiscoveredBoard
import java.util.Locale

/**
 * Aynı adlı tahtaları (ör. Pardus ETAP varsayılan "etap" hostname'i) ayırt etmek ve sıralamak için saf mantık.
 *
 * Kısa kimlik (`#A4F2`) sertifika parmak izinin ilk 2 baytından üretilir: mDNS TXT `fp=` alanı ilk 16 baytı
 * taşıdığından keşif listesi, hatırlanan tahtalar ve tahta bekleme ekranı birebir aynı kimliği gösterir.
 */
object BoardIdentity {
    /** 4 haneli kalıcı büyük-harf hex kimlik (ör. "A4F2"). */
    fun shortId(fingerprintOrPrefix: Bytes): String {
        if (fingerprintOrPrefix.size < 2) return fingerprintOrPrefix.toHex().uppercase().padStart(4, '0').take(4)
        val b0 = fingerprintOrPrefix[0].toInt() and 0xFF
        val b1 = fingerprintOrPrefix[1].toInt() and 0xFF
        return String.format(Locale.ROOT, "%02X%02X", b0, b1)
    }

    /** İlk IPv4 adresinin son okteti (ör. ".24"); geçerli IPv4 yoksa `null`. */
    fun ipLastOctet(hosts: List<String>): String? {
        val ip = primaryIpv4(hosts) ?: return null
        val dot = ip.lastIndexOf('.')
        return if (dot in 1 until ip.lastIndex) ip.substring(dot) else null
    }

    /** Listedeki ilk geçerli IPv4 adresi. */
    fun primaryIpv4(hosts: List<String>): String? = hosts.firstOrNull { isIpv4(it) }

    /** Parmak izi [prefix] ile başlıyor mu? */
    fun matchesPrefix(fingerprint: Bytes, prefix: Bytes): Boolean =
        prefix.size > 0 && prefix.size <= fingerprint.size && (0 until prefix.size).all { fingerprint[it] == prefix[it] }

    /**
     * Kart alt başlığı: IP son hanesi + kısa kimlik + (takma ad varsa) tahtanın yayınladığı ham ad.
     * Örnekler: "IP .24 · #A4F2" veya "etap · IP .24 · #A4F2" veya "#A4F2".
     */
    fun subtitle(
        hosts: List<String>,
        fingerprintOrPrefix: Bytes,
        rawName: String,
        nickname: String? = null,
    ): String {
        val parts = mutableListOf<String>()
        val cleanNick = nickname?.trim().orEmpty()
        if (cleanNick.isNotEmpty() && !cleanNick.equals(rawName.trim(), ignoreCase = true)) {
            parts += rawName.trim()
        }
        ipLastOctet(hosts)?.let { parts += "IP $it" }
        parts += "#${shortId(fingerprintOrPrefix)}"
        return parts.joinToString(" · ")
    }

    /** Hatırlanan tahtalar: favoriler üstte, ardından en son bağlanılanlar. */
    fun sortRemembered(boards: List<RememberedBoard>): List<RememberedBoard> =
        boards.sortedWith(
            compareByDescending<RememberedBoard> { it.favorite }
                .thenByDescending { it.lastUsedMs }
                .thenBy { it.displayName.lowercase() },
        )

    /**
     * Keşfedilen tahtalar: favori olarak kayıtlı olanlar en üstte, ardından daha önce bağlanılanlar,
     * sonra yeni keşfedilenler (ad + kısa kimlik sırasıyla).
     */
    fun sortDiscovered(boards: List<DiscoveredBoard>, remembered: List<RememberedBoard>): List<DiscoveredBoard> {
        fun match(b: DiscoveredBoard): RememberedBoard? =
            remembered.firstOrNull { matchesPrefix(it.fingerprint, b.fingerprintPrefix) }
        return boards.sortedWith(
            compareByDescending<DiscoveredBoard> { match(it)?.favorite == true }
                .thenByDescending { match(it)?.lastUsedMs ?: Long.MIN_VALUE }
                .thenBy { (match(it)?.displayName ?: it.name).lowercase() }
                .thenBy { shortId(it.fingerprintPrefix) },
        )
    }

    /** Girdi dizesinden yalnızca 6 haneli OTP rakamlarını ayıklar (boşluk, tire vb. temizlenir). */
    fun sanitizeOtp(raw: String, length: Int = 6): String = raw.filter(Char::isDigit).take(length)

    private fun isIpv4(text: String): Boolean {
        val parts = text.trim().split('.')
        return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }
}
