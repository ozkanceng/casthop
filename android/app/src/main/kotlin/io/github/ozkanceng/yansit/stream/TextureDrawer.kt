package io.github.ozkanceng.yansit.stream

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws a SurfaceTexture (external OES texture) into a rectangle of the current surface, black around it.
 * Every call must come from the GL thread with a current context.
 */
internal class TextureDrawer {
    private val program: Int
    private val aPosition: Int
    private val aTexCoord: Int
    private val uTexMatrix: Int

    // Full-rectangle triangle strip; the viewport places it.
    private val positions = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val texCoords = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    // The same quad with the picture turned 90° clockwise: output (u, v) samples (1 − v, u), so the picture's top
    // edge lands on the right (FrameFlags.ROTATED).
    private val rotatedTexCoords = floats(1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f)

    init {
        program = link(compile(GLES20.GL_VERTEX_SHADER, VERTEX), compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
    }

    fun createTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    fun deleteTexture(id: Int) = GLES20.glDeleteTextures(1, intArrayOf(id), 0)

    /**
     * @param rect where the picture goes, from the top-left of a [frameWidth]×[frameHeight] surface
     * @param rotated draw the picture turned 90° clockwise; [rect] is then in the turned size
     */
    fun draw(texture: Int, texMatrix: FloatArray, frameWidth: Int, frameHeight: Int, rect: FrameRect, rotated: Boolean) {
        GLES20.glViewport(0, 0, frameWidth, frameHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glViewport(rect.x, frameHeight - rect.y - rect.height, rect.width, rect.height)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, if (rotated) rotatedTexCoords else texCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glUseProgram(0)
    }

    fun release() = GLES20.glDeleteProgram(program)

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("shader: $log")
        }
        return shader
    }

    private fun link(vertex: Int, fragment: Int): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vertex)
        GLES20.glAttachShader(p, fragment)
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            error("program: $log")
        }
        return p
    }

    private companion object {
        const val VERTEX = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        // highp where available: mediump texture coordinates blur text at 1920 px.
        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(v)
                position(0)
            }
    }
}
