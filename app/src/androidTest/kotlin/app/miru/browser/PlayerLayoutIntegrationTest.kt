// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.browser

import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.player.PlaybackRequest
import app.miru.player.PlaybackRequests
import app.miru.player.PlayerActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Uses the real local decoder and Activity window; no Gecko runtime, HTTP origin or public network. */
@RunWith(AndroidJUnit4::class)
class PlayerLayoutIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun landscapePlaybackCanExitToPortraitDetailsAndReenterFullscreen() {
        val fixture = File(context.cacheDir, "miru-layout-${System.nanoTime()}.mp4")
        instrumentation.context.assets.open("media/fixture.mp4").use { input -> fixture.outputStream().use { output -> input.copyTo(output) } }
        val title = "Miru portrait layout fixture"
        val request = PlaybackRequest(
            fixture.toURI().toString(), title, pageUrl = "https://layout-fixture.invalid/watch",
            mimeType = "video/mp4", isPrivate = false,
        )
        var scenario: ActivityScenario<PlayerActivity>? = null
        try {
            val active = ActivityScenario.launch<PlayerActivity>(PlaybackRequests.intent(context, request))
            scenario = active
            waitFor {
                var ready = false
                active.onActivity { activity ->
                    ready = activity.playback.state.value.let { it.ready && it.playing && it.width == 320 && it.height == 180 } &&
                        activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && activity.window.decorView.hasWindowFocus()
                }
                ready
            }
            compose.waitForIdle()
            var initialPlayer: Any? = null
            active.onActivity { initialPlayer = it.playback.player }

            // A real system Back dispatch must exit fullscreen before leaving this Activity.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor {
                var portrait = false
                active.onActivity { activity -> portrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT && activity.window.decorView.width < activity.window.decorView.height }
                portrait
            }
            compose.waitForIdle()
            val portraitBounds = bounds(active)
            assertTrue("Portrait video must have a visible native surface", portraitBounds.width() > 0 && portraitBounds.height() > 0)
            val ratio = portraitBounds.width().toFloat() / portraitBounds.height()
            assertTrue("Landscape media should occupy a 16:9 box in portrait, actual=$ratio", abs(ratio - 16f / 9f) < 0.03f)
            active.onActivity { activity ->
                assertSame("Orientation changes must preserve the current player", initialPlayer, activity.playback.player)
                assertFalse(activity.isFinishing)
                assertTrue("The video box must leave space for portrait details", portraitBounds.height() < activity.window.decorView.height * 0.6f)
            }
            val detailTitle = compose.onAllNodesWithText(title).fetchSemanticsNodes().firstOrNull { it.boundsInRoot.top >= portraitBounds.bottom - 2 }
            assertNotNull("The portrait title must be placed below the native video view", detailTitle)
            compose.onNodeWithText(context.getString(app.miru.player.R.string.player_source_site, "layout-fixture.invalid")).assertIsDisplayed()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/miru-m2-031-portrait.png")).use { it.readBytes() }

            // The equivalent controls are explicit, named sliders, including when gestures are unavailable.
            compose.onNodeWithText(context.getString(app.miru.player.R.string.player_playback_options)).performClick()
            val brightness = compose.onNodeWithContentDescription(context.getString(app.miru.player.R.string.player_brightness_control))
            brightness.performScrollTo().assertIsDisplayed()
            assertNotNull(brightness.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
            val volume = compose.onNodeWithContentDescription(context.getString(app.miru.player.R.string.player_volume_control))
            volume.performScrollTo().assertIsDisplayed()
            assertNotNull(volume.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
            compose.onNodeWithText(context.getString(app.miru.player.R.string.player_options_done)).performClick()
            compose.waitForIdle()

            val enterFullscreen = context.getString(app.miru.player.R.string.player_enter_fullscreen)
            if(compose.onAllNodesWithContentDescription(enterFullscreen).fetchSemanticsNodes().isEmpty()) {
                tapVideo(active)
                compose.waitUntil(3_000) { compose.onAllNodesWithContentDescription(enterFullscreen).fetchSemanticsNodes().isNotEmpty() }
            }
            compose.onNodeWithContentDescription(enterFullscreen).performClick()
            waitFor {
                var landscape = false
                active.onActivity { activity -> landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && activity.window.decorView.width > activity.window.decorView.height }
                landscape
            }
            compose.waitForIdle()
            val fullscreenBounds = bounds(active)
            active.onActivity { activity ->
                assertSame(initialPlayer, activity.playback.player)
                assertTrue("Fullscreen native video must fill the window height", fullscreenBounds.height() >= activity.window.decorView.height * 0.9f)
                assertFalse(activity.isFinishing)
            }
        } finally {
            scenario?.close()
            fixture.delete()
        }
    }

    private fun bounds(scenario: ActivityScenario<PlayerActivity>): Rect {
        val result = Rect()
        scenario.onActivity { activity -> checkNotNull(findPlayerView(activity.window.decorView)) { "Native PlayerView was not attached" }.getGlobalVisibleRect(result) }
        return result
    }

    private fun findPlayerView(view: View): View? {
        if(view.javaClass.name in setOf("androidx.media3.ui.PlayerView", "app.miru.superres.RoutedVideoView")) return view
        if(view is ViewGroup) for(index in 0 until view.childCount) findPlayerView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun tapVideo(scenario: ActivityScenario<PlayerActivity>) {
        val rectangle = bounds(scenario)
        val x = rectangle.left + rectangle.width() * 0.85f
        val y = rectangle.top + rectangle.height() * 0.4f
        val down = SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).also {
            it.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(it, true)
            it.recycle()
        }
    }

    private fun waitFor(timeout: Long = 30_000, predicate: () -> Boolean) {
        compose.waitUntil(timeout) { predicate() }
    }
}
