package io.github.ozkanceng.yansit.receiver

import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.ControlCodec

/** A 16-byte id starting with [start]. */
fun id(vararg start: Int): Bytes {
    val b = ByteArray(ControlCodec.ID_SIZE)
    start.forEachIndexed { i, v -> b[i] = v.toByte() }
    return Bytes.of(b)
}

/** A 32-byte key filled with [fill]. */
fun key(fill: Int): Bytes = Bytes.of(ByteArray(ControlCodec.KEY_SIZE) { fill.toByte() })

/** Decoder stand-in that keeps what it was given. */
class RecordingVideoBackend : VideoBackend {
    override val decoderName: String = "recording"
    override val hardware: Boolean = false
    var accept = true
    val configs = ArrayList<Pair<Int, ByteArray>>()
    val submitted = ArrayList<Triple<ByteArray, Long, Boolean>>()
    val rotated = ArrayList<Boolean>()
    var rotation = false
    var resets = 0

    override val supportsRotation: Boolean get() = rotation

    /** When set, every submitted frame is reported as rendered at once. */
    var onSubmit: ((Long) -> Unit)? = null

    override fun configure(codec: Int, config: ByteArray): Boolean {
        configs += codec to config
        return accept
    }

    override fun submit(accessUnit: ByteArray, timestampUs: Long, keyframe: Boolean, rotated: Boolean) {
        submitted += Triple(accessUnit, timestampUs, keyframe)
        this.rotated += rotated
        onSubmit?.invoke(timestampUs)
    }

    override fun reset() {
        resets++
    }
}

class RecordingAudioBackend : AudioBackend {
    var accept = true
    val configs = ArrayList<Pair<Int, ByteArray>>()
    val submitted = ArrayList<Pair<ByteArray, Long>>()

    override fun configure(codec: Int, config: ByteArray): Boolean {
        configs += codec to config
        return accept
    }

    override fun submit(packet: ByteArray, playAtUs: Long) {
        submitted += packet to playAtUs
    }

    override fun reset() = Unit
}
