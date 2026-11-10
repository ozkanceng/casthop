package io.github.ozkanceng.yansit.stream

import java.util.ArrayDeque

/**
 * Which frames GL drew turned (ARCHITECTURE.md §2.1): the encoder hands frames back a little later, on its own
 * thread, and each must carry the ROTATED flag of the picture in it. Entries are matched on the presentation time
 * GL gave the frame; a frame without its own entry takes the last one before it.
 *
 * Thread-safe: GL records, the encoder's callback looks up.
 */
internal class OrientationTimeline(private val capacity: Int = 64) {
    private class Entry(val ptsUs: Long, val rotated: Boolean)

    private val entries = ArrayDeque<Entry>()
    private var lastRotated = false

    @Synchronized
    fun record(ptsUs: Long, rotated: Boolean) {
        if ((entries.peekLast()?.rotated ?: lastRotated) == rotated) return // only changes need an entry
        if (entries.size >= capacity) entries.pollFirst()?.let { lastRotated = it.rotated }
        entries.addLast(Entry(ptsUs, rotated))
    }

    /** The orientation of the frame drawn at [ptsUs]; entries before it are no longer needed. */
    @Synchronized
    fun rotatedAt(ptsUs: Long): Boolean {
        while (true) {
            val first = entries.peekFirst() ?: return lastRotated
            if (first.ptsUs > ptsUs) return lastRotated
            lastRotated = first.rotated
            entries.pollFirst()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        lastRotated = false
    }
}
