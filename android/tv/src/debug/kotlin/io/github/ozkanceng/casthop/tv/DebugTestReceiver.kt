package io.github.ozkanceng.casthop.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.ozkanceng.yansit.receiver.PairingInfo
import io.github.ozkanceng.yansit.receiver.ReceiverObserver
import io.github.ozkanceng.yansit.receiver.SessionSummary
import java.io.File

/**
 * The PROTOCOL.md §11 test contract for a debug build. With the app open:
 *
 *     adb shell am broadcast -n io.github.ozkanceng.casthop.tv/.DebugTestReceiver
 *     adb exec-out run-as io.github.ozkanceng.casthop.tv cat files/test/pairing.json
 *
 * From then on `pairing.json` follows every change and `stats.jsonl` gains a line per finished session. Both stay in
 * app-private storage, reachable only through run-as, which itself works only on a debuggable build.
 */
internal class DebugTestReceiver : BroadcastReceiver() {
    private class Exporter(directory: File) : ReceiverObserver {
        private val pairing = File(directory, "pairing.json")
        private val stats = File(directory, "stats.jsonl")

        init {
            directory.mkdirs()
        }

        override fun onPairingChanged(info: PairingInfo) {
            val tmp = File(pairing.path + ".tmp")
            tmp.writeText(info.toJson())
            tmp.renameTo(pairing)
        }

        override fun onSessionFinished(summary: SessionSummary) {
            stats.appendText(summary.toJson() + "\n")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CastHopTvApp
        val active = exporter ?: Exporter(File(context.filesDir, "test")).also { exporter = it }
        app.controller.addObserver(active)
    }

    private companion object {
        var exporter: ReceiverObserver? = null
    }
}
