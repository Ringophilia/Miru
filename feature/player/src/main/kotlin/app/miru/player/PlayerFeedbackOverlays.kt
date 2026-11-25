// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import android.graphics.Bitmap
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.miru.design.MiruMotion
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

internal sealed interface PlayerFeedback {
    data class DoubleTap(val forward: Boolean, val seconds: Int, val tick: Int) : PlayerFeedback
    data class SwipeSeek(val targetMs: Long, val durationMs: Long, val deltaMs: Long) : PlayerFeedback
    data class Level(val brightness: Boolean, val fraction: Float, val tick: Int) : PlayerFeedback
}

internal fun View.performConfirmHaptic(enabled: Boolean) {
    if (!enabled) return
    val constant = if (Build.VERSION.SDK_INT >= 30) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.CONTEXT_CLICK
    }
    performHapticFeedback(constant)
}

@Composable
internal fun BoxScope.PlayerFeedbackLayer(
    feedback: PlayerFeedback?,
    holdSpeed: Float?,
    seekPreview: SeekPreviewState<Bitmap>,
    reducedMotion: Boolean,
) {
    var retainedLevel by remember { mutableStateOf<PlayerFeedback.Level?>(null) }
    val activeLevel = feedback as? PlayerFeedback.Level
    if (activeLevel != null) retainedLevel = activeLevel

    when (feedback) {
        is PlayerFeedback.DoubleTap -> DoubleTapArcRipple(
            feedback = feedback,
            reducedMotion = reducedMotion,
            modifier = Modifier.fillMaxSize(),
        )
        is PlayerFeedback.SwipeSeek -> SwipeSeekCard(
            feedback = feedback,
            seekPreview = seekPreview,
            modifier = Modifier.align(Alignment.Center),
        )
        is PlayerFeedback.Level, null -> Unit
    }

    AnimatedVisibility(
        visible = activeLevel != null,
        enter = fadeIn(tween(MiruMotion.short1)),
        exit = fadeOut(tween(MiruMotion.short3)),
        modifier = Modifier.align(Alignment.Center),
    ) {
        retainedLevel?.let { LevelIndicatorBar(it) }
    }

    holdSpeed?.let { speed ->
        HoldSpeedPill(
            speed = speed,
            reducedMotion = reducedMotion,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp),
        )
    }
}

@Composable
internal fun DoubleTapArcRipple(
    feedback: PlayerFeedback.DoubleTap,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress = remember(feedback.tick) { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(feedback.tick, reducedMotion) {
        if (!reducedMotion) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = 450, easing = MiruMotion.standardDecelerate))
        }
    }
    val clipShape = remember(feedback.forward) {
        GenericShape { size, _ ->
            val w = size.width
            val h = size.height
            if (feedback.forward) {
                moveTo(w, 0f)
                lineTo(w * 0.25f, 0f)
                quadraticTo(-w * 0.15f, h / 2f, w * 0.25f, h)
                lineTo(w, h)
            } else {
                moveTo(0f, 0f)
                lineTo(w * 0.75f, 0f)
                quadraticTo(w * 1.15f, h / 2f, w * 0.75f, h)
                lineTo(0f, h)
            }
            close()
        }
    }
    val signedSeconds = if (feedback.forward) feedback.seconds else -feedback.seconds
    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth(0.36f)
                .fillMaxHeight()
                .align(if (feedback.forward) Alignment.CenterEnd else Alignment.CenterStart)
                .clip(clipShape)
                .background(
                    Color.White.copy(
                        alpha = if (reducedMotion) 0.16f else 0.12f + 0.08f * (1f - progress.value),
                    ),
                )
                .testTag("player_double_tap_ripple"),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                    for (i in 0 until 3) {
                        val order = if (feedback.forward) i else 2 - i
                        val p = ((progress.value * 3f) - order).coerceIn(0f, 1f)
                        val chevronAlpha = if (reducedMotion) 0.9f else (0.35f + 0.65f * sin(p * PI)).toFloat()
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = chevronAlpha),
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer { if (!feedback.forward) scaleX = -1f },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.player_seek_feedback, signedSeconds),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
        }
    }
}

