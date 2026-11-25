// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.miru.design.MiruMotion
import app.miru.design.animationsReduced

/** Translucent right-side settings/track/superres panel aligned with Spec 7.4. */
@Composable
internal fun PlayerSidePanel(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    reducedMotion: Boolean = animationsReduced(),
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (visible) BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(if (reducedMotion) MiruMotion.short2 else 200)),
            exit = fadeOut(tween(MiruMotion.short2)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = if (reducedMotion) {
                fadeIn(tween(MiruMotion.short2))
            } else {
                slideInHorizontally(tween(320, easing = MiruMotion.emphasizedDecelerate)) { it } +
                    fadeIn(tween(200))
            },
            exit = if (reducedMotion) {
                fadeOut(tween(MiruMotion.short2))
            } else {
                slideOutHorizontally(tween(200, easing = MiruMotion.emphasizedAccelerate)) { it } +
                    fadeOut(tween(MiruMotion.short2))
            },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth(0.85f)
                    .fillMaxHeight()
                    .testTag("player_side_panel")
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { if (!it.isConsumed) it.consume() }
                            }
                        }
                    },
                color = Color(0xEB14141B),
                contentColor = Color.White,
            ) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0x1FFFFFFF)))
                    Column(Modifier.weight(1f).fillMaxHeight().safeDrawingPadding().imePadding()) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            modifier = Modifier
                                .padding(horizontal = 20.dp, vertical = 16.dp)
                                .semantics { heading() },
                        )
                        HorizontalDivider(color = Color(0x1FFFFFFF))
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            content = content,
                        )
                        if (footer != null) {
                            HorizontalDivider(color = Color(0x1FFFFFFF))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                                content = footer,
                            )
                        }
                    }
                }
            }
        }
    }
}
