// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.superres

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** Immutable runtime input. Safety decisions never write back into these user preferences. */
data class SuperResViewConfig(
    val mode: SuperResMode = SuperResMode.OFF,
    val tier: SuperResTier = SuperResTier.BALANCED,
    val title: String = "",
    val pageUrl: String = "",
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val pixelWidthHeightRatio: Float = 1f,
    val frameRate: Float = 0f,
    val hdr: Boolean = false,
    val protectedContent: Boolean = false,
    val speed: Float = 1f,
    val pip: Boolean = false,
    val fit: SuperResFit = SuperResFit.CONTAIN,
    val compareSplit: Float = 0f,
    val deviceCeiling: SuperResTier? = null,
)

data class SuperResViewState(
    val decision: SuperResDecision = SuperResDecision(SuperResMode.OFF, SuperResTier.BALANCED, bypass = SuperResBypass.OFF),
    val rendering: SuperResStatus = SuperResStatus(),
    val imageRect: SuperResImageRect = SuperResImageRect(0f, 0f, 0f, 0f),
    val releasing: Boolean = false,
    val thermalStatus: Int = 0,
    val error: String? = null,
)

/**
 * One display Surface, with explicit MediaCodec ↔ EGL ownership handoff.
 *
 * All public methods and callbacks run on main. The caller binds the Surface callback to its
 * current player, and calls [rebindDecoderSurface] when replacing that player. This view knows
 * neither Media3 nor media URLs beyond the optional animation-classification metadata.
 * [dispose] is permanent and belongs in Compose AndroidView.onRelease.
 */
class RoutedVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val rendererFactory: (AssetManager, (Surface) -> Unit, (String) -> Unit) -> SuperResRenderer =
        { assets, input, error -> SuperResRenderer(assets, input, error) },
) : FrameLayout(context, attrs), SurfaceHolder.Callback {
    val surfaceView = SurfaceView(context)
    var onError: ((String) -> Unit)? = null
    var onProtection: ((SuperResProtection) -> Unit)? = null
    var onStatusChanged: ((SuperResViewState) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val power = context.getSystemService(PowerManager::class.java)
    private var config = SuperResViewConfig()
    private var decoderListener: ((Surface?) -> Unit)? = null
    private var decoderSurface: Surface? = null
    private var displaySurface: Surface? = null
    private var displayWidth = 0
    private var displayHeight = 0
    private var surfaceEpoch = 0L
    private var routeEpoch = 0L
    private var renderer: SuperResRenderer? = null
    private var releasing = false
    private var disposed = false
    private var attached = false
    private var glLayout = false
    private var guard = SuperResGuard()
    private var sessionCeiling: SuperResTier? = null
    private var thermalDisabled = false
    private var performanceDisabled = false
    private var criticalReported = false
    private var failure: String? = null
    private var errorReported = false
    private var thermalStatus = 0
    private var lastRendering = SuperResStatus()
    private var published = SuperResViewState()

    private val poll = object : Runnable {
        override fun run() {
            if(disposed || !attached) return
            thermalStatus = readThermalStatus()
            if(thermalStatus < 3) { thermalDisabled = false; criticalReported = false }
            if(thermalStatus >= 4 && !criticalReported && config.mode != SuperResMode.OFF) {
                criticalReported = true
                thermalDisabled = true
                onProtection?.invoke(SuperResProtection.Disable(SuperResBypass.THERMAL))
            }
            val current = renderer
            if(current != null) {
                lastRendering = current.status()
                if(lastRendering.error != null) fail(lastRendering.error!!)
                else if(lastRendering.active && thermalStatus < 4) applyProtection(guard.evaluate(lastRendering, decision().tier, thermalStatus))
            }
            reconcile()
            publish()
            main.postDelayed(this, 300L)
        }
    }

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        clipChildren = true
        clipToPadding = true
        addView(surfaceView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        surfaceView.holder.addCallback(this)
    }

    /** Bind once, or replace the listener when replacing the owning player. */
    fun setDecoderSurfaceListener(listener: ((Surface?) -> Unit)?) {
        checkMain()
        if(decoderListener === listener) return
        // The former player must relinquish ownership before another player receives a Surface.
        invokeDecoder(decoderListener, null)
        decoderListener = listener
        if(!disposed) rebindDecoderSurface()
    }

    /** Replay the current route to a newly created player, without rebuilding its media source. */
    fun rebindDecoderSurface() {
        checkMain()
        invokeDecoder(decoderListener, decoderSurface?.takeIf { !disposed && it.isValid })
    }

    fun configure(next: SuperResViewConfig) {
        checkMain()
        if(disposed || config == next) return
        val newContent = next.pageUrl != config.pageUrl || next.title != config.title
        val explicitlyReenabled = config.mode == SuperResMode.OFF && next.mode != SuperResMode.OFF
        config = next
        if(newContent || explicitlyReenabled) {
            failure = null
            errorReported = false
            performanceDisabled = false
            sessionCeiling = null
            guard = SuperResGuard()
        }
        thermalStatus = readThermalStatus()
        reconcile()
        requestLayout()
        publish()
    }

    fun setCompare(split: Float) {
        checkMain()
        if(disposed) return
        config = config.copy(compareSplit = split.coerceIn(0f, 1f))
        renderer?.setCompare(config.compareSplit)
        publish()
    }

    fun status(): SuperResStatus = renderer?.status() ?: lastRendering.copy(active = false,
        inputWidth = config.sourceWidth, inputHeight = config.sourceHeight,
        viewportWidth = width, viewportHeight = height, imageRect = imageRect(), error = failure)
    fun state(): SuperResViewState = published
    fun imageRect(): SuperResImageRect = SuperResPolicy.imageRect(config.sourceWidth, config.sourceHeight,
        width, height, config.fit, config.pixelWidthHeightRatio)

    fun decision(): SuperResDecision {
        val rect = imageRect()
        return SuperResPolicy.decide(config.mode, config.tier, config.sourceWidth, config.sourceHeight,
            rect.width, rect.height, config.title, config.pageUrl, config.speed, config.hdr,
            config.protectedContent, config.pip, if(thermalDisabled) maxOf(3, thermalStatus) else thermalStatus,
            config.deviceCeiling, sessionCeiling, failure != null || performanceDisabled).let {
                if(thermalDisabled && config.mode != SuperResMode.OFF) it.copy(shaders = emptyList(), bypass = SuperResBypass.THERMAL) else it
            }
    }

    /** Disconnect the codec immediately; release GL asynchronously before relinquishing the view. */
    fun dispose() {
        checkMain()
        if(disposed) return
        disposed = true
        routeEpoch++
        surfaceEpoch++
        main.removeCallbacks(poll)
        surfaceView.holder.removeCallback(this)
        deliver(null, force = true)
        displaySurface = null
        stopRenderer()
        decoderListener = null
        onStatusChanged = null
        onProtection = null
        onError = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if(disposed) return
        attached = true
        thermalStatus = readThermalStatus()
        main.removeCallbacks(poll)
        main.post(poll)
        reconcile()
    }

    override fun onDetachedFromWindow() {
        attached = false
        main.removeCallbacks(poll)
        deliver(null, force = true)
        stopRenderer()
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if(disposed) return
        surfaceEpoch++
        displaySurface = holder.surface.takeIf { it.isValid }
        displayWidth = holder.surfaceFrame.width()
        displayHeight = holder.surfaceFrame.height()
        reconcile()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if(disposed) return
        displaySurface = holder.surface.takeIf { it.isValid }
        displayWidth = width
        displayHeight = height
        reconcile()
        publish()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceEpoch++
        routeEpoch++
        displaySurface = null
        displayWidth = 0
        displayHeight = 0
        deliver(null, force = true)
        stopRenderer()
        publish()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if(!disposed) { reconcile(); publish() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val rect = childRect(measuredWidth, measuredHeight)
        surfaceView.measure(MeasureSpec.makeMeasureSpec(rect.width.roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(rect.height.roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rect = childRect(right - left, bottom - top)
        surfaceView.layout(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
    }

    private fun childRect(w: Int, h: Int): SuperResImageRect {
        if(glLayout) return SuperResImageRect(0f, 0f, w.toFloat(), h.toFloat())
        return SuperResPolicy.imageRect(config.sourceWidth, config.sourceHeight, w, h,
            config.fit, config.pixelWidthHeightRatio).takeIf { it.width > 0 && it.height > 0 }
            ?: SuperResImageRect(0f, 0f, w.toFloat(), h.toFloat())
    }

    private fun reconcile() {
        if(disposed || !attached || displaySurface?.isValid != true || displayWidth <= 0 || displayHeight <= 0) {
            if(renderer != null) stopRenderer()
            return
        }
        if(releasing) return // EGL still owns the previous window; never attach a second producer.
        val wanted = decision()
        if(!wanted.enabled) {
            if(renderer != null) stopRenderer() else {
                updateLayout()
                deliver(displaySurface)
            }
            return
        }
        if(renderer == null) startRenderer()
        renderer?.let { active ->
            active.setVideoSize(config.sourceWidth, config.sourceHeight, config.pixelWidthHeightRatio)
            active.configure(SuperResConfig(wanted.shaders, config.fit, config.compareSplit, config.speed, config.frameRate))
            displaySurface?.let { active.attachOutput(it, displayWidth, displayHeight) }
        }
    }

    private fun startRenderer() {
        val epoch = ++routeEpoch
        val expectedSurface = surfaceEpoch
        deliver(null, force = true)
        val created = rendererFactory(context.assets, { surface ->
            if(!disposed && attached && epoch == routeEpoch && expectedSurface == surfaceEpoch && renderer != null &&
                displaySurface?.isValid == true && decision().enabled) {
                deliver(surface, force = true)
                publish()
            }
        }, { message ->
            if(!disposed && epoch == routeEpoch) fail(message)
        })
        renderer = created
        updateLayout()
    }

    private fun stopRenderer() {
        val previous = renderer ?: return
        lastRendering = previous.status().copy(active = false)
        renderer = null
        routeEpoch++
        deliver(null, force = true)
        releasing = true
        updateLayout()
        previous.release {
            // No renderer can start while releasing, so this completion owns the handoff.
            releasing = false
            updateLayout()
            if(!disposed) { reconcile(); publish() }
        }
    }

    private fun updateLayout() {
        val next = renderer != null || releasing
        if(glLayout != next) { glLayout = next; requestLayout() }
    }

    private fun applyProtection(action: SuperResProtection) {
        when(action) {
            SuperResProtection.None -> return
            is SuperResProtection.Reduce -> sessionCeiling = action.tier
            is SuperResProtection.Disable -> if(action.reason == SuperResBypass.THERMAL) thermalDisabled = true else performanceDisabled = true
        }
        onProtection?.invoke(action)
    }

    private fun fail(message: String) {
        failure = message
        stopRenderer()
        notifyErrorOnce(message)
        // No-GL initialization errors and failures during teardown still need direct output.
        if(!releasing) reconcile()
        publish()
    }

    private fun deliver(surface: Surface?, force: Boolean = false) {
        val valid = surface?.takeIf { it.isValid }
        if(!force && decoderSurface === valid) return
        decoderSurface = valid
        invokeDecoder(decoderListener, valid)
    }

    private fun invokeDecoder(listener: ((Surface?) -> Unit)?, surface: Surface?) {
        runCatching { listener?.invoke(surface) }.onFailure { notifyErrorOnce("视频输出连接失败：${it.message}") }
    }

    private fun notifyErrorOnce(message: String) {
        if(errorReported || disposed) return
        errorReported = true
        onError?.invoke(message)
    }

    private fun publish() {
        if(disposed) return
        val next = SuperResViewState(decision(), status(), imageRect(), releasing, thermalStatus, failure)
        if(next != published) { published = next; onStatusChanged?.invoke(next) }
    }

    private fun readThermalStatus(): Int = if(Build.VERSION.SDK_INT >= 29)
        runCatching { power?.currentThermalStatus ?: 0 }.getOrDefault(0) else 0

    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) { "RoutedVideoView requires main thread" } }
}
