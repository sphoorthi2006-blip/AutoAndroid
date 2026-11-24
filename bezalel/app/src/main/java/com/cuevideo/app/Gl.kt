package com.cuevideo.app

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** EGL context that draws into the video encoder's input surface. Use it on ONE thread only. */
class EglCore(surface: Surface) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("No EGL display on this phone.")
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw RuntimeException("EGL could not start.")
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1,                       // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] <= 0)
            throw RuntimeException("No suitable EGL configuration.")
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT || eglSurface == EGL14.EGL_NO_SURFACE)
            throw RuntimeException("Could not create the drawing surface.")
    }

    fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context))
            throw RuntimeException("eglMakeCurrent failed.")
    }

    fun setPresentationTime(ns: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ns)
    }

    fun swap(): Boolean = EGL14.eglSwapBuffers(display, eglSurface)

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }
}

/** One textured full-screen quad, drawn with a texture matrix, a scale (letterbox) and a brightness. */
class GlDraw(private val W: Int, private val H: Int) {
    private class Prog(val id: Int) {
        var aPos = 0
        var aUv = 0
        var uTex = 0
        var uScale = 0
        var uBright = 0
        var uSampler = 0
    }

    private val vertex = """
        uniform mat4 uTex;
        uniform vec2 uScale;
        attribute vec2 aPos;
        attribute vec2 aUv;
        varying vec2 vUv;
        void main() {
            gl_Position = vec4(aPos * uScale, 0.0, 1.0);
            vUv = (uTex * vec4(aUv, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val precision = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
    """.trimIndent()

    private val frag2d = """
        $precision
        varying vec2 vUv;
        uniform sampler2D uSampler;
        uniform float uBright;
        void main() {
            vec4 c = texture2D(uSampler, vUv);
            gl_FragColor = vec4(c.rgb * uBright, 1.0);
        }
    """.trimIndent()

    private val fragOes = """
        #extension GL_OES_EGL_image_external : require
        $precision
        varying vec2 vUv;
        uniform samplerExternalOES uSampler;
        uniform float uBright;
        void main() {
            vec4 c = texture2D(uSampler, vUv);
            gl_FragColor = vec4(c.rgb * uBright, 1.0);
        }
    """.trimIndent()

    // x, y, u, v  (triangle strip; uv (0,0) = bottom-left of the screen)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        ))
        position(0)
    }

    private val p2d: Prog = build(frag2d)
    private val pOes: Prog = build(fragOes)

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Shader error: $log")
        }
        return s
    }

    private fun build(fragSrc: String): Prog {
        val vs = shader(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vs)
        GLES20.glAttachShader(id, fs)
        GLES20.glLinkProgram(id)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Program error: " + GLES20.glGetProgramInfoLog(id))
        val p = Prog(id)
        p.aPos = GLES20.glGetAttribLocation(id, "aPos")
        p.aUv = GLES20.glGetAttribLocation(id, "aUv")
        p.uTex = GLES20.glGetUniformLocation(id, "uTex")
        p.uScale = GLES20.glGetUniformLocation(id, "uScale")
        p.uBright = GLES20.glGetUniformLocation(id, "uBright")
        p.uSampler = GLES20.glGetUniformLocation(id, "uSampler")
        return p
    }

    fun newTexture(target: Int = GLES20.GL_TEXTURE_2D): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(target, t[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    fun newOesTexture(): Int = newTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)

    fun deleteTexture(t: Int) {
        GLES20.glDeleteTextures(1, intArrayOf(t), 0)
    }

    fun upload(tex: Int, bmp: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
    }

    fun clear() {
        GLES20.glViewport(0, 0, W, H)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    fun draw(oes: Boolean, tex: Int, texMatrix: FloatArray, sx: Float, sy: Float, bright: Float) {
        val p = if (oes) pOes else p2d
        val target = if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        GLES20.glUseProgram(p.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, tex)
        GLES20.glUniform1i(p.uSampler, 0)
        GLES20.glUniformMatrix4fv(p.uTex, 1, false, texMatrix, 0)
        GLES20.glUniform2f(p.uScale, sx, sy)
        GLES20.glUniform1f(p.uBright, bright)
        quad.position(0)
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(p.aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(p.aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(p.aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(p.aPos)
        GLES20.glDisableVertexAttribArray(p.aUv)
    }

    fun release() {
        GLES20.glDeleteProgram(p2d.id)
        GLES20.glDeleteProgram(pOes.id)
    }
}
