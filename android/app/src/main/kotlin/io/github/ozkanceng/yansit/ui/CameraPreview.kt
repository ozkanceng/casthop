package io.github.ozkanceng.yansit.ui

import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** What the camera sends, shown to the teacher. Camera buffers arrive rotated for the natural screen orientation. */
@Composable
fun CameraPreview(size: Size, onSurface: (Surface?) -> Unit, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.aspectRatio(size.width.toFloat() / size.height),
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var surface: Surface? = null

                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        texture.setDefaultBufferSize(size.width, size.height)
                        setTransform(rotationFix(this@apply, width, height))
                        surface = Surface(texture).also(onSurface)
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        setTransform(rotationFix(this@apply, width, height))
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        onSurface(null)
                        surface?.release()
                        surface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
    )
}

private fun rotationFix(view: TextureView, width: Int, height: Int): Matrix {
    val matrix = Matrix()
    val rotation = view.display?.rotation ?: Surface.ROTATION_0
    if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
        val box = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val turned = RectF(0f, 0f, height.toFloat(), width.toFloat())
        turned.offset(box.centerX() - turned.centerX(), box.centerY() - turned.centerY())
        matrix.setRectToRect(box, turned, Matrix.ScaleToFit.FILL)
        val scale = maxOf(width.toFloat() / height, height.toFloat() / width)
        matrix.postScale(scale, scale, box.centerX(), box.centerY())
        matrix.postRotate(90f * (rotation - 2), box.centerX(), box.centerY())
    }
    return matrix
}
