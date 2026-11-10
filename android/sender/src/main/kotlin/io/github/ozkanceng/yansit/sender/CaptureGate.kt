package io.github.ozkanceng.yansit.sender

/**
 * Back pressure at capture (ARCHITECTURE.md §2.1 "Gönderim kuyruğu").
 *
 * A frame the network cannot take soon is not drawn at all: the source holds the newest picture and asks again,
 * instead of encoding a P-frame that [SendQueue] would later have to throw away together with every frame up to
 * the next IDR. Skipping a picture before the encoder breaks nothing; dropping one after it costs a keyframe burst.
 *
 * Open when the queue is empty or holds one fresh frame. While nothing is being sent (reconnecting, draining,
 * paused) it stays open and the queue's own overflow rules apply, as before.
 * A gate closed for longer than [congestionUs] in one stretch reports congestion once ([onCongestion]), so the bit
 * rate comes down just as it would after a queue drop.
 *
 * Thread-safe: the source asks from its own thread.
 */
class CaptureGate(
    private val freshUs: Long = 16_000,
    private val congestionUs: Long = 50_000,
    private val onCongestion: () -> Unit = {},
) {
    private var closedSinceUs = NONE
    private var reported = false

    /**
     * @param depth frames waiting in the send queue
     * @param oldestAgeUs age of the oldest of them
     * @param sending the link is up and frames are going out now
     */
    fun ready(nowUs: Long, depth: Int, oldestAgeUs: Long, sending: Boolean): Boolean {
        val open = !sending || depth == 0 || (depth == 1 && oldestAgeUs < freshUs)
        var report = false
        synchronized(this) {
            if (open) {
                closedSinceUs = NONE
                reported = false
            } else if (closedSinceUs == NONE) {
                closedSinceUs = nowUs
            } else if (!reported && nowUs - closedSinceUs > congestionUs) {
                reported = true
                report = true
            }
        }
        if (report) onCongestion()
        return open
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
