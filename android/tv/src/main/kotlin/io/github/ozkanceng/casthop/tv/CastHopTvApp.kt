package io.github.ozkanceng.casthop.tv

import android.app.Application

/** Keeps the controller alive across activity instances. */
internal class CastHopTvApp : Application() {
    val controller: ReceiverController by lazy { ReceiverController(this) }
}
