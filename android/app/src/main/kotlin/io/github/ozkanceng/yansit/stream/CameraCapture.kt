package io.github.ozkanceng.yansit.stream

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

private const val TAG = "YansitCamera"

/**
 * Sends a camera to the encoder surface, and to an optional on-screen preview. Camera2 is used directly because
 * it gives both outputs in one session; the encoder surface has no fixed size until the codec is configured,
 * so [start] must run after the encoder has started.
 */
class CameraCapture(
    context: Context,
    private val encoderSurface: Surface,
    val size: Size,
    private val fps: Int,
    private val onFailed: (Throwable?) -> Unit,
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("mirror-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var preview: Surface? = null
    private var facing = CameraCharacteristics.LENS_FACING_BACK
    private var paused = false
    private var released = false

    val canSwitch: Boolean
        get() = lensFacings(manager).size > 1

    fun start() = handler.post { open() }.let { }

    /** The preview surface comes and goes with the screen; the stream keeps running without it. */
    fun setPreview(surface: Surface?) = handler.post {
        if (preview != surface) {
            preview = surface
            configure()
        }
    }.let { }

    fun switchCamera() = handler.post {
        val other = lensFacings(manager).firstOrNull { it != facing } ?: return@post
        facing = other
        closeDevice()
        open()
    }.let { }

    fun pause() = handler.post {
        paused = true
        runCatching { session?.stopRepeating() }.onFailure { Log.w(TAG, "stopRepeating", it) }
    }.let { }

    fun resume() = handler.post {
        paused = false
        session?.let(::repeat)
    }.let { }

    fun release() {
        handler.post {
            released = true
            closeDevice()
            thread.quitSafely()
        }
    }

    @SuppressLint("MissingPermission") // MirrorController checks CAMERA before creating this class
    private fun open() {
        if (released) return
        val id = cameraWith(manager, facing)
        if (id == null) {
            onFailed(null)
            return
        }
        try {
            manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    configure()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) {
                        device = null
                        onFailed(null)
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "camera error $error")
                    camera.close()
                    if (device === camera) device = null
                    onFailed(null)
                }
            })
        } catch (e: CameraAccessException) {
            onFailed(e)
        } catch (e: SecurityException) {
            onFailed(e)
        }
    }

    private fun configure() {
        val camera = device ?: return
        val outputs = listOfNotNull(encoderSurface, preview).map(::OutputConfiguration)
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (device !== camera) return
                session = s
                if (!paused) repeat(s)
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                Log.e(TAG, "session configuration failed")
                onFailed(null)
            }
        }
        try {
            camera.createCaptureSession(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback),
            )
        } catch (e: CameraAccessException) {
            onFailed(e)
        } catch (e: IllegalArgumentException) {
            // A preview surface can be destroyed between the callback and this call.
            Log.w(TAG, "createCaptureSession", e)
            if (preview != null) {
                preview = null
                configure()
            } else {
                onFailed(e)
            }
        }
    }

    private fun repeat(s: CameraCaptureSession) {
        val camera = device ?: return
        try {
            val chars = manager.getCameraCharacteristics(camera.id)
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(encoderSurface)
                preview?.let(::addTarget)
                fpsRange(chars)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                val af = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                if (af?.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true) {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
            }
            s.setRepeatingRequest(request.build(), null, handler)
        } catch (e: CameraAccessException) {
            onFailed(e)
        } catch (e: IllegalStateException) {
            // The session was replaced while this request was queued.
            Log.w(TAG, "setRepeatingRequest", e)
        }
    }

    private fun fpsRange(chars: CameraCharacteristics): Range<Int>? =
        chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper <= fps }
            ?.maxWithOrNull(compareBy({ it.upper }, { it.lower }))

    private fun closeDevice() {
        session = null
        device?.close()
        device = null
    }

    companion object {
        /** Largest camera output no bigger than the plan that the encoder also accepts. */
        fun pickSize(context: Context, maxWidth: Int, maxHeight: Int, accepts: (Size) -> Boolean): Size? {
            val manager = context.getSystemService(CameraManager::class.java)
            val id = cameraWith(manager, CameraCharacteristics.LENS_FACING_BACK)
                ?: cameraWith(manager, CameraCharacteristics.LENS_FACING_FRONT) ?: return null
            val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return null
            val usable = map.getOutputSizes(MediaCodec::class.java).orEmpty()
                .filter { it.width <= maxWidth && it.height <= maxHeight && accepts(it) }
            // A wide picture fills boards and TVs; other shapes are the fallback.
            val wide = usable.filter { it.width * 9 == it.height * 16 }
            return (wide.ifEmpty { usable }).maxByOrNull { it.width * it.height }
        }

        private fun lensFacings(manager: CameraManager): List<Int> = manager.cameraIdList.mapNotNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)
        }.filter { it != CameraCharacteristics.LENS_FACING_EXTERNAL }.distinct()

        private fun cameraWith(manager: CameraManager, facing: Int): String? = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
        }
    }
}
