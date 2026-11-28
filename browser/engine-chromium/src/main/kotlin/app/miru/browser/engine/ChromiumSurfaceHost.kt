// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import app.miru.chromium.engine.MiruChromiumTab
import app.miru.chromium.engine.MiruChromiumViewport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.chromium.ui.OverscrollAction
import org.chromium.ui.OverscrollActivationStatus
import org.chromium.ui.OverscrollRefreshHandler
import org.chromium.ui.base.ActivityWindowAndroid
import org.chromium.ui.base.IntentRequestTracker
import org.chromium.ui.base.WindowAndroid
import java.util.WeakHashMap
import kotlin.math.roundToInt

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private class ChromiumEngineViewAdapter(
    private val root: View,
    private val viewport: MiruChromiumViewport,
    override val verticalScrollPosition: Flow<Float>,
    private val onStartAction: () -> Unit,
    private val onStopAction: () -> Unit,
    private val onReleaseAction: () -> Unit,
) : BrowserEngineView {
    init { BrowserEngineViews.attachTag(this) }

    override fun asView(): View = root
    override fun canScrollVerticallyUp(): Boolean = viewport.canScrollVertically(-1)
    override fun canClearSelection(): Boolean = false
    override fun setDynamicToolbarMaxHeight(height: Int) = Unit
    override fun setVerticalClipping(clipping: Int) = Unit
    override fun onStart() = onStartAction()
    override fun onResume() = viewport.setSurfaceVisibility(true)
    override fun onPause() = viewport.setSurfaceVisibility(false)
    override fun onStop() = onStopAction()
    override fun release() = onReleaseAction()
}

