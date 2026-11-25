// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.miru.design.MiruMotion

@Composable
internal fun PlayerControlsOverlay(
    visible: Boolean,
    locked: Boolean,
    embedded: Boolean,
    portraitVideo: Boolean,
    orientationKey: Int,
    title: String,
    state: PlayerSnapshot,
    diskRanges: List<CachedTimeRange>,
    seekPreview: SeekPreviewState<Bitmap>,
    superResActive: Boolean,
    feedbackActive: Boolean,
    fitLabel: Int,
    drag: Float?,
    dragSourceRevision: Long?,
    seekEnabled: Boolean,
    seekInteractions: MutableInteractionSource,
    reducedMotion: Boolean,
    onBack: () -> Unit,
    onQuality: () -> Unit,
    onTracks: () -> Unit,
    onOptions: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onToggle: () -> Unit,
    onLive: () -> Unit,
    onSpeed: () -> Unit,
    onCycleFit: () -> Unit,
    onPip: () -> Unit,
    onOrientation: () -> Unit,
    onToggleLock: () -> Unit,
    onSeekValueChange: (Long, Float) -> Unit,
    onSeekValueChangeFinished: (Long) -> Unit,
) {
    val barSlidePx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val showUnlockedControls = visible && !locked
    val topEnter = if (reducedMotion) fadeIn(tween(MiruMotion.short2))
    else slideInVertically(tween(MiruMotion.short3, easing = MiruMotion.standardDecelerate)) { -barSlidePx } + fadeIn(tween(MiruMotion.short3))
    val topExit = if (reducedMotion) fadeOut(tween(MiruMotion.short2))
    else slideOutVertically(tween(MiruMotion.short2, easing = MiruMotion.standardAccelerate)) { -barSlidePx } + fadeOut(tween(MiruMotion.short2))
    val bottomEnter = if (reducedMotion) fadeIn(tween(MiruMotion.short2))
    else slideInVertically(tween(MiruMotion.short3, easing = MiruMotion.standardDecelerate)) { barSlidePx } + fadeIn(tween(MiruMotion.short3))
    val bottomExit = if (reducedMotion) fadeOut(tween(MiruMotion.short2))
    else slideOutVertically(tween(MiruMotion.short2, easing = MiruMotion.standardAccelerate)) { barSlidePx } + fadeOut(tween(MiruMotion.short2))

    AnimatedVisibility(
        visible = showUnlockedControls,
        enter = fadeIn(tween(MiruMotion.short3)),
        exit = fadeOut(tween(MiruMotion.short2)),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent, Color.Black.copy(alpha = 0.72f))),
            ),
        )
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        AnimatedVisibility(
            visible = showUnlockedControls,
            enter = topEnter,
            exit = topExit,
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.player_back), tint = Color.White)
                }
                Text(title, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.height > 0) {
                    Text(
                        playerResolutionLabel(state.width, state.height),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                TextButton(
                    onClick = onQuality,
                    modifier = Modifier.testTag("player_quality").sizeIn(minHeight = 48.dp),
                ) {
                    Text(
                        stringResource(R.string.player_quality),
                        color = if (superResActive) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                if (!embedded) {
                    IconButton(
                        onClick = onTracks,
                        modifier = Modifier.testTag("player_tracks").sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(Icons.Outlined.Subtitles, stringResource(R.string.player_tracks), tint = Color.White)
                    }
                }
                IconButton(onClick = onOptions, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(Icons.Outlined.Settings, stringResource(R.string.player_playback_options), tint = Color.White)
                }
            }
        }

        val centerVisible = showUnlockedControls && !feedbackActive
        val playScale by animateFloatAsState(
            targetValue = if (centerVisible) 1f else 0.9f,
            animationSpec = tween(durationMillis = if (reducedMotion) 0 else MiruMotion.short3),
            label = "player-center-play-scale",
        )
        AnimatedVisibility(
            visible = centerVisible,
            enter = fadeIn(tween(MiruMotion.short3)),
            exit = fadeOut(tween(MiruMotion.short2)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                IconButton(
                    onClick = { onSeekBy(-10_000) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Icon(Icons.Outlined.Replay10, stringResource(R.string.player_rewind), Modifier.size(32.dp), tint = Color.White)
                }
                Surface(
                    onClick = onToggle,
                    shape = CircleShape,
                    color = Color(0x80000000),
                    border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                    modifier = Modifier
                        .size(64.dp)
                        .graphicsLayer { scaleX = playScale; scaleY = playScale },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            stringResource(if (state.playing) R.string.player_pause else R.string.player_play),
                            Modifier.size(40.dp),
                            tint = Color.White,
                        )
                    }
                }
                IconButton(
                    onClick = { onSeekBy(10_000) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Icon(Icons.Outlined.Forward10, stringResource(R.string.player_forward), Modifier.size(32.dp), tint = Color.White)
                }
            }
        }

        AnimatedVisibility(
            visible = showUnlockedControls,
            enter = bottomEnter,
            exit = bottomExit,
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                if (state.live) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.player_live), color = Color.Red)
                        TextButton(onClick = onLive, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                            Text(stringResource(R.string.player_live_edge))
                        }
                    }
                } else {
                    // Recreating only the slider cancels an old source/layout's pointer
                    // gesture without touching the main video's output surface.
                    key(state.sourceRevision, embedded, orientationKey) {
                        val sliderSourceRevision = state.sourceRevision
                        PlayerProgressBar(
                            state = state,
                            diskRanges = diskRanges,
                            seekPreview = seekPreview,
                            drag = drag,
                            dragSourceRevision = dragSourceRevision,
                            seekEnabled = seekEnabled,
                            sliderSourceRevision = sliderSourceRevision,
                            seekInteractions = seekInteractions,
                            reducedMotion = reducedMotion,
                            onValueChange = { pos -> onSeekValueChange(sliderSourceRevision, pos) },
                            onValueChangeFinished = { onSeekValueChangeFinished(sliderSourceRevision) },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${playerTime(state.position)} / ${playerTime(state.duration)}",
                        Modifier.weight(1f),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    )
                    if (!embedded) {
                        TextButton(onClick = onSpeed, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                            Text("${state.speed}x")
                        }
                        TextButton(onClick = onCycleFit, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                            Text(stringResource(fitLabel))
                        }
                    }
                    IconButton(onClick = onPip, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(Icons.Outlined.PictureInPictureAlt, stringResource(R.string.player_pip), tint = Color.White)
                    }
                    IconButton(onClick = onOrientation, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(
                            if (embedded) Icons.Outlined.Fullscreen else Icons.Outlined.FullscreenExit,
                            stringResource(
                                if (portraitVideo) R.string.player_rotate
                                else if (embedded) R.string.player_enter_fullscreen
                                else R.string.player_exit_fullscreen,
                            ),
                            tint = Color.White,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(MiruMotion.short3)),
            exit = fadeOut(tween(MiruMotion.short2)),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = if (locked) Color(0x9914141B) else Color(0x4D000000),
            ) {
                IconButton(
                    onClick = onToggleLock,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("player_lock_toggle"),
                ) {
                    Icon(
                        if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                        stringResource(if (locked) R.string.player_unlock else R.string.player_lock),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
