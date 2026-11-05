package io.github.ozkanceng.yansit.tools.fakereceiver

import io.github.ozkanceng.yansit.protocol.Auth
import io.github.ozkanceng.yansit.protocol.Codec
import io.github.ozkanceng.yansit.protocol.CodecSupport
import io.github.ozkanceng.yansit.protocol.client.MonotonicClock
import io.github.ozkanceng.yansit.receiver.AudioBackend
import io.github.ozkanceng.yansit.receiver.AudioPipeline
import io.github.ozkanceng.yansit.receiver.FileTrustedStore
import io.github.ozkanceng.yansit.receiver.JsseServerTransport
import io.github.ozkanceng.yansit.receiver.PairingInfo
import io.github.ozkanceng.yansit.receiver.ReceiverEngine
import io.github.ozkanceng.yansit.receiver.ReceiverObserver
import io.github.ozkanceng.yansit.receiver.SessionSummary
import io.github.ozkanceng.yansit.receiver.VideoBackend
import io.github.ozkanceng.yansit.receiver.VideoPipeline
import java.io.File
import java.io.IOException
import java.net.BindException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

private const val USAGE = """fake-receiver: the Android TV receiver's core on the JVM, for tools/conformance

  --headless              accepted for the PROTOCOL.md §11 contract; this tool has no screen anyway
  --port N                TCP port (default 47800)
  --data-dir D            test certificate and pairing keys (default ./fake-receiver-data)
  --pairing-file F        §11 JSON: uri, pin, port, fingerprint, token; rewritten when any of them changes
  --stats-file F          §11: one JSON line per finished session
  --approval-timeout MS   takeover approval timeout, 1000-120000 (default 20000)
  --qr-host IP            address to put in the QR; repeat for several (default 127.0.0.1)
  --board-name S          receiver name (default "Fake Receiver")

There is no decoder or screen here: frames are counted as rendered when they arrive and audio packets as played
when they are due, so frames_rendered says nothing about pictures. It only proves the protocol and session logic.
The certificate comes from keytool and is for tests only. A real receiver keeps its key in hardware or OS storage."""

private class Options(
    val port: Int,
    val dataDir: File,
    val pairingFile: File?,
    val statsFile: File?,
    val approvalTimeoutMs: Long,
    val qrHosts: List<String>,
    val name: String,
)

private fun usage(message: String): Nothing {
    System.err.println("error: $message\n\n$USAGE")
    exitProcess(2)
}

private fun parseArgs(args: Array<String>): Options {
    val values = HashMap<String, String>()
    val hosts = ArrayList<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i++]
        when (a) {
            "--help", "-h" -> {
                println(USAGE)
                exitProcess(0)
            }
            "--headless" -> Unit
            "--port", "--data-dir", "--pairing-file", "--stats-file", "--approval-timeout", "--board-name" -> {
                if (i >= args.size) usage("$a needs a value")
                values[a] = args[i++]
            }
            "--qr-host" -> {
                if (i >= args.size) usage("$a needs a value")
                hosts += args[i++]
            }
            else -> usage("unknown option: $a")
        }
    }
    val port = values["--port"]?.let { it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: usage("--port: bad value") }
    val approval = values["--approval-timeout"]?.let {
        it.toLongOrNull()?.takeIf { ms -> ms in 1000..120_000 } ?: usage("--approval-timeout: 1000-120000 ms")
    }
    return Options(
        port = port ?: 47800,
        dataDir = File(values["--data-dir"] ?: "fake-receiver-data"),
        pairingFile = values["--pairing-file"]?.let(::File),
        statsFile = values["--stats-file"]?.let(::File),
        approvalTimeoutMs = approval ?: 20_000,
        qrHosts = hosts.ifEmpty { listOf("127.0.0.1") },
        name = values["--board-name"] ?: "Fake Receiver",
    )
}

/** Stands in for the decoder and the screen: a frame is rendered the moment it arrives. */
private class CountingVideoBackend : VideoBackend {
    @Volatile
    var pipeline: VideoPipeline? = null

    override val decoderName: String = "fake-receiver"
    override val hardware: Boolean = false

    override fun configure(codec: Int, config: ByteArray): Boolean = true

    override fun submit(accessUnit: ByteArray, timestampUs: Long, keyframe: Boolean) {
        pipeline?.onRendered(timestampUs, MonotonicClock.nowUs())
    }

    override fun reset() = Unit
}

/** Stands in for the speaker: a packet counts as played when it is handed over, at its planned latency. */
private class CountingAudioBackend : AudioBackend {
    @Volatile
    var pipeline: AudioPipeline? = null

    override fun configure(codec: Int, config: ByteArray): Boolean = codec == Codec.OPUS || codec == Codec.AAC_LC

    override fun submit(packet: ByteArray, playAtUs: Long) {
        val p = pipeline ?: return
        p.onPlayed(p.targetLatencyUs)
    }

    override fun reset() = Unit
}

private class FileObserver(private val pairingFile: File?, private val statsFile: File?) : ReceiverObserver {
    override fun onPairingChanged(info: PairingInfo) {
        val target = pairingFile ?: return
        val tmp = File(target.path + ".tmp")
        // A reader must never see a half-written file, and the token is a secret.
        tmp.delete()
        tmp.createNewFile()
        tmp.setReadable(false, false)
        tmp.setReadable(true, true)
        tmp.writeText(info.toJson())
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun onSessionFinished(summary: SessionSummary) {
        statsFile?.appendText(summary.toJson() + "\n")
    }
}

fun main(args: Array<String>) {
    val o = parseArgs(args)
    val identity = try {
        TestIdentity.loadOrCreate(o.dataDir)
    } catch (e: IOException) {
        System.err.println("error: test certificate: ${e.message}")
        exitProcess(1)
    }
    val transport = try {
        JsseServerTransport(identity.context, o.port)
    } catch (_: BindException) {
        System.err.println("error: port ${o.port} is in use")
        exitProcess(1)
    }

    val video = CountingVideoBackend()
    val audio = CountingAudioBackend()
    val engine = ReceiverEngine(
        ReceiverEngine.Options(
            name = o.name,
            videoDecoders = listOf(CodecSupport(Codec.H264, CodecSupport.FLAG_PREFERRED)),
            audioDecoders = listOf(CodecSupport(Codec.OPUS, CodecSupport.FLAG_PREFERRED), CodecSupport(Codec.AAC_LC, 0)),
            approvalTimeoutMs = o.approvalTimeoutMs,
            hosts = { o.qrHosts },
        ),
        transport,
        Auth.sha256(identity.certificateDer),
        video,
        audio,
        FileTrustedStore(File(o.dataDir, "trusted")),
        FileObserver(o.pairingFile, o.statsFile),
    )
    video.pipeline = engine.videoPipeline
    audio.pipeline = engine.audioPipeline

    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            engine.shutdown()
            stopped.countDown()
        },
    )
    engine.start()
    println("listening on port ${transport.port}")
    stopped.await()
}
