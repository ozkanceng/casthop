package io.github.ozkanceng.yansit.sender

/** Kodlanmış ses paketi (Opus: 20 ms ham paket; AAC: ham erişim birimi, ADTS yok). */
class AudioPacket(val data: ByteArray, val ptsUs: Long)

/**
 * Ses kaynağı soyutlaması (Android: AudioPlaybackCapture + MediaCodec; CI: dosya).
 * [config] protokol CONFIG yüküdür (codec baytı hariç): Opus için OpusHead, AAC için AudioSpecificConfig.
 */
interface AudioSource {
    val codec: Int
    val sampleRate: Int
    val channels: Int
    val config: ByteArray

    /** Paketler [sink]'e CLOCK_MONOTONIC yakalama zaman damgalarıyla verilir. */
    fun start(sink: (AudioPacket) -> Unit, onError: (Throwable) -> Unit)
    fun stop()
}

/**
 * Sesin sınırlı kuyruğu: ses akışı kesintisizliği önemli ama gecikme birikmemeli. Kuyruk dolarsa en eski
 * paket atılır (video gibi anahtar kare beklemesi yok; her Opus/AAC paketi bağımsız çözülür).
 */
internal class AudioQueue(private val capacity: Int = 10) {
    private val lock = Object()
    private val queue = ArrayDeque<AudioPacket>()
    private var closed = false

    @Volatile var dropped: Long = 0
        private set

    fun offer(p: AudioPacket) = synchronized(lock) {
        if (closed) return
        while (queue.size >= capacity) {
            queue.removeFirst()
            dropped++
        }
        queue.addLast(p)
        lock.notifyAll()
    }

    fun clear() = synchronized(lock) { queue.clear() }

    fun take(timeoutMs: Long): AudioPacket? = synchronized(lock) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (queue.isEmpty() && !closed) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return null
            lock.wait(left)
        }
        queue.removeFirstOrNull()
    }

    fun close() = synchronized(lock) {
        closed = true
        queue.clear()
        lock.notifyAll()
    }
}
