// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import android.util.TypedValue
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.common.text.Cue
import kotlinx.coroutines.delay
import app.miru.superres.SuperResViewState

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    controller: PlayerController,
    pip: Boolean,
    onBack: () -> Unit,
    onReturnToWeb: () -> Unit,
    onPip: () -> Unit,
    onOrientation: () -> Unit,
    onImportSubtitle: () -> Unit = {},
    onVideoBounds: (Rect) -> Unit = {},
    onEmbeddedModeChanged: (Boolean) -> Unit = {},
    onLockChanged: (Boolean) -> Unit = {},
) {
    val state by controller.state.collectAsState()
    val seekPreview by controller.seekPreview.state.collectAsState()
    val tracks by controller.trackOptions.collectAsState()
    val cues by controller.subtitleCues.collectAsState()
    val preferences by controller.preferences.collectAsState()
    val activity = LocalContext.current as Activity
    val configuration = LocalConfiguration.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val portraitVideo = state.width > 0 && state.height > state.width
    val embedded = !pip && configuration.orientation != Configuration.ORIENTATION_LANDSCAPE && !portraitVideo
    var visible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf<String?>(null) }
    var speedPanel by remember { mutableStateOf(false) }
    var decoderPanel by remember { mutableStateOf(false) }
    var tracksPanel by remember { mutableStateOf(false) }
    var qualityPanel by remember { mutableStateOf(false) }
    var compare by remember { mutableStateOf(false) }
    var split by remember { mutableFloatStateOf(.5f) }
    var output by remember { mutableStateOf(SuperResViewState()) }
    var fit by remember { mutableIntStateOf(0) }
    var drag by remember(controller) { mutableStateOf<Float?>(null) }
    var dragSourceRevision by remember(controller) { mutableStateOf<Long?>(null) }
    var dragOwner by remember(controller) { mutableStateOf<Any?>(null) }
    var lifecycleActive by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val currentPip by rememberUpdatedState(pip)
    val cancelSeek = {
        drag = null
        dragSourceRevision = null
        dragOwner = null
        controller.seekPreview.hide()
    }
    val canSeekNow = {
        val current = controller.state.value
        visible && !locked && !currentPip && lifecycleActive && !current.live &&
            !current.error && !current.timedOut && current.duration > 0
    }
    val seekEnabled = canSeekNow()
    val seekOwner = remember(controller, state.sourceRevision, embedded, configuration.orientation) { Any() }
    val seekInteractions = remember(seekOwner) {
        SeekSliderInteractionSource(onCancel = { if(dragOwner === seekOwner) cancelSeek() })
    }
    var buffering by remember { mutableStateOf(false) }
    val touch = remember(controller) { PlayerTouch(activity, controller, { visible = !visible }, { overlay = it }) }
    SideEffect { touch.locked = locked }
    SideEffect {
        if((!seekEnabled || dragSourceRevision?.let { it != state.sourceRevision } == true) &&
            (drag != null || controller.seekPreview.state.value.targetMs != null)) cancelSeek()
    }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when(event) {
                Lifecycle.Event.ON_RESUME -> lifecycleActive = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    lifecycleActive = false
                    cancelSeek()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancelSeek() }
    }
    DisposableEffect(controller, state.sourceRevision, embedded, configuration.orientation) {
        onDispose { cancelSeek() }
    }
    LaunchedEffect(embedded) { onEmbeddedModeChanged(embedded) }
    LaunchedEffect(locked) { onLockChanged(locked) }
    DisposableEffect(touch) { onDispose { touch.dispose() } }
    LaunchedEffect(state.buffering) { buffering = false; if(state.buffering) { delay(300); buffering = true } }
    LaunchedEffect(visible, state.playing, locked, speedPanel, decoderPanel, tracksPanel, qualityPanel, drag != null) { if(visible && state.playing && drag == null && !speedPanel && !decoderPanel && !tracksPanel && !qualityPanel) { delay(3_000); if(drag == null) visible = false } }
    BackHandler { if(locked) { locked = false; visible = true } else onBack() }
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFFB4AB), background = Color.Black, surface = Color(0xFF1C1B1F))) {
        Column(Modifier.fillMaxSize().background(Color.Black).then(if(embedded) Modifier.safeDrawingPadding() else Modifier)) {
        val videoModifier = if(embedded) Modifier.fillMaxWidth().aspectRatio(16f / 9f) else Modifier.weight(1f).fillMaxWidth()
        Box(videoModifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            onVideoBounds(Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()))
        }) {
            PlayerVideoOutput(controller, state, preferences, pip, fit, if(compare) split else 0f, { output = it }, Modifier.fillMaxSize())
            if(cues.isNotEmpty()) AndroidView(factory = { SubtitleView(it) }, modifier = Modifier.fillMaxSize().testTag("player_subtitle_text"), update = {
                applySubtitleStyle(it, preferences)
                it.setCues(cues)
            })
            if(!pip) Box(Modifier.fillMaxSize().onSizeChanged { touch.width = it.width.toFloat(); touch.height = it.height.toFloat() }.pointerInteropFilter(onTouchEvent = touch::event))
            if(buffering && !pip && !state.error && !state.timedOut) CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp), color = Color.White, strokeWidth = 2.5.dp)
            overlay?.takeIf { !pip }?.let { Text(it, Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f)).padding(20.dp), color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum")) }
            androidx.compose.animation.AnimatedVisibility(visible = visible && !pip, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent, Color.Black.copy(alpha = 0.7f)))).safeDrawingPadding()) {
                    if(!locked) {
                        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.player_back), tint = Color.White) }
                            Text(controller.request.title, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if(state.height > 0) Text(playerResolutionLabel(state.width, state.height), color = Color.White, style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { qualityPanel = true }, modifier = Modifier.testTag("player_quality")) { Text(stringResource(R.string.player_quality), color = if(output.rendering.active) MaterialTheme.colorScheme.primary else Color.White) }
                            if(!embedded) IconButton(onClick = { tracksPanel = true }, modifier = Modifier.testTag("player_tracks")) { Icon(Icons.Outlined.Subtitles, stringResource(R.string.player_tracks), tint = Color.White) }
                            IconButton(onClick = { decoderPanel = true }) { Icon(Icons.Outlined.Settings, stringResource(R.string.player_playback_options), tint = Color.White) }
                        }
                        if(overlay == null) Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                            IconButton(onClick = { controller.seekBy(-10_000) }) { Icon(Icons.Outlined.Replay10, stringResource(R.string.player_rewind), tint = Color.White) }
                            IconButton(onClick = controller::toggle, modifier = Modifier.size(64.dp)) { Icon(if(state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, stringResource(if(state.playing) R.string.player_pause else R.string.player_play), Modifier.size(56.dp), tint = Color.White) }
                            IconButton(onClick = { controller.seekBy(10_000) }) { Icon(Icons.Outlined.Forward10, stringResource(R.string.player_forward), tint = Color.White) }
                        }
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp)) {
                            if(state.live) Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.player_live), color = Color.Red); TextButton(onClick = controller::live) { Text(stringResource(R.string.player_live_edge)) } }
                            else {
                                val seekLabel = stringResource(R.string.player_seek)
                                val primary = MaterialTheme.colorScheme.primary
                                // Recreating only the slider cancels an old source/layout's pointer
                                // gesture without touching the main video's output surface.
                                key(state.sourceRevision, embedded, configuration.orientation) {
                                    val sliderSourceRevision = state.sourceRevision
                                    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp)) {
                                        Slider(value = drag ?: state.position.toFloat().coerceIn(0f, maxOf(1f, state.duration.toFloat())), onValueChange = { position ->
                                            val current = controller.state.value
                                            if(canSeekNow() && current.sourceRevision == sliderSourceRevision) {
                                                dragSourceRevision = sliderSourceRevision
                                                dragOwner = seekOwner
                                                val target = position.coerceIn(0f, current.duration.toFloat())
                                                drag = target
                                                controller.seekPreview.request(target.toLong())
                                            }
                                        }, valueRange = 0f..maxOf(1f, state.duration.toFloat()), onValueChangeFinished = {
                                            if(dragOwner === seekOwner) {
                                                val target = drag?.toLong()
                                                val commit = target != null && canSeekNow() &&
                                                    dragSourceRevision == controller.state.value.sourceRevision &&
                                                    sliderSourceRevision == controller.state.value.sourceRevision
                                                // Clear first: Material3 can finish both Stop and Cancel and
                                                // repeated callbacks must never issue another seek.
                                                cancelSeek()
                                                if(commit) controller.seek(target!!)
                                            }
                                        }, enabled = seekEnabled, interactionSource = seekInteractions,
                                            modifier = Modifier.align(Alignment.Center).testTag("player_seek_slider").semantics { contentDescription = seekLabel },
                                            thumb = { Box(Modifier.size(16.dp).background(Color.White, CircleShape)) },
                                            track = { Canvas(Modifier.fillMaxWidth().height(if(drag == null) 4.dp else 8.dp)) {
                                                val y = size.height / 2
                                                val total = maxOf(1f, state.duration.toFloat())
                                                fun line(color: Color, fraction: Float) { drawLine(color, Offset(0f, y), Offset(size.width * fraction.coerceIn(0f, 1f), y), size.height, StrokeCap.Round) }
                                                line(Color.White.copy(alpha = 0.2f), 1f)
                                                line(Color.White.copy(alpha = 0.45f), state.buffered / total)
                                                line(primary, (drag ?: state.position.toFloat()) / total)
                                            } },
                                        )
                                        drag?.takeIf { seekEnabled && dragSourceRevision == state.sourceRevision }?.let { position ->
                                            val frame = seekPreview.frame?.takeIf { seekPreview.targetMs == position.toLong() && !it.image.isRecycled }
                                            val bubbleWidth = minOf(maxWidth, if(frame == null) 80.dp else 176.dp)
                                            val fraction = (position / maxOf(1f, state.duration.toFloat())).coerceIn(0f, 1f)
                                            val bubbleLeft = (maxWidth * fraction - bubbleWidth / 2f).coerceIn(0.dp, maxWidth - bubbleWidth)
                                            SeekPreviewBubble(position.toLong(), frame,
                                                Modifier.align(Alignment.BottomStart).offset(x = bubbleLeft, y = (-48).dp)
                                                    .wrapContentHeight(Alignment.Bottom, unbounded = true).width(bubbleWidth))
                                        }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${playerTime(state.position)} / ${playerTime(state.duration)}", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
                                if(!embedded) {
                                    TextButton(onClick = { speedPanel = true }) { Text("${state.speed}x") }
                                    TextButton(onClick = { fit = (fit + 1) % 3 }) { Text(stringResource(when(fit) { 1 -> R.string.player_crop; 2 -> R.string.player_stretch; else -> R.string.player_fit })) }
                                }
                                IconButton(onClick = onPip) { Icon(Icons.Outlined.PictureInPictureAlt, stringResource(R.string.player_pip), tint = Color.White) }
                                IconButton(onClick = onOrientation) {
                                    Icon(if(embedded) Icons.Outlined.Fullscreen else Icons.Outlined.FullscreenExit,
                                        stringResource(if(portraitVideo) R.string.player_rotate else if(embedded) R.string.player_enter_fullscreen else R.string.player_exit_fullscreen), tint = Color.White)
                                }
                            }
                        }
                    }
                    IconButton(onClick = { locked = !locked }, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) { Icon(if(locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen, stringResource(if(locked) R.string.player_unlock else R.string.player_lock), tint = Color.White) }
                }
            }
            if((state.error || state.timedOut) && !pip) Surface(Modifier.align(Alignment.Center).padding(24.dp), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(if(state.timedOut) R.string.player_timeout else R.string.player_error))
                    Row { TextButton(onClick = controller::retry) { Text(stringResource(R.string.player_retry)) }; TextButton(onClick = if(controller.request.fromWeb) onReturnToWeb else onBack) { Text(stringResource(if(controller.request.fromWeb) R.string.player_return_web else R.string.player_back)) } }
                }
            }
            if(state.resumed && visible && !pip && !state.error) TextButton(onClick = controller::restart, modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 56.dp)) { Text(stringResource(R.string.player_resumed)) }
            if(compare && output.rendering.active && output.decision.enabled && !pip && !locked)
                SuperResComparison(output, split) { split = it }
        }
        if(embedded) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(controller.request.title.ifBlank { stringResource(R.string.player_title) }, color = Color.White, style = MaterialTheme.typography.titleLarge)
            val source = remember(controller.request.pageUrl, controller.request.url) { runCatching { Uri.parse(controller.request.pageUrl.ifBlank { controller.request.url }).host }.getOrNull().orEmpty() }
            if(source.isNotBlank()) Text(stringResource(R.string.player_source_site, source), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            if(state.width > 0 && state.height > 0) Text(stringResource(R.string.player_source_resolution, playerResolutionLabel(state.width, state.height), state.width, state.height), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { speedPanel = true }) { Text(stringResource(R.string.player_speed_value, state.speed.toString())) }
                OutlinedButton(onClick = { fit = (fit + 1) % 3 }) { Text(stringResource(when(fit) { 1 -> R.string.player_crop; 2 -> R.string.player_stretch; else -> R.string.player_fit })) }
            }
            TextButton(onClick = { decoderPanel = true }) { Text(stringResource(R.string.player_playback_options)) }
            TextButton(onClick = { tracksPanel = true }, modifier = Modifier.testTag("player_tracks")) { Text(stringResource(R.string.player_tracks)) }
            TextButton(onClick = { qualityPanel = true }, modifier = Modifier.testTag("player_quality_details")) { Text(stringResource(R.string.player_quality)) }
        }
        }
        if(speedPanel) AlertDialog(onDismissRequest = { speedPanel = false }, title = { Text(stringResource(R.string.player_speed)) }, text = { Column {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f).forEach { speed -> TextButton(onClick = { controller.speed(speed); speedPanel = false }, modifier = Modifier.fillMaxWidth().semantics { selected = speed == state.speed }) { Text("${speed}x", color = if(speed == state.speed) MaterialTheme.colorScheme.primary else Color.White) } }
        } }, confirmButton = {})
        if(decoderPanel) PlaybackOptionsDialog(activity, controller, onDismiss = { decoderPanel = false })
        if(tracksPanel) TracksDialog(controller, onImportSubtitle, onDismiss = { tracksPanel = false })
        if(qualityPanel) SuperResQualityPanel(controller, output, compare, onCompare = {
            compare = it
            if(it) { qualityPanel = false; visible = false }
        }, onDismiss = { qualityPanel = false })
    }
}

