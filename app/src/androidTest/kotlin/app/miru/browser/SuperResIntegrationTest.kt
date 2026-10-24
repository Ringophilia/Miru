// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.browser

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.semantics.SemanticsActions
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.player.*
import app.miru.superres.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Real decoder → OES → unchanged multipass GLSL → displayed SurfaceView, never fake timings. */
@RunWith(AndroidJUnit4::class)
class SuperResIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun qualitySwitchComparisonAndOrientationKeepTheSamePlayer() = withPlayer { scenario ->
        val owner = player(scenario)
        var view = output(scenario)
        scenario.onActivity { it.playback.superResMode(SuperResMode.GENERAL) }
        compose.waitUntil(45_000) { view = output(scenario); view.status().let { it.active && it.frames >= 10 && it.gpuMs > 0 } }
        scenario.onActivity { it.playback.pause() }
        compose.waitForIdle()
        if(compose.onAllNodesWithTag("player_quality").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("player_video_output").performTouchInput { click() }
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("player_quality").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player_quality").performClick()
        compose.onNodeWithContentDescription(context.getString(app.miru.player.R.string.player_superres_compare))
            .performScrollTo().assertIsDisplayed().performClick()
        try {
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("player_superres_comparison").fetchSemanticsNodes().size == 1 }
        } catch(error: Throwable) {
            throw AssertionError("Comparison UI missing after explicitly enabling it; routing=${view.state()}, latestRender=${view.status()}, " +
                "qualityDialogs=${compose.onAllNodesWithContentDescription(context.getString(app.miru.player.R.string.player_superres_compare)).fetchSemanticsNodes().size}", error)
        }
        assertTrue("The comparison must be backed by an enabled enhancement decision", view.state().decision.enabled)
        assertTrue("The comparison must retain a real enhanced image", view.status().active)
        val presentations = view.status().presentations
        compose.onNodeWithTag("player_superres_comparison").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.waitUntil(8_000) { view.status().presentations > presentations }
        val original = copyEventually(view.surfaceView)
        val originalCount = view.status().presentations
        compose.onNodeWithTag("player_superres_comparison").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.waitUntil(8_000) { view.status().presentations > originalCount }
        var enhanced: Bitmap? = null
        try {
            compose.waitUntil(8_000) {
                enhanced?.recycle()
                enhanced = copyEventually(view.surfaceView)
                differentPixels(original, enhanced!!) > 25
            }
            assertTrue("Actual enhancement must change decoded pixels on the same paused frame", differentPixels(original, enhanced!!) > 25)
        } finally { original.recycle(); enhanced?.recycle() }
        // Changing real shaders and fitting must redraw the retained paused image. Decoder frame
        // counters remain independent: neither this assertion nor benchmark uses redraws as frames.
        val pausedAt = position(scenario)
        val previousPresentation = view.status().presentations
        scenario.onActivity { it.playback.superResMode(SuperResMode.ANIME); it.playback.superResTier(SuperResTier.FAST) }
        compose.waitUntil(20_000) { view.state().decision.mode == SuperResMode.ANIME && view.state().decision.tier == SuperResTier.FAST &&
            view.status().let { it.active && it.presentations > previousPresentation && it.passes >= 12 } }
        val fastPresentation = view.status().presentations
        scenario.onActivity { it.playback.superResTier(SuperResTier.BALANCED) }
        compose.waitUntil(20_000) { view.state().decision.tier == SuperResTier.BALANCED &&
            view.status().let { it.active && it.presentations > fastPresentation && it.passes >= 20 } }
        assertFalse("Tier selection must not resume paused playback", playing(scenario))
        assertEquals("Tier selection must keep the paused media position", pausedAt, position(scenario))
        assertEquals("Reprocessing the retained frame must not count as a decoded frame", 0L, view.status().frames)
        val fitLabel = context.getString(app.miru.player.R.string.player_fit)
        if(compose.onAllNodesWithText(fitLabel).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("player_video_output").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .85f, height * .6f)) }
        compose.waitUntil(3_000) { compose.onAllNodesWithText(fitLabel).fetchSemanticsNodes().isNotEmpty() }
        val beforeFit = view.status().presentations
        compose.onNodeWithText(fitLabel).assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { view.status().let { it.active && it.presentations > beforeFit } &&
            view.imageRect().let { it.width >= view.width && it.height >= view.height } }
        assertFalse("Fitting must not resume paused playback", playing(scenario))
        assertEquals(pausedAt, position(scenario))
        assertEquals("Resizing a paused frame must not satisfy decoder frame benchmarks", 0L, view.status().frames)
        val pausedPicture = copyEventually(view.surfaceView)
        try { assertTrue("Paused configuration changes must still display actual pixels", nonBlackPixels(pausedPicture) > 100) } finally { pausedPicture.recycle() }
        scenario.onActivity { activity ->
            assertSame(owner, activity.playback.player)
            activity.playback.superResMode(SuperResMode.OFF)
            activity.playback.resume()
        }
        compose.waitUntil(15_000) { view.state().decision.bypass == SuperResBypass.OFF && !view.state().releasing && playing(scenario) }
        val before = position(scenario)
        compose.waitUntil(5_000) { position(scenario) != before }
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) { var portrait = false; scenario.onActivity { portrait = it.window.decorView.width < it.window.decorView.height }; portrait }
        scenario.onActivity { assertSame("Rotation must retain the current ExoPlayer", owner, it.playback.player) }
    }

    @Test fun actualShaderFailureFallsBackToDirectAndPlaybackContinues() = withPlayer { scenario ->
        val owner = player(scenario)
        val failures = AtomicInteger()
        var replacement: RoutedVideoView? = null
        scenario.onActivity { activity ->
            checkNotNull(find(activity.window.decorView)).dispose()
            replacement = RoutedVideoView(activity, rendererFactory = { assets, input, error ->
                SuperResRenderer(assets, input, error).apply {
                    configure(SuperResConfig(listOf("missing_integration_test_shader")))
                }
            }).apply {
                setDecoderSurfaceListener { owner.setVideoSurface(it) }
                onError = { failures.incrementAndGet() }
                configure(SuperResViewConfig(mode = SuperResMode.GENERAL, sourceWidth = 1280, sourceHeight = 720, frameRate = 24f))
            }
            activity.addContentView(replacement, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val view = replacement!!
        try {
            compose.waitUntil(15_000) { failures.get() == 1 && view.state().error != null && !view.state().releasing && !view.status().active }
            val before = position(scenario)
            compose.waitUntil(8_000) { position(scenario) != before && playing(scenario) }
            // A successful PixelCopy may still copy the fresh Surface's clear buffer
            // before the decoder presents its first direct-output frame. Observe actual
            // pixels within a bounded window, independently of the playback clock.
            var presented = false
            compose.waitUntil(8_000) {
                val image = copyEventually(view.surfaceView)
                try { presented = nonBlackPixels(image) > 100 } finally { image.recycle() }
                presented
            }
            assertTrue("Fallback must display actual pixels", presented)
            assertEquals("A failed renderer reports once", 1, failures.get())
            scenario.onActivity { assertSame(owner, it.playback.player) }
        } finally { scenario.onActivity { view.dispose(); (view.parent as? ViewGroup)?.removeView(view) } }
    }

    @Test fun allThreeRealAnimeNetworksAndFsrCompleteSixtyDisplayedFrames() = withPlayer { scenario ->
        val owner = player(scenario)
        var surface: SurfaceView? = null
        val ready = AtomicReference<android.view.Surface?>()
        scenario.onActivity { activity ->
            checkNotNull(find(activity.window.decorView)).dispose()
            surface = SurfaceView(activity).apply {
                holder.setFixedSize(1920, 1080)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { ready.set(holder.surface) }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { ready.set(holder.surface) }
                    override fun surfaceDestroyed(holder: SurfaceHolder) { ready.set(null) }
                })
            }
            activity.addContentView(surface, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        var renderer: SuperResRenderer? = null
        try {
            compose.waitUntil(10_000) { ready.get()?.isValid == true }
            val chains = SuperResTier.entries.map { SuperResPolicy.chain(true, it) } + listOf(SuperResPolicy.chain(false, SuperResTier.BALANCED))
            for(chain in chains) {
                val failure = AtomicReference<String?>()
                scenario.onActivity {
                    owner.setVideoSurface(null)
                    renderer = SuperResRenderer(context.assets, { input -> owner.setVideoSurface(input) }, { failure.set(it) }).also {
                        it.setVideoSize(1280, 720)
                        it.configure(SuperResConfig(chain, frameRateHint = 24f))
                        it.attachOutput(ready.get()!!, 1920, 1080)
                    }
                }
                try {
                    compose.waitUntil(60_000) {
                        failure.get()?.let { throw AssertionError("Actual GLSL failed for $chain: $it") }
                        renderer!!.status().let { it.active && it.frames >= 60 && it.gpuMs > 0 }
                    }
                } catch(timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                    var playerSnapshot = ""
                    scenario.onActivity { playerSnapshot = "state=${owner.playbackState} playing=${owner.isPlaying} position=${owner.currentPosition}" }
                    throw AssertionError("Real chain did not render 60 valid frames: chain=$chain status=${renderer!!.status()} " +
                        "player=$playerSnapshot", timeout)
                }
                val sample = renderer!!.status()
                android.util.Log.i("MiruSuperResAcceptance", "chain=$chain status=$sample")
                assertEquals(1920, sample.outputWidth)
                assertEquals(1080, sample.outputHeight)
                assertTrue("CNN chains must execute their real multiple passes", sample.passes >= if(chain.first() == "FSR_EASU") 2 else 12)
                val picture = copyEventually(surface!!)
                try { assertTrue(nonBlackPixels(picture) > 100) } finally { picture.recycle() }
                val complete = CountDownLatch(1)
                scenario.onActivity { owner.setVideoSurface(null); renderer!!.release { complete.countDown() } }
                assertTrue("EGL must disconnect before the next chain", complete.await(10, TimeUnit.SECONDS))
                renderer = null
            }
        } finally {
            scenario.onActivity {
                owner.setVideoSurface(null)
                renderer?.release()
                surface?.let { (it.parent as? ViewGroup)?.removeView(it) }
            }
        }
    }

    private fun withPlayer(block: (ActivityScenario<PlayerActivity>) -> Unit) {
        val preferences = PlayerPreferencesRepository(context)
        val saved = runBlocking { preferences.settings.first() }
        runBlocking { preferences.update { it.copy(superResMode = SuperResMode.OFF, superResTier = SuperResTier.BALANCED, superResDeviceCeiling = null) } }
        val file = File(context.cacheDir, "miru-superres-${System.nanoTime()}.mp4")
        context.assets.open("superres/benchmark.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        var scenario: ActivityScenario<PlayerActivity>? = null
        try {
            val launched = ActivityScenario.launch<PlayerActivity>(PlaybackRequests.intent(context, PlaybackRequest(file.toURI().toString(), "Synthetic super resolution fixture", mimeType = "video/mp4")))
            scenario = launched
            compose.waitUntil(30_000) {
                var playable = false
                launched.onActivity { activity -> activity.playback.player?.repeatMode = Player.REPEAT_MODE_ONE
                    playable = activity.playback.state.value.let { it.ready && it.playing && it.width == 1280 && it.height == 720 }
                }
                playable
            }
            block(launched)
        } finally {
            scenario?.close()
            file.delete()
            runBlocking { preferences.update { saved } }
        }
    }

    private fun output(scenario: ActivityScenario<PlayerActivity>): RoutedVideoView {
        var result: RoutedVideoView? = null
        scenario.onActivity { result = find(it.window.decorView) }
        return checkNotNull(result) { "RoutedVideoView missing from the real player" }
    }
    private fun find(view: View): RoutedVideoView? {
        if(view is RoutedVideoView) return view
        if(view is ViewGroup) for(index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun player(scenario: ActivityScenario<PlayerActivity>): androidx.media3.exoplayer.ExoPlayer {
        var result: androidx.media3.exoplayer.ExoPlayer? = null
        scenario.onActivity { result = it.playback.player }
        return checkNotNull(result)
    }
    private fun position(scenario: ActivityScenario<PlayerActivity>): Long { var result = 0L; scenario.onActivity { result = it.playback.player!!.currentPosition }; return result }
    private fun playing(scenario: ActivityScenario<PlayerActivity>): Boolean { var result = false; scenario.onActivity { result = it.playback.player!!.isPlaying }; return result }
    private fun copyEventually(view: SurfaceView): Bitmap {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while(true) {
            val done = CountDownLatch(1)
            val result = AtomicInteger(PixelCopy.ERROR_UNKNOWN)
            val bitmap = Bitmap.createBitmap(1024, (1024f * view.height / view.width.coerceAtLeast(1)).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            instrumentation.runOnMainSync { PixelCopy.request(view, bitmap, { result.set(it); done.countDown() }, Handler(Looper.getMainLooper())) }
            check(done.await(5, TimeUnit.SECONDS)) { "PixelCopy callback timed out" }
            if(result.get() == PixelCopy.SUCCESS) return bitmap
            bitmap.recycle()
            check(SystemClock.elapsedRealtime() < deadline) { "PixelCopy failed: ${result.get()}" }
            SystemClock.sleep(50)
        }
    }
    private fun differentPixels(a: Bitmap, b: Bitmap): Int {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        var changed = 0
        for(y in 0 until a.height step 3) for(x in 0 until a.width step 3) {
            val one = a.getPixel(x,y); val two = b.getPixel(x,y)
            val delta = kotlin.math.abs(((one shr 16) and 255) - ((two shr 16) and 255)) +
                kotlin.math.abs(((one shr 8) and 255) - ((two shr 8) and 255)) + kotlin.math.abs((one and 255) - (two and 255))
            if(delta > 6) changed++
        }
        return changed
    }
    private fun nonBlackPixels(bitmap: Bitmap): Int {
        var pixels = 0
        for(y in 0 until bitmap.height step 5) for(x in 0 until bitmap.width step 5)
            if(bitmap.getPixel(x,y) and 0x00ffffff != 0) pixels++
        return pixels
    }
}
