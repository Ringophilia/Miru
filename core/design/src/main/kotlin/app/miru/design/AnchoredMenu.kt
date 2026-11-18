// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Place in a Box that wraps the button opening the menu. */
@Composable
fun AnchoredMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(expanded, reducedMotion) {
        val target = if (expanded) 1f else 0f
        if (progress.value == target) return@LaunchedEffect
        val duration = when {
            reducedMotion -> MiruMotion.short2
            expanded -> MiruMotion.short3
            else -> MiruMotion.short1
        }
        val easing = if (expanded) MiruMotion.emphasizedDecelerate else MiruMotion.emphasizedAccelerate
        withReducedMotionFade(reducedMotion) {
            progress.animateTo(target, tween(duration, easing = easing))
        }
    }
    if (!expanded && progress.value == 0f) return

    val margin = with(LocalDensity.current) { 48.dp.roundToPx() }
    Popup(
        popupPositionProvider = remember(margin) { AnchoredMenuPosition(margin) },
        onDismissRequest = { if (expanded) onDismissRequest() },
        properties = PopupProperties(focusable = expanded),
    ) {
        Surface(
            modifier = Modifier.graphicsLayer {
                val fraction = progress.value
                val scale = if (reducedMotion) 1f else 0.85f + 0.15f * fraction
                scaleX = scale
                scaleY = scale
                alpha = fraction
                transformOrigin = TransformOrigin(1f, 0f)
            },
            shape = MenuDefaults.shape,
            color = MenuDefaults.containerColor,
            tonalElevation = MenuDefaults.TonalElevation,
            shadowElevation = MenuDefaults.ShadowElevation,
        ) {
            Column(
                modifier.width(IntrinsicSize.Max).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                content = content,
            )
        }
    }
}

internal class AnchoredMenuPosition(private val verticalMargin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxLeft = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val preferredLeft = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val maxTop = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val margin = verticalMargin.coerceAtMost(maxTop / 2)
        val fallbackRange = margin..(maxTop - margin)
        val below = anchorBounds.bottom
        val above = anchorBounds.top - popupContentSize.height
        val top = when {
            below in 0..maxTop -> below
            above in 0..maxTop -> above
            anchorBounds.center.y < windowSize.height / 2 -> below.coerceIn(fallbackRange)
            else -> above.coerceIn(fallbackRange)
        }
        return IntOffset(preferredLeft.coerceIn(0, maxLeft), top)
    }
}