/** Cancellation must reach our state before Slider invokes its finish callback. */
private class SeekSliderInteractionSource(
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
        if(interaction is DragInteraction.Cancel) onCancel()
    }
}

@Composable
private fun SeekPreviewBubble(targetMs: Long, frame: SeekPreviewFrame<Bitmap>?, modifier: Modifier = Modifier) {
    Surface(modifier.testTag("player_seek_preview_bubble"), shape = MaterialTheme.shapes.small,
        color = Color.Black.copy(alpha = .88f), contentColor = Color.White, shadowElevation = 4.dp) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            frame?.let {
                val ratio = it.image.width.toFloat() / it.image.height.coerceAtLeast(1)
                val imageWidth = minOf(160f, 90f * ratio).dp
                val imageHeight = minOf(90f, 160f / ratio).dp
                Image(it.image.asImageBitmap(), stringResource(R.string.player_seek_preview, playerTime(targetMs)),
                    Modifier.size(imageWidth, imageHeight).testTag("player_seek_preview_image"), contentScale = ContentScale.Fit)
            }
            Text(playerTime(targetMs), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), maxLines = 1)
        }
    }
}

private fun applySubtitleStyle(view: SubtitleView, preferences: PlayerPreferences) {
    view.setApplyEmbeddedStyles(false)
    view.setApplyEmbeddedFontSizes(false)
    view.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, preferences.subtitleSizeSp)
    view.setBottomPaddingFraction(preferences.subtitleBottomMargin)
    view.setStyle(CaptionStyleCompat(android.graphics.Color.WHITE, (preferences.subtitleBackgroundOpacity * 255).toInt() shl 24,
        android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null))
}

