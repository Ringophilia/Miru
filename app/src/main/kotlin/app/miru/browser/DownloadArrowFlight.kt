// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.miru.design.MiruMotion
import kotlin.math.roundToInt

@Composable
internal fun DownloadArrowFlight(
    start: Int,
    arrived: Int,
    visible: Boolean,
    menuBounds: Rect?,
    onArrival: (Int) -> Unit,
) {
    if (start == arrived) return
    val progress = remember(start) { Animatable(0f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    val ready = bounds != IntSize.Zero && menuBounds != null
    LaunchedEffect(start, visible, ready) {
        if (!visible) onArrival(start)
        else if (ready) {
            progress.animateTo(1f, tween(MiruMotion.long2, easing = MiruMotion.emphasized))
            onArrival(start)
        }
    }

    if (visible) Box(Modifier.fillMaxSize().onGloballyPositioned {
        origin = it.positionInRoot()
        bounds = it.size
    }) {
        if (ready && menuBounds != null) Icon(
            Icons.Outlined.Download,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.offset {
                val fraction = progress.value
                val center = Offset(bounds.width / 2f, bounds.height / 2f)
                val target = menuBounds.center - origin
                val arc = 56.dp.toPx() * 4f * fraction * (1f - fraction)
                val point = lerp(center, target, fraction)
                val radius = 12.dp.toPx()
                IntOffset((point.x - radius).roundToInt(), (point.y - radius - arc).roundToInt())
            }.size(24.dp).testTag("download-arrow-flight"),
        )
    }
}
