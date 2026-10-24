// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import app.miru.browser.engine.BrowserController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.EngineView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The toolbar moves over a stable-size Gecko surface. Gecko's compositor scroll
 * position drives its offset; Gecko's APZ result controls pull-to-refresh through
 * the actual AC SwipeRefreshFeature, including content-handled touch gestures.
 *
 * Keep this composed below route/NTP/editor overlays. [webContent] disables the
 * native surface and gestures without detaching/recreating its EngineView.
 * [overlay] receives the visible content rectangle, excluding the current toolbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserViewport(
    controller: BrowserController,
    webContent: Boolean,
    toolbarAtBottom: Boolean,
    toolbarVisible: Boolean,
    modifier: Modifier = Modifier,
    forceToolbarVisible: Boolean = false,
    refreshEnabled: Boolean = true,
    reducedMotion: Boolean = false,
    toolbar: @Composable () -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    val snapshot by controller.state.collectAsStateWithLifecycle()
    val tab = snapshot.selectedTab
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val motion = remember(controller, scope) { ViewportToolbarMotion(scope) }
    val indicator = remember { GeckoRefreshIndicatorState() }
    var refreshing by remember { mutableStateOf(false) }
    var toolbarHeight by remember { mutableIntStateOf(0) }
    val forced = forceToolbarVisible || !webContent || tab?.loading == true
    val activeHeight = if (toolbarVisible) toolbarHeight else 0

    SideEffect { motion.configure(activeHeight.toFloat(), forced, reducedMotion) }
    DisposableEffect(motion) { onDispose { motion.release() } }
    LaunchedEffect(tab?.id, tab?.showToolbarAsExpanded) {
        if (tab?.showToolbarAsExpanded == true) {
            motion.show()
            controller.consumeToolbarExpansion()
        }
    }

    // Creation waits for Gecko initialization, then survives NTP, editing and route overlays.
    val engineView = if (snapshot.ready) remember(controller, context) { controller.createView(context) } else null
    val refreshView = engineView?.let { engine ->
        remember(controller, engine, context) {
            GeckoRefreshLayout(context, engine).apply {
                onGestureStart = { controller.contextMenus.beginTouchGesture() }
                onTouchState = { touching ->
                    if (engine.canClearSelection()) motion.show()
                    motion.touch(touching)
                }
                onIndicator = { fraction, running -> indicator.fraction = fraction; refreshing = running }
            }
        }
    }
    if (engineView != null && refreshView != null) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(controller, engineView, refreshView, lifecycle) {
            val binding = controller.bind(engineView)
            val refresh = controller.swipeRefreshFeature(refreshView)
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> { binding.start(); refresh.start() }
                    Lifecycle.Event.ON_STOP -> { refresh.stop(); binding.stop() }
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { binding.start(); refresh.start() }
            onDispose {
                lifecycle.removeObserver(observer)
                refresh.release()
                refreshView.clearCallbacks()
                engineView.setDynamicToolbarMaxHeight(0)
                engineView.setVerticalClipping(0)
                binding.release()
            }
        }
        LaunchedEffect(engineView, tab?.id, tab?.url) {
            motion.reset()
            engineView.verticalScrollPosition.collect { position -> motion.scroll(position) }
        }
    }

    val shownPixels = (activeHeight - motion.hidden).coerceIn(0f, activeHeight.toFloat())
    val shownHeight = with(density) { shownPixels.toDp() }
    Box(modifier.fillMaxSize().clipToBounds().testTag("browser-viewport")) {
        if (refreshView != null) AndroidView(
            factory = { refreshView },
            modifier = Modifier.fillMaxSize().offset { IntOffset(0, if (toolbarAtBottom) 0 else shownPixels.roundToInt()) },
            update = { native ->
                native.configure(
                    webContent = webContent,
                    canRefresh = refreshEnabled && webContent && !forceToolbarVisible,
                    loading = tab?.loading == true,
                    maximumToolbarHeight = activeHeight,
                    // Gecko's baseline is the fully visible toolbar, with negative clipping
                    // as it hides (Firefox 158 EngineViewClippingBehavior). A positive
                    // visible-height value leaves APZ believing it can still scroll to top.
                    clippingHeight = (shownPixels - activeHeight).roundToInt().let {
                        if(abs(activeHeight + it) <= 2) -activeHeight else it
                    },
                )
            },
        )
        Box(
            Modifier.fillMaxSize().padding(
                top = if (toolbarAtBottom) 0.dp else shownHeight,
                bottom = if (toolbarAtBottom) shownHeight else 0.dp,
            ),
            content = overlay,
        )
        if (toolbarVisible) Box(
            Modifier.align(if (toolbarAtBottom) Alignment.BottomCenter else Alignment.TopCenter)
                .offset { IntOffset(0, (if (toolbarAtBottom) motion.hidden else -motion.hidden).roundToInt()) }
                .onSizeChanged { toolbarHeight = it.height }
                .testTag("browser-viewport-toolbar"),
        ) { toolbar() }
        if (webContent) PullToRefreshDefaults.Indicator(
            state = indicator,
            isRefreshing = refreshing,
            modifier = Modifier.align(Alignment.TopCenter)
                .padding(top = if (toolbarAtBottom) 0.dp else shownHeight),
        )
    }
}

/** Reads Gecko compositor positions, never Compose scroll or a JavaScript scroll proxy. */
@Stable
private class ViewportToolbarMotion(private val scope: CoroutineScope) {
    var hidden by mutableFloatStateOf(0f)
        private set
    private var height = 0f
    private var forced = true
    private var reducedMotion = false
    private var touching = false
    private var previousPosition: Float? = null
    private var previousTime = 0L
    private var velocity = 0f
    private var settle: Job? = null

