// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.toolbar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import app.miru.design.MiruMotion
import app.miru.design.withReducedMotionFade
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun MediaPill(count: Int, enabled: Boolean, reducedMotion: Boolean, onClick: () -> Unit) {
    val showing = count > 0
    val expansion = remember { Animatable(0f) }
    val opacity = remember { Animatable(0f) }
    val animatingOut by remember { derivedStateOf { expansion.value > 0f || opacity.value > 0f } }
    var lastCount by remember { mutableIntStateOf(count) }
    SideEffect { if (showing) lastCount = count }
    LaunchedEffect(showing, reducedMotion) {
        if (!showing && expansion.value == 0f && opacity.value == 0f) return@LaunchedEffect
        val target = if (showing) 1f else 0f
        if (reducedMotion) {
            expansion.snapTo(0f)
            withReducedMotionFade(true) { opacity.animateTo(target, tween(MiruMotion.short2)) }
        } else coroutineScope {
            launch {
                if (showing) expansion.animateTo(target, spring(MiruMotion.bouncyDamping, MiruMotion.bouncyStiffness))
                else expansion.animateTo(target, tween(MiruMotion.short3, easing = MiruMotion.standardAccelerate))
            }
            launch {
                opacity.animateTo(target, tween(if (showing) MiruMotion.medium1 else MiruMotion.short3))
            }
        }
    }
    if (!showing && !animatingOut) return

    val number = if (showing) count else lastCount
    val description = pluralStringResource(R.plurals.toolbar_media_count, number, number)
    // Report the changing width to the toolbar Row so the weighted address capsule yields space.
    Layout(modifier = Modifier.clipToBounds().testTag("toolbar-media-slot"), content = {
        TextButton(
            onClick = onClick, enabled = enabled && showing,
            contentPadding = PaddingValues(horizontal = 6.dp),
            modifier = Modifier.graphicsLayer { alpha = opacity.value }
                .then(if (showing) Modifier.semantics { stateDescription = description } else Modifier.clearAndSetSemantics {}),
        ) {
            Icon(Icons.Outlined.PlayArrow, stringResource(R.string.toolbar_media), Modifier.size(20.dp)
                .graphicsLayer {
                    val scale = if (reducedMotion) 1f else 0.6f + 0.4f * expansion.value.coerceAtLeast(0f)
                    scaleX = scale
                    scaleY = scale
                }.testTag("toolbar-media-icon"))
            AnimatedToolbarCount(number.toString(), reducedMotion) { label, modifier ->
                Text(label, modifier.clearAndSetSemantics { testTag = "toolbar-media-count-$label" }, style = MaterialTheme.typography.labelMedium)
            }
        }
    }) { children, constraints ->
        val button = children.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = if (reducedMotion) button.width else (expansion.value.coerceIn(0f, 1f) * button.width).roundToInt()
        layout(width, button.height) { button.placeRelative(0, 0) }
    }
}
