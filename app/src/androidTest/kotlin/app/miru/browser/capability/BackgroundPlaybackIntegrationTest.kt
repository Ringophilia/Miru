// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.capability

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.SystemClock
import androidx.media3.common.C
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.player.PlaybackRequest
import app.miru.player.PlaybackRequests
import app.miru.player.PlayerActivity
import app.miru.player.PlayerPreferencesRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundPlaybackIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository = PlayerPreferencesRepository(context)

    @Test fun enabledKeepsAudioAndMediaControlsWhenThePlayerIsHidden() = withPlayer(enabled = true) { activity ->
        waitFor { foregroundService() }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        }
        waitFor {
            context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
            }
        }
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MEDIA_CONTENT_CONTROL)
        try {
            val controls = checkNotNull(mediaController())
            val returnToPlayer = checkNotNull(controls.sessionActivity)
            instrumentation.runOnMainSync { assertTrue(activity.moveTaskToBack(true)) }
            waitFor { activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED }
            waitFor {
                var audioOnly = false
                instrumentation.runOnMainSync {
                    val player = activity.playback.player
                    audioOnly = player?.currentTracks?.let { tracks ->
                        tracks.isTypeSelected(C.TRACK_TYPE_AUDIO) && !tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                    } == true
                }
                audioOnly && activity.playback.state.value.playing && !activity.isInPictureInPictureMode
            }
            val position = activity.playback.state.value.position
            waitFor { activity.playback.state.value.position >= position + 500 }
            assertTrue(foregroundService())
            controls.transportControls.pause()
            waitFor { !activity.playback.state.value.playing }
            controls.transportControls.play()
            waitFor { activity.playback.state.value.playing }
            returnToPlayer.send()
            waitFor { activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED }
            waitFor { activity.playback.state.value.let { it.playing && it.width > 0 && it.height > 0 } }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun disabledPausesWhenThePlayerIsHidden() = withPlayer(enabled = false) { activity ->
        assertFalse(app.miru.player.PlayerPreferences().backgroundPlayback)
        instrumentation.runOnMainSync { assertTrue(activity.moveTaskToBack(true)) }
        waitFor { !activity.playback.state.value.playing }
        assertFalse(foregroundService())
    }

    @Test fun privateVideoDoesNotPublishABackgroundSession() = withPlayer(enabled = true, privateVideo = true) { activity ->
        assertFalse(foregroundService())
        instrumentation.runOnMainSync { assertTrue(activity.moveTaskToBack(true)) }
        waitFor { !activity.playback.state.value.playing }
        assertFalse(foregroundService())
    }

    private fun withPlayer(enabled: Boolean, privateVideo: Boolean = false, block: (PlayerActivity) -> Unit) {
        val saved = runBlocking { repository.settings.first() }
        val video = File.createTempFile("background-playback-", ".mp4", context.cacheDir)
        var scenario: ActivityScenario<PlayerActivity>? = null
        try {
            instrumentation.context.assets.open("media/fixture.mp4").use { input ->
                video.outputStream().use { input.copyTo(it) }
            }
            runBlocking { repository.update { it.copy(backgroundPlayback = enabled, autoPip = enabled && !privateVideo) } }
            val request = PlaybackRequest(video.toURI().toString(), "Background playback fixture", mimeType = "video/mp4", isPrivate = privateVideo)
            val opened = ActivityScenario.launch<PlayerActivity>(PlaybackRequests.intent(context, request))
            scenario = opened
            lateinit var activity: PlayerActivity
            opened.onActivity { activity = it }
            waitFor { activity.playback.state.value.let { it.playing && it.width > 0 && it.height > 0 } }
            block(activity)
        } finally {
            scenario?.close()
            runBlocking { repository.update { saved } }
            video.delete()
        }
        waitFor { !foregroundService() }
    }

    private fun mediaController(): MediaController? =
        context.getSystemService(MediaSessionManager::class.java).getActiveSessions(null)
            .firstOrNull { it.packageName == context.packageName }

    @Suppress("DEPRECATION")
    private fun foregroundService(): Boolean =
        context.getSystemService(ActivityManager::class.java).getRunningServices(100)
            .any { it.service.className == "app.miru.player.PlayerService" && it.foreground }

    private fun waitFor(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= end) throw AssertionError("Background playback condition timed out")
            SystemClock.sleep(50)
        }
    }
}
