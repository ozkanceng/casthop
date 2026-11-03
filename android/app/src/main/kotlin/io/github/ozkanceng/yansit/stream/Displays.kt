package io.github.ozkanceng.yansit.stream

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface

/** Ana ekranın şu anki yönelimdeki piksel boyutu (yansıtılacak kaynak). */
fun currentDisplaySize(context: Context): Pair<Int, Int> {
    val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    val mode = display.mode
    // physicalWidth/Height doğal yönelimdedir; 90/270° döndürülmüşse yer değiştirir.
    val rotated = display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270
    return if (rotated) mode.physicalHeight to mode.physicalWidth else mode.physicalWidth to mode.physicalHeight
}
