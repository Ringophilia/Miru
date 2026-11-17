// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.toolbar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import app.miru.design.MiruMotion
import app.miru.design.MiruTheme
import app.miru.design.withReducedMotionFade

@Composable
internal fun AdblockIndicator(count: Int, needsReload: Boolean, reducedMotion: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val countDescription = pluralStringResource(R.plurals.toolbar_adblock_count, count, count)
    IconButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.semantics { if (!needsReload || count > 0) stateDescription = countDescription },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (needsReload) {
                Icon(Icons.Outlined.WarningAmber, stringResource(R.string.toolbar_adblock_reload), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            } else {
                val color by animateColorAsState(
                    if (count > 0) MiruTheme.colors.adblock else MaterialTheme.colorScheme.onSurfaceVariant,
                    tween(MiruMotion.short2, easing = MiruMotion.standard), label = "adblockShieldColor",
                )
                Icon(Icons.Outlined.Shield, stringResource(R.string.toolbar_adblock), Modifier.size(18.dp).testTag("toolbar-adblock-shield"), tint = color)
            }
            val label = when {
                count <= 0 -> null
                count > 999 -> "999+"
                else -> count.toString()
            }
            key(needsReload) {
                if (reducedMotion) FadingAdblockCount(label) else RollingAdblockCount(label)
            }
        }
    }
}

@Composable
private fun RollingAdblockCount(label: String?) {
    AnimatedContent(label, transitionSpec = {
        val enter = slideInVertically(tween(MiruMotion.short2, easing = MiruMotion.standardDecelerate)) { it } + fadeIn(tween(MiruMotion.short2))
        val exit = slideOutVertically(tween(MiruMotion.short2, easing = MiruMotion.standardAccelerate)) { -it } + fadeOut(tween(MiruMotion.short2))
        (enter togetherWith exit).using(SizeTransform { _, _ -> tween(MiruMotion.short2) })
    }, label = "blockedCount") { current ->
        if (current != null) AdblockCountLabel(current)
    }
}

@Composable
private fun FadingAdblockCount(label: String?) {
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
        outgoing?.let { AdblockCountLabel(it, Modifier.graphicsLayer { alpha = 1f - opacity.value }) }
        shown?.let { AdblockCountLabel(it, Modifier.graphicsLayer { alpha = opacity.value }) }
    }
}

@Composable
private fun AdblockCountLabel(label: String, modifier: Modifier = Modifier) {
    Text(label, modifier.clearAndSetSemantics { testTag = "toolbar-adblock-count-$label" },
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), maxLines = 1)
}