    fun configure(height: Float, forced: Boolean, reducedMotion: Boolean) {
        val changed = this.height != height || this.forced != forced || this.reducedMotion != reducedMotion
        this.height = height
        this.forced = forced
        this.reducedMotion = reducedMotion
        if (forced || height == 0f) show()
        else if (changed) { hidden = hidden.coerceIn(0f, height); scheduleSettle() }
    }

    fun reset() { previousPosition = null; previousTime = 0; velocity = 0f; show() }
    fun show() { settle?.cancel(); hidden = 0f; velocity = 0f }
    fun touch(active: Boolean) {
        touching = active
        if (active) settle?.cancel() else scheduleSettle()
    }

    fun scroll(position: Float) {
        if (!position.isFinite()) return
        val now = SystemClock.uptimeMillis()
        val oldPosition = previousPosition
        val elapsed = (now - previousTime).coerceAtLeast(1)
        previousPosition = position
        previousTime = now
        if (forced || position <= 1f) { show(); return }
        if (oldPosition == null) return
        val delta = position - oldPosition
        if (abs(delta) < 0.01f) return
        settle?.cancel()
        val oldHidden = hidden
        hidden = (hidden + delta).coerceIn(0f, height)
        velocity = ((hidden - oldHidden) * 1000f / elapsed).coerceIn(-6000f, 6000f)
        if (!touching) scheduleSettle()
    }

    private fun scheduleSettle() {
        settle?.cancel()
        if (touching || forced || hidden == 0f || hidden == height) return
        settle = scope.launch {
            // APZ may keep delivering fling positions after ACTION_UP. Every real
            // position cancels this wait, so the spring starts after scrolling rests.
            delay(100)
            val target = if (hidden > height / 2f) height else 0f
            if (reducedMotion) hidden = target
            else Animatable(hidden).animateTo(target, spring(dampingRatio = 0.8f, stiffness = 380f), initialVelocity = velocity) {
                hidden = value.coerceIn(0f, height)
            }
        }
    }
    fun release() { settle?.cancel() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Stable
private class GeckoRefreshIndicatorState : PullToRefreshState {
    var fraction by mutableFloatStateOf(0f)
    override val distanceFraction: Float get() = fraction
    override val isAnimating: Boolean get() = false
    override suspend fun animateToThreshold() { fraction = 1f }
    override suspend fun animateToHidden() { fraction = 0f }
    override suspend fun snapTo(targetValue: Float) { fraction = targetValue.coerceAtLeast(0f) }
}

/**
 * SwipeRefreshFeature must see EngineView as this layout's direct child. Native
 * APZ decides interception; the transparent native indicator supplies only its
 * real pull distance to the Material 3 indicator above the AndroidView.
 */
private class GeckoRefreshLayout(context: Context, private val engine: EngineView) : SwipeRefreshLayout(context) {
    var onGestureStart: (() -> Unit)? = null
    var onTouchState: ((Boolean) -> Unit)? = null
    var onIndicator: ((Float, Boolean) -> Unit)? = null
    private val startOffset = -(40 * resources.displayMetrics.density).roundToInt()
    private val endOffset = (72 * resources.displayMetrics.density).roundToInt()
    private var previousFraction = -1f
    private var previousRefreshing = false
    private var thresholdFeedback = false
    private var maximumHeight = -1
    private var clipping = -1

    init {
        addView(engine.asView(), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setProgressViewOffset(false, startOffset, endOffset)
        setDistanceToTriggerSync(endOffset)
        setColorSchemeColors(Color.TRANSPARENT)
        setProgressBackgroundColorSchemeColor(Color.TRANSPARENT)
        // AC emits CONFIRM on refresh. This layout's feedback is disabled so only
        // the threshold feedback below, performed by the EngineView, is emitted.
        isHapticFeedbackEnabled = false
        setWillNotDraw(false)
    }

    fun configure(webContent: Boolean, canRefresh: Boolean, loading: Boolean, maximumToolbarHeight: Int, clippingHeight: Int) {
        engine.asView().visibility = if (webContent) View.VISIBLE else View.INVISIBLE
        isEnabled = canRefresh && (!loading || isRefreshing)
        if (maximumHeight != maximumToolbarHeight) {
            engine.setDynamicToolbarMaxHeight(maximumToolbarHeight)
            maximumHeight = maximumToolbarHeight
            clipping = -1 // Gecko resets clipping when the maximum changes.
        }
        if (clipping != clippingHeight) {
            engine.setVerticalClipping(clippingHeight)
            clipping = clippingHeight
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onGestureStart?.invoke()
            thresholdFeedback = false
            onTouchState?.invoke(true)
        }
        val handled = super.dispatchTouchEvent(event)
        reportIndicator()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) onTouchState?.invoke(false)
        return handled
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        reportIndicator()
    }

    private fun reportIndicator() {
        val circle = (0 until childCount).map { getChildAt(it) }.firstOrNull { it !== engine.asView() }
        val fraction = if (circle?.visibility == View.VISIBLE && isEnabled) {
            ((circle.top - startOffset).toFloat() / (endOffset - startOffset)).coerceAtLeast(0f)
        } else 0f
        if (fraction >= 1f && !thresholdFeedback && !isRefreshing) {
            thresholdFeedback = true
            engine.asView().performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK,
            )
        }
        if (fraction != previousFraction || isRefreshing != previousRefreshing) {
            previousFraction = fraction
            previousRefreshing = isRefreshing
            onIndicator?.invoke(fraction, isRefreshing)
        }
    }

    fun clearCallbacks() { onGestureStart = null; onTouchState = null; onIndicator = null }
}
