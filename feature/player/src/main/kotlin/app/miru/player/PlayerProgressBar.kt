// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import android.graphics.Bitmap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.miru.design.MiruMotion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerProgressBar(
    state: PlayerSnapshot,
    diskRanges: List<CachedTimeRange>,
    seekPreview: SeekPreviewState<Bitmap>,
    drag: Float?,
    dragSourceRevision: Long?,
    seekEnabled: Boolean,
    sliderSourceRevision: Long,
    seekInteractions: MutableInteractionSource,
    reducedMotion: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val seekLabel = stringResource(R.string.player_seek)
    val primary = MaterialTheme.colorScheme.primary
    val dragging = drag != null
    val trackHeight by animateDpAsState(
        targetValue = if (dragging) 8.dp else 4.dp,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else MiruMotion.short2),
        label = "player-seek-track-height",
    )
    val thumbDiameter by animateDpAsState(
        targetValue = if (dragging) 16.dp else 0.dp,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else MiruMotion.short2),
        label = "player-seek-thumb-size",
    )
    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp)) {
        val sliderRange = 0f..maxOf(1f, state.duration.toFloat())
        val sliderState = remember(sliderRange) { SliderState(trackRange = sliderRange) }
        sliderState.value = drag ?: state.position.toFloat().coerceIn(sliderRange)
        Slider(
            state = sliderState,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            enabled = seekEnabled,
            interactionSource = seekInteractions,
            modifier = Modifier
                .align(Alignment.Center)
                .testTag("player_seek_slider")
                .semantics { contentDescription = seekLabel },
            thumb = {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    if (thumbDiameter > 0.dp) {
                        Box(Modifier.size(thumbDiameter).background(Color.White, CircleShape))
                    }
                }
            },
            track = {
                Canvas(Modifier.fillMaxWidth().height(trackHeight).testTag("player_seek_track")) {
                    val y = size.height / 2f
                    val total = maxOf(1f, state.duration.toFloat())
                    fun line(color: Color, fraction: Float) {
                        drawLine(color, Offset(0f, y), Offset(size.width * fraction.coerceIn(0f, 1f), y), size.height, StrokeCap.Round)
                    }
                    line(Color.White.copy(alpha = 0.2f), 1f)
                    if (state.ready && state.duration > 0) {
                        diskRanges.forEach { range ->
                            val durationUs = state.duration.toDouble() * 1000.0
                            val start = (range.startUs / durationUs).coerceIn(0.0, 1.0).toFloat() * size.width
                            val end = (range.endUs / durationUs).coerceIn(0.0, 1.0).toFloat() * size.width
                            if (start < end) {
                                drawLine(Color.White.copy(alpha = 0.32f), Offset(start, y), Offset(end, y), size.height, StrokeCap.Butt)
                            }
                        }
                    }
                    line(Color.White.copy(alpha = 0.45f), state.buffered / total)
                    line(primary, (drag ?: state.position.toFloat()) / total)
                }
            },
        )
        drag?.takeIf { seekEnabled && dragSourceRevision == state.sourceRevision && sliderSourceRevision == state.sourceRevision }?.let { position ->
            val frame = seekPreview.frame?.takeIf { seekPreview.targetMs == position.toLong() && !it.image.isRecycled }
            val bubbleWidth = minOf(maxWidth, if (frame == null) 80.dp else 176.dp)
            val fraction = (position / maxOf(1f, state.duration.toFloat())).coerceIn(0f, 1f)
            val bubbleLeft = (maxWidth * fraction - bubbleWidth / 2f).coerceIn(0.dp, maxWidth - bubbleWidth)
            SeekPreviewBubble(
                targetMs = position.toLong(),
                frame = frame,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = bubbleLeft, y = (-48).dp)
                    .wrapContentHeight(Alignment.Bottom, unbounded = true)
                    .width(bubbleWidth),
            )
        }
    }
}

/** Cancellation must reach our state before Slider invokes its finish callback. */
internal class SeekSliderInteractionSource(
    private val onCancel: () -> Unit,
    private val delegate: MutableInteractionSource = MutableInteractionSource(),
) : MutableInteractionSource by delegate {
    override suspend fun emit(interaction: Interaction) {
        cancelIfNeeded(interaction)
        delegate.emit(interaction)
    }

    override fun tryEmit(interaction: Interaction): Boolean {
        cancelIfNeeded(interaction)
        return delegate.tryEmit(interaction)
    }

    private fun cancelIfNeeded(interaction: Interaction) {
        if (interaction is DragInteraction.Cancel) onCancel()
    }
}

@Composable
private fun SeekPreviewBubble(targetMs: Long, frame: SeekPreviewFrame<Bitmap>?, modifier: Modifier = Modifier) {
    Surface(
        modifier.testTag("player_seek_preview_bubble"),
        shape = MaterialTheme.shapes.small,
        color = Color.Black.copy(alpha = 0.88f),
        contentColor = Color.White,
        shadowElevation = 4.dp,
    ) {
        Column(
            Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            frame?.let {
                val ratio = it.image.width.toFloat() / it.image.height.coerceAtLeast(1)
                val imageWidth = minOf(160f, 90f * ratio).dp
                val imageHeight = minOf(90f, 160f / ratio).dp
                Image(
                    it.image.asImageBitmap(),
                    stringResource(R.string.player_seek_preview, playerTime(targetMs)),
                    Modifier.size(imageWidth, imageHeight).testTag("player_seek_preview_image"),
                    contentScale = ContentScale.Fit,
                )
            }
            Text(
                playerTime(targetMs),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
            )
        }
    }
}
