package io.github.ozkanceng.yansit.protocol

/** Değişmez bayt dizisi; içerik eşitliği ve onaltılık gösterim sağlar. */
public class Bytes private constructor(private val data: ByteArray) {
    public val size: Int get() = data.size

    public operator fun get(index: Int): Byte = data[index]

    /** Kopyasını döndürür (iç dizi dışarı sızmaz). */
    public fun toByteArray(): ByteArray = data.copyOf()

    internal fun unsafeArray(): ByteArray = data

    public fun toHex(): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean = other is Bytes && data.contentEquals(other.data)

    override fun hashCode(): Int = data.contentHashCode()

    override fun toString(): String = "Bytes(${toHex()})"

    public companion object {
        private const val HEX = "0123456789abcdef"

        public val EMPTY: Bytes = Bytes(ByteArray(0))

        public fun of(array: ByteArray): Bytes = Bytes(array.copyOf())

        public fun fromHex(hex: String): Bytes {
            require(hex.length % 2 == 0) { "tek sayıda onaltılık karakter" }
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(hex[i * 2], 16)
                val lo = Character.digit(hex[i * 2 + 1], 16)
                require(hi >= 0 && lo >= 0) { "geçersiz onaltılık karakter" }
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return Bytes(out)
        }

        internal fun wrap(array: ByteArray): Bytes = Bytes(array)
    }
}