private class ChromiumSurfaceContainer(context: Context) : FrameLayout(context) {
    var onGestureStart: (() -> Unit)? = null
    var onTouchState: ((Boolean) -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onGestureStart?.invoke()
                onTouchState?.invoke(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onTouchState?.invoke(false)
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * Owns Chromium's compositor [MiruChromiumViewport], [ActivityWindowAndroid], active tab rebinding,
 * and overscroll pull-to-refresh feedback behind Miru's backend-neutral [BrowserSurfaceHost].
 */
internal class ChromiumSurfaceHost(
    private val context: Context,
    private val controller: ChromiumEngineController,
    private val scope: CoroutineScope,
) : BrowserSurfaceHost {
    private val viewport = MiruChromiumViewport(context)
    private val container = ChromiumSurfaceContainer(context).apply {
        addView(viewport, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
    private var windowAndroid: WindowAndroid? = null
    private var tabCollector: Job? = null
    private var currentTab: MiruChromiumTab? = null
    private var pullEnabled = false
    private var pullActive = false
    private var pullDistancePx = 0f
    private var thresholdTriggered = false
    private val triggerDistancePx = (72f * context.resources.displayMetrics.density).coerceAtLeast(1f)

    override var onGestureStart: (() -> Unit)?
        get() = container.onGestureStart
        set(value) { container.onGestureStart = value }

    override var onTouchState: ((Boolean) -> Unit)?
        get() = container.onTouchState
        set(value) { container.onTouchState = value }

    override var onIndicator: ((fraction: Float, refreshing: Boolean) -> Unit)? = null
    override var performThresholdFeedback: (Int) -> Unit = { feedback ->
        container.performHapticFeedback(feedback)
    }

    private val overscrollHandler = object : OverscrollRefreshHandler {
        override fun start(type: Int, initiatingEdge: Int): Boolean {
            if (!pullEnabled || type != OverscrollAction.PULL_TO_REFRESH) return false
            pullActive = true
            pullDistancePx = 0f
            thresholdTriggered = false
            onIndicator?.invoke(0f, false)
            return true
        }

        override fun pull(xDelta: Float, yDelta: Float) {
            if (!pullActive) return
            pullDistancePx = (pullDistancePx + yDelta).coerceAtLeast(0f)
            val fraction = (pullDistancePx / triggerDistancePx).coerceIn(0f, 1.5f)
            if (!thresholdTriggered && fraction >= 1f) {
                thresholdTriggered = true
                performThresholdFeedback(
                    if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                    else HapticFeedbackConstants.CONTEXT_CLICK,
                )
            }
            onIndicator?.invoke(fraction, false)
        }

        override fun release(status: Int) {
            val shouldRefresh = pullActive &&
                pullDistancePx >= triggerDistancePx &&
                (status == OverscrollActivationStatus.ALLOW_ACTIVATION || status == OverscrollActivationStatus.FORCE_ACTIVATION)
            pullActive = false
            pullDistancePx = 0f
            thresholdTriggered = false
            if (shouldRefresh) {
                onIndicator?.invoke(1f, true)
                controller.reload()
            } else {
                onIndicator?.invoke(0f, false)
            }
        }

        override fun reset() {
            pullActive = false
            pullDistancePx = 0f
            thresholdTriggered = false
            onIndicator?.invoke(0f, false)
        }

        override fun setEnabled(enabled: Boolean) {
            pullEnabled = enabled
            if (!enabled) reset()
        }
    }

    override val engineView: BrowserEngineView = ChromiumEngineViewAdapter(
        root = container,
        viewport = viewport,
        verticalScrollPosition = controller.verticalScrollPosition,
        onStartAction = ::start,
        onStopAction = ::stop,
        onReleaseAction = ::release,
    )

    override val containerView: View get() = container
    override val verticalScrollPosition: Flow<Float> get() = controller.verticalScrollPosition

    init { BrowserSurfaceHosts.attachTag(this) }

    override fun configure(
        webContent: Boolean,
        canRefresh: Boolean,
        loading: Boolean,
        maximumToolbarHeight: Int,
        shownToolbarPixels: Float,
    ) {
        viewport.visibility = if (webContent) View.VISIBLE else View.INVISIBLE
        pullEnabled = webContent && canRefresh && !loading
        if (!loading && !pullActive) {
            onIndicator?.invoke(0f, false)
        }
        val topPadding = if (webContent) shownToolbarPixels.roundToInt().coerceAtLeast(0) else 0
        if (viewport.paddingTop != 0 || topPadding < 0) {
            viewport.setPadding(0, 0, 0, 0)
        }
    }

    override fun start() {
        if (windowAndroid == null) {
            val activity = context.findActivity()
            val window = if (activity != null) {
                ActivityWindowAndroid(activity, true, IntentRequestTracker.createFromActivity(activity), null, true)
            } else {
                WindowAndroid(context, false)
            }
            windowAndroid = window
            viewport.initialize(window)
        }
        viewport.setSurfaceVisibility(true)
        if (tabCollector == null) {
            tabCollector = scope.launch(Dispatchers.Main.immediate) {
                controller.activeNativeTab.collect { tab ->
                    currentTab?.let { detachOverscroll(it) }
                    currentTab = tab
                    viewport.attachTab(tab)
                    tab?.let { attachOverscroll(it) }
                }
            }
        }
    }

    override fun stop() {
        tabCollector?.cancel()
        tabCollector = null
        currentTab?.let { detachOverscroll(it) }
        viewport.setSurfaceVisibility(false)
    }

    override fun release() {
        stop()
        currentTab = null
        container.onGestureStart = null
        container.onTouchState = null
        onIndicator = null
        viewport.destroy()
        windowAndroid?.destroy()
        windowAndroid = null
    }

    private fun attachOverscroll(tab: MiruChromiumTab) {
        val proxy = installedOverscrollProxies.getOrPut(tab) {
            TabOverscrollProxy().also { tab.setOverscrollRefreshHandler(it) }
        }
        proxy.delegate = overscrollHandler
    }

    private fun detachOverscroll(tab: MiruChromiumTab) {
        installedOverscrollProxies[tab]?.let { proxy ->
            if (proxy.delegate === overscrollHandler) {
                proxy.delegate = null
            }
        }
        overscrollHandler.reset()
    }

    override fun captureThumbnail() = controller.captureThumbnail()

    private class TabOverscrollProxy : OverscrollRefreshHandler {
        @Volatile var delegate: OverscrollRefreshHandler? = null
        override fun start(type: Int, initiatingEdge: Int): Boolean = delegate?.start(type, initiatingEdge) ?: false
        override fun pull(xDelta: Float, yDelta: Float) { delegate?.pull(xDelta, yDelta) }
        override fun release(status: Int) { delegate?.release(status) }
        override fun reset() { delegate?.reset() }
        override fun setEnabled(enabled: Boolean) { delegate?.setEnabled(enabled) }
    }

    private companion object {
        // Attach at most one C++ OverscrollRefreshHandler per MiruChromiumTab to avoid freeing a
        // raw handler pointer while a speculative/swapped RenderWidgetHostViewAndroid still holds it.
        val installedOverscrollProxies = WeakHashMap<MiruChromiumTab, TabOverscrollProxy>()
    }
}
