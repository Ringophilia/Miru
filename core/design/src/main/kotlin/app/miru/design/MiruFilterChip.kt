// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Spec 6.6 & 7.3 FilterChip: smooth tonal transition and width-expanding check mark. */
@Composable
fun MiruFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    reducedMotion: Boolean = animationsReduced(),
    unselectedLeadingIcon: (@Composable () -> Unit)? = null,
) {
    val colorSpec = tween<androidx.compose.ui.graphics.Color>(MiruMotion.short2, easing = MiruMotion.standard)
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        animationSpec = colorSpec,
        label = "chip-container",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = colorSpec,
        label = "chip-label",
    )
    val checkEnter = if (reducedMotion) {
        fadeIn(tween(MiruMotion.short2, easing = MiruMotion.standardDecelerate))
    } else {
        expandHorizontally(tween(MiruMotion.short2, easing = MiruMotion.standardDecelerate), expandFrom = Alignment.Start) +
            fadeIn(tween(MiruMotion.short2, easing = MiruMotion.standardDecelerate))
    }
    val checkExit = if (reducedMotion) {
        fadeOut(tween(MiruMotion.short2, easing = MiruMotion.standardAccelerate))
    } else {
        shrinkHorizontally(tween(MiruMotion.short2, easing = MiruMotion.standardAccelerate), shrinkTowards = Alignment.Start) +
            fadeOut(tween(MiruMotion.short2, easing = MiruMotion.standardAccelerate))
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = containerColor,
            selectedContainerColor = containerColor,
            labelColor = labelColor,
            selectedLabelColor = labelColor,
            iconColor = labelColor,
            selectedLeadingIconColor = labelColor,
        ),
        leadingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(visible = selected, enter = checkEnter, exit = checkExit) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize).testTag("miru-chip-check"),
                    )
                }
                if (unselectedLeadingIcon != null) {
                    if (selected) Spacer(Modifier.width(4.dp))
                    unselectedLeadingIcon()
                }
            }
        },
    )
}
