// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.browser

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.player.*
import app.miru.superres.RoutedVideoView
import app.miru.superres.SuperResMode
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real MP4 disk layer with native cold decoder, exact source holes and unchanged memory buffer. */
@RunWith(AndroidJUnit4::class)
class Mp4CachedTimelineUiIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun cachedNinePublishesTheCompleteHoleAndMetadataRemovalWithdrawsIt() = probe(9_000, true, "nine")
    @Test fun uncachedFiveFailsAtTheByteHoleAndNeverPublishesThatTime() = probe(5_000, false, "green")
    @Test fun longMovieDrawsCachedIntervalsBeyondTheObservedMemoryBuffer() = probe(9_000, true, "long", true)
    @Test fun pausedCachedSeekRecoversAfterFuturePrefetchFailure() = probe(9_000, true, "retry", true, true)
    @Test fun selectedAvColdSeekPublishesNativeReadClosureAndMetadataRemovalWithdrawsIt() = probe(9_000, true, "av-nine", av = true)
    @Test fun selectedAvMissingMiddleFailsWithoutPublishingThatTime() = probe(5_000, false, "av-green", av = true)
    @Test fun longSelectedAvDrawsDiskIntervalsBeyondTheActualMemoryBuffer() = probe(9_000, true, "av-long", longMovie = true, av = true)
    @Test fun longSelectedAvPausedSeekRecoversAfterTheRealFutureHoleFailure() = probe(9_000, true, "av-retry", longMovie = true, waitForPrefetchFailure = true, av = true)

    private fun probe(positionMs: Long, expectBlue: Boolean, name: String, longMovie: Boolean = false, waitForPrefetchFailure: Boolean = false, av: Boolean = false) {
        val assets = instrumentation.context.assets
        val reference = JSONObject(assets.open("media/cached-ranges/${if(av && longMovie) "long-av-colors.raw" else if(av) "av-colors.raw" else if(longMovie) "long-colors" else "colors"}.samples.json").bufferedReader().use { it.readText() })
        val bytes = assets.open(reference.getString("asset")).use { it.readBytes() }
        assertEquals(reference.getLong("bytes"), bytes.size.toLong())
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(reference.getString("sha256"), digest)
        val tracks = reference.getJSONArray("tracks")
        val green = (0 until if(av) tracks.length() else 1).flatMap { index ->
            val track = tracks.getJSONObject(index)
            val samples = track.getJSONArray("samples")
            val scale = if(av) track.getString("timeBase").substringAfter('/').toLong() else 1_000_000L
            (0 until samples.length()).map(samples::getJSONObject).filter {
                val pts = if(av) it.getLong("ptsTicks") else it.getLong("ptsUs")
                pts % (12 * scale) in 4 * scale until 8 * scale
            }
        }
        if(av) assertEquals(3, tracks.length()) else assertEquals(if(longMovie) 400 else 40, green.size)
        val missing = green.map { it.getLong("offset") to it.getLong("offset") + it.getLong("size") }.sortedBy { it.first }.fold(mutableListOf<Pair<Long, Long>>()) { all, next ->
            val last = all.lastOrNull()
            if(last != null && next.first <= last.second) all[all.lastIndex] = last.first to maxOf(last.second, next.second) else all += next
            all
        }
        val holeStart = green.minOf { it.getLong("offset") }
        val holeEnd = green.maxOf { it.getLong("offset") + it.getLong("size") }
        assertTrue(holeStart > 1376 && holeEnd < bytes.size)
        val report = JSONObject().put("sourceSha256", digest).put("sourceBytes", bytes.size)
            .put("holeStart", holeStart).put("holeEnd", holeEnd).put("targetMs", positionMs).put("av", av).put("scope", "Production MP4 cache disk layer vertical test; full P11 remains pending")
        val settings = PlayerPreferencesRepository(context)
        val original = runBlocking { settings.settings.first() }
        runBlocking { settings.update { it.copy(bufferSeconds = 15, autoPip = false, superResMode = SuperResMode.OFF) } }
        try {
            Mp4TimelineOrigin(bytes).use { origin ->
                val item = MediaItem.fromUri(origin.url)
                val source = PlaybackSourceSession(context, 0, item, emptyMap())
                val key = source.cacheKey(DataSpec.Builder().setUri(origin.url).build())
                val pts = AtomicLong(-1)
                var scene: ActivityScenario<PlayerActivity>? = null
                try {
                    io {
                        val cache = PlaybackCache.get(context)
                        val factory = CacheDataSource.Factory().setCache(cache).setCacheKeyFactory(source.cacheKeyFactory)
                            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
                        fun fill(spec: DataSpec) {
                            assertEquals(key, source.cacheKey(spec))
                            val data = factory.createDataSource()
                            try { data.open(spec); val buffer = ByteArray(4096); while(data.read(buffer, 0, buffer.size) >= 0) { } }
                            finally { data.close() }
                        }
                        fill(DataSpec.Builder().setUri(origin.url).build())
                        val actualEnd = ContentMetadata.getContentLength(cache.getContentMetadata(key))
                        assertEquals(bytes.size.toLong(), actualEnd)
                        cache.removeResource(key)
                        var at = 0L
                        for((from, to) in missing) {
                            if(from > at) fill(DataSpec.Builder().setUri(origin.url).setPosition(at).setLength(from - at).build())
                            at = to
                        }
                        if(at < bytes.size) fill(DataSpec.Builder().setUri(origin.url).setPosition(at).setLength(bytes.size - at).build())
                        // Removing the last span removes metadata too. Restore the
                        // measured native end of this same immutable, hashed body.
                        cache.applyContentMetadataMutations(key, ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, actualEnd) })
                        assertTrue(cache.isCached(key, 0, holeStart))
                        for((from, to) in missing) assertFalse(cache.isCached(key, from, to - from))
                        assertTrue(cache.isCached(key, holeEnd, bytes.size - holeEnd))
                        report.put("seededSpans", JSONArray(cache.getCachedSpans(key).map { JSONObject().put("position", it.position).put("length", it.length) }))
                    }
                    val before = origin.calls.size
                    origin.available.set(false)
                    val request = PlaybackRequest(origin.url, "Owned MP4 native seek probe", pageUrl = origin.url + "/watch",
                        mimeType = "video/mp4", positionMs = positionMs)
                    val launched = ActivityScenario.launch<PlayerActivity>(PlaybackRequests.intent(context, request).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    scene = launched
                    var snapshot = PlayerSnapshot()
                    var avConfigured = false
                    compose.waitUntil(30_000) {
                        var done = false
                        launched.onActivity {
                            if(av && !avConfigured) it.playback.player?.let { native ->
                                native.volume = 0f
                                native.trackSelectionParameters = native.trackSelectionParameters.buildUpon().setPreferredAudioLanguage("en").build()
                                avConfigured = true
                            }
                            it.playback.player?.setVideoFrameMetadataListener { value, _, _, _ -> pts.set(value) }
                            snapshot = it.playback.state.value
                            if(expectBlue && (snapshot.timedOut || snapshot.error && !waitForPrefetchFailure)) throw AssertionError("Cold cached-blue MP4 failed: $snapshot; requests=${origin.calls.drop(before).map { call -> call.json() }}")
                            val output = findVideo(it.window.decorView)
                            val surface = output?.surfaceView
                            val frame = surface?.holder?.surfaceFrame
                            val visibleOutput = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                                it.window.decorView.width > it.window.decorView.height &&
                                it.window.decorView.hasWindowFocus() && output?.isLaidOut == true && !output.isLayoutRequested &&
                                surface?.holder?.surface?.isValid == true && frame?.width() == surface.width && frame.height() == surface.height &&
                                abs(surface.width * 90L - surface.height * 160L) <= 160L
                            done = if(waitForPrefetchFailure) snapshot.error
                                else if(expectBlue) snapshot.ready && snapshot.duration == (if(longMovie) 120_000L else 12_000L) && snapshot.width == 160 && visibleOutput
                                else snapshot.error
                            if(done) {
                                report.put("pauseWindowSize", "${it.window.decorView.width}x${it.window.decorView.height}")
                                    .put("pauseSurfaceSize", "${surface?.width}x${surface?.height}")
                                it.playback.pause()
                            }
                        }
                        done
                    }
                    report.put("player", snapshot.toString())
                    if(waitForPrefetchFailure) launched.onActivity {
                        val error = checkNotNull(it.playback.player?.playerError)
                        assertEquals(androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, error.errorCode)
                        var cause: Throwable? = error; var status = 0
                        repeat(8) { if(cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) status = (cause as androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException).responseCode; cause = cause?.cause }
                        assertEquals(503, status)
                        report.put("expectedPrefetchFailure", "native HTTP 503 before explicit paused cached seek")
                    }
                    if(expectBlue) {
                        var view: RoutedVideoView? = null
                        launched.onActivity {
                            view = findVideo(it.window.decorView)
                            report.put("beforeSeekNativeState", it.playback.player?.playbackState)
                                .put("beforeSeekErrorCode", it.playback.player?.playerError?.errorCode)
                            if(av && longMovie)pts.set(-1)
                            it.playback.seek(positionMs)
                        }
                        compose.waitUntil(10_000) { abs(pts.get() - positionMs * 1000) <= 150_000 }
                        val rgb = blue(requireNotNull(view), name, report)
                        report.put("rgb", JSONArray(rgb))
                        if(av) launched.onActivity {
                            val groups = checkNotNull(it.playback.player).currentTracks.groups
                            val audio = groups.filter { group -> group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                                .flatMap { group -> (0 until group.length).filter(group::isTrackSelected).map(group::getTrackFormat) }
                            assertEquals(1, audio.size)
                            assertEquals("en", audio.single().language)
                            report.put("selectedAudioLanguage", audio.single().language)
                        }
                        if(!longMovie) assertEquals("A fresh decoder must not fetch the stopped middle to start at cached nine", before, origin.calls.size)
                        else assertTrue("Only future uncached green may be prefetched", origin.calls.drop(before).all { call -> call.status == 503 && missing.any { call.from in it.first until it.second } })
                        // After the exact 3.9s frame, a cold interior seek needs
                        // the missing 4s frame; sample duration alone overstates
                        // availability. Keep the independently observed 9s RAP.
                        val holes = if(av && longMovie) listOf(CachedTimeRange(0, 3_900_001)) +
                            (0..8).map { CachedTimeRange((9+it*12)*1_000_000L,(16+it*12)*1_000_000L-99_999) } +
                            CachedTimeRange(117_000_000,120_000_000)
                            else if(av) listOf(CachedTimeRange(0, 3_900_001), CachedTimeRange(9_000_000, 12_000_000))
                            else if(!longMovie) listOf(CachedTimeRange(0, 3_900_001), CachedTimeRange(8_000_000, 12_000_000)) else
                            listOf(CachedTimeRange(0, 3_900_001)) + (0..8).map { CachedTimeRange((8 + it * 12) * 1_000_000L, (16 + it * 12) * 1_000_000L-99_999) } + CachedTimeRange(116_000_000, 120_000_000)
                        compose.waitUntil(10_000) { var actual = emptyList<CachedTimeRange>(); launched.onActivity { actual = it.playback.diskRanges.value }; actual == holes }
                        report.put("diskRanges", holes.toString())
                        if(waitForPrefetchFailure && compose.onAllNodesWithTag("player_seek_track", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
                            // Waiting for a real error outlasts normal control auto-hide.
                            // Reveal controls through the same public video tap as a user.
                            val point = IntArray(2); var x = 0f; var y = 0f
                            launched.onActivity {
                                val surface = requireNotNull(findVideo(it.window.decorView)).surfaceView
                                surface.getLocationOnScreen(point)
                                x = point[0] + surface.width / 2f; y = point[1] + surface.height / 2f
                            }
                            val down = SystemClock.uptimeMillis()
                            for(action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { this.source = InputDevice.SOURCE_TOUCHSCREEN }
                                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                            }
                        }
                        compose.waitUntil(5_000) { compose.onAllNodesWithTag("player_seek_track", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                        var trackImage = compose.onNodeWithTag("player_seek_track", useUnmergedTree = true).captureToImage()
                        if(longMovie) {
                            // Backend publication precedes the next Compose draw. Observe the
                            // actual rendered layer, retaining the original pixel thresholds.
                            compose.waitUntil(5_000) {
                                trackImage = compose.onNodeWithTag("player_seek_track", useUnmergedTree = true).captureToImage()
                                val pixels = trackImage.toPixelMap()
                                fun light(seconds: Int): Float {
                                    val x = (pixels.width * seconds / 120f).toInt().coerceIn(1, pixels.width - 2)
                                    return (-1..1).map { dx -> pixels[x + dx, pixels.height / 2].let { (it.red + it.green + it.blue) * 85f } }.average().toFloat()
                                }
                                val left = light(84); val hole = light(90); val right = light(94)
                                report.put("lastTrackBrightness84_90_94", JSONArray(listOf(left, hole, right)))
                                left - hole >= 30f && right - hole >= 30f
                            }
                        }
                        val strip = trackImage.asAndroidBitmap()
                        File(context.filesDir, "mp4-ui-$name-track-holes.png").outputStream().use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        if(longMovie) {
                            var buffered = 0L; launched.onActivity { buffered = it.playback.state.value.buffered }
                            assertTrue("Native memory must not mask the sampled late disk layer", buffered < 80_000)
                            val pixels = trackImage.toPixelMap()
                            fun brightness(seconds: Int): Float {
                                val x = (pixels.width * seconds / 120f).toInt().coerceIn(1, pixels.width - 2)
                                return (-1..1).map { dx -> pixels[x + dx, pixels.height / 2].let { (it.red + it.green + it.blue) * 85f } }.average().toFloat()
                            }
                            val left = brightness(84); val hole = brightness(90); val right = brightness(94)
                            report.put("memoryMs", buffered).put("trackBrightness84_90_94", JSONArray(listOf(left, hole, right)))
                            assertTrue("Cached 84s must be visible above the empty 90s track", left - hole >= 30f)
                            assertTrue("Cached 94s must be visible above the empty 90s track", right - hole >= 30f)
                        }
                        var oldPlayer: androidx.media3.exoplayer.ExoPlayer? = null
                        var oldPosition = 0L
                        launched.onActivity { oldPlayer = it.playback.player; oldPosition = it.playback.state.value.position }
                        io { val cache = PlaybackCache.get(context); cache.getCachedSpans(key).single { it.position == 0L }.let(cache::removeSpan) }
                        compose.waitUntil(10_000) { var empty = false; launched.onActivity { empty = it.playback.diskRanges.value.isEmpty() }; empty }
                        launched.onActivity {
                            assertSame(oldPlayer, it.playback.player)
                            assertFalse(checkNotNull(it.playback.player).playWhenReady)
                            assertTrue(abs(it.playback.state.value.position - oldPosition) <= 150)
                            assertTrue(it.playback.state.value.buffered >= 9_000)
                        }
                        report.put("afterMetadataRemoval", "disk empty; same paused player and native buffer retained")
                    } else {
                        assertTrue(origin.calls.drop(before).any { it.status == 503 && it.from in holeStart until holeEnd })
                        assertTrue(abs(pts.get() - positionMs * 1000) > 150_000)
                        launched.onActivity { assertTrue(it.playback.diskRanges.value.none { range -> 5_000_000L in range.startUs until range.endUs }) }
                    }
                    report.put("status", "PASSED")
                } catch(failure: Throwable) {
                    report.put("status", "FAILED").put("failure", failure.toString())
                    runCatching { scene?.onActivity {
                        val native = it.playback.player
                        val view = findVideo(it.window.decorView)
                        report.put("failurePlayer", it.playback.state.value.toString()).put("failureDiskRanges", it.playback.diskRanges.value.toString())
                            .put("failureNativeState", native?.playbackState).put("failurePlaying", native?.playWhenReady)
                            .put("failureErrorCode", native?.playerError?.errorCode)
                            .put("failureVideoSize", native?.videoSize.toString()).put("failureRoute", view?.state().toString())
                            .put("failureViewSize", "${view?.width}x${view?.height}").put("failureSurfaceSize", "${view?.surfaceView?.width}x${view?.surfaceView?.height}")
                    } }
                    throw failure
                } finally {
                    report.put("ptsUs", pts.get()).put("calls", JSONArray(origin.calls.map { it.json() }))
                    File(context.filesDir, "mp4-ui-cold-$name.json").writeText(report.toString(2))
                    scene?.close(); source.close()
                    io { PlaybackCache.get(context).removeResource(key) }
                }
            }
        } finally { runBlocking { settings.update { original } } }
    }
    private fun blue(view: RoutedVideoView, name: String, report: JSONObject): List<Double> {
        val started = android.os.SystemClock.elapsedRealtime()
        var rgb = emptyList<Double>()
        compose.waitUntil(8_000) {
            val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
            try {
                val done = CountDownLatch(1); val status = AtomicInteger(-1)
                instrumentation.runOnMainSync { PixelCopy.request(view.surfaceView, bitmap, { status.set(it); done.countDown() }, Handler(Looper.getMainLooper())) }
                check(done.await(5, TimeUnit.SECONDS))
                report.put("pixelCopyStatus", status.get())
                if(status.get() != PixelCopy.SUCCESS) false else {
                    rgb = (0..2).map { channel -> (15..35).sumOf { y -> (55..95).sumOf { x ->
                        val color = bitmap.getPixel(x, y); when(channel) { 0 -> Color.red(color); 1 -> Color.green(color); else -> Color.blue(color) }
                    } }.toDouble() / (21 * 41) }
                    report.put("lastRgb", JSONArray(rgb))
                    if(android.os.SystemClock.elapsedRealtime() - started > 7_500) File(context.filesDir, "mp4-ui-$name-failed-frame.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    (abs(rgb[0]) <= 45 && abs(rgb[1]) <= 45 && abs(rgb[2] - 255) <= 45).also { matches ->
                        if(matches) File(context.filesDir, "mp4-ui-cold-$name-blue.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            } finally { bitmap.recycle() }
        }
        return rgb
    }
    private fun findVideo(view: View): RoutedVideoView? {
        if(view is RoutedVideoView) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) findVideo(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun <T> io(block: () -> T): T = runBlocking { withContext(Dispatchers.IO) { block() } }
}

private class Mp4TimelineOrigin(private val body: ByteArray) : Closeable {
    class Call(val from: Long, val to: Long, val status: Int) {
        fun json() = JSONObject().put("from", from).put("to", to).put("status", status)
    }
    val calls = CopyOnWriteArrayList<Call>()
    val available = AtomicBoolean(true)
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val url = "http://127.0.0.1:${server.localPort}/clip.mp4"
    init { thread(isDaemon = true) {
        while(running.get()) {
            val socket = try { server.accept() } catch(_: java.io.IOException) { break }
            sockets += socket
            thread(isDaemon = true) { socket.use { try { serve(it) } catch(_: java.io.IOException) { } finally { sockets -= it } } }
        }
    } }
    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream().bufferedReader()
        val request = input.readLine()?.split(' ') ?: return
        val headers = linkedMapOf<String, String>()
        while(true) { val line = input.readLine()?.takeIf { it.isNotBlank() } ?: break; headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
        val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val from = range?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val to = range?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(body.lastIndex.toLong()) ?: body.lastIndex.toLong()
        if(!available.get()) {
            calls += Call(from, to, 503)
            socket.getOutputStream().apply { write("HTTP/1.1 503 Stopped\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            return
        }
        require(from <= to && from < body.size)
        val code = if(range == null) 200 else 206
        calls += Call(from, to, code)
        socket.getOutputStream().apply {
            write(("HTTP/1.1 $code OK\r\nContent-Type: video/mp4\r\nContent-Length: ${to - from + 1}\r\nAccept-Ranges: bytes\r\n" +
                (if(range == null) "" else "Content-Range: bytes $from-$to/${body.size}\r\n") + "Connection: close\r\n\r\n").toByteArray())
            if(request[0] != "HEAD") write(body, from.toInt(), (to - from + 1).toInt())
            flush()
        }
    }
    override fun close() { running.set(false); server.close(); sockets.forEach { runCatching { it.close() } } }
}
