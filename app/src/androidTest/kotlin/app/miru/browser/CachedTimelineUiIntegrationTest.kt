// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.browser

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.player.CachedTimeRange
import app.miru.player.PlaybackCache
import app.miru.player.PlaybackRequest
import app.miru.player.PlaybackRequests
import app.miru.player.PlaybackSourceSession
import app.miru.player.PlayerActivity
import app.miru.player.PlayerPreferencesRepository
import app.miru.superres.RoutedVideoView
import app.miru.superres.SuperResMode
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Activity, network HLS, committed disk, displayed track and decoded SurfaceView. */
@RunWith(AndroidJUnit4::class)
class CachedTimelineUiIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun diskHolesAreDrawnBelowMemoryBufferAndClearWithoutChangingPlayback() = withPlayer { running ->
        removeMiddle(running)
        var buffered = 0L
        var identity: androidx.media3.exoplayer.ExoPlayer? = null
        var position = 0L
        running.scenario.onActivity {
            buffered = it.playback.state.value.buffered
            identity = it.playback.player
            position = it.playback.state.value.position
        }
        assertTrue("The observed memory layer must not mask the sampled late disk geometry", buffered < 80_000)
        compose.waitForIdle()
        val image = compose.onNodeWithTag("player_seek_track", useUnmergedTree = true).captureToImage()
        val pixels = image.toPixelMap()
        fun brightness(seconds: Int): Float {
            val x = (pixels.width * seconds / 120f).toInt().coerceIn(1, pixels.width - 2)
            return (-1..1).map { dx -> pixels[x + dx, pixels.height / 2].let { (it.red + it.green + it.blue) * 85f } }.average().toFloat()
        }
        val left = brightness(84); val hole = brightness(90); val right = brightness(94)
        image.asAndroidBitmap().let { bitmap -> File(context.filesDir, "cached-ranges-ui-holes.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        Log.i(TAG, "track pixels: cached84=$left hole90=$hole cached94=$right memoryMs=$buffered")
        assertTrue("The cached left interval must be visibly above the empty track", left - hole >= 30f)
        assertTrue("The cached right interval must be visibly above the empty track", right - hole >= 30f)
        ownKeys(running).forEach { key -> io { PlaybackCache.get(context).removeResource(key) } }
        compose.waitUntil(10_000) { ranges(running).isEmpty() }
        running.scenario.onActivity {
            assertSame(identity, it.playback.player)
            assertFalse(checkNotNull(it.playback.player).playWhenReady)
            assertTrue(abs(it.playback.state.value.position - position) <= 150)
            assertTrue("Clearing disk must not pretend the native memory buffer was cleared", it.playback.state.value.buffered > 0)
        }
        compose.waitForIdle()
        val cleared = compose.onNodeWithTag("player_seek_track", useUnmergedTree = true).captureToImage()
        cleared.asAndroidBitmap().let { bitmap -> File(context.filesDir, "cached-ranges-ui-cleared.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test fun stoppedOriginStillDecodesCachedBlueButTheUncachedGreenSeekFails() = withPlayer { running ->
        removeMiddle(running)
        val renderedUs = AtomicLong(-1)
        running.scenario.onActivity { activity ->
            checkNotNull(activity.playback.player).setVideoFrameMetadataListener { timeUs, _, _, _ -> renderedUs.set(timeUs) }
        }
        val beforeBlueRequests = running.origin.calls.count { it.path == "/ts-02.ts" }
        running.origin.available.set(false) // A real origin now rejects every upstream load with 503.
        running.scenario.onActivity { it.playback.seek(93_000) }
        compose.waitUntil(10_000) { abs(renderedUs.get() - 93_000_000L) <= 150_000 }
        var output: RoutedVideoView? = null
        running.scenario.onActivity { output = findVideo(it.window.decorView) }
        val bitmap = blueFrame(requireNotNull(output))
        try { File(context.filesDir, "cached-ranges-stopped-origin-blue.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        assertEquals("A cached seek must not ask the stopped origin for its blue segment", beforeBlueRequests, running.origin.calls.count { it.path == "/ts-02.ts" })
        Log.i(TAG, "stopped origin decoded actual presentationUs=${renderedUs.get()}; cached blue made no new upstream request")
        renderedUs.set(-1)
        running.scenario.onActivity { it.playback.seek(89_000) }
        compose.waitUntil(15_000) {
            var failed = false
            running.scenario.onActivity { failed = it.playback.state.value.error }
            failed && running.origin.calls.any { it.path == "/ts-01.ts" && it.status == 503 }
        }
        assertTrue("The missing interval cannot render a frame at its requested timestamp", abs(renderedUs.get() - 89_000_000L) > 150_000)
        assertTrue(ranges(running).none { 89_000_000 in it.startUs until it.endUs })
    }

    @Test fun privateHlsHasNoDiskLayerAndRetainsItsSecureWindow() {
        val before = io { PlaybackCache.get(context).keys.associateWith { key -> PlaybackCache.get(context).getCachedSpans(key).map { it.position to it.length } } }
        withPlayer(isPrivate = true) { running ->
            assertTrue(ranges(running).isEmpty())
            running.scenario.onActivity {
                assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue(it.playback.state.value.ready)
            }
        }
        val after = io { PlaybackCache.get(context).keys.associateWith { key -> PlaybackCache.get(context).getCachedSpans(key).map { it.position to it.length } } }
        assertEquals(before, after)
    }

    @Test fun decoderRebuildWithdrawsTheOldLayerBeforeTheNewSourceBecomesReady() = withPlayer { running ->
        var oldPlayer: androidx.media3.exoplayer.ExoPlayer? = null
        var revision = 0L
        running.scenario.onActivity {
            oldPlayer = it.playback.player
            revision = it.playback.state.value.sourceRevision
            assertEquals(FULL, it.playback.diskRanges.value)
            it.playback.decoder("software")
            assertTrue("Source rebuild must clear the old disk layer synchronously", it.playback.diskRanges.value.isEmpty())
            assertFalse(it.playback.state.value.ready)
        }
        compose.waitUntil(30_000) {
            var ready = false
            running.scenario.onActivity {
                if(it.playback.state.value.error) throw AssertionError("Controlled decoder rebuild failed")
                ready = it.playback.state.value.ready && it.playback.state.value.sourceRevision > revision &&
                    it.playback.player !== oldPlayer && it.playback.diskRanges.value == FULL
                if(ready) it.playback.pause()
            }
            ready
        }
    }

    private fun withPlayer(isPrivate: Boolean = false, verify: (Running) -> Unit) {
        val repository = PlayerPreferencesRepository(context)
        val original = runBlocking { repository.settings.first() }
        runBlocking { repository.update { it.copy(bufferSeconds = 15, autoPip = false, superResMode = SuperResMode.OFF) } }
        try {
            RangeOrigin(instrumentation.context).use { origin ->
                val request = PlaybackRequest("${origin.url}/video.m3u8", "Controlled cached ranges", pageUrl = "${origin.url}/watch",
                    mimeType = "application/vnd.apple.mpegurl", isPrivate = isPrivate, fromWeb = true)
                val scenario = ActivityScenario.launch<PlayerActivity>(PlaybackRequests.intent(context, request).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val running = Running(origin, scenario, request)
                try {
                    compose.waitUntil(30_000) {
                        var ready = false
                        scenario.onActivity {
                            val state = it.playback.state.value
                            if(state.error || state.timedOut) throw AssertionError("Controlled HLS failed before the UI gate")
                            ready = state.ready && state.duration == 120_000L && state.width > 0 &&
                                it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && it.window.decorView.hasWindowFocus()
                            if(ready) it.playback.pause()
                        }
                        ready
                    }
                    if(!isPrivate) compose.waitUntil(10_000) { ranges(running) == FULL }
                    compose.waitUntil(10_000) {
                        var settled = false
                        scenario.onActivity { settled = !checkNotNull(it.playback.player).isLoading && !checkNotNull(it.playback.player).playWhenReady }
                        settled
                    }
                    compose.waitForIdle()
                    if(!isPrivate && compose.onAllNodesWithTag("player_seek_track", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
                        compose.onNodeWithTag("player_video_output", useUnmergedTree = true).performTouchInput { click() }
                        compose.waitUntil(5_000) { compose.onAllNodesWithTag("player_seek_track", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                    }
                    verify(running)
                    origin.assertHealthy()
                } finally {
                    scenario.close()
                    if(!isPrivate) ownKeys(running).forEach { key -> io { PlaybackCache.get(context).removeResource(key) } }
                }
            }
        } finally { runBlocking { repository.update { original } } }
    }

    private class Running(val origin: RangeOrigin, val scenario: ActivityScenario<PlayerActivity>, val request: PlaybackRequest)
    private fun ownKeys(running: Running): List<String> {
        val session = PlaybackSourceSession(context, 0, MediaItem.fromUri(running.request.url), emptyMap())
        return try { (0..2).map { session.cacheKey(DataSpec.Builder().setUri("${running.origin.url}/ts-0$it.ts").build()) } }
        finally { session.close() }
    }
    private fun removeMiddle(running: Running) {
        io { PlaybackCache.get(context).removeResource(ownKeys(running)[1]) }
        compose.waitUntil(10_000) { ranges(running) == HOLES }
    }
    private fun ranges(running: Running): List<CachedTimeRange> { var ranges = emptyList<CachedTimeRange>(); running.scenario.onActivity { ranges = it.playback.diskRanges.value }; return ranges }
    private fun <T> io(block: () -> T): T = runBlocking { withContext(Dispatchers.IO) { block() } }
    private fun findVideo(view: View): RoutedVideoView? {
        if(view is RoutedVideoView) return view
        if(view is ViewGroup) for(index in 0 until view.childCount) findVideo(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun blueFrame(view: RoutedVideoView): Bitmap {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while(true) {
            val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
            val done = CountDownLatch(1)
            val status = AtomicInteger(PixelCopy.ERROR_UNKNOWN)
            instrumentation.runOnMainSync { PixelCopy.request(view.surfaceView, bitmap, { status.set(it); done.countDown() }, Handler(Looper.getMainLooper())) }
            check(done.await(5, TimeUnit.SECONDS)) { "Video PixelCopy callback timed out" }
            if(status.get() == PixelCopy.SUCCESS) {
                val rgb = (0..2).map { channel -> (15..35).sumOf { y -> (55..95).sumOf { x ->
                    val color = bitmap.getPixel(x, y)
                    when(channel) { 0 -> Color.red(color); 1 -> Color.green(color); else -> Color.blue(color) }
                } }.toDouble() / (21 * 41) }
                if(abs(rgb[0]) <= 45 && abs(rgb[1]) <= 45 && abs(rgb[2] - 255) <= 45) { Log.i(TAG, "cached native RGB=$rgb"); return bitmap }
            }
            bitmap.recycle()
            check(SystemClock.elapsedRealtime() < deadline) { "Stopped-origin cached seek did not display the blue frame" }
            SystemClock.sleep(30)
        }
    }
    companion object {
        private const val TAG = "MiruDiskRangeUi"
        private val FULL = listOf(CachedTimeRange(0, 120_000_000))
        private val HOLES = listOf(CachedTimeRange(0, 4_000_000)) + (0..8).map { CachedTimeRange((8 + it * 12) * 1_000_000L, (16 + it * 12) * 1_000_000L) } + CachedTimeRange(116_000_000, 120_000_000)
    }
}

private class RangeOrigin(assets: Context) : Closeable {
    class Call(val path: String, val status: Int)
    val available = AtomicBoolean(true)
    val calls = CopyOnWriteArrayList<Call>()
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val failure = AtomicReference<String?>()
    val url = "http://127.0.0.1:${server.localPort}"
    private val files = (0..2).associate { "ts-0$it.ts" to assets.assets.open("media/seek-preview/ts-0$it.ts").use { input -> input.readBytes() } }.toMutableMap()
    init {
        files["video.m3u8"] = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:4\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-PLAYLIST-TYPE:VOD\n")
            repeat(10) { loop -> if(loop > 0) append("#EXT-X-DISCONTINUITY\n"); repeat(3) { append("#EXTINF:4,\nts-0$it.ts\n") } }
            append("#EXT-X-ENDLIST\n")
        }.toByteArray()
        thread(isDaemon = true, name = "miru-cached-range-origin") {
            while(running.get()) {
                val socket = try { server.accept() } catch(_: java.io.IOException) { break }
                sockets += socket
                thread(isDaemon = true) {
                    socket.use {
                        try { serve(it) } catch(_: java.io.IOException) { /* Native release can close an in-flight fixture response. */ }
                        catch(error: Exception) { failure.compareAndSet(null, error.javaClass.simpleName) }
                        finally { sockets -= it }
                    }
                }
            }
        }
    }
    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val reader = socket.getInputStream().bufferedReader()
        val request = reader.readLine()?.split(' ') ?: return
        val path = request.getOrNull(1)?.substringBefore('?') ?: return
        val headers = linkedMapOf<String, String>()
        while(true) { val line = reader.readLine()?.takeIf { it.isNotBlank() } ?: break; headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
        val body = files[path.removePrefix("/")]
        if(!available.get() || body == null) {
            val code = if(!available.get()) 503 else 404
            calls += Call(path, code)
            socket.getOutputStream().apply { write("HTTP/1.1 $code Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            return
        }
        val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val from = range?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val to = range?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(body.lastIndex.toLong()) ?: body.lastIndex.toLong()
        if(from > to || from >= body.size) throw IllegalArgumentException("Unexpected fixture range")
        val code = if(range == null) 200 else 206
        calls += Call(path, code)
        val mime = if(path.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t"
        socket.getOutputStream().apply {
            write(("HTTP/1.1 $code OK\r\nContent-Type: $mime\r\nContent-Length: ${to - from + 1}\r\nAccept-Ranges: bytes\r\n" +
                (if(range == null) "" else "Content-Range: bytes $from-$to/${body.size}\r\n") + "Connection: close\r\n\r\n").toByteArray())
            if(request[0] != "HEAD") write(body, from.toInt(), (to - from + 1).toInt())
            flush()
        }
    }
    fun assertHealthy() { assertNull("Controlled HLS origin failed", failure.get()) }
    override fun close() { running.set(false); server.close(); sockets.forEach { runCatching { it.close() } } }
}
