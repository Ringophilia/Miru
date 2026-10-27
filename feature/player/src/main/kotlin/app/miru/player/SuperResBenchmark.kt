// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import app.miru.superres.SuperResConfig
import app.miru.superres.SuperResPolicy
import app.miru.superres.SuperResRenderer
import app.miru.superres.SuperResStatus
import app.miru.superres.SuperResTier
import app.miru.superres.SuperResTiming

/**
 * Measures the bundled 720P clip against an actual 1920×1080 display buffer, independently of
 * playback's automatic tier guard. Applying a result calls [onApply]; the owner closes the dialog.
 * A synchronized-render fallback is explicitly identified and is never presented as a GPU query.
 */
@Composable
fun SuperResBenchmarkDialog(onDismiss: () -> Unit, onApply: (SuperResTier) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember { mutableStateOf(SuperResBenchmarkState()) }
    val session = remember(context) { SuperResBenchmarkSession(context) { state = it } }
    val close = { session.close(); onDismiss() }
    DisposableEffect(session, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_STOP) session.interrupt()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); session.close() }
    }
    val previewDescription = stringResource(R.string.superres_benchmark_preview)
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.92f).widthIn(max = 560.dp), shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.superres_benchmark_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.superres_benchmark_detail), style = MaterialTheme.typography.bodyMedium)
                AndroidView(factory = { viewContext ->
                    SurfaceView(viewContext).apply {
                        // The system compositor scales this buffer into the visible preview. The GL
                        // viewport remains 1080P even when the dialog is only a few hundred dp wide.
                        holder.setFixedSize(BENCHMARK_OUTPUT_WIDTH, BENCHMARK_OUTPUT_HEIGHT)
                        holder.addCallback(session)
                    }
                }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).semantics { contentDescription = previewDescription },
                    onRelease = { view -> view.holder.removeCallback(session); session.close() })
                when {
                    state.error != null -> Text(stringResource(state.error!!), color = MaterialTheme.colorScheme.error)
                    state.finished -> {
                        val recommended = state.recommendation
                        Text(if(recommended == null) stringResource(R.string.superres_benchmark_no_recommendation)
                            else stringResource(R.string.superres_benchmark_recommendation, benchmarkTierName(recommended)),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    state.tier != null -> {
                        Text(stringResource(R.string.superres_benchmark_progress, benchmarkTierName(state.tier!!), state.frames.coerceAtMost(60L).toInt()))
                        LinearProgressIndicator(progress = { (state.frames / 60f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> Text(stringResource(R.string.superres_benchmark_preparing))
                }
                state.results.forEach { result ->
                    val timing = stringResource(if(result.timing == SuperResTiming.GPU_QUERY)
                        R.string.superres_benchmark_gpu_timer else R.string.superres_benchmark_sync_timer)
                    Text(stringResource(R.string.superres_benchmark_result, benchmarkTierName(result.tier), result.milliseconds, timing),
                        style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.superres_benchmark_limitations), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = close) {
                        Text(stringResource(if(state.finished || state.error != null) R.string.superres_benchmark_close else R.string.superres_benchmark_cancel))
                    }
                    state.recommendation?.let { recommendation ->
                        TextButton(onClick = { session.close(); onApply(recommendation) }) {
                            Text(stringResource(R.string.superres_benchmark_apply))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun benchmarkTierName(tier: SuperResTier): String = stringResource(when(tier) {
    SuperResTier.FAST -> R.string.superres_benchmark_fast
    SuperResTier.BALANCED -> R.string.superres_benchmark_balanced
    SuperResTier.QUALITY -> R.string.superres_benchmark_quality
})

private const val BENCHMARK_OUTPUT_WIDTH = 1920
private const val BENCHMARK_OUTPUT_HEIGHT = 1080
private const val BENCHMARK_TIER_TIMEOUT_MS = 45_000L
private const val BENCHMARK_DIAGNOSTIC_TAG = "MiruBenchmarkRun"
private const val BENCHMARK_DIAGNOSTIC_INTERVAL_MS = 5_000L

private enum class BenchmarkStage { WAITING_OUTPUT, STARTING, COLLECTING, AWAITING_RELEASE, FINISHED }
private enum class BenchmarkEvent {
    CREATED, OUTPUT_CREATED, OUTPUT_CHANGED, OUTPUT_DESTROYED, TIER_STARTED, INPUT_READY,
    FIRST_FRAME, PROGRESS, TIER_MEASURED, RELEASE_REQUESTED, RELEASE_CALLBACK,
    PLAYER_STATE, PLAYER_PLAYING, FINISHED, FAILED, CLOSED,
}
private enum class BenchmarkFailure { NONE, TIMEOUT, DECODE, RENDER, THERMAL, INTERRUPTED, OTHER }

private data class SuperResBenchmarkResult(val tier: SuperResTier, val milliseconds: Double,
    val timing: SuperResTiming, val frames: Long, val passes: Int)
private data class SuperResBenchmarkState(
    val tier: SuperResTier? = null,
    val frames: Long = 0,
    val results: List<SuperResBenchmarkResult> = emptyList(),
    val error: Int? = null,
    val finished: Boolean = false,
) {
    val recommendation: SuperResTier? get() = if(finished && error == null)
        results.filter { it.milliseconds < 16.0 }.maxByOrNull { it.tier.ordinal }?.tier else null
}

/** All state and decoder/surface ownership live on main; the renderer owns its separate GL thread. */
private class SuperResBenchmarkSession(context: Context, private val onState: (SuperResBenchmarkState) -> Unit) : SurfaceHolder.Callback {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var state = SuperResBenchmarkState()
    private var player: ExoPlayer? = null
    private var renderer: SuperResRenderer? = null
    private var output: Surface? = null
    private var epoch = 0L
    private var generation: Long? = null
    private var deadline = 0L
    private var closed = false
    private var started = false
    private val diagnosticRun = SystemClock.elapsedRealtimeNanos()
    private val sessionStartedAt = SystemClock.elapsedRealtime()
    private var stage = BenchmarkStage.WAITING_OUTPUT
    private var stageStartedAt = sessionStartedAt
    private var tierStartedAt = 0L
    private var releaseRequestedAt = 0L
    private var releaseCallbackAt = 0L
    private var lastDiagnosticAt = 0L
    private var lastSample: SuperResStatus? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) { fail(R.string.superres_benchmark_decode_failed) }
        override fun onPlaybackStateChanged(playbackState: Int) { diagnose(BenchmarkEvent.PLAYER_STATE) }
        override fun onIsPlayingChanged(isPlaying: Boolean) { diagnose(BenchmarkEvent.PLAYER_PLAYING) }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if(videoSize.width <= 0 || videoSize.height <= 0) return
            if(videoSize.width != 1280 || videoSize.height != 720) fail(R.string.superres_benchmark_decode_failed)
            else renderer?.setVideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if(closed || state.error != null || state.finished) return
            if(tooHot()) { fail(R.string.superres_benchmark_hot); return }
            if(SystemClock.elapsedRealtime() >= deadline) { fail(R.string.superres_benchmark_timeout); return }
            val active = renderer
            val sample = active?.status()
            if(sample != null) lastSample = sample
            if(sample?.error != null) { fail(R.string.superres_benchmark_render_failed); return }
            if(sample != null && sample.frames > 0) {
                if(stage == BenchmarkStage.STARTING) {
                    setStage(BenchmarkStage.COLLECTING)
                    diagnose(BenchmarkEvent.FIRST_FRAME, sample)
                }
                // Every tier owns a fresh renderer. Only one stable configuration generation may
                // supply a measurement; old samples can never count towards a subsequent tier.
                if(generation == null) generation = sample.generation
                if(generation != sample.generation) { fail(R.string.superres_benchmark_interrupted); return }
                publish(state.copy(frames = sample.frames))
                if(validSample(sample)) { completeTier(sample); return }
            }
            if(SystemClock.elapsedRealtime() - lastDiagnosticAt >= BENCHMARK_DIAGNOSTIC_INTERVAL_MS)
                diagnose(BenchmarkEvent.PROGRESS, sample ?: lastSample)
            handler.postDelayed(this, 100)
        }
    }

    init {
        // Also bound waiting for a visible 1080P buffer, before the first decoder is created.
        deadline = SystemClock.elapsedRealtime() + BENCHMARK_TIER_TIMEOUT_MS
        diagnose(BenchmarkEvent.CREATED)
        handler.post(poll)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        diagnose(BenchmarkEvent.OUTPUT_CREATED)
        holder.setFixedSize(BENCHMARK_OUTPUT_WIDTH, BENCHMARK_OUTPUT_HEIGHT)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        diagnose(BenchmarkEvent.OUTPUT_CHANGED)
        if(closed || state.error != null || state.finished) return
        if(width != BENCHMARK_OUTPUT_WIDTH || height != BENCHMARK_OUTPUT_HEIGHT) {
            holder.setFixedSize(BENCHMARK_OUTPUT_WIDTH, BENCHMARK_OUTPUT_HEIGHT)
            return
        }
        if(!holder.surface.isValid) return
        output = holder.surface
        if(!started) {
            started = true
            if(tooHot()) { fail(R.string.superres_benchmark_hot); return }
            try {
                player = ExoPlayer.Builder(context).build()
                player!!.apply {
                    volume = 0f
                    repeatMode = Player.REPEAT_MODE_ONE
                    addListener(playerListener)
                    setMediaItem(MediaItem.fromUri(Uri.parse("asset:///superres/benchmark.mp4")))
                    prepare()
                }
                startTier(SuperResTier.FAST)
            } catch(_: Exception) { fail(R.string.superres_benchmark_decode_failed) }
        } else renderer?.attachOutput(holder.surface, BENCHMARK_OUTPUT_WIDTH, BENCHMARK_OUTPUT_HEIGHT)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        diagnose(BenchmarkEvent.OUTPUT_DESTROYED)
        output = null
        if(!closed && !state.finished && state.error == null) fail(R.string.superres_benchmark_interrupted)
    }

    private fun startTier(tier: SuperResTier) {
        if(closed || state.error != null || tooHot()) {
            if(!closed && state.error == null) fail(R.string.superres_benchmark_hot)
            return
        }
        val surface = output?.takeIf { it.isValid } ?: run { fail(R.string.superres_benchmark_interrupted); return }
        val passEpoch = ++epoch
        generation = null
        deadline = SystemClock.elapsedRealtime() + BENCHMARK_TIER_TIMEOUT_MS
        tierStartedAt = deadline - BENCHMARK_TIER_TIMEOUT_MS
        releaseRequestedAt = 0L
        releaseCallbackAt = 0L
        lastSample = null
        setStage(BenchmarkStage.STARTING)
        publish(state.copy(tier = tier, frames = 0))
        diagnose(BenchmarkEvent.TIER_STARTED)
        try {
            renderer = SuperResRenderer(context.assets, onInputSurface = { input ->
                if(!closed && state.error == null && passEpoch == epoch) {
                    player?.apply { setVideoSurface(input); seekTo(0); play() }
                    diagnose(BenchmarkEvent.INPUT_READY)
                }
            }, onError = {
                if(!closed && passEpoch == epoch) fail(R.string.superres_benchmark_render_failed)
            }).also { next ->
                next.setVideoSize(1280, 720)
                next.configure(SuperResConfig(SuperResPolicy.chain(anime = true, tier = tier), frameRateHint = 24f))
                next.attachOutput(surface, BENCHMARK_OUTPUT_WIDTH, BENCHMARK_OUTPUT_HEIGHT)
            }
            handler.removeCallbacks(poll)
            handler.post(poll)
        } catch(_: Exception) { fail(R.string.superres_benchmark_render_failed) }
    }

    private fun validSample(sample: SuperResStatus): Boolean = sample.frames >= 60 && validMeasurement(sample)

    private fun validMeasurement(sample: SuperResStatus): Boolean = sample.initialized && sample.active &&
        sample.averageMs.isFinite() && sample.averageMs > 0.0 && sample.passes > 0 &&
        sample.inputWidth == 1280 && sample.inputHeight == 720 &&
        sample.viewportWidth == BENCHMARK_OUTPUT_WIDTH && sample.viewportHeight == BENCHMARK_OUTPUT_HEIGHT &&
        sample.outputWidth == BENCHMARK_OUTPUT_WIDTH && sample.outputHeight == BENCHMARK_OUTPUT_HEIGHT &&
        sample.timing != SuperResTiming.NONE

    private fun completeTier(sample: SuperResStatus) {
        val tier = state.tier ?: return
        lastSample = sample
        diagnose(BenchmarkEvent.TIER_MEASURED, sample)
        handler.removeCallbacks(poll)
        player?.pause()
        player?.clearVideoSurface()
        val completed = renderer ?: return
        renderer = null
        val passEpoch = ++epoch
        val result = SuperResBenchmarkResult(tier, sample.averageMs, sample.timing, sample.frames, sample.passes)
        publish(state.copy(results = state.results + result))
        setStage(BenchmarkStage.AWAITING_RELEASE)
        val releaseStartedAt = SystemClock.elapsedRealtime()
        releaseRequestedAt = releaseStartedAt
        diagnose(BenchmarkEvent.RELEASE_REQUESTED, sample)
        // Keep the same deadline active even if a driver's asynchronous EGL release stalls.
        handler.postDelayed(poll, 100)
        // Await EGL's disconnect before a different renderer attaches to the same SurfaceView.
        completed.release {
            val callbackAt = SystemClock.elapsedRealtime()
            releaseCallbackAt = callbackAt
            // A late release after failure/close is diagnostic evidence too. It cannot
            // restart a tier because the original ownership guards remain below.
            diagnose(BenchmarkEvent.RELEASE_CALLBACK, completed.status(), callbackEpoch = passEpoch,
                callbackTier = tier, releaseStart = releaseStartedAt, callbackAt = callbackAt)
            if(closed || state.error != null || passEpoch != epoch) return@release
            val next = SuperResTier.entries.getOrNull(tier.ordinal + 1)
            if(next != null) startTier(next) else {
                publish(state.copy(finished = true))
                setStage(BenchmarkStage.FINISHED)
                diagnose(BenchmarkEvent.FINISHED, sample)
                releasePlayer()
            }
        }
    }

    private fun tooHot(): Boolean = Build.VERSION.SDK_INT >= 29 &&
        power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE

    private fun publish(next: SuperResBenchmarkState) { state = next; onState(next) }

    private fun setStage(next: BenchmarkStage) {
        stage = next
        stageStartedAt = SystemClock.elapsedRealtime()
    }

    /** Fixed events and numeric/enum state only; never stringify errors, media, URLs or headers. */
    private fun diagnose(event: BenchmarkEvent, sample: SuperResStatus? = renderer?.status() ?: lastSample,
        failure: BenchmarkFailure = BenchmarkFailure.NONE, callbackEpoch: Long = -1L,
        callbackTier: SuperResTier? = null, releaseStart: Long = releaseRequestedAt,
        callbackAt: Long = releaseCallbackAt) {
        val now = SystemClock.elapsedRealtime()
        lastDiagnosticAt = now
        val currentPlayer = player
        val validFrames = sample?.takeIf(::validMeasurement)?.frames ?: 0L
        Log.i(BENCHMARK_DIAGNOSTIC_TAG, "run=$diagnosticRun event=$event stage=$stage tier=${state.tier} " +
            "failure=$failure nowMs=$now sessionElapsedMs=${now - sessionStartedAt} " +
            "stageElapsedMs=${now - stageStartedAt} tierElapsedMs=${if(tierStartedAt == 0L) -1L else now - tierStartedAt} " +
            "deadlineMs=$deadline remainingMs=${deadline - now} epoch=$epoch expectedGeneration=${generation ?: -1L} " +
            "releaseRequestedMs=$releaseStart releaseCallbackMs=$callbackAt " +
            "releaseElapsedMs=${if(releaseStart == 0L) -1L else (if(callbackAt == 0L) now else callbackAt) - releaseStart} " +
            "callbackEpoch=$callbackEpoch callbackTier=$callbackTier closed=$closed finished=${state.finished} " +
            "playerPresent=${currentPlayer != null} playerState=${currentPlayer?.playbackState ?: -1} " +
            "isPlaying=${currentPlayer?.isPlaying ?: false} playWhenReady=${currentPlayer?.playWhenReady ?: false} " +
            "suppression=${currentPlayer?.playbackSuppressionReason ?: -1} " +
            "outputPresent=${output != null} outputValid=${output?.isValid ?: false} " +
            "surfaceWidth=$surfaceWidth surfaceHeight=$surfaceHeight rendererPresent=${renderer != null} " +
            "samplePresent=${sample != null} initialized=${sample?.initialized ?: false} active=${sample?.active ?: false} " +
            "frames=${sample?.frames ?: 0L} validFrames=$validFrames requiredFrames=60 " +
            "sampleValid=${sample?.let(::validSample) ?: false} presentations=${sample?.presentations ?: 0L} " +
            "generation=${sample?.generation ?: -1L} timing=${sample?.timing ?: SuperResTiming.NONE} " +
            "gpuMs=${sample?.gpuMs ?: 0.0} averageMs=${sample?.averageMs ?: 0.0} " +
            "frameIntervalMs=${sample?.frameIntervalMs ?: 0.0} passes=${sample?.passes ?: 0} " +
            "inputWidth=${sample?.inputWidth ?: 0} inputHeight=${sample?.inputHeight ?: 0} " +
            "outputWidth=${sample?.outputWidth ?: 0} outputHeight=${sample?.outputHeight ?: 0} " +
            "viewportWidth=${sample?.viewportWidth ?: 0} viewportHeight=${sample?.viewportHeight ?: 0} " +
            "rectLeft=${sample?.imageRect?.left ?: 0f} rectTop=${sample?.imageRect?.top ?: 0f} " +
            "rectWidth=${sample?.imageRect?.width ?: 0f} rectHeight=${sample?.imageRect?.height ?: 0f} " +
            "consecutiveOverloaded=${sample?.consecutiveOverloadedFrames ?: 0} " +
            "overloadDetected=${sample?.overloadDetected ?: false} rendererError=${sample?.error != null}")
    }

    private fun fail(message: Int) {
        if(closed || state.error != null || state.finished) return
        val failure = when(message) {
            R.string.superres_benchmark_timeout -> BenchmarkFailure.TIMEOUT
            R.string.superres_benchmark_decode_failed -> BenchmarkFailure.DECODE
            R.string.superres_benchmark_render_failed -> BenchmarkFailure.RENDER
            R.string.superres_benchmark_hot -> BenchmarkFailure.THERMAL
            R.string.superres_benchmark_interrupted -> BenchmarkFailure.INTERRUPTED
            else -> BenchmarkFailure.OTHER
        }
        diagnose(BenchmarkEvent.FAILED, failure = failure)
        publish(state.copy(error = message))
        stop()
    }

    fun interrupt() { fail(R.string.superres_benchmark_interrupted) }

    private fun releasePlayer() {
        player?.apply { removeListener(playerListener); clearVideoSurface(); release() }
        player = null
    }

    private fun stop() {
        epoch++
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        renderer?.release()
        renderer = null
    }

    fun close() {
        if(closed) return
        closed = true
        diagnose(BenchmarkEvent.CLOSED)
        stop()
        output = null
    }
}
