package io.github.ozkanceng.yansit.protocol

import io.github.ozkanceng.yansit.protocol.ControlMessage.Attach
import io.github.ozkanceng.yansit.protocol.ControlMessage.AttachResult
import io.github.ozkanceng.yansit.protocol.ControlMessage.Bye
import io.github.ozkanceng.yansit.protocol.ControlMessage.Challenge
import io.github.ozkanceng.yansit.protocol.ControlMessage.Hello
import io.github.ozkanceng.yansit.protocol.ControlMessage.KeyframeRequest
import io.github.ozkanceng.yansit.protocol.ControlMessage.Pause
import io.github.ozkanceng.yansit.protocol.ControlMessage.PendingApproval
import io.github.ozkanceng.yansit.protocol.ControlMessage.Ping
import io.github.ozkanceng.yansit.protocol.ControlMessage.Pong
import io.github.ozkanceng.yansit.protocol.ControlMessage.ReceiverReport
import io.github.ozkanceng.yansit.protocol.ControlMessage.Reject
import io.github.ozkanceng.yansit.protocol.ControlMessage.Resume
import io.github.ozkanceng.yansit.protocol.ControlMessage.SenderStats
import io.github.ozkanceng.yansit.protocol.ControlMessage.StreamConfig
import io.github.ozkanceng.yansit.protocol.ControlMessage.Unknown
import io.github.ozkanceng.yansit.protocol.ControlMessage.Unpause
import io.github.ozkanceng.yansit.protocol.ControlMessage.Welcome

/** Kontrol mesajlarını kanonik (artan etiket sıralı) TLV olarak kodlar/çözer. */
public object ControlCodec {
    public const val ID_SIZE: Int = 16
    public const val KEY_SIZE: Int = 32

    public fun encode(msg: ControlMessage): ByteArray {
        val w = TlvWriter(msg.msgType)
        when (msg) {
            is Challenge -> {
                w.u16(0x01, msg.minVersion); w.u16(0x02, msg.maxVersion); w.bytes(0x03, msg.serverNonce, ID_SIZE)
                msg.receiverName?.let { w.str(0x04, it) }
            }
            is Hello -> {
                w.u16(0x01, msg.version); w.str(0x02, msg.deviceName); w.bytes(0x03, msg.deviceId, ID_SIZE)
                w.str(0x04, msg.appVersion); w.u8(0x05, msg.authMethod); w.bytes(0x06, msg.clientNonce, ID_SIZE)
                w.bytes(0x07, msg.authProof, KEY_SIZE); msg.keyId?.let { w.bytes(0x08, it, ID_SIZE) }
                w.list(0x09, msg.videoCodecs); msg.audioCodecs?.let { w.list(0x0A, it) }
                w.u16(0x0B, msg.maxWidth); w.u16(0x0C, msg.maxHeight); w.u8(0x0D, msg.maxFps)
                msg.requestPairing?.let { w.bool(0x0E, it) }
            }
            is Welcome -> {
                w.u16(0x01, msg.version); w.str(0x02, msg.receiverName); w.bytes(0x03, msg.sessionId, ID_SIZE)
                w.u16(0x04, msg.displayWidth); w.u16(0x05, msg.displayHeight); w.u8(0x06, msg.displayRefresh)
                w.pairs(0x07, msg.videoDecoders); w.pairs(0x08, msg.audioDecoders)
                w.bytes(0x09, msg.attachKey, KEY_SIZE); w.bytes(0x0A, msg.resumeToken, KEY_SIZE)
                msg.pairKey?.let { w.bytes(0x0B, it, KEY_SIZE) }; msg.keyId?.let { w.bytes(0x0C, it, ID_SIZE) }
                w.bool(0x0D, msg.audioEnabled)
                if (msg.rotatedFrames) w.bool(0x0E, true)
            }
            is Reject -> { w.u8(0x01, msg.reason); msg.retryAfterMs?.let { w.u32(0x02, it) } }
            is PendingApproval -> w.u32(0x01, msg.timeoutMs)
            is StreamConfig -> {
                w.u8(0x01, msg.streamKind); w.u8(0x02, msg.codec)
                msg.width?.let { w.u16(0x03, it) }; msg.height?.let { w.u16(0x04, it) }
                msg.fps?.let { w.u8(0x05, it) }; msg.bitrateBps?.let { w.u32(0x06, it) }
                msg.sampleRate?.let { w.u32(0x07, it) }; msg.channels?.let { w.u8(0x08, it) }
                msg.rotationDeg?.let { w.u16(0x09, it) }
            }
            is KeyframeRequest -> w.u8(0x01, msg.reason)
            is Ping -> { w.u32(0x01, msg.pingId); w.u64(0x02, msg.t0Us); msg.offsetUs?.let { w.u64(0x03, it) } }
            is Pong -> { w.u32(0x01, msg.pingId); w.u64(0x02, msg.t0Us); w.u64(0x03, msg.t1Us); w.u64(0x04, msg.t2Us) }
            is ReceiverReport -> {
                w.u16(0x01, msg.intervalMs); w.u32(0x02, msg.framesReceived); w.u32(0x03, msg.framesRendered)
                w.u32(0x04, msg.framesDropped); w.u32(0x05, msg.bytesReceived); w.u32(0x06, msg.c2rAvgUs)
                w.u32(0x07, msg.c2rMinUs); w.u32(0x08, msg.c2rMaxUs); w.u32(0x09, msg.c2dAvgUs)
                w.u16(0x0A, msg.decodeBacklog); w.u16(0x0B, msg.audioLate)
            }
            is SenderStats -> {
                w.u32(0x01, msg.bitrateBps); w.u16(0x02, msg.fpsX100); w.u32(0x03, msg.framesDropped)
                msg.encoderName?.let { w.str(0x04, it) }
            }
            Pause, Unpause -> Unit
            is Attach -> { w.bytes(0x01, msg.sessionId, ID_SIZE); w.u8(0x02, msg.streamKind); w.bytes(0x03, msg.proof, KEY_SIZE) }
            is AttachResult -> w.u8(0x01, msg.status)
            is Resume -> { w.bytes(0x01, msg.sessionId, ID_SIZE); w.bytes(0x02, msg.proof, KEY_SIZE) }
            is Bye -> w.u8(0x01, msg.reason)
            is Unknown -> throw IllegalArgumentException("Unknown mesaj kodlanamaz")
        }
        return w.toByteArray()
    }

