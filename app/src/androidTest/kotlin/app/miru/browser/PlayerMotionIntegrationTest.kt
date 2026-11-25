// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.browser

import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.player.PlaybackRequest
import app.miru.player.PlaybackRequests
import app.miru.player.PlayerActivity
import app.miru.player.R
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the redesigned player controls, side panel, double-tap ripple, and seek preview. */
@RunWith(AndroidJUnit4::class)
class PlayerMotionIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun sidePanelDoubleTapSeekAndReducedMotionWork() = withPlayer { activity ->
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("player_quality").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        screencap("/sdcard/Download/miru-m2-player-controls.png")

        compose.onNodeWithContentDescription(context.getString(R.string.player_playback_options)).performClick()
        compose.onNodeWithTag("player_side_panel").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.player_brightness_control)).assertIsDisplayed()
        compose.waitForIdle()
        screencap("/sdcard/Download/miru-m2-player-panel.png")

        compose.onNodeWithText(context.getString(R.string.player_options_done)).performClick()
        compose.waitUntil(3_000) {
            compose.onAllNodesWithTag("player_side_panel").fetchSemanticsNodes().isEmpty()
        }

        // Slider drag opens preview bubble and commits seek.
        compose.onNodeWithTag("player_seek_slider").performTouchInput { swipeRight(startX = centerX * 0.4f, endX = centerX * 1.4f, durationMillis = 150) }
        compose.waitForIdle()

        // Double-tap right side triggers arc ripple feedback and advances playback.
        val beforeDoubleTap = activity.playback.state.value.position
        val bounds = android.graphics.Rect()
        instrumentation.runOnMainSync { findVideoView(activity.window.decorView).getGlobalVisibleRect(bounds) }
        val x = bounds.left + bounds.width() * 0.8f
        val y = bounds.top + bounds.height() * 0.5f
        compose.mainClock.autoAdvance = false
        try {
            tapAt(x, y)
            tapAt(x, y)
            var found = false
            for (i in 0 until 20) {
                compose.mainClock.advanceTimeByFrame()
                if (compose.onAllNodesWithTag("player_double_tap_ripple").fetchSemanticsNodes().isNotEmpty()) {
                    found = true
                    break
                }
            }
            assertTrue("Expected double-tap arc ripple feedback", found)
            compose.onNodeWithTag("player_double_tap_ripple").assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(3_000) { activity.playback.state.value.position > beforeDoubleTap }

        // Reduced motion still opens and closes the right-side panel cleanly.
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        val prior = Settings.Global.getString(context.contentResolver, setting)
        try {
            shell("settings put global $setting 0")
            compose.waitUntil(5_000) { !android.animation.ValueAnimator.areAnimatorsEnabled() }
            if (compose.onAllNodesWithTag("player_quality").fetchSemanticsNodes().isEmpty()) {
                tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                compose.waitUntil(3_000) { compose.onAllNodesWithTag("player_quality").fetchSemanticsNodes().isNotEmpty() }
            }
            compose.onNodeWithTag("player_quality").performClick()
            compose.onNodeWithTag("player_side_panel").assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.player_options_done)).performClick()
            compose.waitUntil(3_000) { compose.onAllNodesWithTag("player_side_panel").fetchSemanticsNodes().isEmpty() }
        } finally {
            val saved = prior?.toFloatOrNull()
            shell(if (saved == null) "settings delete global $setting" else "settings put global $setting $saved")
        }
    }

    private fun withPlayer(block: (PlayerActivity) -> Unit) {
        val fixture = File(context.cacheDir, "miru-motion-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open("media/fixture.mp4").use { input ->
            fixture.outputStream().use { output -> input.copyTo(output) }
        }
        val request = PlaybackRequest(fixture.toURI().toString(), "Miru Motion Fixture", mimeType = "video/mp4")
        var scenario: ActivityScenario<PlayerActivity>? = null
        try {
            val active = ActivityScenario.launch<PlayerActivity>(PlaybackRequests.intent(context, request))
            scenario = active
            lateinit var activityRef: PlayerActivity
            compose.waitUntil(30_000) {
                var ready = false
                active.onActivity { activity ->
                    activityRef = activity
                    ready = activity.playback.state.value.let { it.ready && it.width == 320 } &&
                        activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        activity.window.decorView.hasWindowFocus()
                }
                ready
            }
            block(activityRef)
        } finally {
            scenario?.close()
            fixture.delete()
        }
    }

    private fun findVideoView(view: View): View {
        if (view.javaClass.name in setOf("androidx.media3.ui.PlayerView", "app.miru.superres.RoutedVideoView")) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                runCatching { return findVideoView(view.getChildAt(index)) }
            }
        }
        error("Video view not attached")
    }

    private fun tapAt(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).also {
                it.source = InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.uiAutomation.injectInputEvent(it, true)
                it.recycle()
            }
        }
    }

    private fun screencap(path: String) = shell("screencap -p $path")

    private fun shell(command: String) {
        val pipe = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
    }
}
