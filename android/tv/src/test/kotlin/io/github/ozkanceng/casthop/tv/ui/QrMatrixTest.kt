package io.github.ozkanceng.casthop.tv.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class QrMatrixTest {
    private val uri = "yansit://c?v=1&h=192.168.1.20,10.0.0.7&p=47800&t=AAECAwQFBgcICQoLDA0ODw&" +
        "f=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&n=Sinif%20Tahtasi"

    @Test
    fun `the matrix reads back as the same uri`() {
        val matrix = QrMatrix.encode(uri)
        val scale = 4
        val width = matrix.width * scale
        val height = matrix.height * scale
        val pixels = IntArray(width * height) { i ->
            if (matrix.get(i % width / scale, i / width / scale)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        val result = QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true))
        assertEquals(uri, result.text)
    }

    @Test
    fun `the code has the four module quiet zone`() {
        val matrix = QrMatrix.encode(uri)
        for (i in 0 until matrix.width) {
            for (edge in 0 until 4) {
                assertFalse(matrix.get(i, edge))
                assertFalse(matrix.get(edge, i))
            }
        }
    }
}
