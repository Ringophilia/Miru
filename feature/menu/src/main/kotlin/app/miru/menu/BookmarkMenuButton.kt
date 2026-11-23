// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.menu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.miru.design.MiruMotion
import app.miru.design.withReducedMotionFade
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun BookmarkMenuButton(
    bookmarked: Boolean,
    expanded: Boolean,
    reducedMotion: Boolean,
    onBookmark: ((Boolean?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    // Replies belong to the menu that initiated the write, even if it was closed meanwhile.
    val saving = remember(expanded) { mutableStateOf(false) }
    val saved = remember(expanded) { mutableStateOf<Boolean?>(null) }
    val filled = saved.value ?: bookmarked
    val fill = remember { Animatable(if (bookmarked) 1f else 0f) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(expanded, filled, saved.value, reducedMotion) {
        if (!expanded) return@LaunchedEffect
        coroutineScope {
            launch {
                withReducedMotionFade(reducedMotion) {
                    fill.animateTo(if (filled) 1f else 0f, tween(MiruMotion.short2))
                }
            }
            if (saved.value == true && !reducedMotion) {
                scale.animateTo(1.25f, tween(MiruMotion.short1, easing = MiruMotion.emphasizedDecelerate))
                scale.animateTo(1f, spring(dampingRatio = MiruMotion.bouncyDamping, stiffness = MiruMotion.bouncyStiffness))
            } else scale.snapTo(1f)
        }
        if (saved.value != null) onDismiss()
    }

    val label = stringResource(if (filled) R.string.menu_bookmark_remove else R.string.menu_bookmark)
    IconButton(
        onClick = {
            if (expanded && !saving.value) {
                saving.value = true
                onBookmark { result ->
                    saved.value = result
                    if (result == null) saving.value = false
                }
            }
        },
        enabled = expanded && !saving.value,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label },
    ) {
        Box(Modifier.size(24.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }.testTag("bookmark-menu-star")) {
            Icon(Icons.Outlined.StarBorder, null, Modifier.graphicsLayer { alpha = 1f - fill.value })
            Icon(Icons.Filled.Star, null, Modifier.graphicsLayer { alpha = fill.value }, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
