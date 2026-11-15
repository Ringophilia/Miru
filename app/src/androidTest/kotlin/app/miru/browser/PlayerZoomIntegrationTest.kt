// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.player.PlaybackRequest
import app.miru.player.PlaybackRequests
import app.miru.player.PlayerActivity
import app.miru.player.PlayerPreferencesRepository
import app.miru.superres.RoutedVideoView
import app.miru.superres.SuperResMode
import app.miru.superres.SuperResTier
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerZoomIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun pinchAndPanChangeVisibleVideoAndSnapToFitAndCover() = withPlayer { scenario, view ->
        val viewport = viewport(view)
        val base = viewOnMain(view) { imageRect() }
        val cover = max(viewport.width() / base.width, viewport.height() / base.height)
        assertTrue("The landscape viewport must allow distinct fit and cover sizes", cover in 1.08f..2f)
        val before = screenshot()
        try {
            pinch(viewport, .16f, .64f)
            waitFor { viewOnMain(view) { surfaceView.scaleX == 3f } }
            waitForSomePixelsToChange(before, viewport)
        } finally {
            before.recycle()
        }
        val centered = viewOnMain(view) { imageRect() }
        pinch(viewport, .30f, .30f, panX = .08f, panY = .12f)
        waitFor {
            val moved = viewOnMain(view) { imageRect() }
            moved.left > centered.left + viewport.width() * .06f && moved.top > centered.top + viewport.height() * .10f
        }
        pinch(viewport, .60f, .60f * cover * 1.025f / 3f)
        waitFor {
            viewOnMain(view) {
                surfaceView.scaleX == 1f && abs(imageRect().width - base.width * cover) < 2f
            }
        }
        pinch(viewport, .50f, .50f * 1.025f / cover)
        waitFor { viewOnMain(view) { surfaceView.scaleX == 1f && imageRect() == base } }

        pinch(viewport, .20f, .40f)
        waitFor { viewOnMain(view) { abs(surfaceView.scaleX - 2f) < .02f } }
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        waitFor {
            var portrait = false
            scenario.onActivity { portrait = it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            portrait && viewport(view).width() < viewport.width()
        }
        assertEquals(2f, viewOnMain(view) { surfaceView.scaleX }, .02f)
        scenario.onActivity { assertFalse("Pinching a paused video must not toggle playback", it.playback.state.value.playing) }
        if (context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            scenario.onActivity { it.enterPip() }
            waitFor {
                var inPip = false
                scenario.onActivity { inPip = it.isInPictureInPictureMode }
                inPip && viewOnMain(view) { surfaceView.scaleX == 1f && surfaceView.translationX == 0f }
            }
        }
    }

    @Test fun pausedSuperResolutionOutputCanBeZoomedWithoutRebuildingRenderer() = withPlayer { scenario, view ->
        scenario.onActivity { it.playback.superResMode(SuperResMode.GENERAL); it.playback.resume() }
        waitFor { view.status().let { it.active && it.frames >= 3 } }
        scenario.onActivity { it.playback.pause() }
        val viewport = viewport(view)
        val generation = view.status().generation
        val before = screenshot()
        try {
            pinch(viewport, .20f, .40f)
            waitFor { viewOnMain(view) { abs(surfaceView.scaleX - 2f) < .02f } }
            waitForSomePixelsToChange(before, viewport)
            assertTrue(view.status().active)
            assertEquals(generation, view.status().generation)
        } finally {
            before.recycle()
        }
    }

    private fun withPlayer(test: (ActivityScenario<PlayerActivity>, RoutedVideoView) -> Unit) {
        val repository = PlayerPreferencesRepository(context)
        val saved = runBlocking { repository.settings.first() }
        val file = File.createTempFile("miru-zoom-", ".mp4", context.cacheDir)
        try {
            runBlocking { repository.update {
                it.copy(superResMode = SuperResMode.OFF, superResTier = SuperResTier.FAST,
                    backgroundPlayback = false, autoPip = false, superResDeviceCeiling = null)
            } }
            context.assets.open("superres/benchmark.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
            val request = PlaybackRequest(file.toURI().toString(), "Zoom fixture", mimeType = "video/mp4")
            ActivityScenario.launch<PlayerActivity>(PlaybackRequests.intent(context, request)).use { scenario ->
                lateinit var view: RoutedVideoView
                waitFor {
                    var ready = false
                    scenario.onActivity { activity ->
                        activity.playback.player?.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                        find(activity.window.decorView)?.let { view = it }
                        ready = activity.playback.state.value.let { it.ready && it.playing && it.width == 1280 && it.height == 720 } &&
                            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                            activity.hasWindowFocus()
                    }
                    ready
                }
                scenario.onActivity { it.playback.pause() }
                instrumentation.waitForIdleSync()
                test(scenario, view)
            }
        } finally {
            runBlocking { repository.update { saved } }
            file.delete()
        }
    }

    private fun find(view: View): RoutedVideoView? = when (view) {
        is RoutedVideoView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
        else -> null
    }

    private fun <T> viewOnMain(view: RoutedVideoView, block: RoutedVideoView.() -> T): T {
        val result = java.util.concurrent.atomic.AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(view.block()) }
        return result.get()
    }

    private fun viewport(view: RoutedVideoView): Rect = viewOnMain(view) {
        val location = IntArray(2)
        getLocationOnScreen(location)
        Rect(location[0], location[1], location[0] + width, location[1] + height)
    }

    private fun pinch(view: Rect, from: Float, to: Float, panX: Float = 0f, panY: Float = 0f) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, count: Int, fraction: Float) {
            val spread = from + (to - from) * fraction
            val centerX = view.exactCenterX() + view.width() * panX * fraction
            // Start in the video area between the header and the transport controls.
            val centerY = view.top + view.height() * (.34f + panY * fraction)
            val properties = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coordinates = Array(count) { i -> MotionEvent.PointerCoords().apply {
                x = centerX + view.width() * spread * (if (i == 0) -.5f else .5f)
                y = centerY
                pressure = 1f
                size = .04f
            } }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            SystemClock.sleep(24)
        }
        send(MotionEvent.ACTION_DOWN, 1, 0f)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 0f)
        for (step in 1..8) send(MotionEvent.ACTION_MOVE, 2, step / 8f)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 1f)
        send(MotionEvent.ACTION_UP, 1, 1f)
    }

    private fun screenshot(): Bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())

    private fun waitForSomePixelsToChange(before: Bitmap, viewport: Rect) {
        waitFor {
            val after = screenshot()
            try {
                var changed = 0
                for (y in viewport.top + viewport.height() / 4 until viewport.bottom - viewport.height() / 4 step 16) {
                    for (x in viewport.left + 32 until viewport.right - 32 step 16) {
                        if (before.getPixel(x, y) != after.getPixel(x, y)) changed++
                    }
                }
                changed > 100
            } finally {
                after.recycle()
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 30_000
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= end) throw AssertionError("Video zoom condition timed out")
            SystemClock.sleep(50)
        }
    }
}
