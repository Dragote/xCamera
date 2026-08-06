package com.dragote.xcamera.feature.camera.ui.gl

import android.graphics.SurfaceTexture
import android.media.Image
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * App-owned GLES rendering of the live Camera2 preview onto a [SurfaceTexture] — replaces letting
 * Camera2 write directly into a `TextureView`'s `SurfaceTexture` (see `docs/features/camera-capture.md`
 * for the full history of why: that path's requested buffer size isn't always honored by the HAL
 * across a session reopen, with no way to detect the mismatch, which showed up as an intermittently
 * stretched preview). `CameraController` instead targets its preview repeating request at an
 * `ImageReader` this class never sees directly — [onPreviewFrame] receives each delivered [Image],
 * whose `width`/`height` are a hard `ImageReader` construction-time guarantee, never a silently
 * substituted one, so the crop/rotation transform this class computes is always built from *verified*
 * frame dimensions rather than an assumption.
 *
 * Owns its own [HandlerThread] + EGL context/surface — GL contexts are single-thread-bound, and this
 * deliberately never shares a thread with `CameraController`'s own `backgroundHandler` (which acquires
 * each [Image] and hands it here via `Handler.post` onto [handler] — see `CameraRepository
 * .setPreviewFrameListener`'s own doc). Every entry point below except [start]/[stop]/[updateViewMetrics]
 * /[updateRotation] is expected to run *on* [handler] (either because the caller already posted onto it,
 * or because this class posts internally) — there is deliberately only ever one owner driving this
 * renderer's lifecycle (`ui/CameraScreen`'s `TextureView` attach/detach), unlike an earlier, since-
 * reverted attempt elsewhere in this module that let two independent lifecycle reactions both touch
 * `Surface` state and crashed; keep it that way.
 */
class CameraPreviewRenderer {

    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // Read/written exclusively on the render thread's own Handler — every caller of onPreviewFrame
    // (CameraController, via Handler.post onto this class's own handler) and every internal init/
    // release call all serialize through that single Handler's message queue, so this needs no
    // synchronization of its own despite start()/stop() being called from a different (UI) thread.
    private var released = true

    private var programId = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var transformHandle = 0
    private var yTextureUniform = 0
    private var uTextureUniform = 0
    private var vTextureUniform = 0
    private var yTextureId = 0
    private var uTextureId = 0
    private var vTextureId = 0

    private var viewWidth = 0
    private var viewHeight = 0
    private var sensorOrientationDegrees = 0
    private var lastImageWidth = 0
    private var lastImageHeight = 0
    private var transformDirty = true
    private val transformMatrix = FloatArray(16)

    // Set whenever the image dimensions change (alongside transformDirty) so uploadTexture knows to
    // (re)allocate GL texture storage via glTexImage2D exactly once per size, then glTexSubImage2D
    // (copy into existing storage, no reallocation) on every subsequent frame of that size — the
    // dominant per-frame GPU cost otherwise.
    private var textureStorageAllocated = false

    // Reused scratch buffers for the repacked (tightly-packed, stride-stripped) plane data GLES 2.0's
    // glTexImage2D requires — see repackPlane's own doc for why this repacking is necessary at all.
    // Resized (not reallocated per-frame) only when the source image's dimensions actually change.
    private var yScratch: ByteBuffer? = null
    private var uScratch: ByteBuffer? = null
    private var vScratch: ByteBuffer? = null

    // Reused scratch arrays for repackPlane's row-at-a-time bulk reads — see its own doc for why bulk
    // array access (vs. per-byte ByteBuffer get/put) matters for frame rate.
    private var interleavedRowScratch: ByteArray? = null
    private var destRowScratch: ByteArray? = null

