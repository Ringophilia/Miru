// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.*

class PlayerActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var playback: PlayerController; private set
    private lateinit var request: PlaybackRequest
    private var pip by mutableStateOf(false)
    private var failed = false
    private var orientationSet = false
    private var videoBounds: Rect? = null
    private var orientationLocked = false
    private var orientationBeforeLock = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var receiverRegistered = false
    private var subtitlePickerOpen = false
    private var resumeAfterSubtitlePicker = false
    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        subtitlePickerOpen = false
        if(::playback.isInitialized && !isFinishing) {
            if(uri != null) playback.loadLocalSubtitle(uri)
            if(resumeAfterSubtitlePicker) playback.resume()
            updatePip()
        }
        resumeAfterSubtitlePicker = false
    }
    private val actionName get() = "$packageName.miru.PLAYER_CONTROL"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when(intent?.getIntExtra("command", -1)) { 0 -> playback.seekBy(-10_000); 1 -> playback.toggle(); 2 -> playback.seekBy(10_000) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra("miru.playback.token")
        val supplied = if(token != null) PlaybackRequests.take(token) else null
        val external = intent.data?.takeIf { intent.action == Intent.ACTION_VIEW && it.scheme in setOf("https", "http", "content") }
        // Missing in-memory context after process death must not reconstruct private headers from disk.
        request = supplied ?: if(token == null && external != null) PlaybackRequest(external.toString(), getString(R.string.player_title), mimeType = intent.type)
            else { finish(); return }
        if(request.isPrivate) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        playback = PlayerController(this, request, scope) { returnToWeb() }
        if(Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(actionName), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, IntentFilter(actionName))
        receiverRegistered = true
        scope.launch { playback.state.collect { state ->
            if(!orientationSet && !orientationLocked && state.width > 0 && state.height > 0) {
                orientationSet = true
                requestedOrientation = if(state.width >= state.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            updatePip()
        } }
        setContent {
            PlayerScreen(playback, pip, onBack = ::handleBack, onReturnToWeb = ::returnToWeb, onPip = ::enterPip,
                onOrientation = ::toggleOrientation,
                onImportSubtitle = ::openSubtitlePicker,
                onVideoBounds = { bounds -> if(!pip) videoBounds = Rect(bounds) },
                onEmbeddedModeChanged = ::updateSystemBars,
                onLockChanged = ::lockOrientation)
        }
    }

    private fun openSubtitlePicker() {
        if(subtitlePickerOpen) return
        subtitlePickerOpen = true
        resumeAfterSubtitlePicker = playback.state.value.playing
        updatePip()
        runCatching { subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "application/x-ass", "application/x-ssa", "application/octet-stream")) }
            .onFailure { subtitlePickerOpen = false; resumeAfterSubtitlePicker = false; updatePip(); Toast.makeText(this, R.string.player_subtitle_import_error, Toast.LENGTH_LONG).show() }
    }

    private fun returnToWeb() {
        failed = true
        Toast.makeText(this, R.string.player_web_required, Toast.LENGTH_LONG).show()
        finish()
    }
    private fun handleBack() {
        if(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            orientationSet = true
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        else if(playback.state.value.playing && playback.preferences.value.autoPip && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) enterPip()
        else finish()
    }
    private fun toggleOrientation() {
        if(orientationLocked) return
        // An explicit choice made before metadata arrives must not be overwritten by auto orientation.
        orientationSet = true
        requestedOrientation = if(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    private fun lockOrientation(locked: Boolean) {
        if(orientationLocked == locked) return
        orientationLocked = locked
        if(locked) {
            orientationBeforeLock = requestedOrientation
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else requestedOrientation = orientationBeforeLock
    }
    private fun updateSystemBars(embedded: Boolean) {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if(embedded) show(WindowInsetsCompat.Type.systemBars()) else hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    private fun pipParams(): PictureInPictureParams {
        val state = playback.state.value
        val ratio = if(state.width > 0 && state.height > 0) (state.width.toFloat() / state.height).coerceIn(1f / 2.39f, 2.39f) else 16f / 9
        val commands = listOf(R.string.player_rewind, if(state.playing) R.string.player_pause else R.string.player_play, R.string.player_forward)
        val icons = listOf(android.R.drawable.ic_media_rew, if(state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, android.R.drawable.ic_media_ff)
        val actions = commands.mapIndexed { index, text ->
            val action = Intent(actionName).setPackage(packageName).putExtra("command", index)
            RemoteAction(Icon.createWithResource(this, icons[index]), getString(text), getString(text), PendingIntent.getBroadcast(this, index, action, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000)).setActions(actions)
            .setSourceRectHint(videoBounds?.takeIf { it.width() > 0 && it.height() > 0 } ?: Rect().also { window.decorView.getGlobalVisibleRect(it) })
            .apply { if(Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(state.playing && playback.preferences.value.autoPip && !failed && !subtitlePickerOpen) }.build()
    }
    private fun updatePip() { if(::playback.isInitialized && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) runCatching { setPictureInPictureParams(pipParams()) } }
    fun enterPip() { if(::playback.isInitialized && playback.state.value.ready && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) runCatching { enterPictureInPictureMode(pipParams()) } }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if(::playback.isInitialized && !subtitlePickerOpen && playback.state.value.playing && playback.preferences.value.autoPip) enterPip() }
    override fun onPictureInPictureModeChanged(inPictureInPictureMode: Boolean, newConfig: Configuration) { super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig); pip = inPictureInPictureMode }
    override fun onStop() { if(::playback.isInitialized && !isInPictureInPictureMode) playback.pause(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState) /* No media URL, title or headers in saved state. */ }
    override fun onDestroy() {
        if(receiverRegistered) unregisterReceiver(receiver)
        if(::playback.isInitialized) playback.release()
        if(::request.isInitialized && isFinishing) request.onReturn?.invoke(failed)
        // Let the final bounded progress write finish; no player survives this Activity.
        scope.launch { delay(500); scope.cancel() }
        super.onDestroy()
    }
}
