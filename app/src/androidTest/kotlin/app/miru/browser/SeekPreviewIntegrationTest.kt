// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.browser

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.player.PlaybackRequest
import app.miru.player.PlaybackRequests
import app.miru.player.PlayerActivity
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity, pointer stream, Media3 decoder and displayed thumbnail; no fake player. */
@RunWith(AndroidJUnit4::class)
class SeekPreviewIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun draggingDisplaysTheDecodedBlueBucketAndUpSeeksExactlyOnce() {
        localFixture { fixture ->
            withPausedPlayer(localRequest(fixture)) { running ->
                dragToBlue(running).use { drag ->
                    awaitBlue(running)
                    assertUnchanged(running)
                    assertEquals("DOWN/MOVE must never seek the main player", 0, running.seeks.get())
                    compose.onNodeWithTag(IMAGE, useUnmergedTree = true).assertIsDisplayed()
                    val pixels = compose.onNodeWithTag(IMAGE, useUnmergedTree = true).captureToImage().toPixelMap()
                    assertBlue(pixels.width, pixels.height) { x, y ->
                        val pixel = pixels[x, y]
                        Triple(pixel.red * 255, pixel.green * 255, pixel.blue * 255)
                    }
                    val target = previewTarget(running)
                    drag.finish(MotionEvent.ACTION_UP)
                    awaitHidden(running)
                    compose.waitUntil(10_000) {
                        var settled = false
                        running.scenario.onActivity { activity ->
                            val player = checkNotNull(activity.playback.player)
                            settled = player.playbackState == Player.STATE_READY && abs(player.currentPosition - target) <= 350
                        }
                        settled
                    }
                    compose.waitForIdle()
                    assertEquals("A physical UP must commit one seek", 1, running.seeks.get())
                    running.scenario.onActivity { activity ->
                        assertSame(running.identity.player, activity.playback.player)
                        assertEquals(running.identity.revision, activity.playback.state.value.sourceRevision)
                        assertFalse(checkNotNull(activity.playback.player).playWhenReady)
                    }
                }
            }
        }
    }

    @Test fun actualActionCancelClearsThePreviewWithoutCommittingASeek() {
        localFixture { fixture ->
            withPausedPlayer(localRequest(fixture)) { running ->
                dragToBlue(running).use { drag ->
                    awaitBlue(running)
                    assertUnchanged(running)
                    assertEquals(0, running.seeks.get())
                    // Material3 may invoke onValueChangeFinished for cancellation too.
                    // Only this native CANCEL, never a synthetic callback or hide(),
                    // terminates the same DOWN/MOVE pointer stream.
                    drag.finish(MotionEvent.ACTION_CANCEL)
                    awaitHidden(running)
                    instrumentation.waitForIdleSync()
                    compose.waitForIdle()
                    assertEquals("ACTION_CANCEL must commit no seek", 0, running.seeks.get())
                    assertUnchanged(running)
                }
            }
        }
    }

    @Test fun privatePreviewRefreshesRejectedHeadersOnceWithoutChangingMainPlaybackOrDisk() {
        SeekUiOrigin(instrumentation.context).use { origin ->
            val refreshes = AtomicInteger()
            val before = mediaCacheSnapshot()
            val request = PlaybackRequest(
                url = origin.url + "/colors.mp4", title = "Synthetic private preview",
                pageUrl = "https://seek-preview.invalid/private", mimeType = "video/mp4",
                headers = origin.headers(fresh = false), fromWeb = true, isPrivate = true,
                refreshHeaders = { refreshes.incrementAndGet(); origin.headers(fresh = true) },
            )
            withPausedPlayer(request) { running ->
                // The tiny fast-start asset must be completely buffered by the main
                // player before only subsequent HTTP requests begin rejecting A.
                compose.waitUntil(15_000) {
                    var complete = false
                    running.scenario.onActivity { activity ->
                        val player = checkNotNull(activity.playback.player)
                        complete = player.bufferedPosition >= player.duration - 150
                    }
                    origin.assertHealthy()
                    complete
                }
                assertTrue("Main playback must really fetch with header A", origin.calls.any { !it.previewPhase && it.oldHeaders && it.status in 200..299 })
                origin.rejectOldHeaders.set(true)
                assertSecure(running)
                dragToBlue(running).use { drag ->
                    awaitBlue(running, origin)
                    compose.onNodeWithTag(IMAGE, useUnmergedTree = true).assertIsDisplayed()
                    // FLAG_SECURE stays enabled: inspect the controller's owned
                    // decoded bitmap, never capture a private window or UI image.
                    running.scenario.onActivity { activity ->
                        val frame = checkNotNull(activity.playback.seekPreview.state.value.frame)
                        assertBlueBitmap(frame.image)
                    }
                    assertEquals("Preview authentication may refresh only once", 1, refreshes.get())
                    val previewCalls = origin.calls.filter { it.previewPhase }
                    assertTrue("An actual preview A request must receive HTTP 403", previewCalls.any { it.oldHeaders && it.status == 403 })
                    assertTrue("A real B request must decode after refresh", previewCalls.any { it.freshHeaders && it.status in 200..299 })
                    assertTrue("Every successful preview request must use refreshed headers", previewCalls.filter { it.status in 200..299 }.all { it.freshHeaders })
                    assertUnchanged(running)
                    assertSecure(running)
                    assertEquals(0, running.seeks.get())
                    drag.finish(MotionEvent.ACTION_CANCEL)
                    awaitHidden(running)
                    assertUnchanged(running)
                    assertSecure(running)
                    assertEquals(0, running.seeks.get())
                }
            }
            instrumentation.waitForIdleSync()
            origin.assertHealthy()
            assertEquals("Private playback and preview must write no media cache files", before, mediaCacheSnapshot())
            assertEquals(1, refreshes.get())
        }
    }

    @Test fun playingDragKeepsThePreviewVisibleBeyondAutoHideAndCancelKeepsPlaybackRunning() {
        localFixture { fixture ->
            withPausedPlayer(localRequest(fixture)) { running ->
                running.scenario.onActivity { it.playback.resume() }
                compose.waitUntil(5_000) {
                    var playing = false
                    running.scenario.onActivity { activity ->
                        playing = activity.playback.player?.isPlaying == true && activity.playback.state.value.playing
                    }
                    playing
                }
                dragToBlue(running).use { drag ->
                    awaitBlue(running)
                    val target = previewTarget(running)
                    val beforeHold = assertPlayingUnchanged(running)
                    val heldFrom = SystemClock.uptimeMillis()
                    // Keep the same native pointer DOWN, with no MOVE to restart
                    // timers, past the playing controls' three-second auto-hide.
                    SystemClock.sleep(3_300)
                    assertTrue(SystemClock.uptimeMillis() - heldFrom > 3_200)
                    compose.waitForIdle()
                    compose.onNodeWithTag(SLIDER, useUnmergedTree = true).assertIsDisplayed()
                    compose.onNodeWithTag(BUBBLE, useUnmergedTree = true).assertIsDisplayed()
                    compose.onNodeWithTag(IMAGE, useUnmergedTree = true).assertIsDisplayed()
                    assertEquals(target, previewTarget(running))
                    assertTrue("The main player must naturally advance while the pointer stays down",
                        assertPlayingUnchanged(running) > beforeHold)
                    assertEquals("Holding a playing drag must issue no seek", 0, running.seeks.get())
                    drag.finish(MotionEvent.ACTION_CANCEL)
                    awaitHidden(running)
                    assertPlayingUnchanged(running)
                    assertEquals("Cancelling the playing drag must issue no seek", 0, running.seeks.get())
                }
            }
        }
    }

    @Test fun rejectedPrivatePreviewFallsBackToACompactTimeBubbleWhenRefreshReturnsNull() {
        SeekUiOrigin(instrumentation.context).use { origin ->
            val refreshes = AtomicInteger()
            val before = mediaCacheSnapshot()
            val request = PlaybackRequest(
                url = origin.url + "/colors.mp4", title = "Synthetic private preview fallback",
                pageUrl = "https://seek-preview.invalid/fallback", mimeType = "video/mp4",
                headers = origin.headers(fresh = false), fromWeb = true, isPrivate = true,
                refreshHeaders = { refreshes.incrementAndGet(); null },
            )
            withPausedPlayer(request) { running ->
                compose.waitUntil(15_000) {
                    var complete = false
                    running.scenario.onActivity { activity ->
                        val player = checkNotNull(activity.playback.player)
                        complete = player.bufferedPosition >= player.duration - 150
                    }
                    origin.assertHealthy()
                    complete
                }
                assertTrue("The main player must first consume a real A response",
                    origin.calls.any { !it.previewPhase && it.oldHeaders && it.status in 200..299 })
                origin.rejectOldHeaders.set(true)
                dragToBlue(running).use { drag ->
                    compose.waitUntil(20_000) {
                        origin.assertHealthy()
                        var timeOnly = false
                        running.scenario.onActivity { activity ->
                            val state = activity.playback.seekPreview.state.value
                            timeOnly = state.targetMs?.let { it in 10_500L..11_500L } == true && state.frame == null
                        }
                        refreshes.get() > 0 && timeOnly && hasTag(BUBBLE) && !hasTag(IMAGE)
                    }
                    compose.waitForIdle()
                    assertEquals("The real preview failure must deliver one refresh callback", 1, refreshes.get())
                    val previewCalls = origin.calls.filter { it.previewPhase }
                    assertTrue("Fallback must follow an actual preview HTTP 403", previewCalls.any { it.oldHeaders && it.status == 403 })
                    assertTrue("No refreshed or successful preview response may hide the failure",
                        previewCalls.all { it.oldHeaders && it.status == 403 })
                    val target = previewTarget(running)
                    val expectedTime = if(target < 11_000) "0:10" else "0:11"
                    compose.onNodeWithTag(BUBBLE, useUnmergedTree = true).assertIsDisplayed()
                    compose.onNode(hasText(expectedTime) and hasAnyAncestor(hasTestTag(BUBBLE)), useUnmergedTree = true)
                        .assertIsDisplayed()
                    assertFalse("Failed preview must render no Image node", hasTag(IMAGE))
                    var density = 1f
                    running.scenario.onActivity { activity ->
                        density = activity.resources.displayMetrics.density
                        val preview = activity.playback.seekPreview.state.value
                        assertEquals(target, preview.targetMs)
                        assertNull(preview.frame)
                    }
                    val heightDp = compose.onNodeWithTag(BUBBLE, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height / density
                    assertTrue("Time-only fallback must not reserve a 90dp image frame; actual height=$heightDp dp",
                        heightDp > 0 && heightDp < 60)
                    assertUnchanged(running)
                    assertSecure(running)
                    assertEquals(0, running.seeks.get())
                    drag.finish(MotionEvent.ACTION_CANCEL)
                    awaitHidden(running)
                    assertUnchanged(running)
                    assertSecure(running)
                    assertEquals(0, running.seeks.get())
                }
            }
            instrumentation.waitForIdleSync()
            origin.assertHealthy()
            assertEquals("Private fallback must write no media cache files", before, mediaCacheSnapshot())
            assertEquals(1, refreshes.get())
        }
    }

    private fun localRequest(fixture: File) = PlaybackRequest(
        fixture.toURI().toString(), "Synthetic seek preview", pageUrl = "https://seek-preview.invalid/watch",
        mimeType = "video/mp4", fromWeb = true,
    )

    private fun localFixture(block: (File) -> Unit) {
        val fixture = File(context.cacheDir, "seek-ui-${System.nanoTime()}.mp4")
        try {
            instrumentation.context.assets.open("media/seek-preview/colors.mp4").use { input ->
                fixture.outputStream().use { input.copyTo(it) }
            }
            block(fixture)
        } finally { fixture.delete() }
    }

    private fun withPausedPlayer(request: PlaybackRequest, block: (RunningPlayer) -> Unit) {
        val intent = PlaybackRequests.intent(context, request)
        assertNull("Playback URL must not become Intent data", intent.data)
        assertEquals("Only an opaque one-use token may cross the Activity boundary",
            setOf("miru.playback.token"), checkNotNull(intent.extras).keySet())
        val scenario = ActivityScenario.launch<PlayerActivity>(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val seeks = AtomicInteger()
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if(reason == Player.DISCONTINUITY_REASON_SEEK) seeks.incrementAndGet()
            }
        }
        var identity: Identity? = null
        try {
            compose.waitUntil(30_000) {
                var ready = false
                scenario.onActivity { activity ->
                    val state = activity.playback.state.value
                    if(state.error || state.timedOut) throw AssertionError("Synthetic playback failed before the UI gate")
                    ready = state.ready && state.duration in 11_500..12_500 && state.width > 0 &&
                        activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        activity.window.decorView.hasWindowFocus()
                    if(ready) activity.playback.pause()
                }
                ready
            }
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val player = checkNotNull(activity.playback.player)
                assertFalse(player.isPlaying)
                assertFalse(player.playWhenReady)
                identity = Identity(player, player.currentPosition, activity.playback.state.value.sourceRevision)
                player.addListener(listener)
            }
            ensureSlider(scenario)
            block(RunningPlayer(scenario, checkNotNull(identity), seeks))
        } finally {
            runCatching { scenario.onActivity { identity?.player?.removeListener(listener) } }
            scenario.close()
        }
    }

    private fun ensureSlider(scenario: ActivityScenario<PlayerActivity>) {
        if(!hasTag(SLIDER)) {
            var x = 0f; var y = 0f
            scenario.onActivity { activity ->
                val location = IntArray(2)
                activity.window.decorView.getLocationOnScreen(location)
                x = location[0] + activity.window.decorView.width * .8f
                y = location[1] + activity.window.decorView.height * .4f
            }
            val down = SystemClock.uptimeMillis()
            inject(down, MotionEvent.ACTION_DOWN, x, y)
            inject(down, MotionEvent.ACTION_UP, x, y)
        }
        compose.waitUntil(5_000) { hasTag(SLIDER) }
        compose.onNodeWithTag(SLIDER, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun dragToBlue(running: RunningPlayer): PointerDrag {
        val bounds = compose.onNodeWithTag(SLIDER, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val origin = IntArray(2)
        var radius = 0f
        var duration = 0L
        running.scenario.onActivity { activity ->
            checkNotNull(findComposeView(activity.window.decorView)).getLocationOnScreen(origin)
            radius = 8 * activity.resources.displayMetrics.density // Production thumb is 16dp.
            duration = checkNotNull(activity.playback.player).duration
        }
        val screen = Rect(bounds.left + origin[0], bounds.top + origin[1], bounds.right + origin[0], bounds.bottom + origin[1])
        fun x(position: Long) = screen.left + radius + (screen.width - 2 * radius) * position.toFloat() / duration
        val drag = PointerDrag(x(running.identity.positionMs), screen.center.y)
        try {
            // Cross touch slop as an actual drag before reaching the 10-second
            // cache bucket; the fixture is blue from 8 to 12 seconds.
            drag.move(x(duration / 2))
            drag.move(x(11_000))
            return drag
        } catch(error: Throwable) {
            drag.close()
            throw error
        }
    }

    private inner class PointerDrag(x: Float, private val y: Float) : Closeable {
        private val down = SystemClock.uptimeMillis()
        private var lastX = x
        private var active = true
        init { inject(down, MotionEvent.ACTION_DOWN, x, y) }
        fun move(x: Float) {
            check(active)
            SystemClock.sleep(16)
            lastX = x
            inject(down, MotionEvent.ACTION_MOVE, x, y)
        }
        fun finish(action: Int) {
            check(active && action in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL))
            active = false
            inject(down, action, lastX, y)
        }
        override fun close() { if(active) finish(MotionEvent.ACTION_CANCEL) }
    }

    private fun inject(down: Long, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).also { event ->
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue("The native pointer event must reach the Activity", instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally { event.recycle() }
        }
    }

    private fun awaitBlue(running: RunningPlayer, origin: SeekUiOrigin? = null) {
        compose.waitUntil(20_000) {
            origin?.assertHealthy()
            var decoded = false
            running.scenario.onActivity { activity ->
                val state = activity.playback.seekPreview.state.value
                decoded = state.targetMs?.let { it in 10_500L..11_500L } == true &&
                    state.frame?.let { abs(it.presentationTimeMs - 10_000) <= 150 } == true
            }
            decoded && hasTag(IMAGE)
        }
        compose.waitForIdle()
        compose.onNodeWithTag(BUBBLE, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(IMAGE, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun previewTarget(running: RunningPlayer): Long {
        var target = -1L
        running.scenario.onActivity { target = checkNotNull(it.playback.seekPreview.state.value.targetMs) }
        assertTrue("Final drag must stay in the ten-second bucket", target in 10_500..11_500)
        return target
    }

    private fun awaitHidden(running: RunningPlayer) {
        compose.waitUntil(5_000) {
            var cleared = false
            running.scenario.onActivity { activity ->
                val state = activity.playback.seekPreview.state.value
                cleared = state.targetMs == null && state.frame == null
            }
            cleared && !hasTag(BUBBLE) && !hasTag(IMAGE)
        }
        compose.waitForIdle()
    }

    private fun assertUnchanged(running: RunningPlayer) {
        running.scenario.onActivity { activity ->
            val player = checkNotNull(activity.playback.player)
            assertSame("Preview must preserve the main player object", running.identity.player, player)
            assertEquals("Preview must preserve the main source revision", running.identity.revision, activity.playback.state.value.sourceRevision)
            assertTrue("Preview must preserve the paused main position", abs(player.currentPosition - running.identity.positionMs) <= 100)
            assertFalse(player.playWhenReady)
        }
    }

    private fun assertPlayingUnchanged(running: RunningPlayer): Long {
        var position = -1L
        running.scenario.onActivity { activity ->
            val player = checkNotNull(activity.playback.player)
            assertSame("A playing preview must preserve the main player", running.identity.player, player)
            assertEquals(running.identity.revision, activity.playback.state.value.sourceRevision)
            assertTrue("The main player must keep playing during and after the drag", player.isPlaying)
            assertTrue(player.playWhenReady)
            assertTrue(activity.playback.state.value.playing)
            position = player.currentPosition
        }
        return position
    }

    private fun assertSecure(running: RunningPlayer) {
        running.scenario.onActivity { activity ->
            assertTrue("Private preview must retain FLAG_SECURE", activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
    }

    private fun assertBlueBitmap(bitmap: Bitmap) = assertBlue(bitmap.width, bitmap.height) { x, y ->
        val pixel = bitmap.getPixel(x, y)
        Triple(android.graphics.Color.red(pixel).toFloat(), android.graphics.Color.green(pixel).toFloat(), android.graphics.Color.blue(pixel).toFloat())
    }

    private fun assertBlue(width: Int, height: Int, pixel: (Int, Int) -> Triple<Float, Float, Float>) {
        assertTrue(width > 0 && height > 0)
        var red = 0f; var green = 0f; var blue = 0f; var count = 0
        // Avoid encoded time text near the bottom and the bubble's outer edges.
        for(y in height * 17 / 100 until height * 39 / 100) for(x in width * 34 / 100 until width * 59 / 100) {
            val rgb = pixel(x, y)
            red += rgb.first; green += rgb.second; blue += rgb.third; count++
        }
        assertTrue("Decoded/captured blue must match the fixture, RGB=${listOf(red / count, green / count, blue / count)}",
            count > 0 && red / count < 45 && green / count < 45 && blue / count > 210)
    }

    private fun hasTag(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun findComposeView(view: View): View? {
        if(view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
        if(view is ViewGroup) for(index in 0 until view.childCount) findComposeView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun mediaCacheSnapshot(): Pair<Boolean, Map<String, String>> {
        val media = File(context.cacheDir, "media")
        val files = media.walkTopDown().filter(File::isFile).toList() +
            File(context.applicationInfo.dataDir, "databases").listFiles().orEmpty().filter { it.isFile && it.name.startsWith("exoplayer") }
        val marks = files.associate { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while(true) { val read = input.read(buffer); if(read < 0) break; digest.update(buffer, 0, read) }
            }
            file.relativeTo(File(context.applicationInfo.dataDir)).path to
                "${file.length()}:${file.lastModified()}:${digest.digest().joinToString("") { "%02x".format(it) }}"
        }
        return media.exists() to marks
    }

    private data class Identity(val player: Player, val positionMs: Long, val revision: Long)
    private data class RunningPlayer(val scenario: ActivityScenario<PlayerActivity>, val identity: Identity, val seeks: AtomicInteger)
    private companion object {
        const val SLIDER = "player_seek_slider"
        const val BUBBLE = "player_seek_preview_bubble"
        const val IMAGE = "player_seek_preview_image"
    }
}

/** Synthetic Range origin. Its observations deliberately retain no header values. */
private class SeekUiOrigin(assets: Context) : Closeable {
    private val bytes = assets.assets.open("media/seek-preview/colors.mp4").use { it.readBytes() }
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val failure = AtomicReference<String?>(null)
    val rejectOldHeaders = AtomicBoolean(false)
    val calls = CopyOnWriteArrayList<SeekUiCall>()
    val url = "http://127.0.0.1:${server.localPort}"
    fun headers(fresh: Boolean) = mapOf(
        "Authorization" to if(fresh) "Bearer seek-ui-synthetic-B" else "Bearer seek-ui-synthetic-A",
        "Cookie" to if(fresh) "seek_ui=synthetic_B" else "seek_ui=synthetic_A",
        "User-Agent" to "Miru-Seek-UI-Fixture", "Referer" to "$url/source",
    )

    init {
        thread(isDaemon = true, name = "miru-seek-ui-origin") {
            while(running.get()) {
                val socket = try { server.accept() } catch(_: java.io.IOException) { break }
                sockets += socket
                thread(isDaemon = true, name = "miru-seek-ui-response") {
                    socket.use {
                        try { serve(it) }
                        catch(_: java.io.IOException) { /* Reader cancellation can close the socket mid-body. */ }
                        catch(error: Exception) { failure.compareAndSet(null, error.javaClass.simpleName) }
                        finally { sockets -= it }
                    }
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val request = input.readLine()?.split(' ') ?: return
        val received = linkedMapOf<String, String>()
        while(true) {
            val line = input.readLine()?.takeIf { it.isNotBlank() } ?: break
            received[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        val old = headers(false).all { (name, value) -> received[name.lowercase()] == value }
        val fresh = headers(true).all { (name, value) -> received[name.lowercase()] == value }
        val phase = rejectOldHeaders.get()
        val output = socket.getOutputStream()
        fun reject(status: Int, reason: String, extra: String = "") {
            calls += SeekUiCall(status, phase, old, fresh)
            output.write("HTTP/1.1 $status $reason\r\nContent-Length: 0\r\n${extra}Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        }
        if(request.getOrNull(1)?.substringBefore('?') != "/colors.mp4") { reject(404, "Not Found"); return }
        if((!old && !fresh) || (phase && old)) { reject(403, "Forbidden"); return }
        val rangeHeader = received["range"]
        val range = rangeHeader?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        if(rangeHeader != null && range == null) { reject(416, "Range Not Satisfiable", "Content-Range: bytes */${bytes.size}\r\n"); return }
        val from = range?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val to = (range?.groupValues?.get(2)?.toLongOrNull() ?: bytes.lastIndex.toLong()).coerceAtMost(bytes.lastIndex.toLong())
        if(from > to || from >= bytes.size) { reject(416, "Range Not Satisfiable", "Content-Range: bytes */${bytes.size}\r\n"); return }
        val status = if(range == null) 200 else 206
        calls += SeekUiCall(status, phase, old, fresh)
        output.write(("HTTP/1.1 $status ${if(range == null) "OK" else "Partial Content"}\r\n" +
            "Content-Type: video/mp4\r\nContent-Length: ${to - from + 1}\r\nAccept-Ranges: bytes\r\nCache-Control: no-store\r\n" +
            (if(range == null) "" else "Content-Range: bytes $from-$to/${bytes.size}\r\n") +
            "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        if(request[0] != "HEAD") output.write(bytes, from.toInt(), (to - from + 1).toInt())
        output.flush()
    }

    fun assertHealthy() { failure.get()?.let { throw AssertionError("Synthetic seek origin failed: $it") } }
    override fun close() {
        running.set(false)
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }
}

private data class SeekUiCall(val status: Int, val previewPhase: Boolean, val oldHeaders: Boolean, val freshHeaders: Boolean)
