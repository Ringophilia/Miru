// Adapted from Ringo-Cove's video_player_android plugin; original BSD-3-Clause
// license is retained in feature/superres/licenses/Flutter-BSD-3-Clause.txt.
package app.miru.superres

import android.content.res.AssetManager
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
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Runs the original Anime4K/FSR mpv shader chains between a decoder and an Android Surface.
 *
 * ExoPlayer decodes into [inputSurface]; every frame is copied from the external texture into an
 * RGBA texture (top row first, like mpv), run through the hook passes on half-float render targets,
 * and drawn to a SurfaceView surface. Initialization, shader compilation and disposal are
 * asynchronous; no wait, codec call, or UI surface ownership is hidden inside this class.
 */
class SuperResRenderer(
    private val assets: AssetManager,
    private val onInputSurface: (Surface) -> Unit,
    private val onError: (String) -> Unit,
) : SurfaceTexture.OnFrameAvailableListener {

    private val thread = HandlerThread("MiruSuperRes").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowWidth = 0
    private var windowHeight = 0
    private var outputSurface: Surface? = null
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var outputAttached = false

    private var oesTexture = 0
    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var inputSurface: Surface
    private val transform = FloatArray(16)

    private var copyProgram = 0
    private var finalProgram = 0
    private var downsampleProgram = 0
    private val programs = HashMap<HookPass, Program>()
    private val parsed = HashMap<String, List<HookPass>>()
    private var passes: List<HookPass> = emptyList()
    private var config = SuperResConfig(emptyList())

    @Volatile private var videoWidth = 0
    @Volatile private var videoHeight = 0
    private var pixelRatio = 1f

    private var input: Target? = null
    private val pool = ArrayList<Target>()

    @Volatile private var snapshot = SuperResStatus()
    private var avgMs = 0.0
    private var totalMeasuredMs = 0.0
    private var measuredFrames = 0L
    private var frameIntervalMs = 0.0
    private var frames = 0L
    private var presentations = 0L
    private var generation = 0L
    private var failed: String? = null
    private var initialized = false
    private var haveFrame = false
    private var presentedOutput = false
    private var lastTimestamp = 0L
    private val released = AtomicBoolean(false)
    private val releaseLock = Any()
    private var releaseComplete = false
    private val releaseCallbacks = ArrayList<() -> Unit>()
    private var lastInput = 0L
    private var lastTargetSize = 0L
    private var timerQueries = IntArray(0)
    private data class TimerSample(val query: Int, val frame: Long, val budgetMs: Double)
    private val pendingTimers = ArrayDeque<TimerSample>()
    private val freeTimers = ArrayDeque<Int>()
    private val frameLoad = SuperResFrameLoad()

    /** First frames after a change allocate targets and finish lazy driver compiles. */
    private fun resetStats() {
        avgMs = 0.0
        totalMeasuredMs = 0.0
        measuredFrames = 0
        frameIntervalMs = 0.0
        frames = 0
        generation++
        frameLoad.clear()
        lastTimestamp = 0L
        presentedOutput = false
        pendingTimers.clear()
        freeTimers.clear()
        timerQueries.forEach { freeTimers.addLast(it) }
    }

    init {
        handler.post {
            if(released.get()) return@post
            try {
                setUpGl()
                initialized = true
                snapshot = snapshot.copy(initialized = true)
                main.post { if(!released.get() && failed == null) onInputSurface(inputSurface) }
            } catch (t: Throwable) {
                fail("超分初始化失败：${t.message}")
                tearDownGl()
            }
        }
    }

    fun configure(next: SuperResConfig) = enqueue {
        try {
            val normalized = next.copy(compareSplit = next.compareSplit.coerceIn(0f, 1f))
            if(normalized == config) return@enqueue
            if(normalized.copy(compareSplit = config.compareSplit) == config) {
                config = normalized
                if(haveFrame && outputAttached) render(false)
                return@enqueue
            }
            check(next.shaders.size <= 12) { "着色器链过长" }
            val chain = next.shaders.flatMap { name ->
                require(name.matches(Regex("[A-Za-z0-9_]+"))) { "无效着色器名称" }
                parsed.getOrPut(name) {
                    HookShader.parse(assets.open("anime4k/$name.glsl").bufferedReader().use { it.readText() })
                }
            }
            // mpv runs PREKERNEL hooks after every MAIN hook, right before the final scaler.
            passes = chain.filter { it.hook == "MAIN" } + chain.filter { it.hook == "PREKERNEL" }
            config = normalized
            for (pass in passes) program(pass)
            clearPool()
            resetStats()
            redrawRetainedFrame()
            publish()
        } catch (t: Throwable) {
            fail("超分着色器加载失败：${t.message}")
        }
    }

    fun setVideoSize(width: Int, height: Int, pixelWidthHeightRatio: Float = 1f) = enqueue {
        if(videoWidth != width || videoHeight != height || pixelRatio != pixelWidthHeightRatio) {
            videoWidth = width.coerceAtLeast(0)
            videoHeight = height.coerceAtLeast(0)
            pixelRatio = pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
            if(width > 0 && height > 0) surfaceTexture.setDefaultBufferSize(width, height)
            clearPool()
            resetStats()
            redrawRetainedFrame()
            publish()
        }
    }

    fun setCompare(split: Float) = enqueue {
        config = config.copy(compareSplit = split.coerceIn(0f, 1f))
        // Redraw the retained decoded frame so comparison also follows a drag while paused.
        redrawRetainedFrame()
    }

    /** SurfaceView owns the supplied Surface; this renderer never releases it. */
    fun attachOutput(surface: Surface, width: Int, height: Int) = enqueue {
        val changed = outputSurface !== surface || width != viewportWidth || height != viewportHeight || !outputAttached
        if(changed) {
            destroyWindow()
            clearPool()
            resetStats()
        }
        outputSurface = surface
        viewportWidth = width.coerceAtLeast(0)
        viewportHeight = height.coerceAtLeast(0)
        outputAttached = surface.isValid && width > 0 && height > 0
        if(changed) redrawRetainedFrame()
        publish()
    }

    /** Keep draining the decoder input while its display Surface is unavailable. */
    fun detachOutput() = enqueue {
        outputAttached = false
        outputSurface = null
        destroyWindow()
        publish()
    }

    fun status(): SuperResStatus = snapshot

    private fun enqueue(block: () -> Unit): Boolean {
        if(released.get()) return false
        return handler.post {
            if(!released.get() && initialized && failed == null)
                runCatching(block).onFailure { fail("超分渲染失败：${it.message}") }
        }
    }

    /** Completion runs on main after EGL disconnects from output, allowing decoder direct output. */
    fun release(onReleased: (() -> Unit)? = null) {
        if(onReleased != null) synchronized(releaseLock) {
            if(releaseComplete) main.post(onReleased) else releaseCallbacks.add(onReleased)
        }
        if (!released.compareAndSet(false, true)) return
        snapshot = snapshot.copy(active = false)
        handler.post {
            try {
                tearDownGl()
            } finally {
                val callbacks = synchronized(releaseLock) {
                    releaseComplete = true
                    releaseCallbacks.toList().also { releaseCallbacks.clear() }
                }
                main.post { callbacks.forEach { it() } }
                thread.quitSafely()
            }
        }
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        if (released.get()) return
        try {
            surfaceTexture.updateTexImage()
            haveFrame = true
            if (failed != null || !outputAttached) return
            val timestamp = surfaceTexture.timestamp
            if (lastTimestamp != 0L && timestamp > lastTimestamp) {
                val interval = (timestamp - lastTimestamp) / 1e6
                if (interval < 250) {
                    val scaled = interval / config.playbackSpeed.takeIf { it.isFinite() && it > 0f }.let { it ?: 1f }
                    frameIntervalMs = if (frameIntervalMs == 0.0) scaled else frameIntervalMs * 0.9 + scaled * 0.1
                }
            }
            lastTimestamp = timestamp
            render(true)
        } catch (t: Throwable) {
            fail("超分渲染失败：${t.message}")
        }
    }

    private fun fail(message: String) {
        if (failed != null) return
        failed = message
        snapshot = snapshot.copy(active = false, error = message)
        main.post { if(!released.get()) onError(message) }
    }

    private fun publish(passes: Int = snapshot.passes, outW: Int = snapshot.outputWidth, outH: Int = snapshot.outputHeight) {
        snapshot = SuperResStatus(initialized = initialized, active = initialized && failed == null && outputAttached && presentedOutput,
            inputWidth = videoWidth, inputHeight = videoHeight, outputWidth = outW, outputHeight = outH,
            viewportWidth = viewportWidth, viewportHeight = viewportHeight,
            imageRect = SuperResPolicy.imageRect(videoWidth, videoHeight, viewportWidth, viewportHeight, config.fit, pixelRatio),
            gpuMs = avgMs, averageMs = if(measuredFrames > 0) totalMeasuredMs / measuredFrames else 0.0,
            frameIntervalMs = frameBudget(), passes = passes, frames = frames, presentations = presentations, generation = generation,
            timing = if(timerQueries.isEmpty()) SuperResTiming.SYNCHRONIZED_RENDER else SuperResTiming.GPU_QUERY,
            consecutiveOverloadedFrames = frameLoad.consecutive, overloadDetected = frameLoad.overloadDetected, error = failed)
    }

    /** Retained decoded pixels can be reprocessed while paused; these are not new decoder frames. */
    private fun redrawRetainedFrame() {
        if(haveFrame && failed == null && outputAttached)
            runCatching { render(false) }.onFailure { fail("超分渲染失败：${it.message}") }
    }

    private fun frameBudget(): Double = if(config.frameRateHint.isFinite() && config.frameRateHint > 0f)
        1000.0 / config.frameRateHint / config.playbackSpeed.coerceAtLeast(.1f) else frameIntervalMs

    private fun measured(frame: Long, ms: Double, budget: Double) {
        if(frame < WARMUP_FRAMES || !ms.isFinite() || ms <= 0) return
        avgMs = if(avgMs == 0.0) ms else avgMs * .8 + ms * .2
        totalMeasuredMs += ms
        measuredFrames++
        frameLoad.record(frame, ms, budget)
    }

    private fun collectTimers() {
        val value = IntArray(1)
        while(pendingTimers.isNotEmpty()) {
            val sample = pendingTimers.first()
            GLES30.glGetQueryObjectuiv(sample.query, GLES30.GL_QUERY_RESULT_AVAILABLE, value, 0)
            if(value[0] == 0) break
            GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, value, 0)
            if(value[0] == 0) {
                GLES30.glGetQueryObjectuiv(sample.query, GLES30.GL_QUERY_RESULT, value, 0)
                measured(sample.frame, (value[0].toLong() and 0xffffffffL) / 1e6, sample.budgetMs)
            } else { frameLoad.record(sample.frame, Double.NaN, sample.budgetMs) }
            pendingTimers.removeFirst()
            freeTimers.addLast(sample.query)
        }
    }

    // region GL setup

    private fun setUpGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "设备不支持 OpenGL ES 3"
        }
        eglConfig = configs[0]
        context = EGL14.eglCreateContext(
            display, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent" }

        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        if ("GL_EXT_disjoint_timer_query" in extensions) {
            val bits = IntArray(1)
            GLES30.glGetQueryiv(GL_TIME_ELAPSED_EXT, GL_QUERY_COUNTER_BITS_EXT, bits, 0)
            if (GLES30.glGetError() == GLES30.GL_NO_ERROR && bits[0] >= 30) {
                timerQueries = IntArray(8)
                GLES30.glGenQueries(timerQueries.size, timerQueries, 0)
                timerQueries.forEach { freeTimers.addLast(it) }
                // Clear pre-existing DVFS/disjoint state before measuring this chain.
                GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, bits, 0)
            }
        }
        check("GL_EXT_color_buffer_half_float" in extensions || "GL_EXT_color_buffer_float" in extensions) {
            "设备不支持浮点渲染目标"
        }

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        oesTexture = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(oesTexture)
        surfaceTexture.setOnFrameAvailableListener(this, handler)
        inputSurface = Surface(surfaceTexture)

        copyProgram = link(VERTEX, COPY_FRAGMENT)
        finalProgram = link(VERTEX, FINAL_FRAGMENT)
        downsampleProgram = link(VERTEX, BOX_FRAGMENT)
    }

    private fun tearDownGl() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        for (program in programs.values) GLES30.glDeleteProgram(program.id)
        programs.clear()
        if (timerQueries.isNotEmpty()) GLES30.glDeleteQueries(timerQueries.size, timerQueries, 0)
        if (copyProgram != 0) GLES30.glDeleteProgram(copyProgram)
        if (finalProgram != 0) GLES30.glDeleteProgram(finalProgram)
        if (downsampleProgram != 0) GLES30.glDeleteProgram(downsampleProgram)
        input?.delete()
        input = null
        pool.forEach { it.delete() }
        pool.clear()
        if (oesTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTexture), 0)
        if (::surfaceTexture.isInitialized) surfaceTexture.release()
        if (::inputSurface.isInitialized) inputSurface.release()
        destroyWindow()
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
        initialized = false
        snapshot = snapshot.copy(initialized = false, active = false)
    }

    private fun destroyWindow() {
        if (window != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            EGL14.eglDestroySurface(display, window)
            window = EGL14.EGL_NO_SURFACE
        }
        windowWidth = 0
        windowHeight = 0
    }

    private fun ensureWindow(width: Int, height: Int): Boolean {
        if (window != EGL14.EGL_NO_SURFACE && width == windowWidth && height == windowHeight) {
            return true
        }
        destroyWindow()
        val surface = outputSurface?.takeIf { outputAttached && it.isValid } ?: return false
        window = EGL14.eglCreateWindowSurface(
            display, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface: 0x${EGL14.eglGetError().toString(16)}" }
        windowWidth = width
        windowHeight = height
        return true
    }

    private fun clearPool() {
        pool.forEach { it.delete() }
        pool.clear()
        lastTargetSize = 0
    }

    // endregion

    // region Rendering

    private class Target(val texture: Int, val fbo: Int, val width: Int, val height: Int) {
        fun delete() {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }

    private class Program(val id: Int, val samplers: List<Pair<String, Int>>, val sizes: List<Pair<String, Int>>)

    private fun newTarget(width: Int, height: Int): Target {
        val limit = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, limit, 0)
        check(width in 1..limit[0] && height in 1..limit[0]) { "渲染尺寸超出设备限制" }
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glGenFramebuffers(1, ids, 0)
        val fbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "浮点渲染目标不可用"
        }
        return Target(texture, fbo, width, height)
    }

    /** Reuses an allocated texture of the same size that no live name refers to. */
    private fun acquire(width: Int, height: Int, live: Collection<Target>): Target {
        pool.firstOrNull { it.width == width && it.height == height && live.none { l -> l === it } }
            ?.let { return it }
        return newTarget(width, height).also { pool.add(it) }
    }

    private fun outputSize(width: Int, height: Int): Pair<Int, Int> {
        val rect = SuperResPolicy.imageRect(width, height, viewportWidth, viewportHeight, config.fit, pixelRatio)
        val boxW = rect.width.coerceAtLeast(2f)
        val boxH = rect.height.coerceAtLeast(2f)
        val maxSize = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxSize, 0)
        val limit = min(maxSize[0], 4096).toDouble()
        // Intermediates are bounded independently of the actual SurfaceView buffer dimensions.
        val factor = min(1.0, min(limit / boxW, limit / boxH))
        return Pair(max(2, (boxW * factor).roundToInt() and 1.inv()),
            max(2, (boxH * factor).roundToInt() and 1.inv()))
    }

    private fun render(measure: Boolean) {
        val width = videoWidth
        val height = videoHeight
        if (width <= 0 || height <= 0) return
        val started = SystemClock.elapsedRealtimeNanos()
        val (outW, outH) = outputSize(width, height)
        val rect = SuperResPolicy.imageRect(width, height, viewportWidth, viewportHeight, config.fit, pixelRatio)
        val outputKey = (outW.toLong() shl 32) or outH.toLong()
        if(lastTargetSize != outputKey) {
            clearPool()
            lastTargetSize = outputKey
        }
        val inputKey = (width.toLong() shl 32) or height.toLong()
        if (inputKey != lastInput) {
            lastInput = inputKey
            resetStats()
        }

        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent(input)" }
        collectTimers()
        val query = if(measure && frames >= WARMUP_FRAMES) freeTimers.removeFirstOrNull() ?: 0 else 0
        if (query != 0) GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, query)
        val source = input?.takeIf { it.width == width && it.height == height }
            ?: newTarget(width, height).also { input?.delete(); input = it }
        surfaceTexture.getTransformMatrix(transform)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, source.fbo)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(copyProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(copyProgram, "tex"), 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(copyProgram, "st"), 1, false, transform, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        val textures = HashMap<String, Target>()
        textures["MAIN"] = source
        val native = source
        var ran = 0
        fun size(name: String): Pair<Int, Int>? = when (name) {
            "OUTPUT" -> Pair(outW, outH)
            "NATIVE" -> Pair(native.width, native.height)
            "HOOKED" -> textures["MAIN"]?.let { Pair(it.width, it.height) }
            else -> textures[name]?.let { Pair(it.width, it.height) }
        }
        for (pass in passes) {
            val cond = pass.condition?.let { HookShader.eval(it, ::size) }
            if (pass.condition != null && (cond == null || cond == 0.0)) continue
            val hooked = textures["MAIN"] ?: continue
            val bound = pass.binds.map { name ->
                name to when (name) {
                    "HOOKED" -> hooked
                    "NATIVE" -> native
                    else -> textures[name]
                }
            }
            if (bound.any { it.second == null }) continue
            val w = pass.width?.let { HookShader.eval(it, ::size) }?.roundToInt() ?: hooked.width
            val h = pass.height?.let { HookShader.eval(it, ::size) }?.roundToInt() ?: hooked.height
            if (w <= 0 || h <= 0) continue
            val target = acquire(w, h, textures.values + native)
            val program = program(pass)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.fbo)
            GLES30.glViewport(0, 0, w, h)
            GLES30.glUseProgram(program.id)
            val byName = bound.associate { it.first to it.second!! }
            program.samplers.forEachIndexed { unit, (name, location) ->
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, byName.getValue(name).texture)
                GLES30.glUniform1i(location, unit)
            }
            for ((name, location) in program.sizes) {
                val t = byName.getValue(name)
                GLES30.glUniform2f(location, t.width.toFloat(), t.height.toFloat())
            }
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            textures[pass.target] = target
            ran++
        }

        var mainTexture = textures.getValue("MAIN")
        if (mainTexture.width > outW * 1.5 || mainTexture.height > outH * 1.5) {
            val reduced = acquire(max(1, mainTexture.width / 2), max(1, mainTexture.height / 2),
                listOf(mainTexture, native))
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, reduced.fbo)
            GLES30.glViewport(0, 0, reduced.width, reduced.height)
            GLES30.glUseProgram(downsampleProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mainTexture.texture)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(downsampleProgram, "main_tex"), 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            mainTexture = reduced
            ran++
        }

        if (!ensureWindow(viewportWidth, viewportHeight)) {
            if (query != 0) GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
            if(query != 0) freeTimers.addLast(query)
            return
        }
        check(EGL14.eglMakeCurrent(display, window, window, context)) { "eglMakeCurrent(output)" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        // A negative viewport origin crops cover; contain leaves black bars. Top-left UI coords
        // become bottom-left GL viewport coordinates without modifying source sampling.
        GLES30.glViewport(rect.left.roundToInt(), (viewportHeight - rect.bottom).roundToInt(),
            rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1))
        GLES30.glUseProgram(finalProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mainTexture.texture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(finalProgram, "main_tex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, native.texture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(finalProgram, "native_tex"), 1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(finalProgram, "split"), config.compareSplit)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "OpenGL frame error" }
        // Timestamp every rendered frame without serializing the GPU queue. Drivers lacking
        // timer queries use an explicitly identified synchronized render duration instead.
        if (query != 0) {
            GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
            pendingTimers.addLast(TimerSample(query, frames, frameBudget()))
        }
        if (measure && timerQueries.isEmpty()) GLES30.glFinish()
        EGLExt.eglPresentationTimeANDROID(display, window, surfaceTexture.timestamp)
        check(EGL14.eglSwapBuffers(display, window)) { "eglSwapBuffers" }
        if (measure && timerQueries.isEmpty()) {
            val ms = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
            measured(frames, ms, frameBudget())
        }
        if(measure) frames++
        presentedOutput = true
        presentations++
        publish(ran, outW, outH)
    }

    // endregion

    // region Shaders

    private fun program(pass: HookPass): Program = programs.getOrPut(pass) {
        val names = LinkedHashSet<String>()
        pass.binds.forEach { names.add(it) }
        val source = StringBuilder(
            "#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n" +
                "in vec2 v_pos;\nout vec4 frag_color;\n")
        for (name in names) {
            source.append(
                """
                uniform sampler2D ${name}_raw;
                uniform vec2 ${name}_size;
                #define ${name}_pos v_pos
                #define ${name}_pt (vec2(1.0) / ${name}_size)
                #define ${name}_tex(pos) texture(${name}_raw, pos)
                #define ${name}_texOff(off) texture(${name}_raw, v_pos + ${name}_pt * (off))
                #define ${name}_mul 1.0
                """.trimIndent()).append('\n')
        }
        // mpv exposes the hooked texture under its own name as well.
        if ("HOOKED" in names && "MAIN" !in names) {
            for (suffix in listOf("raw", "size", "pos", "pt", "tex", "texOff", "mul")) {
                source.append("#define MAIN_$suffix HOOKED_$suffix\n")
            }
        }
        source.append(pass.body).append("\nvoid main() { frag_color = hook(); }\n")
        val id = link(VERTEX, source.toString())
        Program(
            id,
            names.map { it to GLES30.glGetUniformLocation(id, "${it}_raw") }.filter { it.second >= 0 },
            names.map { it to GLES30.glGetUniformLocation(id, "${it}_size") }.filter { it.second >= 0 },
        )
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException(log.take(300))
        }
        return shader
    }

    private fun link(vertex: String, fragment: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException(log.take(300))
        }
        return program
    }

    private companion object {
        const val WARMUP_FRAMES = 6L
        const val GL_TIME_ELAPSED_EXT = 0x88BF
        const val GL_GPU_DISJOINT_EXT = 0x8FBB
        const val GL_QUERY_COUNTER_BITS_EXT = 0x8864

        const val VERTEX = """#version 300 es
out vec2 v_pos;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    v_pos = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

        // Row 0 of every intermediate texture is the top of the picture, as in mpv.
        const val COPY_FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES tex;
uniform mat4 st;
in vec2 v_pos;
out vec4 frag_color;
void main() {
    vec2 uv = (st * vec4(v_pos.x, 1.0 - v_pos.y, 0.0, 1.0)).xy;
    frag_color = vec4(texture(tex, uv).rgb, 1.0);
}
"""

        const val BOX_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D main_tex;
in vec2 v_pos;
out vec4 frag_color;
void main() {
    vec2 pt = 0.5 / vec2(textureSize(main_tex, 0));
    frag_color = 0.25 * (texture(main_tex, v_pos + vec2(-pt.x, -pt.y))
        + texture(main_tex, v_pos + vec2(pt.x, -pt.y))
        + texture(main_tex, v_pos + vec2(-pt.x, pt.y))
        + texture(main_tex, v_pos + pt));
}
"""

        const val FINAL_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D main_tex;
uniform sampler2D native_tex;
uniform float split;
in vec2 v_pos;
out vec4 frag_color;
float cubic(float x) {
    x = abs(x);
    return x < 1.0 ? (1.5*x - 2.5)*x*x + 1.0
        : x < 2.0 ? ((-0.5*x + 2.5)*x - 4.0)*x + 2.0 : 0.0;
}
vec4 bicubic(vec2 p) {
    vec2 size = vec2(textureSize(main_tex, 0));
    vec2 at = p * size - 0.5;
    vec2 base = floor(at), f = fract(at);
    vec4 c = vec4(0.0);
    for (int y = -1; y <= 2; y++) {
        for (int x = -1; x <= 2; x++) {
            vec2 o = vec2(float(x), float(y));
            c += texture(main_tex, (base + o + 0.5) / size)
                * cubic(o.x - f.x) * cubic(o.y - f.y);
        }
    }
    return c;
}
void main() {
    vec2 p = vec2(v_pos.x, 1.0 - v_pos.y);
    vec4 c = p.x < split ? texture(native_tex, p) : bicubic(p);
    frag_color = vec4(clamp(c.rgb, 0.0, 1.0), 1.0);
}
"""
    }

    // endregion
}
