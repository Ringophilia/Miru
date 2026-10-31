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
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSpec
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real MPD/Range HTTP, native sidx, same public UI flow and a fresh decoder with media supply stopped. */
@RunWith(AndroidJUnit4::class)
class DashCachedTimelineUiIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun actualSidxHoleAndCachedBlueSurviveAStoppedMediaOriginAndFreshDecoder() = verifyStoppedMedia("base", 12_000_000,
        listOf(CachedTimeRange(0, 4_000_000), CachedTimeRange(8_000_000, 12_000_000)))

    @Test fun actualSidxPresentationOffsetUsesTheNativeTenSecondWindow() = verifyStoppedMedia("pto", 10_000_000,
        listOf(CachedTimeRange(0, 2_000_000), CachedTimeRange(6_000_000, 10_000_000)))

    @Test fun privateDashPublishesNoDiskRangesAndKeepsTheSecureFlag() {
        val before = io { PlaybackCache.get(context).keys.associateWith { key -> PlaybackCache.get(context).getCachedSpans(key).map { it.position to it.length } } }
        withPlayer("base", 12_000_000, true) { scenario, _ ->
            scenario.onActivity {
                assertTrue(it.playback.diskRanges.value.isEmpty())
                assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        }
        val after = io { PlaybackCache.get(context).keys.associateWith { key -> PlaybackCache.get(context).getCachedSpans(key).map { it.position to it.length } } }
        assertEquals(before, after)
    }

    private fun verifyStoppedMedia(kind: String, durationUs: Long, holes: List<CachedTimeRange>) = withPlayer(kind, durationUs) { scenario, origin ->
        val surfaceEvents = CopyOnWriteArrayList<String>()
        val unexpectedSurfaceSizes = CopyOnWriteArrayList<String>()
        scenario.onActivity { activity ->
            val view = requireNotNull(findVideo(activity.window.decorView))
            surfaceEvents += "initial=${view.surfaceView.width}x${view.surfaceView.height}"
            view.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { surfaceEvents += "created" }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    surfaceEvents += "changed=${width}x$height position=${activity.playback.player?.currentPosition} source=${activity.playback.state.value.sourceRevision}"
                    // Window insets/rotation may still resize the viewport. The
                    // square-pixel 160x90 fixture must retain its ratio throughout.
                    if(abs(width * 90L - height * 160L) > 160L) unexpectedSurfaceSizes += "${width}x$height"
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceEvents += "destroyed" }
            })
        }
        val key = key(origin)
        io {
            val cache = PlaybackCache.get(context)
            val middle = cache.getCachedSpans(key).single { it.position == 8406L }
            assertEquals(6978L, middle.length)
            cache.removeSpan(middle)
        }
        compose.waitUntil(10_000) { ranges(scenario) == holes }
        val blueRequests = origin.calls.count { it.path == "/base.mp4" && it.from == 15_384L }
        origin.mediaAvailable.set(false) // MPD stays reachable; every upstream media request now returns 503.
        var old: androidx.media3.exoplayer.ExoPlayer? = null
        var revision = 0L
        scenario.onActivity {
            old = it.playback.player; revision = it.playback.state.value.sourceRevision
            it.playback.seek(9_000)
            it.playback.decoder("software")
            assertTrue(it.playback.diskRanges.value.isEmpty())
        }
        compose.waitUntil(30_000) {
            var ready = false
            scenario.onActivity {
                if(it.playback.state.value.error) throw AssertionError("Cached DASH decoder restart requested stopped media")
                ready = it.playback.state.value.ready && it.playback.state.value.sourceRevision > revision && it.playback.player !== old
                if(ready) it.playback.pause()
            }
            ready
        }
        val pts = AtomicLong(-1)
        scenario.onActivity {
            checkNotNull(it.playback.player).setVideoFrameMetadataListener { value, _, _, _ -> pts.set(value) }
            it.playback.seek(9_000)
        }
        compose.waitUntil(10_000) { abs(pts.get() - 9_000_000) <= 150_000 }
        Log.i("MiruDashDiskUi", "kind=$kind surfaceEvents=$surfaceEvents")
        assertEquals("Replacing a decoder for the same video must retain its aspect ratio: $surfaceEvents",
            emptyList<String>(), unexpectedSurfaceSizes.toList())
        var output: RoutedVideoView? = null
        scenario.onActivity { output = findVideo(it.window.decorView) }
        val frame = try { blue(requireNotNull(output), kind) } catch(failure: Throwable) {
            scenario.onActivity {
                val player = checkNotNull(it.playback.player)
                val view = requireNotNull(findVideo(it.window.decorView))
                Log.e("MiruDashDiskUi", "kind=$kind pts=${pts.get()} position=${player.currentPosition} state=${player.playbackState} playing=${player.playWhenReady} " +
                    "video=${player.videoSize.width}x${player.videoSize.height} view=${view.width}x${view.height} surface=${view.surfaceView.width}x${view.surfaceView.height} " +
                    "route=${view.state()} disk=${it.playback.diskRanges.value} surfaceEvents=$surfaceEvents")
            }
            throw failure
        }
        try { File(context.filesDir, "dash-cached-$kind-blue.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { frame.recycle() }
        assertEquals("A fresh decoder must read cached blue, not ask the stopped origin", blueRequests,
            origin.calls.count { it.path == "/base.mp4" && it.from == 15_384L })
        compose.waitUntil(10_000) { ranges(scenario).any { 9_000_000L in it.startUs until it.endUs } }
        // Removing the initialization/index span must revoke the disk proof even
        // though the prepared decoder can still retain both in memory.
        io { val cache = PlaybackCache.get(context); cache.getCachedSpans(key).single { it.position == 0L }.let(cache::removeSpan) }
        compose.waitUntil(10_000) { ranges(scenario).isEmpty() }
    }

    private fun withPlayer(kind: String, durationUs: Long, isPrivate: Boolean = false,
        verify: (ActivityScenario<PlayerActivity>, DashRangeOrigin) -> Unit) {
        val settings = PlayerPreferencesRepository(context)
        val original = runBlocking { settings.settings.first() }
        runBlocking { settings.update { it.copy(autoPip = false, superResMode = SuperResMode.OFF) } }
        try {
            DashRangeOrigin(instrumentation.context).use { origin ->
                val request = PlaybackRequest("${origin.url}/$kind.mpd", "Controlled DASH ranges", pageUrl = "${origin.url}/watch",
                    mimeType = "application/dash+xml", fromWeb = true, isPrivate = isPrivate)
                val scene = ActivityScenario.launch<PlayerActivity>(PlaybackRequests.intent(context, request).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                try {
                    compose.waitUntil(30_000) {
                        var ready = false
                        scene.onActivity {
                            val state = it.playback.state.value
                            if(state.error || state.timedOut) throw AssertionError("Controlled DASH failed before the UI gate")
                            ready = state.ready && state.duration == durationUs / 1000 && state.width > 0 &&
                                it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && it.window.decorView.hasWindowFocus()
                            if(ready) it.playback.pause()
                        }
                        ready
                    }
                    if(!isPrivate) compose.waitUntil(10_000) { ranges(scene) == listOf(CachedTimeRange(0, durationUs)) }
                    compose.waitUntil(10_000) { var complete = false; scene.onActivity { complete = !checkNotNull(it.playback.player).isLoading }; complete }
                    verify(scene, origin)
                } finally {
                    scene.close()
                    if(!isPrivate) io { PlaybackCache.get(context).removeResource(key(origin)) }
                }
            }
        } finally { runBlocking { settings.update { original } } }
    }
    private fun key(origin: DashRangeOrigin): String {
        val session = PlaybackSourceSession(context, 0, MediaItem.fromUri("${origin.url}/base.mpd"), emptyMap())
        return try { session.cacheKey(DataSpec.Builder().setUri("${origin.url}/base.mp4").setLength(872).build()) } finally { session.close() }
    }
    private fun ranges(scene: ActivityScenario<PlayerActivity>): List<CachedTimeRange> { var result = emptyList<CachedTimeRange>(); scene.onActivity { result = it.playback.diskRanges.value }; return result }
    private fun <T> io(block: () -> T): T = runBlocking { withContext(Dispatchers.IO) { block() } }
    private fun findVideo(view: View): RoutedVideoView? {
        if(view is RoutedVideoView) return view
        if(view is ViewGroup) for(index in 0 until view.childCount) findVideo(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun blue(view: RoutedVideoView, kind: String): Bitmap {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        var lastRgb = emptyList<Double>()
        while(true) {
            val frame = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
            val done = CountDownLatch(1); val result = AtomicInteger(PixelCopy.ERROR_UNKNOWN)
            instrumentation.runOnMainSync { PixelCopy.request(view.surfaceView, frame, { result.set(it); done.countDown() }, Handler(Looper.getMainLooper())) }
            check(done.await(5, TimeUnit.SECONDS))
            if(result.get() == PixelCopy.SUCCESS) {
                val rgb = (0..2).map { channel -> (15..35).sumOf { y -> (55..95).sumOf { x ->
                    val color = frame.getPixel(x, y); when(channel) { 0 -> Color.red(color); 1 -> Color.green(color); else -> Color.blue(color) }
                } }.toDouble() / (21 * 41) }
                lastRgb = rgb
                if(abs(rgb[0]) <= 45 && abs(rgb[1]) <= 45 && abs(rgb[2] - 255) <= 45) {
                    Log.i("MiruDashDiskUi", "kind=$kind RGB=$rgb PixelCopy=${result.get()}")
                    return frame
                }
            }
            if(SystemClock.elapsedRealtime() >= deadline) {
                File(context.filesDir, "dash-cached-$kind-failed.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Log.e("MiruDashDiskUi", "kind=$kind lastRGB=$lastRgb PixelCopy=${result.get()} validSurface=${view.surfaceView.holder.surface.isValid}")
            }
            frame.recycle()
            check(SystemClock.elapsedRealtime() < deadline) { "Cached DASH source did not display blue" }
            SystemClock.sleep(30)
        }
    }
}

private class DashRangeOrigin(assets: Context) : Closeable {
    class Call(val path: String, val from: Long, val status: Int)
    val calls = CopyOnWriteArrayList<Call>()
    val mediaAvailable = AtomicBoolean(true)
    private val alive = AtomicBoolean(true)
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val url = "http://127.0.0.1:${server.localPort}"
    private val files = listOf("base.mp4", "base.mpd", "pto.mpd").associate { it to assets.assets.open("media/cached-ranges/dash/$it").use { input -> input.readBytes() } }
    init { thread(isDaemon = true) {
        while(alive.get()) {
            val socket = try { server.accept() } catch(_: java.io.IOException) { break }
            sockets += socket
            thread(isDaemon = true) { socket.use { try { serve(it) } catch(_: java.io.IOException) { } finally { sockets -= it } } }
        }
    } }
    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream().bufferedReader()
        val request = input.readLine()?.split(' ') ?: return
        val path = request.getOrNull(1)?.substringBefore('?') ?: return
        val headers = linkedMapOf<String, String>()
        while(true) { val line = input.readLine()?.takeIf { it.isNotBlank() } ?: break; headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
        val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val from = range?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val body = files[path.removePrefix("/")]
        if(body == null || path.endsWith(".mp4") && !mediaAvailable.get()) {
            val status = if(body == null) 404 else 503
            calls += Call(path, from, status)
            socket.getOutputStream().apply { write("HTTP/1.1 $status Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            return
        }
        val to = range?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(body.lastIndex.toLong()) ?: body.lastIndex.toLong()
        require(from <= to && from < body.size)
        val status = if(range == null) 200 else 206
        calls += Call(path, from, status)
        socket.getOutputStream().apply {
            val mime = if(path.endsWith(".mpd")) "application/dash+xml" else "video/mp4"
            write(("HTTP/1.1 $status OK\r\nContent-Type: $mime\r\nContent-Length: ${to - from + 1}\r\nAccept-Ranges: bytes\r\n" +
                (if(range == null) "" else "Content-Range: bytes $from-$to/${body.size}\r\n") + "Connection: close\r\n\r\n").toByteArray())
            if(request[0] != "HEAD") write(body, from.toInt(), (to - from + 1).toInt())
            flush()
        }
    }
    override fun close() { alive.set(false); server.close(); sockets.forEach { runCatching { it.close() } } }
}
