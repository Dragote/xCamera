package com.dragote.xcamera.feature.camera.data.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.dragote.xcamera.feature.camera.domain.model.CubeLut
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Offscreen (headless, no on-screen `Surface`) GLES 3.0 pass that runs a captured still JPEG through
 * the same LUT-sampling math `ui/gl/CameraPreviewRenderer`'s fragment shader uses for the live
 * preview (issue #43) — decode -> upload as a 2D texture -> sample + blend against the 3D LUT texture
 * in an FBO -> read back -> re-encode. Called from `CameraController.takePhoto` only while a LUT is
 * actually active; a capture with no LUT selected never touches this class at all (straight JPEG-to-
 * MediaStore, unchanged from before this issue).
 *
 * Deliberately creates and tears down its own EGL context/pbuffer surface *per call* rather than
 * keeping one alive across captures, unlike `CameraPreviewRenderer`'s own long-lived render-thread
 * context — a still capture happens at most a few times a minute (not per-frame), so the extra
 * context-setup latency is an acceptable, simple tradeoff against the complexity of a second
 * long-lived GL thread/context sitting alongside the preview's; revisit only if on-device latency
 * proves this wrong.
 *
 * **Not unit-testable** — real GLES/EGL calls, per this project's own camera testing conventions
 * (`.claude/agents/camera-engineer.md`). Kept thin/mechanical on purpose; the actual LUT math it
 * leans on (parsing, blend-intensity semantics) lives in plain testable domain code
 * (`domain/model/CubeLutParser.kt`). **Entirely unverified on-device** (no device available to this
 * change) — flag this file first if a LUT-graded capture comes out corrupt, upside-down, or with
 * wrong colors; see [renderGraded]'s own doc for the specific orientation assumption most likely to
 * need correcting.
 */
class LutJpegProcessor {

    /**
     * [jpegBytes] is the raw still-capture JPEG straight off `ImageReader`; [cubeLut]/[intensityPercent]
     * mirror `CameraPreviewRenderer.setLut`'s own parameters. Returns the graded JPEG's bytes, or
     * `null` on any failure (see this class's own doc) — `CameraController.takePhoto` falls back to
     * the ungraded original bytes in that case rather than losing the capture entirely.
     */
    fun apply(jpegBytes: ByteArray, cubeLut: CubeLut, intensityPercent: Int): ByteArray? = try {
        processInternal(jpegBytes, cubeLut, intensityPercent)
    } catch (e: Exception) {
        // Deliberately broad — see this class's own doc for why a LUT-processing failure must never
        // fail the underlying capture. Narrowed to Exception (not Throwable), so a genuine
        // OutOfMemoryError still propagates rather than being silently swallowed alongside a
        // recoverable GL/IO failure.
        null
    }

    private fun processInternal(jpegBytes: ByteArray, cubeLut: CubeLut, intensityPercent: Int): ByteArray? {
        val sourceBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
        val width = sourceBitmap.width
        val height = sourceBitmap.height
        if (width <= 0 || height <= 0) {
            sourceBitmap.recycle()
            return null
        }

        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val versionOut = IntArray(2)
            if (!EGL14.eglInitialize(display, versionOut, 0, versionOut, 1)) return null

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val gotConfig = EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            if (!gotConfig || numConfigs[0] == 0) return null
            val config = configs[0] ?: return null

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) return null

            // 1x1 pbuffer — the real render target is the FBO built inside renderGraded; this only
            // exists because EGL requires *some* current surface to make the context current at all.
            val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return null

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null

            val gradedBitmap = renderGraded(sourceBitmap, cubeLut, intensityPercent, width, height) ?: return null
            val outputBytes = encodeJpeg(gradedBitmap, jpegBytes)
            gradedBitmap.recycle()
            return outputBytes
        } finally {
            sourceBitmap.recycle()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }

    /**
     * Builds the shader program + FBO fresh per call (no reuse across captures — see this class's own
     * doc), uploads [sourceBitmap] and [cubeLut] as textures, draws one full-screen quad into the FBO,
     * then reads it back into a new [Bitmap].
     *
     * Deliberately uses a *straightforward* (unflipped) texcoord mapping, unlike
     * `CameraPreviewRenderer.computeTransform`'s explicit vertical flip for its on-screen use — that
     * flip exists because a naive upload/render makes an *on-screen* image appear upside-down; here,
     * the same naive upload combined with `glReadPixels`' own row-0-is-framebuffer-bottom convention
     * flips twice (once implicitly on upload, once implicitly on readback), which cancels out — so
     * the output buffer's row order should already match [sourceBitmap]'s own top-down row order with
     * no further correction needed. Reasoned, not on-device-verified (see this class's own doc) —
     * this is the first thing to check if a graded photo ever comes out upside-down.
     */
    private fun renderGraded(sourceBitmap: Bitmap, cubeLut: CubeLut, intensityPercent: Int, width: Int, height: Int): Bitmap? {
        val program = buildProgram()
        if (program == 0) return null
        GLES30.glUseProgram(program)

        val positionHandle = GLES30.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES30.glGetAttribLocation(program, "aTexCoord")
        val sourceUniform = GLES30.glGetUniformLocation(program, "uSource")
        val lutUniform = GLES30.glGetUniformLocation(program, "uLut")
        val intensityUniform = GLES30.glGetUniformLocation(program, "uLutIntensity")

        val sourceTexIds = IntArray(1)
        GLES30.glGenTextures(1, sourceTexIds, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTexIds[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, sourceBitmap, 0)

        val lutTexIds = IntArray(1)
        GLES30.glGenTextures(1, lutTexIds, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexIds[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        val lutBytes = ByteArray(cubeLut.values.size) { i -> (cubeLut.values[i].coerceIn(0f, 1f) * 255f).toInt().toByte() }
        val lutBuffer = ByteBuffer.allocateDirect(lutBytes.size).order(ByteOrder.nativeOrder()).apply {
            put(lutBytes)
            position(0)
        }
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB, cubeLut.size, cubeLut.size, cubeLut.size, 0,
            GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, lutBuffer,
        )

        val fboTexIds = IntArray(1)
        GLES30.glGenTextures(1, fboTexIds, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexIds[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)

        val fboIds = IntArray(1)
        GLES30.glGenFramebuffers(1, fboIds, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboIds[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTexIds[0], 0,
        )
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) return null

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        val vertexBuffer = directFloatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        val texCoordBuffer = directFloatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        GLES30.glVertexAttribPointer(positionHandle, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer)
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glVertexAttribPointer(texCoordHandle, 2, GLES30.GL_FLOAT, false, 0, texCoordBuffer)
        GLES30.glEnableVertexAttribArray(texCoordHandle)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTexIds[0])
        GLES30.glUniform1i(sourceUniform, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexIds[0])
        GLES30.glUniform1i(lutUniform, 1)
        GLES30.glUniform1f(intensityUniform, intensityPercent.coerceIn(0, 100) / 100f)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        val readBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, readBuffer)
        readBuffer.position(0)

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.copyPixelsFromBuffer(readBuffer)

        GLES30.glDisableVertexAttribArray(positionHandle)
        GLES30.glDisableVertexAttribArray(texCoordHandle)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, fboIds, 0)
        GLES30.glDeleteTextures(1, fboTexIds, 0)
        GLES30.glDeleteTextures(1, sourceTexIds, 0)
        GLES30.glDeleteTextures(1, lutTexIds, 0)
        GLES30.glDeleteProgram(program)

        return result
    }

    /**
     * Re-encodes [gradedBitmap] to JPEG and copies the [ExifInterface.TAG_ORIENTATION] tag from
     * [originalJpegBytes] onto it — decoding to a raw [Bitmap] and re-encoding otherwise loses every
     * EXIF tag the original capture had, including orientation, which would make a graded photo
     * appear rotated wrong even though its pixel content is correct. `ExifInterface` can't write
     * attributes onto an in-memory byte stream (it needs a real file or file descriptor to do so), so
     * this round-trips through a short-lived cache file that's deleted immediately after.
     */
    private fun encodeJpeg(gradedBitmap: Bitmap, originalJpegBytes: ByteArray): ByteArray {
        val plainBytes = ByteArrayOutputStream().use { stream ->
            gradedBitmap.compress(Bitmap.CompressFormat.JPEG, JpegQuality, stream)
            stream.toByteArray()
        }

        val orientation = try {
            ExifInterface(ByteArrayInputStream(originalJpegBytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_UNDEFINED
        }
        if (orientation == ExifInterface.ORIENTATION_UNDEFINED) return plainBytes

        val tempFile = File.createTempFile("xcamera_lut_", ".jpg")
        return try {
            tempFile.writeBytes(plainBytes)
            ExifInterface(tempFile.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            tempFile.readBytes()
        } finally {
            tempFile.delete()
        }
    }

    private fun buildProgram(): Int {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER_SRC)
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SRC)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == GLES30.GL_FALSE) {
            Log.e(TAG, "Program link failed: ${GLES30.glGetProgramInfoLog(program)}")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == GLES30.GL_FALSE) {
            Log.e(TAG, "Shader compile failed (type=$type): ${GLES30.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    private fun directFloatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(values)
            .apply { position(0) }

    private companion object {
        private const val TAG = "LutJpegProcessor"
        const val JpegQuality = 92

        // See CameraPreviewRenderer's own doc on this same pattern: #version must be the literal first
        // characters handed to glShaderSource, so trimIndent() strips the leading blank line a raw
        // triple-quoted string would otherwise carry — confirmed on-device (Adreno, Pixel 9 Pro) that a
        // preceding blank line silently fails shader compilation on this class's identical sibling.
        val VERTEX_SHADER_SRC = """
            #version 300 es
            in vec4 aPosition;
            in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val FRAGMENT_SHADER_SRC = """
            #version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            uniform sampler2D uSource;
            uniform highp sampler3D uLut;
            uniform float uLutIntensity;
            out vec4 fragColor;
            void main() {
                vec3 color = texture(uSource, vTexCoord).rgb;
                vec3 graded = texture(uLut, clamp(color, 0.0, 1.0)).rgb;
                fragColor = vec4(mix(color, graded, uLutIntensity), 1.0);
            }
        """.trimIndent()
    }
}