    private val vertexBuffer: FloatBuffer = directFloatBuffer(
        floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        ),
    )
    private val texCoordBuffer: FloatBuffer = directFloatBuffer(
        floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        ),
    )

    /** Render-thread [Handler] frames must be posted to (see this class's own doc) — `null` before
     *  [start] completes / after [stop]. */
    val handler: Handler? get() = renderHandler

    /** Spins up the render thread and builds the EGL context/surface/GL program against
     *  [surfaceTexture]. Cheap to call again after [stop] — a fresh thread/context is built each time,
     *  matching `CameraController.ensureBackgroundThread`/`stopBackgroundThread`'s own pattern. */
    fun start(surfaceTexture: SurfaceTexture) {
        val thread = HandlerThread("CameraPreviewRenderer").apply { start() }
        renderThread = thread
        val threadHandler = Handler(thread.looper)
        renderHandler = threadHandler
        threadHandler.post { initGl(surfaceTexture) }
    }

    /** Tears down GL/EGL state and joins the render thread — safe to call even if a frame is
     *  concurrently in flight (posted-order on the same Handler guarantees this runs after any frame
     *  already queued ahead of it, and after this runs, [released] makes any later-arriving frame a
     *  no-op instead of touching torn-down EGL state). */
    fun stop() {
        val threadHandler = renderHandler ?: return
        val thread = renderThread
        threadHandler.post { releaseGl() }
        thread?.quitSafely()
        try {
            thread?.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        renderThread = null
        renderHandler = null
    }

    /** Called from `ui/CameraScreen` on layout/resize — cheap to call from any thread, just posts an
     *  invalidation onto the render thread. */
    fun updateViewMetrics(width: Int, height: Int) {
        renderHandler?.post {
            if (viewWidth != width || viewHeight != height) {
                viewWidth = width
                viewHeight = height
                transformDirty = true
            }
        }
    }

    /** Called from `ui/CameraScreen` whenever the bound lens (and therefore
     *  `CameraCharacteristics.SENSOR_ORIENTATION`) changes — see `CameraRepository
     *  .previewRotationDegrees`. Cheap to call from any thread, same as [updateViewMetrics]. */
    fun updateRotation(degrees: Int) {
        renderHandler?.post {
            if (sensorOrientationDegrees != degrees) {
                sensorOrientationDegrees = degrees
                transformDirty = true
            }
        }
    }

    /**
     * Takes ownership of [image]: always closes it before returning, on every path. Must run on
     * [handler] — `CameraController`'s frame hand-off already guarantees this (see
     * `CameraRepository.setPreviewFrameListener`'s doc), this does not itself post/dispatch.
     *
     * [image]'s own `ImageReader` can be torn down (`CameraController` rebinding — e.g. the
     * lens-resolves-after-launch "double bind" at cold start, or a lens switch/reopen racing this
     * exact frame) between when [CameraController] acquired [image] and when this call actually runs
     * on the render thread — confirmed on-device, this invalidates the `Image`'s buffers out from
     * under it (`IllegalStateException: buffer is inaccessible` reading a plane, `close()` can throw
     * too). Not fatal: the next delivered frame comes from whatever reader is current by then, so a
     * stale frame is simply dropped rather than crashing the render thread.
     */
    fun onPreviewFrame(image: Image) {
        try {
            if (!released) drawFrame(image)
        } catch (e: IllegalStateException) {
            // Stale frame — its ImageReader was already torn down mid-flight. See this method's own
            // doc; safe to drop.
        } finally {
            try {
                image.close()
            } catch (e: IllegalStateException) {
                // Already invalid for the same reason — nothing left to release.
            }
        }
    }

    private fun initGl(surfaceTexture: SurfaceTexture) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return
        val versionOut = IntArray(2)
        if (!EGL14.eglInitialize(display, versionOut, 0, versionOut, 1)) return
        eglDisplay = display

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val gotConfig = EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        val config = configs[0]
        if (!gotConfig || numConfigs[0] == 0 || config == null) {
            releaseGl()
            return
        }

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (context == EGL14.EGL_NO_CONTEXT) {
            releaseGl()
            return
        }
        eglContext = context

        val surface = EGL14.eglCreateWindowSurface(display, config, surfaceTexture, intArrayOf(EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE) {
            releaseGl()
            return
        }
        eglSurface = surface

        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            releaseGl()
            return
        }

        programId = buildProgram()
        positionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
        transformHandle = GLES20.glGetUniformLocation(programId, "uTexTransform")
        yTextureUniform = GLES20.glGetUniformLocation(programId, "uTextureY")
        uTextureUniform = GLES20.glGetUniformLocation(programId, "uTextureU")
        vTextureUniform = GLES20.glGetUniformLocation(programId, "uTextureV")

        // Our repacked plane buffers are tightly packed (width bytes/row, no padding) — GL's default
        // 4-byte row alignment would misread row boundaries whenever a plane width isn't a multiple
        // of 4 (chroma planes at odd/2 widths hit this often). Must match the buffers repackPlane
        // produces.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)

        val textureIds = IntArray(3)
        GLES20.glGenTextures(3, textureIds, 0)
        yTextureId = textureIds[0]
        uTextureId = textureIds[1]
        vTextureId = textureIds[2]
        for (id in textureIds) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        released = false
    }

    private fun releaseGl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        programId = 0
        transformDirty = true
        textureStorageAllocated = false
        released = true
    }

    private fun drawFrame(image: Image) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || programId == 0) return
        if (viewWidth <= 0 || viewHeight <= 0) return

        if (image.width != lastImageWidth || image.height != lastImageHeight) {
            lastImageWidth = image.width
            lastImageHeight = image.height
            transformDirty = true
            textureStorageAllocated = false
        }
        if (transformDirty) {
            computeTransform()
            transformDirty = false
        }

        uploadPlanes(image)

        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(programId)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)

        texCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        GLES20.glUniformMatrix4fv(transformHandle, 1, false, transformMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yTextureId)
        GLES20.glUniform1i(yTextureUniform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uTextureId)
        GLES20.glUniform1i(uTextureUniform, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vTextureId)
        GLES20.glUniform1i(vTextureUniform, 2)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * Center-crop-fill (mirrors the deleted `ui/CameraScreen.previewFillTransform`'s own `max(...)`
     * reasoning, now expressed as a GL texture-coordinate matrix instead of an
     * `android.graphics.Matrix`): the sampled window must cover the whole view in both axes once the
     * image is rotated into display orientation, so excess is cropped rather than letterboxed. Unlike
     * the deleted function, [sensorOrientationDegrees] is applied explicitly here — an `ImageReader`
     * surface (unlike a `TextureView`'s own on-screen `SurfaceTexture`) never gets automatic
     * producer-side rotation, the same reason `ZebraMask.rotatedBy` already needs an explicit angle.
     *
     * Built around (0.5, 0.5) — texture-coordinate center — rather than the origin, so rotation and
     * scale both pivot around the middle of the frame instead of a corner. The initial `scaleM(1, -1)`
     * is a vertical flip: [uploadPlanes] fills each texture row-major from the image's own top-down
     * byte layout, but GL's texture-coordinate `v` increases upward, not downward.
     *
     * Sign/direction of [sensorOrientationDegrees] and which axis ends up flipped are the most likely
     * things to need on-device correction on the first pass — there's no way to verify orientation
     * math without seeing it rendered (see this repo's own `docs/features/camera-capture.md` risk
     * notes for this rewrite).
     */
    private fun computeTransform() {
        val imgW = lastImageWidth.toFloat()
        val imgH = lastImageHeight.toFloat()
        if (imgW <= 0f || imgH <= 0f) return

        val quarterTurn = sensorOrientationDegrees == 90 || sensorOrientationDegrees == 270
        val rotatedW = if (quarterTurn) imgH else imgW
        val rotatedH = if (quarterTurn) imgW else imgH

        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        val imageAspect = rotatedW / rotatedH
        val cropScaleX: Float
        val cropScaleY: Float
        if (imageAspect > viewAspect) {
            cropScaleX = viewAspect / imageAspect
            cropScaleY = 1f
        } else {
            cropScaleX = 1f
            cropScaleY = imageAspect / viewAspect
        }

        Matrix.setIdentityM(transformMatrix, 0)
        Matrix.translateM(transformMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(transformMatrix, 0, -sensorOrientationDegrees.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(transformMatrix, 0, cropScaleX, -cropScaleY, 1f)
        Matrix.translateM(transformMatrix, 0, -0.5f, -0.5f, 0f)
    }

    private fun uploadPlanes(image: Image) {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val chromaWidth = image.width / 2
        val chromaHeight = image.height / 2

        val y = ensureCapacity(yScratch, image.width * image.height).also { yScratch = it }
        val u = ensureCapacity(uScratch, chromaWidth * chromaHeight).also { uScratch = it }
        val v = ensureCapacity(vScratch, chromaWidth * chromaHeight).also { vScratch = it }

        repackPlane(yPlane, image.width, image.height, y)
        repackPlane(uPlane, chromaWidth, chromaHeight, u)
        repackPlane(vPlane, chromaWidth, chromaHeight, v)

        val allocate = !textureStorageAllocated
        uploadTexture(yTextureId, image.width, image.height, y, allocate)
        uploadTexture(uTextureId, chromaWidth, chromaHeight, u, allocate)
        uploadTexture(vTextureId, chromaWidth, chromaHeight, v, allocate)
        textureStorageAllocated = true
    }

    private fun uploadTexture(textureId: Int, width: Int, height: Int, pixels: ByteBuffer, allocate: Boolean) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        if (allocate) {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, pixels,
            )
        } else {
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, pixels,
            )
        }
    }

    private fun ensureCapacity(existing: ByteBuffer?, size: Int): ByteBuffer {
        if (existing != null && existing.capacity() >= size) return existing
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
    }

    /**
     * `glTexImage2D`/`glTexSubImage2D` require a tightly-packed buffer (`rowStride == width`,
     * `pixelStride == 1`) — Camera2's `YUV_420_888` planes very commonly aren't (`rowStride` padded to
     * a sensor alignment boundary; chroma planes are frequently semi-planar with `pixelStride == 2`,
     * U and V bytes interleaved). This strips both. Every read from [plane]'s buffer goes through a
     * *bulk* `get(byte[], ...)` into a reused scratch array first, then de-interleaves (when
     * `pixelStride != 1`) via a plain-array loop — deliberately never a per-byte `ByteBuffer.get`/`put`
     * call in the hot loop, which measured as the dominant per-frame CPU cost (direct-buffer indexed
     * access doesn't optimize the way a tight on-heap array loop does): a single dropped frame here
     * compounds through `CameraController.previewFrameInFlight`'s gating into a visibly lower preview
     * frame rate, not just a one-off slow frame.
     */
    private fun repackPlane(plane: Image.Plane, width: Int, height: Int, dest: ByteBuffer) {
        val src = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        dest.clear()
        val dup = src.duplicate()
        val row = ensureByteArray(destRowScratch, width).also { destRowScratch = it }
        if (pixelStride == 1) {
            for (r in 0 until height) {
                dup.position(r * rowStride)
                dup.get(row, 0, width)
                dest.put(row, 0, width)
            }
        } else {
            val requiredBytes = (width - 1) * pixelStride + 1
            val interleaved = ensureByteArray(interleavedRowScratch, requiredBytes).also { interleavedRowScratch = it }
            for (r in 0 until height) {
                dup.position(r * rowStride)
                dup.get(interleaved, 0, requiredBytes)
                var si = 0
                for (di in 0 until width) {
                    row[di] = interleaved[si]
                    si += pixelStride
                }
                dest.put(row, 0, width)
            }
        }
        dest.flip()
    }

    private fun ensureByteArray(existing: ByteArray?, size: Int): ByteArray {
        if (existing != null && existing.size >= size) return existing
        return ByteArray(size)
    }

    private fun buildProgram(): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_SRC)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SRC)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private companion object {
        fun directFloatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(values)
                .apply { position(0) }

        const val VERTEX_SHADER_SRC = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexTransform;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexTransform * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        // Standard BT.601-ish YUV->RGB conversion, Y/U/V each sampled from their own GL_LUMINANCE
        // texture (U/V centered at 0.5, matching YUV_420_888's unsigned-byte-with-128-bias chroma
        // encoding). Exact color calibration (limited- vs full-range Y, BT.601 vs BT.709 coefficients)
        // is a real on-device tuning item, not verified against hardware yet.
        const val FRAGMENT_SHADER_SRC = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTextureY;
            uniform sampler2D uTextureU;
            uniform sampler2D uTextureV;
            void main() {
                float y = texture2D(uTextureY, vTexCoord).r;
                float u = texture2D(uTextureU, vTexCoord).r - 0.5;
                float v = texture2D(uTextureV, vTexCoord).r - 0.5;
                float r = y + 1.402 * v;
                float g = y - 0.344136 * u - 0.714136 * v;
                float b = y + 1.772 * u;
                gl_FragColor = vec4(r, g, b, 1.0);
            }
        """
    }
}
