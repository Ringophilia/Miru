// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.design

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/** Only the toolbar and new-tab page consume these transient colors. */
@Composable
fun rememberBrowserChromeColors(incognito: Boolean, reducedMotion: Boolean): State<ColorScheme> {
    val theme = MaterialTheme.colorScheme
    val surface = rememberPrivacyColor(theme.surface, incognito, reducedMotion)
    val container = rememberPrivacyColor(theme.surfaceContainer, incognito, reducedMotion)
    return remember(theme, surface, container) {
        derivedStateOf {
            val background = surface.value
            val capsule = container.value
            theme.copy(
                surface = background,
                surfaceContainer = capsule,
                onSurface = theme.onSurface.readableDuringTransition(background, theme.surface),
                onSurfaceVariant = theme.onSurfaceVariant.readableDuringTransition(capsule, theme.surfaceContainer),
            )
        }
    }
}

@Composable
private fun rememberPrivacyColor(target: Color, incognito: Boolean, reducedMotion: Boolean): State<Color> {
    val color = remember { Animatable(target) }
    val previousMode = remember { mutableStateOf(incognito) }
    LaunchedEffect(target, incognito, reducedMotion) {
        val switching = previousMode.value != incognito || color.value != color.targetValue
        previousMode.value = incognito
        if (switching) {
            withReducedMotionFade(reducedMotion) {
                color.animateTo(target, tween(if (reducedMotion) MiruMotion.short2 else MiruMotion.medium1))
            }
        } else color.snapTo(target)
    }
    return color.asState()
}

private fun Color.readableDuringTransition(background: Color, settled: Color): Color {
    if (background == settled) return this
    val ink = luminance()
    val paper = background.luminance()
    if ((max(ink, paper) + 0.05f) / (min(ink, paper) + 0.05f) >= 4.5f) return this
    return if (paper > 0.179f) Color.Black else Color.White
}
