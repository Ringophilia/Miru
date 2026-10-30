// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.browser.capability

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.effect.R as EffectR
import androidx.media3.inspector.frame.FrameExtractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Candidate-engine probe, not P6 implementation/acceptance. Run explicitly with:
 * -e class app.miru.browser.capability.SeekPreviewFrameExtractorCapabilityTest
 * -e miruSeekPreviewCapability true
 * Routine suite should exclude package app.miru.browser.capability.
 */
@RunWith(AndroidJUnit4::class)
class SeekPreviewFrameExtractorCapabilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val decoderContext by lazy { seekPreviewDecoderContext() }

    @Before fun explicitlyEnabled() {
        assumeTrue("Independent P6 capability probe requires explicit opt-in",
            InstrumentationRegistry.getArguments().getString("miruSeekPreviewCapability") == "true")
    }

    @Test fun httpMp4HasDistinctDecodedFramesAtThreeTimes() = httpFrames("colors.mp4", MimeTypes.VIDEO_MP4, listOf("colors.mp4"))
    @Test fun hlsTsHasDistinctDecodedFramesAtThreeTimes() = httpFrames("ts.m3u8", MimeTypes.APPLICATION_M3U8,
        listOf("ts.m3u8", "ts-00.ts", "ts-01.ts", "ts-02.ts"))
    @Test fun hlsFmp4HasDistinctDecodedFramesAtThreeTimes() = httpFrames("fmp4.m3u8", MimeTypes.APPLICATION_M3U8,
        listOf("fmp4.m3u8", "fmp4-init.mp4", "fmp4-00.m4s", "fmp4-01.m4s", "fmp4-02.m4s"))
    @Test fun hlsAes128HasDistinctDecryptedFramesAtThreeTimes() = httpFrames("aes.m3u8", MimeTypes.APPLICATION_M3U8,
        listOf("aes.m3u8", "aes.key", "aes-00.ts", "aes-01.ts", "aes-02.ts"))
    @Test fun dashHasDistinctDecodedFramesAtThreeTimes() = httpFrames("dash.mpd", MimeTypes.APPLICATION_MPD,
        listOf("dash.mpd", "dash-init-0.m4s", "dash-0-00001.m4s", "dash-0-00002.m4s", "dash-0-00003.m4s"))

    @Test fun contentUriHasDistinctDecodedFramesAtThreeTimes() {
        val probe = createProbe(SeekPreviewFixtureProvider.URI.toString(), MimeTypes.VIDEO_MP4)
        try { assertThreeFrames(probe) } finally { probe.close() }
    }

    @Test fun cancellingHungFrameMustReleaseItsSocketAndAllowNextRequest() {
        SeekPreviewOrigin(instrumentation.context, hangFirst = true).use { origin ->
            val probe = createProbe("${origin.url}/hang.mp4", MimeTypes.VIDEO_MP4, origin)
            try {
                val hung = probe.frame(1_000)
                assertTrue("Origin must actually enter a body read before cancel", origin.hangStarted.await(10, TimeUnit.SECONDS))
                assertTrue("Client must actually consume media bytes before cancel", origin.clientBodyRead.await(5, TimeUnit.SECONDS))
                assertHungPending(hung, origin)
                val actionAt = SystemClock.elapsedRealtime()
                val deadline = actionAt + 6_000
                val cancelled = hung.cancel(true)
                assertTrue("Cancellation must actually transition the pending public future", cancelled)
                val next = probe.frame(9_000)
                val recovered = runCatching {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    check(remaining > 0) { "Main-thread frame dispatch exhausted the six-second gate" }
                    assertFrame(next.get(remaining, TimeUnit.MILLISECONDS), 9_000, Color.BLUE)
                    check(SystemClock.elapsedRealtime() <= deadline) { "Recovery exceeded the original six-second deadline" }
                }
                origin.peerClosed.await((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                val released = peerClosedInWindow(origin, actionAt, deadline)
                Log.i(TAG, "cancel probe: futureCancelled=$cancelled bodyReadAt=${origin.clientBodyReadAt.get()} actionAt=$actionAt peerClosedAt=${origin.peerClosedAt.get()} observedAt=${SystemClock.elapsedRealtime()} deadline=$deadline readTimeoutMs=$READ_TIMEOUT_MS peerClosedInWindow=$released nextFrame=${recovered.isSuccess}")
                origin.assertHealthy()
                assertTrue("Future cancellation must close the peer socket before fixture cleanup (30s read timeout; 6s gate)", released)
                recovered.getOrThrow()
                assertTrue("Recovery must make a second real HTTP request", origin.calls.count { it.path == "/hang.mp4" } >= 2)
            } finally { probe.close() }
        }
    }

    @Test fun closeDuringHungReadMustReleaseSocketAndPermitFreshExtractor() {
        SeekPreviewOrigin(instrumentation.context, hangFirst = true).use { origin ->
            val probe = createProbe("${origin.url}/hang.mp4", MimeTypes.VIDEO_MP4, origin)
            try {
                val hung = probe.frame(1_000)
                assertTrue("Origin must actually enter a body read before close", origin.hangStarted.await(10, TimeUnit.SECONDS))
                assertTrue("Client must actually consume media bytes before close", origin.clientBodyRead.await(5, TimeUnit.SECONDS))
                assertHungPending(hung, origin)
                val actionAt = SystemClock.elapsedRealtime()
                val deadline = actionAt + 5_000
                assertTrue("Cancellation must actually transition the pending public future", hung.cancel(true))
                probe.closeExtractor()
                val closeReturnedAt = SystemClock.elapsedRealtime()
                // Observe the real peer while server and original transport remain untouched.
                origin.peerClosed.await((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                val released = peerClosedInWindow(origin, actionAt, deadline) && closeReturnedAt <= deadline
                Log.i(TAG, "close observation: bodyReadAt=${origin.clientBodyReadAt.get()} actionAt=$actionAt closeReturnedAt=$closeReturnedAt peerClosedAt=${origin.peerClosedAt.get()} deadline=$deadline readTimeoutMs=$READ_TIMEOUT_MS peerClosedInWindow=$released")
                val freshStartedAt = SystemClock.elapsedRealtime()
                val freshDeadline = freshStartedAt + 10_000
                val fresh = createProbe("${origin.url}/colors.mp4", MimeTypes.VIDEO_MP4, origin)
                val recovered = try { runCatching {
                    val next = fresh.frame(5_000)
                    val remaining = freshDeadline - SystemClock.elapsedRealtime()
                    check(remaining > 0) { "Main-thread fresh-extractor dispatch exhausted the recovery gate" }
                    assertFrame(next.get(remaining, TimeUnit.MILLISECONDS), 5_000, Color.GREEN)
                    check(SystemClock.elapsedRealtime() <= freshDeadline) { "Fresh extraction exceeded its original ten-second deadline" }
                } }
                    finally { fresh.close() }
                Log.i(TAG, "close probe: peerClosedWithin5s=$released freshExtractor=${recovered.isSuccess}")
                origin.assertHealthy()
                assertTrue("cancel+close must close peer within 5s, before 30s read timeout and fixture cleanup", released)
                recovered.getOrThrow()
            } finally { probe.close() }
        }
    }

    private fun assertHungPending(hung: ListenableFuture<FrameExtractor.Frame>, origin: SeekPreviewOrigin) {
        assertFalse("The first extraction must still be pending at the cancellation boundary", hung.isDone)
        assertEquals("Peer must still be open before the action", 0L, origin.peerClosedAt.get())
        assertEquals("Peer-close latch must not already be released", 1L, origin.peerClosed.count)
        val bodyAt = origin.clientBodyReadAt.get()
        assertTrue("Actual client body read must precede the action and remain before its source timeout",
            bodyAt > 0 && SystemClock.elapsedRealtime() - bodyAt < READ_TIMEOUT_MS)
    }

    private fun peerClosedInWindow(origin: SeekPreviewOrigin, actionAt: Long, deadline: Long): Boolean {
        val peerAt = origin.peerClosedAt.get()
        val bodyAt = origin.clientBodyReadAt.get()
        // The event's own monotonic timestamp, not the later await result, is the oracle.
        return peerAt in actionAt..deadline && peerAt >= bodyAt && peerAt - bodyAt < READ_TIMEOUT_MS
    }

    private fun httpFrames(path: String, mime: String, required: List<String>) {
        SeekPreviewOrigin(instrumentation.context).use { origin ->
            val probe = createProbe("${origin.url}/$path", mime, origin)
            try {
                assertThreeFrames(probe)
                origin.assertHealthy()
                val calls = origin.calls.toList()
                required.forEach { asset -> assertTrue("Decoder must really request $asset", calls.any { it.path == "/$asset" && it.status in 200..299 }) }
                assertTrue("Synthetic credential headers must reach every media/manifest/key request", calls.isNotEmpty() && calls.all { it.headersValid })
                assertTrue("No media request may be rejected", calls.none { it.status >= 400 })
                Log.i(TAG, "frames passed: $path requests=${calls.size}")
            } finally { probe.close() }
        }
    }

    private fun assertThreeFrames(probe: Probe) {
        // Non-monotonic order also catches an extractor returning the previous bitmap/time.
        for((time, color) in listOf(9_000L to Color.BLUE, 1_000L to Color.RED, 5_000L to Color.GREEN))
            assertFrame(probe.frame(time).get(15, TimeUnit.SECONDS), time, color)
    }

    private fun assertFrame(frame: FrameExtractor.Frame, targetMs: Long, expected: Int) {
        assertTrue("Actual decoder timestamp ${frame.presentationTimeMs} must match target $targetMs", abs(frame.presentationTimeMs - targetMs) <= 150)
        val bitmap = frame.bitmap
        assertEquals(160, bitmap.width)
        assertEquals(90, bitmap.height)
        var red = 0L; var green = 0L; var blue = 0L
        for(y in 15..35) for(x in 55..95) {
            val pixel = bitmap.getPixel(x, y)
            red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel)
        }
        val count = 21 * 41
        val actual = listOf(red / count, green / count, blue / count)
        val wanted = listOf(Color.red(expected), Color.green(expected), Color.blue(expected))
        actual.zip(wanted).forEach { (value, target) -> assertTrue("Decoded RGB $actual must match target $wanted at ${frame.presentationTimeMs}", abs(value - target) <= 45) }
        Log.i(TAG, "decoded frame: targetMs=$targetMs presentationMs=${frame.presentationTimeMs} rgb=$actual")
        // Borrowed bitmap: FrameExtractor can return the same object again. Never recycle it.
    }

    private fun createProbe(url: String, mime: String, origin: SeekPreviewOrigin? = null): Probe {
        for(id in listOf(EffectR.raw.vertex_shader_transformation_es2, EffectR.raw.fragment_shader_copy_es2)) {
            assertEquals("Effect raw ID must resolve in its verified APK resource table", "raw", decoderContext.resources.getResourceTypeName(id))
            val shader = decoderContext.resources.openRawResource(id).bufferedReader().use { it.readText() }
            assertTrue("GL must receive shader text rather than a colliding main drawable", shader.startsWith("#version"))
            Log.i(TAG, "shader input verified: ${decoderContext.resources.getResourceName(id)}")
        }
        val client = OkHttpClient.Builder().readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
        val http = OkHttpDataSource.Factory(client).setDefaultRequestProperties(origin?.headers().orEmpty())
        if(origin != null) http.setTransferListener(object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                if(dataSpec.uri.path == "/hang.mp4" && bytesTransferred > 0) {
                    origin.clientBodyReadAt.compareAndSet(0, SystemClock.elapsedRealtime())
                    origin.clientBodyRead.countDown()
                }
            }
        })
        val source = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
        var extractor: FrameExtractor? = null
        instrumentation.runOnMainSync {
            extractor = FrameExtractor.Builder(decoderContext, MediaItem.Builder().setUri(url).setMimeType(mime).build())
                .setMediaSourceFactory(source).setSeekParameters(SeekParameters.EXACT).build()
        }
        return Probe(requireNotNull(extractor), client)
    }

    private inner class Probe(private val extractor: FrameExtractor, private val client: OkHttpClient) : Closeable {
        private var closed = false
        private val futures = CopyOnWriteArrayList<ListenableFuture<FrameExtractor.Frame>>()
        fun frame(timeMs: Long): ListenableFuture<FrameExtractor.Frame> {
            var result: ListenableFuture<FrameExtractor.Frame>? = null
            instrumentation.runOnMainSync { result = extractor.getFrame(timeMs) }
            return requireNotNull(result).also { futures += it }
        }
        fun closeExtractor() { if(!closed) { closed = true; instrumentation.runOnMainSync { extractor.close() } } }
        override fun close() {
            // A wait timeout alone does not cancel a queued task. Keep failed probes
            // from starting a late request after their origin has been torn down.
            futures.forEach { it.cancel(true) }
            closeExtractor()
            // Fixture cleanup only. Never count this explicit transport abort as engine release.
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    companion object {
        private const val TAG = "MiruSeekPreviewProbe"
        private const val READ_TIMEOUT_MS = 30_000L
    }
}

private data class SeekPreviewCall(val path: String, val status: Int, val headersValid: Boolean)

/** Real HTTP range origin; records booleans only, never credential values. */
private class SeekPreviewOrigin(private val assets: Context, private val hangFirst: Boolean = false) : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val didHang = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val bytes = ConcurrentHashMap<String, ByteArray>()
    private val failure = AtomicReference<String?>(null)
    val calls = CopyOnWriteArrayList<SeekPreviewCall>()
    val hangStarted = CountDownLatch(1)
    val clientBodyRead = CountDownLatch(1)
    val clientBodyReadAt = AtomicLong(0)
    val peerClosed = CountDownLatch(1)
    val peerClosedAt = AtomicLong(0)
    val url = "http://127.0.0.1:${server.localPort}"
    fun headers() = mapOf("Cookie" to "miru_preview=synthetic", "Authorization" to "Bearer preview-fixture",
        "Referer" to "$url/source", "Origin" to url, "User-Agent" to "Miru-Seek-Preview-Probe")

    init { thread(isDaemon = true, name = "miru-preview-origin") {
        while(running.get()) {
            val socket = try { server.accept() } catch(_: java.io.IOException) { break }
            sockets += socket
            thread(isDaemon = true, name = "miru-preview-response") {
                socket.use {
                    try { serve(it) }
                    catch(_: java.io.IOException) { /* client release may abort the body */ }
                    catch(error: Exception) { failure.compareAndSet(null, error.javaClass.simpleName) }
                    finally { sockets -= it }
                }
            }
        }
    } }

    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream().bufferedReader()
        val request = input.readLine()?.split(' ') ?: return
        val path = request.getOrNull(1)?.substringBefore('?') ?: return
        val received = linkedMapOf<String, String>()
        while(true) {
            val line = input.readLine()?.takeIf(String::isNotBlank) ?: break
            received[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        val valid = headers().all { (key, value) -> received[key.lowercase()] == value }
        val name = if(path == "/hang.mp4") "colors.mp4" else path.removePrefix("/")
        // No directory traversal or arbitrary APK-asset exposure.
        val known = name.matches(Regex("(colors\\.mp4|(?:ts|fmp4|aes)\\.m3u8|(?:ts|aes)-0[0-2]\\.ts|fmp4-0[0-2]\\.m4s|fmp4-init\\.mp4|aes\\.key|dash\\.mpd|dash-init-0\\.m4s|dash-0-0000[1-3]\\.m4s)"))
        if(!valid || !known) {
            calls += SeekPreviewCall(path, if(valid) 404 else 403, valid)
            socket.getOutputStream().apply { write("HTTP/1.1 ${if(valid) "404 Not Found" else "403 Forbidden"}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            return
        }
        val body = bytes.getOrPut(name) { assets.assets.open("media/seek-preview/$name").use { it.readBytes() } }
        val range = received["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val from = range?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val to = range?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(body.lastIndex.toLong()) ?: body.lastIndex.toLong()
        if(from > to || from >= body.size) {
            calls += SeekPreviewCall(path, 416, true)
            socket.getOutputStream().apply { write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nContent-Range: bytes */${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            return
        }
        val status = if(range == null) 200 else 206
        calls += SeekPreviewCall(path, status, true)
        val mime = when { name.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"; name.endsWith(".mpd") -> "application/dash+xml"; name.endsWith(".ts") -> "video/mp2t"; name.endsWith(".key") -> "application/octet-stream"; else -> "video/mp4" }
        val length = (to - from + 1).toInt()
        val output = socket.getOutputStream()
        output.write(("HTTP/1.1 $status ${if(status == 200) "OK" else "Partial Content"}\r\nContent-Type: $mime\r\nContent-Length: $length\r\nAccept-Ranges: bytes\r\nCache-Control: no-store\r\n" +
            (if(range != null) "Content-Range: bytes $from-$to/${body.size}\r\n" else "") + "Connection: close\r\n\r\n").toByteArray())
        if(path == "/hang.mp4" && hangFirst && didHang.compareAndSet(false, true)) {
            // A genuine partially received response, then a blocked native/HTTP read.
            output.write(body, from.toInt(), minOf(32, length)); output.flush()
            hangStarted.countDown()
            socket.soTimeout = 250
            while(running.get()) {
                try { if(input.read() == -1) { recordPeerClose(); return } }
                catch(_: SocketTimeoutException) { /* Continue observing peer; no server timeout closure. */ }
                catch(_: java.io.IOException) { recordPeerClose(); return }
            }
        } else { if(request[0] != "HEAD") output.write(body, from.toInt(), length); output.flush() }
    }

    private fun recordPeerClose() {
        if(running.get()) {
            peerClosedAt.compareAndSet(0, SystemClock.elapsedRealtime())
            peerClosed.countDown()
        }
    }

    fun assertHealthy() { failure.get()?.let { throw AssertionError("Synthetic preview server failed: $it") } }
    override fun close() {
        running.set(false) // Before forced fixture cleanup, so it cannot count as peer release.
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }
}
