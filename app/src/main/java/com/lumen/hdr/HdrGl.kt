package com.lumen.hdr

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Grade settings chosen in the UI. */
data class HdrSettings(
    val peakNits: Float = 1000f,   // brightest highlight in the output
    val strength: Float = 0.8f,    // 0 = keep SDR brightness, 1 = full highlight expansion
    val shadows: Float = 0.3f,     // lift dark areas
    val saturation: Float = 1.15f, // 1 = unchanged
    val sharpness: Float = 0.25f,
    val exposure: Float = 0f,      // in EV stops
)

/**
 * Owns the EGL context that draws decoded SDR frames into the HDR10 encoder.
 * The window surface is 10-bit (RGBA 1010102) and tagged BT.2020 PQ, and the
 * shader writes PQ-encoded BT.2020 values, so the encoder receives real HDR10 signal.
 */
class HdrGl(encoderSurface: Surface, private val srcWidth: Int, private val srcHeight: Int) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private val windowSurface = encoderSurface

    private var program = 0
    private var textureId = 0
    lateinit var surfaceTexture: SurfaceTexture
        private set
    lateinit var decoderSurface: Surface
        private set

    private val frameThread = HandlerThread("hdr-frames").apply { start() }
    private val frameLock = Object()
    private var frameAvailable = false
    private val texMatrix = FloatArray(16)

    /** True when the driver accepted the BT.2020 PQ surface tag. */
    var pqTagged = false
        private set

    init {
        setupEgl()
        setupProgram()
    }

    private fun setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL init failed" }

        val config = chooseConfig(recordable = true) ?: chooseConfig(recordable = false)
            ?: error("This phone has no 10-bit GPU output, which HDR10 needs.")

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "EGL context failed" }

        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) ?: ""
        if (extensions.contains("EGL_EXT_gl_colorspace_bt2020_pq")) {
            val attrs = intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_PQ_EXT, EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(display, config, windowSurface, attrs, 0)
            pqTagged = eglSurface != EGL14.EGL_NO_SURFACE
        }
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            eglSurface = EGL14.eglCreateWindowSurface(display, config, windowSurface, intArrayOf(EGL14.EGL_NONE), 0)
        }
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Could not connect GPU to the video encoder" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
    }

    private fun chooseConfig(recordable: Boolean): EGLConfig? {
        val attribs = mutableListOf(
            EGL14.EGL_RED_SIZE, 10,
            EGL14.EGL_GREEN_SIZE, 10,
            EGL14.EGL_BLUE_SIZE, 10,
            EGL14.EGL_ALPHA_SIZE, 2,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
        )
        if (recordable) attribs += listOf(EGL_RECORDABLE_ANDROID, 1)
        attribs += EGL14.EGL_NONE
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val ok = EGL14.eglChooseConfig(display, attribs.toIntArray(), 0, configs, 0, 1, count, 0)
        return if (ok && count[0] > 0) configs[0] else null
    }

    private fun setupProgram() {
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, compile(GLES30.GL_VERTEX_SHADER, VERTEX))
        GLES30.glAttachShader(program, compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES30.glLinkProgram(program)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { "Shader link failed: " + GLES30.glGetProgramInfoLog(program) }

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setDefaultBufferSize(srcWidth, srcHeight)
        surfaceTexture.setOnFrameAvailableListener({
            synchronized(frameLock) {
                frameAvailable = true
                frameLock.notifyAll()
            }
        }, Handler(frameThread.looper))
        decoderSurface = Surface(surfaceTexture)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "Shader compile failed: " + GLES30.glGetShaderInfoLog(s) }
        return s
    }

    /** Waits for the decoder's next frame to land in the texture. */
    fun awaitFrame() {
        synchronized(frameLock) {
            val deadline = System.currentTimeMillis() + 3000
            while (!frameAvailable) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) error("Timed out waiting for a decoded frame")
                frameLock.wait(left)
            }
            frameAvailable = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
    }

    fun draw(outWidth: Int, outHeight: Int, s: HdrSettings, presentationNs: Long) {
        GLES30.glViewport(0, 0, outWidth, outHeight)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(loc("uTex"), 0)
        GLES30.glUniformMatrix4fv(loc("uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniform2f(loc("uTexel"), 1f / srcWidth, 1f / srcHeight)
        GLES30.glUniform1f(loc("uPeak"), s.peakNits)
        GLES30.glUniform1f(loc("uStrength"), s.strength)
        GLES30.glUniform1f(loc("uShadows"), s.shadows)
        GLES30.glUniform1f(loc("uSat"), s.saturation)
        GLES30.glUniform1f(loc("uSharp"), s.sharpness)
        GLES30.glUniform1f(loc("uExposure"), s.exposure)

        val aPos = GLES30.glGetAttribLocation(program, "aPos")
        val aTex = GLES30.glGetAttribLocation(program, "aTex")
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, QUAD_POS)
        GLES30.glEnableVertexAttribArray(aTex)
        GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 0, QUAD_TEX)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationNs)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    private fun loc(name: String) = GLES30.glGetUniformLocation(program, name)

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        decoderSurface.release()
        surfaceTexture.release()
        frameThread.quitSafely()
        display = EGL14.EGL_NO_DISPLAY
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340

        private fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

        private val QUAD_POS = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val QUAD_TEX = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        private const val VERTEX = """#version 300 es
in vec4 aPos;
in vec4 aTex;
uniform mat4 uTexMatrix;
out vec2 vUv;
void main() {
    gl_Position = aPos;
    vUv = (uTexMatrix * aTex).xy;
}
"""

        // SDR (BT.709, BT.1886 gamma) -> HDR10 (BT.2020 primaries, SMPTE ST 2084 PQ)
        private const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform vec2 uTexel;
