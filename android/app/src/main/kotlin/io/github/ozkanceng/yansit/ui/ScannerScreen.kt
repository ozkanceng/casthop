package io.github.ozkanceng.yansit.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ozkanceng.yansit.R
import io.github.ozkanceng.yansit.protocol.QrException
import io.github.ozkanceng.yansit.protocol.QrPayload
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import zxingcpp.BarcodeReader

/** Tahtadaki QR kodunu okur (CameraX + zxing-cpp; Google Play Hizmetleri gerekmez). */
@Composable
fun ScannerScreen(onResult: (QrPayload) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hint by remember { mutableStateOf<Int?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }

    BackHandler(onBack = onBack)

    DisposableEffect(Unit) {
        onDispose {
            if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val view = PreviewView(ctx)
                val reader = BarcodeReader(BarcodeReader.Options(formats = setOf(BarcodeReader.Format.QR_CODE)))
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { a ->
                        a.setAnalyzer(executor) { image ->
                            val text = image.use { runCatching { reader.read(it) }.getOrDefault(emptyList()) }
                                .firstNotNullOfOrNull { it.text }
                                ?: return@setAnalyzer
                            val payload = try {
                                QrPayload.parse(text)
                            } catch (e: QrException) {
                                ContextCompat.getMainExecutor(ctx).execute { hint = R.string.scan_not_yansit }
                                return@setAnalyzer
                            }
                            if (done.compareAndSet(false, true)) {
                                ContextCompat.getMainExecutor(ctx).execute { onResult(payload) }
                            }
                        }
                    }
                providerFuture.addListener({
                    try {
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        val selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        } else {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                    } catch (e: Exception) {
                        Log.e("YansitScanner", "kamera açılamadı", e)
                        hint = R.string.scan_camera_error
                    }
                }, ContextCompat.getMainExecutor(ctx))
                view
            },
        )
        ScanFrame(Modifier.align(Alignment.Center).fillMaxSize(0.6f).aspectRatio(1f))
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(hint ?: R.string.scan_hint),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.action_cancel), color = Color.White)
            }
        }
    }
}

/** QR kodunun nereye tutulacağını gösteren köşe işaretleri (süsleyici). */
@Composable
private fun ScanFrame(modifier: Modifier) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val arm = size.minDimension * 0.18f
        val width = 6.dp.toPx()
        val corners = listOf(
            Offset(0f, 0f) to Offset(1f, 1f),
            Offset(size.width, 0f) to Offset(-1f, 1f),
            Offset(0f, size.height) to Offset(1f, -1f),
            Offset(size.width, size.height) to Offset(-1f, -1f),
        )
        for ((corner, direction) in corners) {
            drawLine(Color.White, corner, corner + Offset(arm * direction.x, 0f), width, StrokeCap.Round)
            drawLine(Color.White, corner, corner + Offset(0f, arm * direction.y), width, StrokeCap.Round)
        }
    }
}
