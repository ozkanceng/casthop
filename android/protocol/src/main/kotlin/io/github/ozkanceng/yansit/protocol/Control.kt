package io.github.ozkanceng.yansit.protocol

/** Kontrol mesajı tipleri (PROTOCOL.md §7). */
public object MsgType {
    public const val CHALLENGE: Int = 0x00
    public const val HELLO: Int = 0x01
    public const val WELCOME: Int = 0x02
    public const val REJECT: Int = 0x03
    public const val PENDING_APPROVAL: Int = 0x04
    public const val STREAM_CONFIG: Int = 0x10
    public const val KEYFRAME_REQUEST: Int = 0x11
    public const val PING: Int = 0x20
    public const val PONG: Int = 0x21
    public const val RECEIVER_REPORT: Int = 0x30
    public const val SENDER_STATS: Int = 0x31
    public const val PAUSE: Int = 0x40
    public const val UNPAUSE: Int = 0x41
    public const val ATTACH: Int = 0x50
    public const val ATTACH_RESULT: Int = 0x51
    public const val RESUME: Int = 0x60
    public const val BYE: Int = 0x70

    /** msg_flags bit0: alıcı tanımıyorsa bağlantıyı kapatmalı. */
    public const val FLAG_CRITICAL: Int = 0x01
}

public object Codec {
    public const val H264: Int = 0x01
    public const val H265: Int = 0x02
    public const val OPUS: Int = 0x10
    public const val AAC_LC: Int = 0x11
}

/** Çözücü desteği: [flags] bit0 = donanım, bit1 = tercih edilen. */
public data class CodecSupport(val codec: Int, val flags: Int) {
    init {
        require(codec in 0..0xFF && flags in 0..0xFF)
    }

    public val hardware: Boolean get() = flags and FLAG_HARDWARE != 0
    public val preferred: Boolean get() = flags and FLAG_PREFERRED != 0

    public companion object {
        public const val FLAG_HARDWARE: Int = 0x01
        public const val FLAG_PREFERRED: Int = 0x02
    }
}

public object AuthMethod {
    public const val QR_TOKEN: Int = 1
    public const val PIN: Int = 2
    public const val PAIRED: Int = 3
}

public object StreamKind {
    public const val VIDEO: Int = 1
    public const val AUDIO: Int = 2
}

public object RejectReason {
    public const val VERSION_UNSUPPORTED: Int = 1
    public const val AUTH_FAILED: Int = 2
    public const val BUSY_DENIED: Int = 3
    public const val BUSY_TIMEOUT: Int = 4
    public const val RATE_LIMITED: Int = 5
    public const val MALFORMED: Int = 6
    public const val INTERNAL: Int = 7
    public const val SESSION_UNKNOWN: Int = 8
    public const val LOCKED_OUT: Int = 9
}

public object KeyframeReason {
    public const val DECODER_RESET: Int = 1
    public const val BACKLOG_DROPPED: Int = 2
    public const val DECODE_ERROR: Int = 3
    public const val USER: Int = 4
}

public object ByeReason {
    public const val USER_STOP: Int = 1
    public const val PROJECTION_STOPPED: Int = 2
    public const val TAKEOVER: Int = 3
    public const val RECEIVER_CLOSING: Int = 4
    public const val ERROR: Int = 5
    public const val TIMEOUT: Int = 6
    public const val MALFORMED: Int = 7
}

/** Kontrol mesajları. Sayısal alanlar: u8/u16 → Int, u32 → Long (0..2^32-1), u64/i64 → Long. */
public sealed interface ControlMessage {
    public val msgType: Int

    public data class Challenge(
        val minVersion: Int,
        val maxVersion: Int,
        val serverNonce: Bytes,
        val receiverName: String? = null,
    ) : ControlMessage { override val msgType: Int get() = MsgType.CHALLENGE }

