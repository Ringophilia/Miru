// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Hosts one browser message queue and keeps the outgoing message until its fade finishes. */
@Composable
fun MiruSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val current = hostState.currentSnackbarData
    val accessibility = LocalAccessibilityManager.current
    val reducedMotion = animationsReduced()
    val travel = with(LocalDensity.current) { 16.dp.roundToPx() }
    LaunchedEffect(current, accessibility) {
        if (current == null) return@LaunchedEffect
        val duration = when (current.visuals.duration) {
            SnackbarDuration.Short -> 4_000L
            SnackbarDuration.Long -> 6_000L
            SnackbarDuration.Indefinite -> return@LaunchedEffect
        }
        delay(accessibility?.calculateRecommendedTimeoutMillis(
            duration, containsIcons = false, containsText = true,
            containsControls = current.visuals.actionLabel != null || current.visuals.withDismissAction,
        ) ?: duration)
        current.dismiss()
    }

    val state = remember(hostState) { SeekableTransitionState(current) }
    val transition = rememberTransition(state, label = "Snackbar")
    LaunchedEffect(state, current, reducedMotion) {
        withReducedMotionFade(reducedMotion) { state.animateTo(current) }
    }
    transition.AnimatedContent(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter,
        transitionSpec = {
            val duration = if (reducedMotion) MiruMotion.short2 else MiruMotion.short3
            val effects = tween<Float>(duration, easing = MiruMotion.standardDecelerate)
            val enter = if (reducedMotion) fadeIn(effects) else fadeIn(effects) +
                slideInVertically(tween(duration, easing = MiruMotion.standardDecelerate)) { travel }
            (enter togetherWith fadeOut(effects)).using(null)
        },
    ) { data ->
        if (data != null) Snackbar(
            snackbarData = data,
            modifier = Modifier.testTag("miru-snackbar").then(
                if (data == current) Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    dismiss { data.dismiss(); true }
                } else Modifier.clearAndSetSemantics { },
            ),
            shape = RoundedCornerShape(8.dp),
            containerColor = MaterialTheme.colorScheme.inverseSurface,
        )
    }
}
