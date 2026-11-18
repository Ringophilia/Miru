// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.toolbar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import app.miru.design.MiruMotion
import app.miru.design.withReducedMotionFade

/** The same roll and reduced-motion replacement for both toolbar counters. */
@Composable
internal fun AnimatedToolbarCount(label: String?, reducedMotion: Boolean, content: @Composable (String, Modifier) -> Unit) {
    if (reducedMotion) {
        var outgoing by remember { mutableStateOf<String?>(null) }
        var shown by remember { mutableStateOf(label) }
        val opacity = remember { Animatable(1f) }
        LaunchedEffect(label) {
            if (shown == label) return@LaunchedEffect
            if (opacity.value >= 0.5f) outgoing = shown
            shown = label
            opacity.snapTo(0f)
            withReducedMotionFade(true) { opacity.animateTo(1f, tween(MiruMotion.short2)) }
            outgoing = null
        }
        Box(contentAlignment = Alignment.CenterEnd) {
            outgoing?.let { content(it, Modifier.graphicsLayer { alpha = 1f - opacity.value }) }
            shown?.let { content(it, Modifier.graphicsLayer { alpha = opacity.value }) }
        }
    } else {
        AnimatedContent(label, transitionSpec = {
            val enter = slideInVertically(tween(MiruMotion.short2, easing = MiruMotion.standardDecelerate)) { it } + fadeIn(tween(MiruMotion.short2))
            val exit = slideOutVertically(tween(MiruMotion.short2, easing = MiruMotion.standardAccelerate)) { -it } + fadeOut(tween(MiruMotion.short2))
            (enter togetherWith exit).using(SizeTransform { _, _ -> tween(MiruMotion.short2) })
        }, label = "toolbarCount") { current ->
            if (current != null) content(current, Modifier)
        }
    }
}