    public data class Hello(
        val version: Int,
        val deviceName: String,
        val deviceId: Bytes,
        val appVersion: String,
        val authMethod: Int,
        val clientNonce: Bytes,
        val authProof: Bytes,
        val keyId: Bytes? = null,
        val videoCodecs: List<Int>,
        val audioCodecs: List<Int>? = null,
        val maxWidth: Int,
        val maxHeight: Int,
        val maxFps: Int,
        val requestPairing: Boolean? = null,
    ) : ControlMessage { override val msgType: Int get() = MsgType.HELLO }

    public data class Welcome(
        val version: Int,
        val receiverName: String,
        val sessionId: Bytes,
        val displayWidth: Int,
        val displayHeight: Int,
        val displayRefresh: Int,
        val videoDecoders: List<CodecSupport>,
        val audioDecoders: List<CodecSupport>,
        val attachKey: Bytes,
        val resumeToken: Bytes,
        val pairKey: Bytes? = null,
        val keyId: Bytes? = null,
        val audioEnabled: Boolean,
        /** The receiver turns [FrameFlags.ROTATED] frames back; written only when true. */
        val rotatedFrames: Boolean = false,
    ) : ControlMessage { override val msgType: Int get() = MsgType.WELCOME }

    public data class Reject(val reason: Int, val retryAfterMs: Long? = null) : ControlMessage {
        override val msgType: Int get() = MsgType.REJECT
    }

    public data class PendingApproval(val timeoutMs: Long) : ControlMessage {
        override val msgType: Int get() = MsgType.PENDING_APPROVAL
    }

    public data class StreamConfig(
        val streamKind: Int,
        val codec: Int,
        val width: Int? = null,
        val height: Int? = null,
        val fps: Int? = null,
        val bitrateBps: Long? = null,
        val sampleRate: Long? = null,
        val channels: Int? = null,
        val rotationDeg: Int? = null,
    ) : ControlMessage { override val msgType: Int get() = MsgType.STREAM_CONFIG }

    public data class KeyframeRequest(val reason: Int) : ControlMessage {
        override val msgType: Int get() = MsgType.KEYFRAME_REQUEST
    }

    public data class Ping(val pingId: Long, val t0Us: Long, val offsetUs: Long? = null) : ControlMessage {
        override val msgType: Int get() = MsgType.PING
    }

    public data class Pong(val pingId: Long, val t0Us: Long, val t1Us: Long, val t2Us: Long) : ControlMessage {
        override val msgType: Int get() = MsgType.PONG
    }

    public data class ReceiverReport(
        val intervalMs: Int,
        val framesReceived: Long,
        val framesRendered: Long,
        val framesDropped: Long,
        val bytesReceived: Long,
        val c2rAvgUs: Long,
        val c2rMinUs: Long,
        val c2rMaxUs: Long,
        val c2dAvgUs: Long,
        val decodeBacklog: Int,
        val audioLate: Int,
    ) : ControlMessage { override val msgType: Int get() = MsgType.RECEIVER_REPORT }

    public data class SenderStats(
        val bitrateBps: Long,
        val fpsX100: Int,
        val framesDropped: Long,
        val encoderName: String? = null,
    ) : ControlMessage { override val msgType: Int get() = MsgType.SENDER_STATS }

    public data object Pause : ControlMessage { override val msgType: Int get() = MsgType.PAUSE }

    public data object Unpause : ControlMessage { override val msgType: Int get() = MsgType.UNPAUSE }

    public data class Attach(val sessionId: Bytes, val streamKind: Int, val proof: Bytes) : ControlMessage {
        override val msgType: Int get() = MsgType.ATTACH
    }

    public data class AttachResult(val status: Int) : ControlMessage {
        override val msgType: Int get() = MsgType.ATTACH_RESULT
    }

    public data class Resume(val sessionId: Bytes, val proof: Bytes) : ControlMessage {
        override val msgType: Int get() = MsgType.RESUME
    }

    public data class Bye(val reason: Int) : ControlMessage { override val msgType: Int get() = MsgType.BYE }

    /** Tanınmayan ve CRITICAL olmayan mesaj: yok sayılmalı. */
    public data class Unknown(override val msgType: Int, val msgFlags: Int) : ControlMessage
}
