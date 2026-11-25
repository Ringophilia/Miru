// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

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
import app.miru.browser.engine.BrowserEngineController
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Engine-agnostic viewport host. The toolbar moves over a stable-size engine surface;
 * compositor scroll position and overscroll pull-to-refresh flow strictly through
 * [app.miru.browser.engine.BrowserSurfaceHost].
 *
 * Keep this composed below route/NTP/editor overlays. [webContent] disables the
 * native surface and gestures without detaching/recreating the host view.
 * [overlay] receives the visible content rectangle, excluding the current toolbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserViewport(
    controller: BrowserEngineController,
    webContent: Boolean,
    toolbarAtBottom: Boolean,
    toolbarVisible: Boolean,
    modifier: Modifier = Modifier,
    forceToolbarVisible: Boolean = false,
    refreshEnabled: Boolean = true,
    reducedMotion: Boolean = false,
    tabSwipeEnabled: Boolean = false,
    toolbar: @Composable () -> Unit,
    foreground: @Composable BoxScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit,
) {
    val snapshot by controller.state.collectAsStateWithLifecycle()
    val tab = snapshot.selectedTab
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val motion = remember(controller, scope) { ViewportToolbarMotion(scope) }
    val tabSwipe = remember(controller, scope) { ToolbarTabSwipe(controller, scope) }
    val canSwipe = tabSwipeEnabled && toolbarVisible && snapshot.swipeTabs() != null
    val indicator = remember { ViewportRefreshIndicatorState() }
    var refreshing by remember { mutableStateOf(false) }
    var toolbarHeight by remember { mutableIntStateOf(0) }
    val forced = forceToolbarVisible || !webContent || tab?.loading == true
    val activeHeight = if (toolbarVisible) toolbarHeight else 0

    SideEffect { motion.configure(activeHeight.toFloat(), forced, reducedMotion) }
    DisposableEffect(motion) { onDispose { motion.release() } }
    LaunchedEffect(canSwipe, reducedMotion) { tabSwipe.cancel() }
    LaunchedEffect(tab?.id) { tabSwipe.selectionChanged(tab?.id) }
    LaunchedEffect(tab?.id, tab?.showToolbarAsExpanded) {
        if (tab?.showToolbarAsExpanded == true) {
            motion.show()
            controller.consumeToolbarExpansion()
        }
    }

    // Creation waits for engine initialization, then survives NTP, editing and route overlays.
    val surfaceHost = if (snapshot.ready) {
        remember(controller, context) {
            controller.createSurfaceHost(context).apply {
                onGestureStart = { controller.contextMenus.beginTouchGesture() }
                onTouchState = { touching ->
                    if (canClearSelection()) motion.show()
                    motion.touch(touching)
                }
                onIndicator = { fraction, running -> indicator.fraction = fraction; refreshing = running }
            }
        }
    } else null

    if (surfaceHost != null) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(controller, surfaceHost, lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> surfaceHost.start()
                    Lifecycle.Event.ON_STOP -> surfaceHost.stop()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) surfaceHost.start()
            onDispose {
                lifecycle.removeObserver(observer)
                surfaceHost.release()
            }
        }
        LaunchedEffect(surfaceHost, tab?.id, tab?.url) {
            motion.reset()
            surfaceHost.verticalScrollPosition.collect { position -> motion.scroll(position) }
        }
        LaunchedEffect(surfaceHost, tab?.id, tab?.url, tab?.loading, webContent, tabSwipeEnabled) {
            if (tabSwipeEnabled && webContent && tab?.isPrivate == false && !tab.loading) {
                delay(180) // Let the newly bound document present a frame before warming the preview.
                surfaceHost.captureThumbnail()
            }
        }
    }

    val shownPixels = (activeHeight - motion.hidden).coerceIn(0f, activeHeight.toFloat())
    val shownHeight = with(density) { shownPixels.toDp() }
    Box(modifier.fillMaxSize().clipToBounds().testTag("browser-viewport")) {
        if (surfaceHost != null) AndroidView(
            factory = { surfaceHost.containerView },
            modifier = Modifier.fillMaxSize().offset { IntOffset(0, if (toolbarAtBottom) 0 else shownPixels.roundToInt()) },
            update = {
                surfaceHost.configure(
                    webContent = webContent,
                    canRefresh = refreshEnabled && webContent && !forceToolbarVisible,
                    loading = tab?.loading == true,
                    maximumToolbarHeight = activeHeight,
                    shownToolbarPixels = shownPixels,
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
        if (canSwipe) ToolbarTabPreviews(
            tabSwipe, snapshot, reducedMotion,
            Modifier.fillMaxSize().padding(
                top = if (toolbarAtBottom) 0.dp else shownHeight,
                bottom = if (toolbarAtBottom) shownHeight else 0.dp,
            ),
        )
        if (toolbarVisible) Box(
            Modifier.align(if (toolbarAtBottom) Alignment.BottomCenter else Alignment.TopCenter)
                .offset { IntOffset(0, (if (toolbarAtBottom) motion.hidden else -motion.hidden).roundToInt()) }
                .onSizeChanged { toolbarHeight = it.height }
                .toolbarTabSwipe(tabSwipe, canSwipe, reducedMotion)
                .testTag("browser-viewport-toolbar"),
        ) { toolbar() }
        if (webContent) PullToRefreshDefaults.Indicator(
            state = indicator,
            isRefreshing = refreshing,
            modifier = Modifier.align(Alignment.TopCenter)
                .padding(top = if (toolbarAtBottom) 0.dp else shownHeight)
                .testTag("browser-pull-refresh"),
        )
        foreground()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Stable
private class ViewportRefreshIndicatorState : PullToRefreshState {
    var fraction by mutableFloatStateOf(0f)
    override val distanceFraction: Float get() = fraction
    override val isAnimating: Boolean get() = false
    override suspend fun animateToThreshold() { fraction = 1f }
    override suspend fun animateToHidden() { fraction = 0f }
    override suspend fun snapTo(targetValue: Float) { fraction = targetValue.coerceAtLeast(0f) }
}
