package io.github.ozkanceng.yansit.stream

import android.graphics.SurfaceTexture
import android.hardware.display.VirtualDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Surface
import io.github.ozkanceng.yansit.protocol.FrameFlags
import io.github.ozkanceng.yansit.sender.EncodedFrame
import io.github.ozkanceng.yansit.sender.StreamPlan
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

private const val TAG = "YansitScreen"

/**
 * Screen capture through OpenGL (ARCHITECTURE.md §2.1): VirtualDisplay → SurfaceTexture → GL → encoder input.
 *
 * The encoder only ever sees frames GL drew at exactly its own size, so no frame of another size can reach it
 * (the exynos_mfc kernel panic on a quality step down). Turning the tablet only resizes the VirtualDisplay and
 * moves the picture inside the same frame, black around it: the encoder keeps running, no new IDR.
 * GL also paces frames to the plan's rate; the encoder does not drop any itself. It holds a picture while the
 * network cannot take one ([setFrameGate]) and repeats the last one on a still screen ([IDLE_REDRAW_MS]).
 *
 * With [canRotate] (the receiver turns ROTATED frames back) a screen turned the other way than the frame is drawn
 * turned 90° and fills it ([FrameFit.layout]); each such frame leaves the encoder with FrameFlags.ROTATED.
 *
 * All GL and SurfaceTexture work happens on one thread; public calls wait for it.
 *
 * @param display created once per session, kept; this object attaches its surface and resizes it
 * @param sourceWidth the screen's size in its current orientation
 * @param maxFps the highest rate quality steps can reach; the encoder is set up for it (operating rate), GL lets
 *   through only the plan's rate
 * @param canRotate the receiver's WELCOME has `rotated_frames`
 */
