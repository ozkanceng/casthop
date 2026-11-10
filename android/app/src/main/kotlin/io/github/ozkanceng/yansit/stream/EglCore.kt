package io.github.ozkanceng.yansit.stream

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * One OpenGL ES 2 context whose surfaces can feed a video encoder (EGL_RECORDABLE_ANDROID).
 * Every call must come from the thread that created it.
 */
internal class EglCore {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    private val context: EGLContext
    private val idle: EGLSurface

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize ${EGL14.eglGetError()}" }
        config = chooseConfig(alpha = true) ?: chooseConfig(alpha = false)
            ?: error("no recordable EGL config")
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext ${EGL14.eglGetError()}" }
        // Keeps the context current while no encoder surface exists (textures are created before it).
        idle = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        check(idle != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface ${EGL14.eglGetError()}" }
        makeCurrent(idle)
    }

    private fun chooseConfig(alpha: Boolean): EGLConfig? {
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, if (alpha) 8 else 0,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
        return configs[0]
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface ${EGL14.eglGetError()}" }
        return s
    }

    fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent ${EGL14.eglGetError()}" }
    }

    fun makeIdleCurrent() = makeCurrent(idle)

    /** Shows [surface]'s frame with the capture time [ptsNs] (System.nanoTime clock); false if the surface is gone. */
    fun swap(surface: EGLSurface, ptsNs: Long): Boolean {
        EGLExt.eglPresentationTimeANDROID(display, surface, ptsNs)
        return EGL14.eglSwapBuffers(display, surface)
    }

    fun destroySurface(surface: EGLSurface) {
        makeIdleCurrent()
        EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, idle)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }
}