uniform float uPeak, uStrength, uShadows, uSat, uSharp, uExposure;
in vec2 vUv;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float SDR_WHITE = 203.0; // ITU-R BT.2408 reference white, in nits

vec3 pq(vec3 nits) {
    vec3 y = clamp(nits / 10000.0, 0.0, 1.0);
    const float m1 = 0.1593017578125;
    const float m2 = 78.84375;
    const float c1 = 0.8359375;
    const float c2 = 18.8515625;
    const float c3 = 18.6875;
    vec3 ym = pow(y, vec3(m1));
    return pow((c1 + c2 * ym) / (1.0 + c3 * ym), vec3(m2));
}

void main() {
    vec3 c = texture(uTex, vUv).rgb;

    // unsharp mask
    vec3 n = texture(uTex, vUv + vec2(uTexel.x, 0.0)).rgb
           + texture(uTex, vUv - vec2(uTexel.x, 0.0)).rgb
           + texture(uTex, vUv + vec2(0.0, uTexel.y)).rgb
           + texture(uTex, vUv - vec2(0.0, uTexel.y)).rgb;
    c = clamp(c + (c - n * 0.25) * uSharp * 1.5, 0.0, 1.0);

    // to linear light
    vec3 lin = pow(c, vec3(2.4)) * exp2(uExposure);
    float L = dot(lin, LUMA);
    lin *= 1.0 + uShadows * 1.5 * (1.0 - smoothstep(0.0, 0.25, L));
    L = dot(lin, LUMA);
    lin = max(mix(vec3(L), lin, uSat), 0.0);
    L = max(dot(lin, LUMA), 1e-5);

    // inverse tone map: SDR white maps to 203 nits, the top of the range expands to uPeak
    float r = max(uPeak / SDR_WHITE, 1.0);
    float Lc = min(L, 0.999);
    float Lh = Lc / (1.0 - Lc * (1.0 - 1.0 / r));
    float gain = mix(1.0, Lh / Lc, uStrength);
    vec3 nits = lin * gain * SDR_WHITE;

    // BT.709 -> BT.2020 primaries (linear)
    const mat3 TO2020 = mat3(
        0.6274, 0.0691, 0.0164,
        0.3293, 0.9195, 0.0880,
        0.0433, 0.0114, 0.8956);
    nits = min(TO2020 * nits, vec3(uPeak));

    fragColor = vec4(pq(nits), 1.0);
}
"""
    }
}
