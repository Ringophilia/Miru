// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.miru.design.MiruMotion
import app.miru.design.withReducedMotionFade
import app.miru.tabs.TabItem
import app.miru.tabs.TabPagePreview
import app.miru.tabs.TabsTray
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private data class TabFlight(val tab: TabItem, val card: Rect)

/** Draws a page's preview between the viewport and the actual grid position. */
@Composable
internal fun TabTrayMotion(
    visible: Boolean,
    tabs: List<TabItem>,
    selected: String?,
    incognito: Boolean,
    reducedMotion: Boolean,
    onIncognitoChanged: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    var mounted by remember { mutableStateOf(visible) }
    var flight by remember { mutableStateOf<TabFlight?>(null) }
    var clicked by remember { mutableStateOf<TabFlight?>(null) }
    val selectedTab = tabs.firstOrNull { it.id == selected && it.incognito == incognito }

    LaunchedEffect(visible, reducedMotion) {
        if (visible) mounted = true
        val target = if (visible) 1f else 0f
        if (progress.value != target) {
            flight = when {
                reducedMotion -> null
                !visible && clicked != null -> clicked
                selectedTab != null -> {
                    val rect = if (visible) withTimeoutOrNull(140) {
                        snapshotFlow { bounds[selectedTab.id] }.filterNotNull().first()
                    } else bounds[selectedTab.id]
                    rect?.let { TabFlight(selectedTab, it) }
                }
                else -> null
            }
            val easing = if (visible) MiruMotion.emphasized else MiruMotion.emphasizedDecelerate
            withReducedMotionFade(reducedMotion) {
                progress.animateTo(target, tween(if (reducedMotion) MiruMotion.short2 else MiruMotion.medium3, easing = easing))
            }
        }
        flight = null
        if (!visible) {
            mounted = false
            clicked = null
            bounds.clear()
        }
    }
    if (!visible && !mounted) return

    val moving = flight?.takeIf { !reducedMotion && it.tab.incognito == incognito }
    Box(Modifier.fillMaxSize()) {
        TabsTray(
            tabs, selected, incognito,
            onIncognitoChanged = {
                flight = null
                onIncognitoChanged(it)
            },
            onSelect = { id ->
                clicked = tabs.firstOrNull { it.id == id }?.let { tab -> bounds[id]?.let { TabFlight(tab, it) } }
                onSelect(id)
            },
            onClose = onClose, onNewTab = onNewTab, onCloseAll = onCloseAll, onDismiss = onDismiss,
            modifier = Modifier.testTag("tabs-tray").graphicsLayer { alpha = progress.value },
            progress = { progress.value },
            transformingTabId = moving?.tab?.id ?: selectedTab?.id?.takeIf { visible && !reducedMotion },
            reducedMotion = reducedMotion,
            onCardBounds = { id, rect -> if (rect == null) bounds.remove(id) else bounds[id] = rect },
        )
        if (moving != null) {
            val preview = tabs.firstOrNull { it.id == moving.tab.id && it.url == moving.tab.url } ?: moving.tab
            MovingTabPreview(preview, moving.card) { progress.value }
        }
    }
}

@Composable
private fun MovingTabPreview(tab: TabItem, card: Rect, progress: () -> Float) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Layout(
        modifier = Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() },
        content = {
            TabPagePreview(tab, Modifier.fillMaxSize().testTag("tabs-container-preview").clearAndSetSemantics { }
                .graphicsLayer {
                    val fraction = progress()
                    shape = RoundedCornerShape(12.dp * fraction)
                    clip = true
                    alpha = ((1f - fraction) / 0.2f).coerceIn(0f, 1f)
                })
        },
    ) { children, constraints ->
        val viewport = Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val bounds = lerp(viewport, card.translate(-origin), progress())
        val child = children.single().measure(Constraints.fixed(bounds.width.roundToInt().coerceAtLeast(1), bounds.height.roundToInt().coerceAtLeast(1)))
        layout(constraints.maxWidth, constraints.maxHeight) { child.place(bounds.left.roundToInt(), bounds.top.roundToInt()) }
    }
}
