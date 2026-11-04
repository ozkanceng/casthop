package io.github.ozkanceng.yansit.sender

/**
 * Stops keyframes from piling up on a slow link. While one is being written, the frames behind it overflow the send
 * queue, and each overflow asks for another keyframe. Those requests wait here and leave as one when the write ends.
 *
 * [timeoutUs] bounds the wait, so a stalled write cannot freeze the picture for longer than that.
 */
class KeyframeGate(private val timeoutUs: Long = 1_000_000) {
    private var writingSinceUs: Long? = null
    private var pending = false

    /** @return true if the keyframe may be requested now. */
    @Synchronized
    fun onRequest(nowUs: Long): Boolean {
        val since = writingSinceUs
        if (since == null || nowUs - since >= timeoutUs) return true
        pending = true
        return false
    }

    @Synchronized
    fun onWriteStarted(nowUs: Long) {
        writingSinceUs = nowUs
    }

    /** @return true if a request waited during the write and must be sent now. */
    @Synchronized
    fun onWriteEnded(): Boolean {
        writingSinceUs = null
        return pending.also { pending = false }
    }
}
