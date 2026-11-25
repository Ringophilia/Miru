// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.miru.browser.engine.BrowserEngineController
import app.miru.browser.engine.BrowserSnapshot
import app.miru.browser.engine.BrowserTab
import app.miru.design.MiruMotion
import app.miru.design.withReducedMotionFade
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class SwipeTabs(val source: String, val previous: String?, val next: String?) {
    fun toward(fraction: Float): String? = when {
        fraction < 0f -> next
        fraction > 0f -> previous
        else -> null
    }
}

internal fun BrowserSnapshot.swipeTabs(): SwipeTabs? {
    val source = selectedTab ?: return null
    val group = tabs.filter { it.isPrivate == source.isPrivate }
    if (group.size < 2) return null
    val current = group.indexOf(source)
    return SwipeTabs(source.id, group.getOrNull(current - 1)?.id, group.getOrNull(current + 1)?.id)
}

@Stable
internal class ToolbarTabSwipe(private val controller: BrowserEngineController, private val scope: CoroutineScope) {
    var tabs by mutableStateOf<SwipeTabs?>(null)
        private set
    var fraction by mutableFloatStateOf(0f)
        private set
    private var settle: Job? = null
    private var committing = false

    fun begin(): Boolean {
        if (committing) return false
        val target = controller.state.value.swipeTabs() ?: return false
        settle?.cancel()
        if (tabs != target) fraction = 0f
        tabs = target
        controller.captureThumbnail()
        return true
    }

    fun drag(delta: Float) { fraction = (fraction + delta).coerceIn(-1f, 1f) }

    fun end(velocity: Float, fast: Boolean, reducedMotion: Boolean, cancelled: Boolean) {
        val gesture = tabs ?: return
        val direction = sign(fraction)
        val next = gesture.toward(fraction).takeIf {
            !cancelled && (abs(fraction) >= 0.5f || (fast && sign(velocity) == direction))
        }
        val target = if (next == null) 0f else direction
        settle?.cancel()
        if (direction == 0f) {
            clear()
            return
        }
        settle = scope.launch {
            val animation = Animatable(fraction)
            withReducedMotionFade(reducedMotion) {
                if (reducedMotion) animation.animateTo(target, tween(MiruMotion.short2)) { fraction = value }
                else animation.animateTo(target, spring(MiruMotion.springDamping, MiruMotion.springStiffness), initialVelocity = velocity) {
                    // An overshoot during return must not flash the other neighbour.
                    fraction = if (direction > 0f) value.coerceIn(0f, 1f) else value.coerceIn(-1f, 0f)
                }
            }
            if (next != null && controller.state.value.swipeTabs() == gesture) {
                committing = true
                controller.selectTab(next)
                withTimeoutOrNull(500) { controller.state.first { it.selectedTabId == next } }
                withFrameNanos { }
            }
            clear()
        }
    }

    fun selectionChanged(selected: String?) {
        if (!committing && tabs?.source != selected) cancel()
    }

    fun cancel() { settle?.cancel(); clear() }

    private fun clear() {
        committing = false
        tabs = null
        fraction = 0f
    }
}

internal fun Modifier.toolbarTabSwipe(state: ToolbarTabSwipe, enabled: Boolean, reducedMotion: Boolean): Modifier =
    if (!enabled) this else pointerInput(state, reducedMotion) {
        val tracker = VelocityTracker()
        val fastThreshold = 1_000.dp.toPx()
        var dragging = false
        detectHorizontalDragGestures(
            onDragStart = {
                tracker.resetTracking()
                dragging = size.width > 0 && state.begin()
            },
            onHorizontalDrag = { change, amount ->
                if (dragging) {
                    tracker.addPosition(change.uptimeMillis, change.position)
                    state.drag(amount / size.width)
                }
            },
            onDragCancel = {
                if (dragging) state.end(0f, false, reducedMotion, cancelled = true)
                dragging = false
            },
            onDragEnd = {
                if (dragging) {
                    val velocity = tracker.calculateVelocity().x
                    state.end(velocity / size.width, abs(velocity) >= fastThreshold, reducedMotion, cancelled = false)
                }
                dragging = false
            },
        )
    }

@Composable
internal fun ToolbarTabPreviews(state: ToolbarTabSwipe, snapshot: BrowserSnapshot, reducedMotion: Boolean, modifier: Modifier) {
    val gesture = state.tabs ?: return
    val source = snapshot.tabs.firstOrNull { it.id == gesture.source } ?: return
    val direction by remember(state) { derivedStateOf { sign(state.fraction) } }
    val next = snapshot.tabs.firstOrNull { it.id == gesture.toward(direction) && it.isPrivate == source.isPrivate }
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier.clipToBounds().background(surface).clearAndSetSemantics { }) {
        TabSwipePreview(source, Modifier.fillMaxSize().graphicsLayer {
            if (!reducedMotion) translationX = state.fraction * size.width * (if (next == null) 0.18f else 1f)
        })
        if (next != null) TabSwipePreview(next, Modifier.fillMaxSize().graphicsLayer {
            if (reducedMotion) alpha = abs(state.fraction)
            else translationX = (state.fraction - sign(state.fraction)) * size.width
        })
    }
}

@Composable
private fun TabSwipePreview(tab: BrowserTab, modifier: Modifier) {
    val bitmap = tab.thumbnail?.takeUnless { it.isRecycled || tab.isPrivate }
    Box(modifier.background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(remember(bitmap) { bitmap.asImageBitmap() }, null,
            Modifier.fillMaxSize().testTag("toolbar-swipe-image"), alignment = Alignment.TopCenter, contentScale = ContentScale.Crop)
        else Text(
            when {
                tab.isPrivate -> stringResource(app.miru.menu.R.string.menu_new_incognito)
                tab.title.isNotBlank() -> tab.title
                else -> stringResource(app.miru.menu.R.string.menu_new_tab)
            },
            Modifier.padding(24.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium,
        )
    }
}
