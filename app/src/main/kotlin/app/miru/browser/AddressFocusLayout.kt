// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.miru.design.MiruMotion
import app.miru.design.withReducedMotionFade
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Place the real editing capsule independently of Gecko and of the 56 dp toolbar. */
@Composable
internal fun AddressFocusLayout(
    source: Rect?,
    target: Rect,
    reducedMotion: Boolean,
    progress: () -> Float,
    content: @Composable () -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Layout(
        content = content,
        modifier = Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() },
    ) { children, constraints ->
        val bounds = if (reducedMotion || source == null) target else lerp(source, target, progress())
        val width = bounds.width.roundToInt().coerceIn(1, constraints.maxWidth.coerceAtLeast(1))
        val height = bounds.height.roundToInt().coerceIn(1, constraints.maxHeight.coerceAtLeast(1))
        val editor = children.single().measure(Constraints.fixed(width, height))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val offset = bounds.topLeft - origin
            editor.place(offset.x.roundToInt(), offset.y.roundToInt())
        }
    }
}

@Composable
internal fun SuggestionArrival(index: Int, reducedMotion: Boolean, content: @Composable () -> Unit) {
    val opacity = remember { Animatable(0f) }
    val offset = with(LocalDensity.current) { 8.dp.toPx() }
    LaunchedEffect(reducedMotion) {
        if (!reducedMotion && opacity.value == 0f) {
            delay((index.coerceAtMost(MiruMotion.staggeredSuggestions - 1) * MiruMotion.suggestionStagger).toLong())
        }
        withReducedMotionFade(reducedMotion) { opacity.animateTo(1f, tween(MiruMotion.short2, easing = MiruMotion.standardDecelerate)) }
    }
    Box(Modifier.graphicsLayer {
        alpha = opacity.value
        translationY = if (reducedMotion) 0f else (1f - opacity.value) * offset
    }) { content() }
}