    /** @throws ProtocolException geçersiz yükte. */
    public fun decode(payload: ByteArray): ControlMessage {
        val r = TlvReader.parse(payload)
        return when (r.msgType) {
            MsgType.CHALLENGE -> Challenge(r.u16(0x01), r.u16(0x02), r.bytes(0x03, ID_SIZE), r.strOrNull(0x04))
            MsgType.HELLO -> Hello(
                version = r.u16(0x01), deviceName = r.str(0x02), deviceId = r.bytes(0x03, ID_SIZE),
                appVersion = r.str(0x04), authMethod = r.u8(0x05), clientNonce = r.bytes(0x06, ID_SIZE),
                authProof = r.bytes(0x07, KEY_SIZE), keyId = r.bytesOrNull(0x08, ID_SIZE),
                videoCodecs = r.list(0x09), audioCodecs = r.listOrNull(0x0A),
                maxWidth = r.u16(0x0B), maxHeight = r.u16(0x0C), maxFps = r.u8(0x0D), requestPairing = r.boolOrNull(0x0E),
            )
            MsgType.WELCOME -> Welcome(
                version = r.u16(0x01), receiverName = r.str(0x02), sessionId = r.bytes(0x03, ID_SIZE),
                displayWidth = r.u16(0x04), displayHeight = r.u16(0x05), displayRefresh = r.u8(0x06),
                videoDecoders = r.pairs(0x07), audioDecoders = r.pairs(0x08),
                attachKey = r.bytes(0x09, KEY_SIZE), resumeToken = r.bytes(0x0A, KEY_SIZE),
                pairKey = r.bytesOrNull(0x0B, KEY_SIZE), keyId = r.bytesOrNull(0x0C, ID_SIZE), audioEnabled = r.bool(0x0D),
                rotatedFrames = r.boolOrNull(0x0E) ?: false,
            )
            MsgType.REJECT -> Reject(r.u8(0x01), r.u32OrNull(0x02))
            MsgType.PENDING_APPROVAL -> PendingApproval(r.u32(0x01))
            MsgType.STREAM_CONFIG -> StreamConfig(
                streamKind = r.u8(0x01), codec = r.u8(0x02), width = r.u16OrNull(0x03), height = r.u16OrNull(0x04),
                fps = r.u8OrNull(0x05), bitrateBps = r.u32OrNull(0x06), sampleRate = r.u32OrNull(0x07),
                channels = r.u8OrNull(0x08), rotationDeg = r.u16OrNull(0x09),
            )
            MsgType.KEYFRAME_REQUEST -> KeyframeRequest(r.u8(0x01))
            MsgType.PING -> Ping(r.u32(0x01), r.u64(0x02), r.u64OrNull(0x03))
            MsgType.PONG -> Pong(r.u32(0x01), r.u64(0x02), r.u64(0x03), r.u64(0x04))
            MsgType.RECEIVER_REPORT -> ReceiverReport(
                intervalMs = r.u16(0x01), framesReceived = r.u32(0x02), framesRendered = r.u32(0x03),
                framesDropped = r.u32(0x04), bytesReceived = r.u32(0x05), c2rAvgUs = r.u32(0x06),
                c2rMinUs = r.u32(0x07), c2rMaxUs = r.u32(0x08), c2dAvgUs = r.u32(0x09),
                decodeBacklog = r.u16(0x0A), audioLate = r.u16(0x0B),
            )
            MsgType.SENDER_STATS -> SenderStats(r.u32(0x01), r.u16(0x02), r.u32(0x03), r.strOrNull(0x04))
            MsgType.PAUSE -> Pause
            MsgType.UNPAUSE -> Unpause
            MsgType.ATTACH -> Attach(r.bytes(0x01, ID_SIZE), r.u8(0x02), r.bytes(0x03, KEY_SIZE))
            MsgType.ATTACH_RESULT -> AttachResult(r.u8(0x01))
            MsgType.RESUME -> Resume(r.bytes(0x01, ID_SIZE), r.bytes(0x02, KEY_SIZE))
            MsgType.BYE -> Bye(r.u8(0x01))
            else -> {
                if (r.msgFlags and MsgType.FLAG_CRITICAL != 0) {
                    throw ProtocolException(ProtocolError.UNKNOWN_CRITICAL_MESSAGE, "0x%02x".format(r.msgType))
                }
                Unknown(r.msgType, r.msgFlags)
            }
        }
    }
}
