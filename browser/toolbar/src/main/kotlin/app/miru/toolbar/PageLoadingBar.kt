// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.toolbar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.miru.design.MiruMotion
import app.miru.design.withReducedMotionFade
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** An overlay at the address bar's lower edge, independent of the viewport's layout. */
@Composable
internal fun PageLoadingBar(loading: Boolean, progress: Int, reducedMotion: Boolean, modifier: Modifier) {
    val reportedProgress by rememberUpdatedState(progress.coerceIn(0, 100))
    val reduce by rememberUpdatedState(reducedMotion)
    val value = remember { Animatable(0.1f).apply { updateBounds(0.1f, 1f) } }
    val opacity = remember { Animatable(1f) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(loading) {
        if (loading) {
            value.snapTo(0.1f)
            opacity.snapTo(1f)
            visible = true
            var previous = 0
            snapshotFlow { reportedProgress to reduce }.collectLatest { (current, reduced) ->
                // Gecko resets progress at page start, including a load that supersedes an active one.
                if (current < previous) value.snapTo(0.1f)
                previous = current
                val target = (current / 100f).coerceAtLeast(0.1f)
                if (reduced) value.snapTo(target)
                else value.animateTo(target.coerceAtLeast(value.value), spring(MiruMotion.springDamping, MiruMotion.springStiffness))
            }
        } else if (visible) {
            // The hold begins after the bar has actually reached full width.
            value.snapTo(1f)
            delay(MiruMotion.short2.toLong())
            withReducedMotionFade(reduce) {
                opacity.animateTo(0f, tween(MiruMotion.short3, easing = MiruMotion.standardAccelerate))
            }
            visible = false
        }
    }

    if (visible) {
        val description = stringResource(R.string.toolbar_page_progress)
        LinearProgressIndicator(
            progress = { value.value },
            modifier = modifier.fillMaxWidth().height(2.dp)
                .graphicsLayer { alpha = opacity.value }
                .testTag("toolbar-page-progress")
                .semantics { contentDescription = description },
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}
