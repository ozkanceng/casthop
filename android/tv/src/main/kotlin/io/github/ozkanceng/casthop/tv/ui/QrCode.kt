package io.github.ozkanceng.casthop.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal object QrMatrix {
    private const val QUIET_ZONE_MODULES = 4

    /** PROTOCOL.md §8: error correction level M, with the standard quiet zone. One matrix cell per module. */
    fun encode(text: String): BitMatrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to QUIET_ZONE_MODULES),
    )
}

/** Sharp at any size: the bitmap has one pixel per module and is scaled up without smoothing. */
@Composable
internal fun QrCode(content: String, description: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) {
        val matrix = QrMatrix.encode(content)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix.get(i % matrix.width, i / matrix.width)) DARK_MODULE else LIGHT_MODULE
        }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = description,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
    )
}

private const val DARK_MODULE = 0xFF000000.toInt()
private const val LIGHT_MODULE = 0xFFFFFFFF.toInt()