internal class ScreenSource(
    caps: AndroidEncoderCaps,
    private val display: VirtualDisplay,
    private val densityDpi: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    private val maxFps: Int,
    private val canRotate: Boolean = false,
) : CaptureSource {
    private val encoder = SurfaceEncoder(caps)
    private val thread = HandlerThread("mirror-gl", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val gl = Handler(thread.looper)

    // --- GL thread only ---
    private lateinit var egl: EglCore
    private lateinit var drawer: TextureDrawer
    private var sourceW = sourceWidth
    private var sourceH = sourceHeight
    private var texture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var displaySurface: Surface? = null
    private var displayW = 0
    private var displayH = 0
    private var window: EGLSurface? = null
    private var frameW = 0
    private var frameH = 0
    private var rect: FrameRect? = null
    private var rotated = false
    private val orientation = OrientationTimeline()
    private var framesEnabled = true
    /** The texture holds a frame of the current SurfaceTexture. */
    private var hasFrame = false
    /** The latched frame has not been drawn yet. */
    private var pending = false
    private var frameTsNs = 0L
    private val texMatrix = FloatArray(16)
    private var intervalNs = 0L
    private var nextDueNs = 0L
    private var lastPtsNs = 0L
    private var lastDrawNs = 0L
    /** A picture passed pacing but the gate was closed; it goes out once the gate opens. */
    private var held = false
    /** The held picture is a redraw: it gets the time it is drawn, not its capture time. */
    private var heldRedraw = false
    private var swapFailed = false
    private var released = false
    @Volatile private var gate: () -> Boolean = { true }
    private val catchUp = Runnable { if (pending) drawPaced(force = true) }
    private val gateRetry = Runnable { if (held) submit(redraw = false) }
    private val idleRedraw = Runnable { drawNow() }

    init {
        try {
            onGl {
                egl = EglCore()
                drawer = TextureDrawer()
            }
        } catch (e: Exception) {
            encoder.release()
            thread.quitSafely()
            throw e
        }
    }

    override fun start(plan: StreamPlan, sink: (EncodedFrame) -> Unit, onError: (Throwable) -> Unit) = onGl {
        check(!released) { "released" }
        stopOnGl()
        val encoderPlan = plan.copy(fps = max(plan.fps, maxFps))
        orientation.clear()
        val input = encoder.start(
            encoderPlan, sink, onError, persistentInput = null, pacedBySource = true,
            extraFlags = { pts -> if (orientation.rotatedAt(pts)) FrameFlags.ROTATED else 0 },
        )
        window = try {
            egl.createWindowSurface(input)
        } catch (e: RuntimeException) {
            encoder.stop()
            throw e
        }
        swapFailed = false
        frameW = plan.width
        frameH = plan.height
        intervalNs = 1_000_000_000L / plan.fps
        applyGeometry()
        // A static screen sends nothing new: give the new encoder the picture it already has.
        if (hasFrame && framesEnabled) drawNow()
    }

    /** The screen turned (or its size changed): only the VirtualDisplay follows, the encoder keeps going. */
    fun setSourceSize(width: Int, height: Int) = onGl {
        if (released || (width == sourceW && height == sourceH)) return@onGl
        Log.i(TAG, "kaynak: ${sourceW}x$sourceH → ${width}x$height")
        sourceW = width
        sourceH = height
        applyGeometry()
    }

    override fun setFramesEnabled(on: Boolean) = onGl {
        if (released || on == framesEnabled) return@onGl
        framesEnabled = on
        display.surface = if (on) displaySurface else null
        if (!on) {
            cancelDraws()
        } else if (hasFrame) {
            drawNow() // the first frame after a pause must come now: it carries the IDR
        }
    }

    /** Quality step: GL lets fewer (or more) frames through; the encoder and its size stay. */
    override fun setFrameRate(fps: Int) = onGl {
        if (fps <= 0) return@onGl
        val ns = 1_000_000_000L / fps
        if (ns != intervalNs) Log.i(TAG, "kare hızı: $fps")
        intervalNs = ns
    }

    override fun setFrameGate(ready: () -> Boolean) {
        gate = ready
    }

    /**
     * The encoder makes its next frame an IDR; on a still screen GL draws that frame now instead of at the next
     * idle redraw. Does not wait for the GL thread (the send loop calls this).
     */
    override fun requestKeyframe() {
        encoder.requestKeyframe()
        gl.post {
            if (!released && System.nanoTime() - lastDrawNs > intervalNs) drawNow()
        }
    }

    override fun setBitrate(bps: Long) = encoder.setBitrate(bps)

    override fun stop() = onGl { stopOnGl() }

    /** Call before releasing the display. */
    override fun release() {
        try {
            onGl {
                released = true
                stopOnGl()
                display.surface = null
                releaseInput()
                drawer.release()
                egl.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "release", e)
        } finally {
            encoder.release()
            thread.quitSafely()
        }
    }

    // --- GL thread ---

    private fun stopOnGl() {
        cancelDraws()
        window?.let { egl.destroySurface(it) }
        window = null
        encoder.stop()
    }

    /** Places the picture in the frame and sizes the VirtualDisplay to it, so the display is never scaled twice. */
    private fun applyGeometry() {
        if (frameW == 0) return
        val layout = FrameFit.layout(frameW, frameH, sourceW, sourceH, canRotate)
        val r = layout.rect
        val w = layout.sourceWidth
        val h = layout.sourceHeight
        val sameInput = w == displayW && h == displayH
        // A new orientation waits for a picture of the new shape; the old texture is never drawn turned.
        if (sameInput && layout.rotated == rotated) {
            rect = r
            return
        }
        rect = r
        rotated = layout.rotated
        display.surface = null
        releaseInput()
        display.resize(w, h, densityDpi)
        displayW = w
        displayH = h
        texture = drawer.createTexture()
        val st = SurfaceTexture(texture).apply {
            setDefaultBufferSize(w, h)
            setOnFrameAvailableListener(::onFrameAvailable, gl)
        }
        surfaceTexture = st
        displaySurface = Surface(st)
        if (framesEnabled) display.surface = displaySurface
        Log.i(TAG, "görüntü ${w}x$h${if (rotated) " (çevrili)" else ""} @ ${r.x},${r.y} ${r.width}x${r.height} / ${frameW}x$frameH")
    }

    private fun releaseInput() {
        cancelDraws()
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
        displaySurface?.release()
        displaySurface = null
        if (texture != 0) drawer.deleteTexture(texture)
        texture = 0
        hasFrame = false
        pending = false
    }

    private fun onFrameAvailable(st: SurfaceTexture) {
        if (st !== surfaceTexture || released) return
        try {
            st.updateTexImage()
        } catch (e: RuntimeException) {
            Log.w(TAG, "updateTexImage", e)
            return
        }
        st.getTransformMatrix(texMatrix)
        frameTsNs = st.timestamp.takeIf { it > 0 } ?: System.nanoTime()
        hasFrame = true
        pending = true
        drawPaced(force = false)
    }

    /**
     * At most one frame per interval, measured on capture times; a frame that comes early waits (catch-up) in case
     * nothing newer follows, so the board always ends on the screen's last picture.
     */
    private fun drawPaced(force: Boolean) {
        gl.removeCallbacks(catchUp)
        if (window == null || !framesEnabled) return
        val tolerance = intervalNs / 4
        if (!force && frameTsNs < nextDueNs - tolerance) {
            gl.postDelayed(catchUp, max(1L, (nextDueNs - frameTsNs) / 1_000_000))
            return
        }
        // After a still screen the schedule restarts from this frame instead of catching up with a burst.
        nextDueNs = if (frameTsNs > nextDueNs + intervalNs) frameTsNs + intervalNs else nextDueNs + intervalNs
        submit(redraw = false)
    }

    /** The current picture again, now: after a pause, for an IDR, and on a still screen. */
    private fun drawNow() {
        gl.removeCallbacks(catchUp)
        gl.removeCallbacks(idleRedraw)
        if (window == null || !framesEnabled || !hasFrame) return
        nextDueNs = System.nanoTime() + intervalNs
        submit(redraw = true)
    }

    /** Draws unless the network is behind; then holds the picture and asks again shortly. */
    private fun submit(redraw: Boolean) {
        gl.removeCallbacks(gateRetry)
        if (window == null || !framesEnabled) return
        val open = try {
            gate()
        } catch (e: RuntimeException) {
            Log.w(TAG, "gate", e)
            true
        }
        if (!open) {
            held = true
            heldRedraw = heldRedraw || redraw
            gl.postDelayed(gateRetry, GATE_RETRY_MS)
            return
        }
        val ts = if (redraw || heldRedraw) System.nanoTime() else frameTsNs
        held = false
        heldRedraw = false
        draw(ts)
    }

    private fun cancelDraws() {
        gl.removeCallbacks(catchUp)
        gl.removeCallbacks(gateRetry)
        gl.removeCallbacks(idleRedraw)
        held = false
        heldRedraw = false
    }

    private fun draw(captureNs: Long) {
        val w = window ?: return
        val r = rect ?: return
        egl.makeCurrent(w)
        drawer.draw(texture, texMatrix, frameW, frameH, r, rotated)
        val pts = max(captureNs, lastPtsNs + 1_000)
        orientation.record(pts / 1_000, rotated)
        if (egl.swap(w, pts)) {
            lastPtsNs = pts
            swapFailed = false
        } else if (!swapFailed) {
            swapFailed = true
            Log.w(TAG, "eglSwapBuffers başarısız")
        }
        pending = false
        lastDrawNs = System.nanoTime()
        // Without new frames the board still gets one now and then: it stays live and keeps measuring latency.
        gl.removeCallbacks(idleRedraw)
        gl.postDelayed(idleRedraw, IDLE_REDRAW_MS)
    }

    private fun <T> onGl(block: () -> T): T {
        if (Looper.myLooper() == thread.looper) return block()
        val done = CountDownLatch(1)
        var result: Result<T>? = null
        check(gl.post { result = runCatching(block); done.countDown() }) { "GL thread gone" }
        check(done.await(GL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "GL thread did not answer" }
        return result!!.getOrThrow()
    }

    private companion object {
        const val GL_TIMEOUT_MS = 3_000L
        /** A still screen sends this often: 2 small frames a second instead of the encoder's 10 full repeats. */
        const val IDLE_REDRAW_MS = 500L
        const val GATE_RETRY_MS = 4L
    }
}
