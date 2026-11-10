package io.github.ozkanceng.yansit.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File

/** protocol/vectors/vectors.json yükleyicisi (ortak test vektörleri). */
object Vectors {
    val root: JsonObject by lazy {
        val dir = System.getProperty("yansit.vectors") ?: error("yansit.vectors sistem özelliği yok")
        Json.parseToJsonElement(File(dir, "vectors.json").readText()).jsonObject
    }

    fun section(name: String): List<JsonObject> = (root[name] as JsonArray).map { it.jsonObject }

    /** JSON değerini karşılaştırılabilir Kotlin değerine çevirir: sayı→Long, dizi→List, nesne→Map. */
    fun normalize(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            else -> e.longOrNull ?: error("sayı Long'a sığmıyor: $e")
        }
        is JsonArray -> e.map { normalize(it) }
        is JsonObject -> e.mapValues { normalize(it.value) }
    }

    fun hex(s: String): ByteArray = Bytes.fromHex(s).toByteArray()
}

/** Mesajı vektörlerdeki alan adlarıyla (snake_case) bir haritaya çevirir. Null alanlar dahil edilmez. */
fun ControlMessage.toFields(): Map<String, Any?> {
    fun b(x: Bytes?) = x?.toHex()
    fun pairs(v: List<CodecSupport>) = v.map { listOf(it.codec.toLong(), it.flags.toLong()) }
    fun list(v: List<Int>?) = v?.map { it.toLong() }
    val m: Map<String, Any?> = when (this) {
        is ControlMessage.Challenge -> mapOf(
            "min_version" to minVersion.toLong(), "max_version" to maxVersion.toLong(),
            "server_nonce" to b(serverNonce), "receiver_name" to receiverName,
        )
        is ControlMessage.Hello -> mapOf(
            "version" to version.toLong(), "device_name" to deviceName, "device_id" to b(deviceId),
            "app_version" to appVersion, "auth_method" to authMethod.toLong(), "client_nonce" to b(clientNonce),
            "auth_proof" to b(authProof), "key_id" to b(keyId), "video_codecs" to list(videoCodecs),
            "audio_codecs" to list(audioCodecs), "max_width" to maxWidth.toLong(), "max_height" to maxHeight.toLong(),
            "max_fps" to maxFps.toLong(), "request_pairing" to requestPairing,
        )
        is ControlMessage.Welcome -> mapOf(
            "version" to version.toLong(), "receiver_name" to receiverName, "session_id" to b(sessionId),
            "display_width" to displayWidth.toLong(), "display_height" to displayHeight.toLong(),
            "display_refresh" to displayRefresh.toLong(), "video_decoders" to pairs(videoDecoders),
            "audio_decoders" to pairs(audioDecoders), "attach_key" to b(attachKey), "resume_token" to b(resumeToken),
            "pair_key" to b(pairKey), "key_id" to b(keyId), "audio_enabled" to audioEnabled,
            "rotated_frames" to rotatedFrames.takeIf { it },
        )
        is ControlMessage.Reject -> mapOf("reason" to reason.toLong(), "retry_after_ms" to retryAfterMs)
        is ControlMessage.PendingApproval -> mapOf("timeout_ms" to timeoutMs)
        is ControlMessage.StreamConfig -> mapOf(
            "stream_kind" to streamKind.toLong(), "codec" to codec.toLong(), "width" to width?.toLong(),
            "height" to height?.toLong(), "fps" to fps?.toLong(), "bitrate_bps" to bitrateBps,
            "sample_rate" to sampleRate, "channels" to channels?.toLong(), "rotation_deg" to rotationDeg?.toLong(),
        )
        is ControlMessage.KeyframeRequest -> mapOf("reason" to reason.toLong())
        is ControlMessage.Ping -> mapOf("ping_id" to pingId, "t0_us" to t0Us, "offset_us" to offsetUs)
        is ControlMessage.Pong -> mapOf("ping_id" to pingId, "t0_us" to t0Us, "t1_us" to t1Us, "t2_us" to t2Us)
        is ControlMessage.ReceiverReport -> mapOf(
            "interval_ms" to intervalMs.toLong(), "frames_received" to framesReceived,
            "frames_rendered" to framesRendered, "frames_dropped" to framesDropped, "bytes_received" to bytesReceived,
            "c2r_avg_us" to c2rAvgUs, "c2r_min_us" to c2rMinUs, "c2r_max_us" to c2rMaxUs, "c2d_avg_us" to c2dAvgUs,
            "decode_backlog" to decodeBacklog.toLong(), "audio_late" to audioLate.toLong(),
        )
        is ControlMessage.SenderStats -> mapOf(
            "bitrate_bps" to bitrateBps, "fps_x100" to fpsX100.toLong(), "frames_dropped" to framesDropped,
            "encoder_name" to encoderName,
        )
        ControlMessage.Pause, ControlMessage.Unpause -> emptyMap()
        is ControlMessage.Attach -> mapOf("session_id" to b(sessionId), "stream_kind" to streamKind.toLong(), "proof" to b(proof))
        is ControlMessage.AttachResult -> mapOf("status" to status.toLong())
        is ControlMessage.Resume -> mapOf("session_id" to b(sessionId), "proof" to b(proof))
        is ControlMessage.Bye -> mapOf("reason" to reason.toLong())
        is ControlMessage.Unknown -> mapOf("msg_type" to msgType.toLong())
    }
    return m.filterValues { it != null }
}

fun ControlMessage.vectorName(): String = when (this) {
    is ControlMessage.Challenge -> "CHALLENGE"
    is ControlMessage.Hello -> "HELLO"
    is ControlMessage.Welcome -> "WELCOME"
    is ControlMessage.Reject -> "REJECT"
    is ControlMessage.PendingApproval -> "PENDING_APPROVAL"
    is ControlMessage.StreamConfig -> "STREAM_CONFIG"
    is ControlMessage.KeyframeRequest -> "KEYFRAME_REQUEST"
    is ControlMessage.Ping -> "PING"
    is ControlMessage.Pong -> "PONG"
    is ControlMessage.ReceiverReport -> "RECEIVER_REPORT"
    is ControlMessage.SenderStats -> "SENDER_STATS"
    ControlMessage.Pause -> "PAUSE"
    ControlMessage.Unpause -> "UNPAUSE"
    is ControlMessage.Attach -> "ATTACH"
    is ControlMessage.AttachResult -> "ATTACH_RESULT"
    is ControlMessage.Resume -> "RESUME"
    is ControlMessage.Bye -> "BYE"
    is ControlMessage.Unknown -> "UNKNOWN"
}
