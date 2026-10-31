// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.browser.capability

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native cold startup/seek I/O probe only; no production MP4 index or P11 acceptance claim. */
@RunWith(AndroidJUnit4::class)
class CachedRangeMp4NativeSeekCapabilityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun explicitlyEnabled() {
        assumeTrue("Separate MP4 native I/O preflight requires explicit opt-in",
            InstrumentationRegistry.getArguments().getString("miruMp4NativeSeekCapability") == "true")
    }
    @Test fun aColdDecoderAtCachedNineMustNotRequestTheMissingGreenBytes() = probe(9_000, true, "nine")
    @Test fun aColdDecoderAtUncachedFiveMustFailAtTheActualByteHole() = probe(5_000, false, "green")

    private fun probe(positionMs: Long, expectBlue: Boolean, name: String) {
        val assets = instrumentation.context.assets
        val reference = JSONObject(assets.open("media/cached-ranges/colors.samples.json").bufferedReader().use { it.readText() })
        val bytes = assets.open(reference.getString("asset")).use { it.readBytes() }
        assertEquals(reference.getLong("bytes"), bytes.size.toLong())
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(reference.getString("sha256"), digest)
        val samples = reference.getJSONArray("tracks").getJSONObject(0).getJSONArray("samples")
        val green = (0 until samples.length()).map(samples::getJSONObject).filter { it.getLong("ptsUs") in 4_000_000 until 8_000_000 }
        assertEquals(40, green.size)
        val holeStart = green.minOf { it.getLong("offset") }
        val holeEnd = green.maxOf { it.getLong("offset") + it.getLong("size") }
        assertTrue(holeStart > 1376 && holeEnd < bytes.size)
        val report = JSONObject().put("sourceSha256", digest).put("sourceBytes", bytes.size)
            .put("holeStart", holeStart).put("holeEnd", holeEnd).put("targetMs", positionMs).put("scope", "Native MP4 I/O only; P11 remains pending")
        val settings = PlayerPreferencesRepository(context)
        val original = runBlocking { settings.settings.first() }
        runBlocking { settings.update { it.copy(autoPip = false, superResMode = SuperResMode.OFF) } }
        try {
            Mp4SeekOrigin(bytes).use { origin ->
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
                        fill(DataSpec.Builder().setUri(origin.url).setLength(holeStart).build())
                        fill(DataSpec.Builder().setUri(origin.url).setPosition(holeEnd).setLength(bytes.size - holeEnd).build())
                        // Removing the last span removes metadata too. Restore the
                        // measured native end of this same immutable, hashed body.
                        cache.applyContentMetadataMutations(key, ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, actualEnd) })
                        assertTrue(cache.isCached(key, 0, holeStart))
                        assertFalse(cache.isCached(key, holeStart, holeEnd - holeStart))
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
                    compose.waitUntil(30_000) {
                        var done = false
                        launched.onActivity {
                            it.playback.player?.setVideoFrameMetadataListener { value, _, _, _ -> pts.set(value) }
                            snapshot = it.playback.state.value
                            if(expectBlue && (snapshot.error || snapshot.timedOut)) throw AssertionError("Cold cached-blue MP4 failed: $snapshot; requests=${origin.calls.drop(before).map { call -> call.json() }}")
                            done = if(expectBlue) snapshot.ready && snapshot.duration == 12_000L && snapshot.width == 160
                                else snapshot.error
                            if(done) it.playback.pause()
                        }
                        done
                    }
                    report.put("player", snapshot.toString())
                    if(expectBlue) {
                        var view: RoutedVideoView? = null
                        launched.onActivity {
                            view = findVideo(it.window.decorView)
                            it.playback.seek(positionMs)
                        }
                        compose.waitUntil(10_000) { abs(pts.get() - positionMs * 1000) <= 150_000 }
                        val rgb = blue(requireNotNull(view), name)
                        report.put("rgb", JSONArray(rgb))
                        assertEquals("A fresh decoder must not fetch the stopped middle to start at cached nine", before, origin.calls.size)
                    } else {
                        assertTrue(origin.calls.drop(before).any { it.status == 503 && it.from in holeStart until holeEnd })
                        assertTrue(abs(pts.get() - positionMs * 1000) > 150_000)
                    }
                    report.put("status", "PASSED")
                } catch(failure: Throwable) {
                    report.put("status", "FAILED").put("failure", failure.toString())
                    throw failure
                } finally {
                    report.put("ptsUs", pts.get()).put("calls", JSONArray(origin.calls.map { it.json() }))
                    File(context.filesDir, "mp4-native-cold-$name.json").writeText(report.toString(2))
                    scene?.close(); source.close()
                    io { PlaybackCache.get(context).removeResource(key) }
                }
            }
        } finally { runBlocking { settings.update { original } } }
    }
    private fun blue(view: RoutedVideoView, name: String): List<Double> {
        var rgb = emptyList<Double>()
        compose.waitUntil(8_000) {
            val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
            try {
                val done = CountDownLatch(1); val status = AtomicInteger(-1)
                instrumentation.runOnMainSync { PixelCopy.request(view.surfaceView, bitmap, { status.set(it); done.countDown() }, Handler(Looper.getMainLooper())) }
                check(done.await(5, TimeUnit.SECONDS))
                if(status.get() != PixelCopy.SUCCESS) false else {
                    rgb = (0..2).map { channel -> (15..35).sumOf { y -> (55..95).sumOf { x ->
                        val color = bitmap.getPixel(x, y); when(channel) { 0 -> Color.red(color); 1 -> Color.green(color); else -> Color.blue(color) }
                    } }.toDouble() / (21 * 41) }
                    (abs(rgb[0]) <= 45 && abs(rgb[1]) <= 45 && abs(rgb[2] - 255) <= 45).also { matches ->
                        if(matches) File(context.filesDir, "mp4-native-cold-$name-blue.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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

private class Mp4SeekOrigin(private val body: ByteArray) : Closeable {
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
