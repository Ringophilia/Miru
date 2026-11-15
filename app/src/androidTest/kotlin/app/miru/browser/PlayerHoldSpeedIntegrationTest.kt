// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.content.res.Configuration
import android.media.AudioManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.player.PlaybackRequest
import app.miru.player.PlaybackRequests
import app.miru.player.PlayerActivity
import app.miru.player.PlayerPreferencesRepository
import app.miru.player.R
import app.miru.superres.SuperResMode
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerHoldSpeedIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun slidingWhileHeldUpdatesTheSpeedAndReleaseDoesNotSeekOrAdjustBrightness() = withPlayer(2f) { activity ->
        val touch = HoldTouch(activity, .35f)
        val brightness = activity.window.attributes.screenBrightness
        val positionBefore = activity.playback.state.value.position
        val startedAt = SystemClock.uptimeMillis()
        touch.down()
        try {
            expectHoldSpeed(activity, 2f)
            touch.move(35f, 0f)
            expectHoldSpeed(activity, 2f)
            touch.move(45f, 0f)
            expectHoldSpeed(activity, 2.5f)
            touch.move(85f, 0f)
            expectHoldSpeed(activity, 3f)
            touch.move(-45f, 0f)
            expectHoldSpeed(activity, 1.5f)
            touch.move(0f, 60f)
            expectHoldSpeed(activity, 2f)
            touch.release()
            waitForSpeed(activity, 1.25f)
            compose.onNodeWithText(context.getString(R.string.player_hold_feedback, 2f)).assertDoesNotExist()
            assertEquals(brightness, activity.window.attributes.screenBrightness, .001f)
            val advance = activity.playback.state.value.position - positionBefore
            val elapsed = SystemClock.uptimeMillis() - startedAt
            assertTrue("The hold must not jump the playback position: ${advance}ms over ${elapsed}ms",
                advance in -100L..(elapsed * 3 + 500))
        } finally {
            touch.cancel()
        }
    }

    @Test fun cancellationRestoresSpeedAndPausedVideoDoesNotEnterHoldMode() = withPlayer(2.5f) { activity ->
        val audio = context.getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val touch = HoldTouch(activity, .70f)
        touch.down()
        try {
            expectHoldSpeed(activity, 2.5f)
            touch.move(-45f, -60f)
            expectHoldSpeed(activity, 2f)
            touch.cancel()
            waitForSpeed(activity, 1.25f)
            assertEquals(volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            instrumentation.runOnMainSync { activity.playback.pause() }
            compose.waitUntil(5_000) { !activity.playback.state.value.playing }
            touch.down()
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
            assertEquals(1.25f, activity.playback.state.value.speed)
            compose.onNodeWithText(context.getString(R.string.player_hold_feedback, 2.5f)).assertDoesNotExist()
        } finally {
            touch.cancel()
        }
    }

    private fun expectHoldSpeed(activity: PlayerActivity, speed: Float) {
        waitForSpeed(activity, speed)
        compose.onNodeWithText(context.getString(R.string.player_hold_feedback, speed)).assertIsDisplayed()
    }

    private fun waitForSpeed(activity: PlayerActivity, speed: Float) {
        try {
            compose.waitUntil(5_000) { activity.playback.state.value.speed == speed }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Expected ${speed}x, player state ${activity.playback.state.value}", failure)
        }
        instrumentation.runOnMainSync {
            val parameters = checkNotNull(activity.playback.player).playbackParameters
            assertEquals(speed, parameters.speed)
            assertEquals(1f, parameters.pitch)
        }
    }

    private fun withPlayer(holdSpeed: Float, test: (PlayerActivity) -> Unit) {
        val preferences = PlayerPreferencesRepository(context)
        val saved = runBlocking { preferences.settings.first() }
        val file = File.createTempFile("miru-hold-", ".mp4", context.cacheDir)
        try {
            runBlocking { preferences.update {
                it.copy(holdSpeed = holdSpeed, backgroundPlayback = false, autoPip = false, superResMode = SuperResMode.OFF)
            } }
            instrumentation.context.assets.open("media/fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
            val request = PlaybackRequest(file.toURI().toString(), "Hold speed fixture", mimeType = "video/mp4")
            ActivityScenario.launch<PlayerActivity>(PlaybackRequests.intent(context, request)).use { scenario ->
                lateinit var activity: PlayerActivity
                scenario.onActivity { activity = it }
                compose.waitUntil(30_000) {
                    var ready = false
                    scenario.onActivity {
                        ready = it.playback.state.value.let { state -> state.playing && state.width > 0 } &&
                            it.hasWindowFocus() && it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    }
                    ready
                }
                scenario.onActivity { it.playback.speed(1.25f) }
                waitForSpeed(activity, 1.25f)
                test(activity)
            }
        } finally {
            runBlocking { preferences.update { saved } }
            file.delete()
        }
    }

    private inner class HoldTouch(activity: PlayerActivity, xFraction: Float) {
        private val density = activity.resources.displayMetrics.density
        private var anchorX = 0f
        private var anchorY = 0f
        private var downAt = 0L
        private var x = 0f
        private var y = 0f

        init {
            instrumentation.runOnMainSync {
                val decor = activity.window.decorView
                val origin = IntArray(2)
                decor.getLocationOnScreen(origin)
                anchorX = origin[0] + decor.width * xFraction
                anchorY = origin[1] + decor.height * .34f
            }
        }

        fun down() {
            downAt = SystemClock.uptimeMillis()
            x = anchorX
            y = anchorY
            send(MotionEvent.ACTION_DOWN)
        }

        fun move(dxDp: Float, dyDp: Float) {
            x = anchorX + dxDp * density
            y = anchorY + dyDp * density
            send(MotionEvent.ACTION_MOVE)
        }

        fun release() = finish(MotionEvent.ACTION_UP)
        fun cancel() = finish(MotionEvent.ACTION_CANCEL)

        private fun finish(action: Int) {
            if (downAt == 0L) return
            send(action)
            downAt = 0L
        }

        private fun send(action: Int) {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            SystemClock.sleep(24)
        }
    }
}
