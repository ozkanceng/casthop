package io.github.ozkanceng.yansit.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.ozkanceng.yansit.R
import io.github.ozkanceng.yansit.sender.LatencyPattern

/**
 * Gecikme ölçüm ekranı (tools/latency): üst bantta her karede o anın CLOCK_MONOTONIC zamanı ikili kodlanır; tahta
 * bunu okuyup uçtan uca gecikmeyi gösterir. Ortadaki büyük saat "iki ekranı tek fotoğrafta çek" yöntemi içindir.
 * Sistem çubukları gizlenir ki desen yakalanan karenin en üstünde olsun.
 */
@Composable
fun LatencyScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var nowUs by remember { mutableLongStateOf(System.nanoTime() / 1000) }
    LaunchedEffect(Unit) {
        // Her ekran karesinde (vsync) güncellenir; damga çizim anının monotonik zamanıdır.
        while (true) withFrameNanos { nowUs = System.nanoTime() / 1000 }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF202020))) {
        Canvas(Modifier.fillMaxSize()) {
            val bits = LatencyPattern.encode(nowUs)
            val bandH = size.height * 0.1f
            val cellW = size.width / LatencyPattern.CELLS
            for (i in 0 until LatencyPattern.CELLS) {
                drawRect(
                    color = if (LatencyPattern.cell(bits, i)) Color.White else Color.Black,
                    topLeft = Offset(i * cellW, 0f),
                    size = Size(cellW + 1f, bandH),
                )
            }
            // Hareket: kodlayıcı her kareyi gerçekten yeni kare olarak kodlasın.
            val x = ((nowUs / 4000) % 1000) / 1000f * size.width
            drawRect(Color(0xFF4DA3FF), topLeft = Offset(x, size.height * 0.8f), size = Size(40f, size.height * 0.1f))
        }
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "%03d.%03d".format((nowUs / 1_000_000) % 1000, (nowUs / 1000) % 1000),
                color = Color.White,
                fontSize = 120.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.latency_hint),
                color = Color(0xFFDDDDDD),
                textAlign = TextAlign.Center,
                fontSize = 18.sp,
            )
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_back), color = Color.White) }
        }
    }
}