@Composable
internal fun HoldSpeedPill(
    speed: Float,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val scale = remember { Animatable(if (reducedMotion) 1f else 0.9f) }
    LaunchedEffect(reducedMotion) {
        if (!reducedMotion) {
            scale.animateTo(
                1f,
                spring(dampingRatio = MiruMotion.bouncyDamping, stiffness = MiruMotion.bouncyStiffness),
            )
        }
    }
    val phase = if (reducedMotion) {
        0f
    } else {
        rememberInfiniteTransition(label = "hold-chevrons").animateFloat(
            initialValue = 0f,
            targetValue = 3f,
            animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart),
            label = "hold-phase",
        ).value
    }
    val semanticLabel = stringResource(R.string.player_hold_feedback, speed)
    Surface(
        modifier
            .testTag("player_hold_pill")
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clearAndSetSemantics { text = AnnotatedString(semanticLabel) },
        shape = RoundedCornerShape(50),
        color = Color(0xC814141B),
        border = BorderStroke(1.dp, Color(0x28FFFFFF)),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                stringResource(R.string.player_hold_speed_short, speed),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                ),
            )
            Spacer(Modifier.width(4.dp))
            for (i in 0 until 3) {
                val alpha = if (reducedMotion) 0.9f else {
                    val dist = ((phase - i + 3f) % 3f).coerceIn(0f, 3f)
                    if (dist < 1f) (0.35f + 0.65f * sin(dist * PI)).toFloat() else 0.35f
                }
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = alpha),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
internal fun SwipeSeekCard(
    feedback: PlayerFeedback.SwipeSeek,
    seekPreview: SeekPreviewState<Bitmap>,
    modifier: Modifier = Modifier,
) {
    val frame = seekPreview.frame?.takeIf { !it.image.isRecycled }
    val deltaSeconds = (feedback.deltaMs / 1000L).toInt()
    Surface(
        modifier.testTag("player_swipe_seek_card"),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xD814141B),
        border = BorderStroke(1.dp, Color(0x28FFFFFF)),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            frame?.let {
                val ratio = it.image.width.toFloat() / it.image.height.coerceAtLeast(1)
                val w = minOf(160f, 90f * ratio).dp
                val h = minOf(90f, 160f / ratio).dp
                Image(
                    it.image.asImageBitmap(),
                    contentDescription = stringResource(R.string.player_seek_preview, playerTime(feedback.targetMs)),
                    modifier = Modifier.size(w, h).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    playerTime(feedback.targetMs),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                )
                Text(
                    "/ ${playerTime(feedback.durationMs)}",
                    color = Color(0x99FFFFFF),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                )
            }
            Text(
                stringResource(R.string.player_seek_feedback, deltaSeconds),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
    }
}

@Composable
internal fun LevelIndicatorBar(
    level: PlayerFeedback.Level,
    modifier: Modifier = Modifier,
) {
    val icon = when {
        level.brightness -> Icons.Outlined.Brightness6
        level.fraction <= 0f -> Icons.AutoMirrored.Outlined.VolumeOff
        else -> Icons.AutoMirrored.Outlined.VolumeUp
    }
    val smoothFraction by animateFloatAsState(
        targetValue = level.fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = MiruMotion.instant),
        label = "level-fraction",
    )
    val label = stringResource(
        if (level.brightness) R.string.player_brightness else R.string.player_volume,
        (level.fraction * 100f).roundToInt(),
    )
    Surface(
        modifier.widthIn(min = 200.dp, max = 236.dp).testTag("player_level_indicator")
            .clearAndSetSemantics { text = AnnotatedString(label) },
        shape = RoundedCornerShape(16.dp),
        color = Color(0xD814141B),
        border = BorderStroke(1.dp, Color(0x28FFFFFF)),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Color(0x38FFFFFF)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(smoothFraction)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Text(
                "${(level.fraction * 100f).roundToInt()}%",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
    }
}

@Composable
internal fun PlayerGlassToast(
    message: String?,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    var lastMessage by remember { mutableStateOf<String?>(null) }
    if (message != null) lastMessage = message
    val offsetPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    AnimatedVisibility(
        visible = message != null,
        enter = if (reducedMotion) {
            fadeIn(tween(MiruMotion.short2))
        } else {
            slideInVertically(tween(MiruMotion.short3, easing = MiruMotion.standardDecelerate)) { offsetPx } +
                fadeIn(tween(MiruMotion.short3))
        },
        exit = fadeOut(tween(MiruMotion.short2)),
        modifier = modifier,
    ) {
        lastMessage?.let { text ->
            Surface(
                modifier = Modifier.testTag("player_glass_toast"),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xD814141B),
                border = BorderStroke(1.dp, Color(0x28FFFFFF)),
            ) {
                Text(
                    text,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
