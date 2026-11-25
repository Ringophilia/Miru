// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.flow.Flow
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Wraps an AC EngineView behind the backend-neutral [BrowserEngineView] contract. */
internal class GeckoEngineViewAdapter(val raw: EngineView) : BrowserEngineView {
    init { BrowserEngineViews.attachTag(this) }

    override fun asView(): View = raw.asView()
    override val verticalScrollPosition: Flow<Float> get() = raw.verticalScrollPosition
    override fun canScrollVerticallyUp(): Boolean = raw.canScrollVerticallyUp()
    override fun canClearSelection(): Boolean = raw.canClearSelection()
    override fun setDynamicToolbarMaxHeight(height: Int) = raw.setDynamicToolbarMaxHeight(height)
    override fun setVerticalClipping(clipping: Int) = raw.setVerticalClipping(clipping)
    override fun onStart() = raw.onStart()
    override fun onResume() = raw.onResume()
    override fun onPause() = raw.onPause()
    override fun onStop() = raw.onStop()
    override fun release() = raw.release()
}

/** Wraps an AMO-signed uBO popup EngineSession without exposing AC EngineSession to `:app`. */
internal class GeckoPopupSession(
    private val createRawView: (Context) -> EngineView,
    private val session: EngineSession,
    private val onClose: () -> Unit,
) : BrowserPopupSession {
    override fun createView(context: Context): BrowserEngineView =
        GeckoEngineViewAdapter(createRawView(context).also { it.render(session) })

    override fun close() = onClose()
}

/**
 * SwipeRefreshFeature must see EngineView as this layout's direct child. Native
 * APZ decides interception; the transparent native indicator supplies only its
 * real pull distance to the Material 3 indicator above the AndroidView.
 */
internal class GeckoRefreshLayout(context: Context, private val engine: EngineView) : SwipeRefreshLayout(context) {
    var onGestureStart: (() -> Unit)? = null
    var onTouchState: ((Boolean) -> Unit)? = null
    var onIndicator: ((Float, Boolean) -> Unit)? = null
    internal var performThresholdFeedback: (Int) -> Unit = { engine.asView().performHapticFeedback(it) }
    private val startOffset = -(40 * resources.displayMetrics.density).roundToInt()
    private val endOffset = (72 * resources.displayMetrics.density).roundToInt()
    private var previousFraction = -1f
    private var previousRefreshing = false
    private var thresholdFeedback = false
    private var movingFinger = false
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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                movingFinger = false
                thresholdFeedback = false
                onGestureStart?.invoke()
                onTouchState?.invoke(true)
            }
            MotionEvent.ACTION_MOVE -> movingFinger = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> movingFinger = false
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
        if (movingFinger && previousFraction < 1f && fraction >= 1f && !thresholdFeedback && !isRefreshing) {
            thresholdFeedback = true
            performThresholdFeedback(
                if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK,
            )
        }
        if (fraction != previousFraction || isRefreshing != previousRefreshing) {
            previousFraction = fraction
            previousRefreshing = isRefreshing
            onIndicator?.invoke(fraction, isRefreshing)
        }
    }

    fun clearCallbacks() { movingFinger = false; onGestureStart = null; onTouchState = null; onIndicator = null }
}

/** Owns the GeckoView surface, SwipeRefreshFeature, and SessionFeature bindings behind [BrowserSurfaceHost]. */
internal class GeckoSurfaceHost(
    context: Context,
    private val controller: BrowserController,
) : BrowserSurfaceHost {
    private val rawEngineView: EngineView = controller.createRawView(context)
    override val engineView: BrowserEngineView = GeckoEngineViewAdapter(rawEngineView)
    private val refreshLayout = GeckoRefreshLayout(context, rawEngineView)
    private val binding: EngineViewBinding = controller.bindRaw(rawEngineView)
    private val refresh: EngineRefreshBinding = controller.swipeRefreshFeature(refreshLayout)

    init { BrowserSurfaceHosts.attachTag(this) }

    override val containerView: View get() = refreshLayout
    override val verticalScrollPosition: Flow<Float> get() = rawEngineView.verticalScrollPosition

    override var onGestureStart: (() -> Unit)?
        get() = refreshLayout.onGestureStart
        set(value) { refreshLayout.onGestureStart = value }

    override var onTouchState: ((Boolean) -> Unit)?
        get() = refreshLayout.onTouchState
        set(value) { refreshLayout.onTouchState = value }

    override var onIndicator: ((fraction: Float, refreshing: Boolean) -> Unit)?
        get() = refreshLayout.onIndicator
        set(value) { refreshLayout.onIndicator = value }

    override var performThresholdFeedback: (Int) -> Unit
        get() = refreshLayout.performThresholdFeedback
        set(value) { refreshLayout.performThresholdFeedback = value }

    override fun configure(
        webContent: Boolean,
        canRefresh: Boolean,
        loading: Boolean,
        maximumToolbarHeight: Int,
        shownToolbarPixels: Float,
    ) {
        // Gecko's baseline is the fully visible toolbar, with negative clipping
        // as it hides (Firefox 158 EngineViewClippingBehavior).
        val clippingHeight = (shownToolbarPixels - maximumToolbarHeight).roundToInt().let {
            if (abs(maximumToolbarHeight + it) <= 2) -maximumToolbarHeight else it
        }
        refreshLayout.configure(
            webContent = webContent,
            canRefresh = canRefresh,
            loading = loading,
            maximumToolbarHeight = maximumToolbarHeight,
            clippingHeight = clippingHeight,
        )
    }

    override fun start() {
        binding.start()
        refresh.start()
    }

    override fun stop() {
        refresh.stop()
        binding.stop()
    }

    override fun release() {
        refresh.release()
        refreshLayout.clearCallbacks()
        rawEngineView.setDynamicToolbarMaxHeight(0)
        rawEngineView.setVerticalClipping(0)
        binding.release()
    }

    override fun captureThumbnail() = controller.captureThumbnail(rawEngineView)
}