@Composable
private fun TracksDialog(controller: PlayerController, onImportSubtitle: () -> Unit, onDismiss: () -> Unit) {
    val tracks by controller.trackOptions.collectAsState()
    val preferences by controller.preferences.collectAsState()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.player_tracks)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.player_audio_tracks), style = MaterialTheme.typography.titleSmall)
            if(tracks.audio.isEmpty()) Text(stringResource(R.string.player_no_audio_tracks), style = MaterialTheme.typography.bodyMedium)
            tracks.audio.forEachIndexed { index, option ->
                TextButton(onClick = { controller.selectAudio(option.id) }, modifier = Modifier.fillMaxWidth().testTag("player_audio_$index").semantics { selected = option.selected }) {
                    Text(option.label, color = if(option.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.player_subtitles), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { controller.selectSubtitle(null) }, modifier = Modifier.fillMaxWidth().testTag("player_subtitle_off").semantics { selected = tracks.subtitlesOff }) {
                Text(stringResource(R.string.player_subtitle_off), color = if(tracks.subtitlesOff) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            tracks.subtitles.forEachIndexed { index, option ->
                TextButton(onClick = { controller.selectSubtitle(option.id) }, modifier = Modifier.fillMaxWidth().testTag("player_subtitle_$index").semantics { selected = option.selected }) {
                    Text(option.label, color = if(option.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
            tracks.localSubtitleName?.let { name ->
                TextButton(onClick = controller::selectLocalSubtitle, modifier = Modifier.fillMaxWidth().testTag("player_subtitle_local").semantics { selected = tracks.localSubtitleSelected }) {
                    Text(stringResource(R.string.player_subtitle_loaded, name), color = if(tracks.localSubtitleSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
            TextButton(onClick = onImportSubtitle, enabled = !tracks.importing, modifier = Modifier.fillMaxWidth().testTag("player_subtitle_import")) {
                Text(stringResource(if(tracks.importing) R.string.player_subtitle_loading else R.string.player_subtitle_import))
            }
            Text(stringResource(R.string.player_subtitle_formats), style = MaterialTheme.typography.bodySmall)
            if(tracks.importError) Text(stringResource(R.string.player_subtitle_import_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            val sizeLabel = stringResource(R.string.player_subtitle_size)
            Text(stringResource(R.string.player_subtitle_size_value, preferences.subtitleSizeSp.toInt()))
            Slider(preferences.subtitleSizeSp, onValueChange = { controller.subtitleStyle(sizeSp = it) }, valueRange = 12f..40f, steps = 27, modifier = Modifier.semantics { contentDescription = sizeLabel })
            val marginLabel = stringResource(R.string.player_subtitle_margin)
            Text(stringResource(R.string.player_level_value, marginLabel, (preferences.subtitleBottomMargin * 100).toInt()))
            Slider(preferences.subtitleBottomMargin, onValueChange = { controller.subtitleStyle(bottomMargin = it) }, valueRange = 0f..0.3f, modifier = Modifier.semantics { contentDescription = marginLabel })
            val backgroundLabel = stringResource(R.string.player_subtitle_background)
            Text(stringResource(R.string.player_level_value, backgroundLabel, (preferences.subtitleBackgroundOpacity * 100).toInt()))
            Slider(preferences.subtitleBackgroundOpacity, onValueChange = { controller.subtitleStyle(backgroundOpacity = it) }, modifier = Modifier.semantics { contentDescription = backgroundLabel })
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_options_done)) } })
}

@Composable
private fun PlaybackOptionsDialog(activity: Activity, controller: PlayerController, onDismiss: () -> Unit) {
    val state by controller.state.collectAsState()
    val audio = remember(activity) { activity.getSystemService(AudioManager::class.java) }
    fun readBrightness() = activity.window.attributes.screenBrightness.takeIf { it >= 0 }
        ?: runCatching { Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f }.getOrDefault(0.5f)
    fun readVolume(): Float = runCatching { (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / (audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1).coerceAtLeast(1) }.getOrDefault(0.5f)
    var brightness by remember(activity) { mutableFloatStateOf(readBrightness().coerceIn(0f, 1f)) }
    var volume by remember(activity) { mutableFloatStateOf(readVolume().coerceIn(0f, 1f)) }
    var volumeFailureShown by remember { mutableStateOf(false) }
    val brightnessLabel = stringResource(R.string.player_brightness_control)
    val volumeLabel = stringResource(R.string.player_volume_control)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.player_playback_options)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.player_decoder), style = MaterialTheme.typography.titleSmall)
            listOf("auto" to R.string.player_decoder_auto, "hardware" to R.string.player_decoder_hardware, "software" to R.string.player_decoder_software).forEach { (mode, label) ->
                TextButton(onClick = { controller.decoder(mode); onDismiss() }, modifier = Modifier.fillMaxWidth().semantics { selected = mode == state.decoder }) { Text(stringResource(label)) }
            }
            HorizontalDivider()
            Text(stringResource(R.string.player_level_value, brightnessLabel, (brightness * 100).toInt()))
            Slider(brightness, onValueChange = { value ->
                brightness = value
                activity.window.attributes = activity.window.attributes.apply { screenBrightness = value }
            }, modifier = Modifier.semantics { contentDescription = brightnessLabel })
            Text(stringResource(R.string.player_level_value, volumeLabel, (volume * 100).toInt()))
            Slider(volume, onValueChange = { value ->
                volume = value
                runCatching { audio?.setStreamVolume(AudioManager.STREAM_MUSIC, (value * (audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1)).toInt(), 0) }
                    .onFailure {
                        volume = readVolume().coerceIn(0f, 1f)
                        if(!volumeFailureShown) { volumeFailureShown = true; Toast.makeText(activity, R.string.player_volume_unavailable, Toast.LENGTH_LONG).show() }
                    }
            }, modifier = Modifier.semantics { contentDescription = volumeLabel })
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_options_done)) } })
}
