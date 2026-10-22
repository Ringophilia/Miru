// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.media.DetectedMedia
import app.miru.media.MediaResolutionProbe
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
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
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class M3ProbeIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun media(url: String, kind: String = "HLS", private: Boolean = false, headers: Map<String, String> = emptyMap()) =
        DetectedMedia(url, "probe-fixture", url, "$url/page", "Synthetic probe fixture", kind, headers = headers, isPrivate = private)

    private fun withCache(block: suspend CoroutineScope.(File) -> Unit) = runBlocking {
        val directory = File(context.cacheDir, "m3-probe-${java.util.UUID.randomUUID()}")
        try { withTimeout(45_000) { block(directory) } } finally { directory.deleteRecursively() }
    }

    @Test fun manifestsAndMp4UseBoundedReadsAndRetainSameOriginHeaders() = withCache { directory ->
        ProbeOrigin(instrumentation.context.assets.open("media/fixture.mp4").use { it.readBytes() }).use { origin ->
            val probe = MediaResolutionProbe(context, directory)
            val headers = mapOf("Cookie" to "miru_probe=fixture", "Authorization" to "Bearer probe-fixture")
            val hls = probe.probe(media("${origin.url}/master.m3u8", headers = headers))
            assertNotNull(hls); assertEquals(1920, hls!!.width); assertEquals(1080, hls.height); assertEquals("1080P", hls.label)
            val dash = probe.probe(media("${origin.url}/manifest.mpd", "DASH", headers = headers))
            assertNotNull(dash); assertEquals(3840, dash!!.width); assertEquals(2160, dash.height); assertEquals("4K", dash.label)
            val mp4 = probe.probe(media("${origin.url}/fixture.mp4", "VIDEO", headers = headers))
            assertNotNull("MP4 stsd must be read from a bounded range", mp4)
            assertEquals(320, mp4!!.width); assertEquals(180, mp4.height)
            assertTrue(origin.calls.all { it.credentialsPresent })
            assertTrue("Master variants must not be downloaded when RESOLUTION is present", origin.calls.none { it.path == "/variant.m3u8" })
            val mp4Calls = origin.calls.filter { it.path == "/fixture.mp4" }
            assertTrue(mp4Calls.isNotEmpty())
            assertTrue("A probe must not consume the entire MP4", mp4Calls.sumOf { it.bytes } < 1_500_000)
        }
    }

    @Test fun redirectedProbesRemoveCredentialsAcrossOrigins() = withCache { directory ->
        ProbeOrigin().use { destination -> ProbeOrigin().use { source ->
            source.redirect = "${destination.url}/manifest.mpd"
            val result = MediaResolutionProbe(context, directory).probe(media("${source.url}/redirect.mpd", "DASH", headers = mapOf("Cookie" to "miru_probe=fixture", "Authorization" to "Bearer probe-fixture")))
            assertNotNull(result)
            assertTrue(source.calls.single().credentialsPresent)
            assertTrue(destination.calls.isNotEmpty())
            assertTrue("Changing port changes origin; credentials must be stripped", destination.calls.none { it.credentialsPresent })
        } }
    }

    @Test fun cacheSurvivesRecreationNormalizesQueryAndStoresOnlyHashes() = withCache { directory ->
        ProbeOrigin().use { origin ->
            val first = MediaResolutionProbe(context, directory)
            val item = media("${origin.url}/master.m3u8?signature=first")
            assertNotNull(first.probe(item))
            val calls = origin.calls.size
            assertNotNull(MediaResolutionProbe(context, directory).probe(media("${origin.url}/master.m3u8?signature=second")))
            assertEquals("A query rotation should reuse the cached dimensions", calls, origin.calls.size)
            first.record(item, 3840, 2160)
            assertEquals(2160, MediaResolutionProbe(context, directory).probe(item)!!.height)
            val files = directory.walkTopDown().filter(File::isFile).toList()
            assertTrue(files.isNotEmpty())
            assertTrue(files.none { file -> file.name.contains("signature") || file.readText().let { it.contains(origin.url) || it.contains("signature") || it.contains("Cookie") } })
        }
    }

    @Test fun privateAndFailedProbesDoNotLeakOrRepeatRequests() = withCache { directory ->
        ProbeOrigin().use { origin ->
            val probe = MediaResolutionProbe(context, directory)
            assertNotNull(probe.probe(media("${origin.url}/master.m3u8?private=fixture", private = true)))
            assertFalse("Private quality results must not create disk files", directory.walkTopDown().any(File::isFile))
            assertNull(probe.probe(media("${origin.url}/missing.m3u8")))
            val count = origin.calls.count { it.path == "/missing.m3u8" }
            assertTrue(count > 0)
            assertNull(MediaResolutionProbe(context, directory).probe(media("${origin.url}/missing.m3u8?retry=now")))
            assertEquals("A failed probe should use the short failure cache", count, origin.calls.count { it.path == "/missing.m3u8" })
        }
    }

    @Test fun closingProbeScopeCancelsQueuedWorkAndReleasesDomainPermits() = withCache { directory ->
        ProbeOrigin().use { origin ->
            val probe = MediaResolutionProbe(context, directory)
            val tasks = (0..9).map { index -> async(Dispatchers.IO) { probe.probe(media("${origin.url}/slow-$index.m3u8")) } }
            val until = SystemClock.elapsedRealtime() + 10_000
            while(origin.active.get() < 2 && SystemClock.elapsedRealtime() < until) delay(25)
            assertEquals(2, origin.active.get())
            delay(250)
            assertEquals("Only two requests may enter a single domain", 2, origin.calls.size)
            tasks.forEach { it.cancel() }; tasks.joinAll()
            origin.releaseSlow.countDown()
            val result = withTimeout(10_000) { probe.probe(media("${origin.url}/master.m3u8")) }
            assertNotNull("Cancelling a sheet must release all semaphore permits", result)
            assertTrue("Queued work must never reach the server", origin.calls.count { it.path.startsWith("/slow-") } <= 2)
        }
    }
}

internal data class ProbeCall(val path: String, val credentialsPresent: Boolean, val bytes: Int)

/** Local, synthetic fixtures. Only boolean credential observations are retained. */
internal class ProbeOrigin(private val mp4: ByteArray = ByteArray(0)) : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val calls = CopyOnWriteArrayList<ProbeCall>()
    val active = AtomicInteger()
    val releaseSlow = CountDownLatch(1)
    @Volatile var redirect: String? = null
    val url = "http://127.0.0.1:${server.localPort}"
    init { thread(isDaemon = true, name = "miru-probe-origin") {
        while(running.get()) {
            val socket = try { server.accept() } catch(_: java.io.IOException) { break }
            sockets += socket
            thread(isDaemon = true, name = "miru-probe-response") {
                socket.use { try { serve(it) } catch(_: java.io.IOException) { } finally { sockets -= it } }
            }
        }
    } }
    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val reader = socket.getInputStream().bufferedReader()
        val request = reader.readLine()?.split(' ') ?: return
        val path = request.getOrNull(1)?.substringBefore('?') ?: return
        val headers = linkedMapOf<String, String>()
        while(true) {
            val line = reader.readLine()?.takeIf(String::isNotBlank) ?: break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        val credentials = headers["cookie"] != null || headers["authorization"] != null
        var code = 200
        var mime = "application/vnd.apple.mpegurl"
        var extra = ""
        var bytes = when {
            path == "/page" -> {
                mime = "text/html"
                "<!doctype html><html><meta name=viewport content='width=device-width'><title>Miru quality fixture</title><body><h1>Miru quality fixture</h1><script>fetch('/master.m3u8').then(r=>r.text())</script></body></html>".toByteArray()
            }
            path == "/redirect.mpd" -> { code = 302; extra = "Location: $redirect\r\n"; ByteArray(0) }
            path == "/master.m3u8" || path.startsWith("/slow-") -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080\nvariant.m3u8\n".toByteArray()
            path == "/manifest.mpd" -> {
                mime = "application/dash+xml"
                """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static"><Period><AdaptationSet contentType="video" mimeType="video/mp4"><Representation id="0" width="1280" height="720" bandwidth="500000"/><Representation id="1" width="3840" height="2160" bandwidth="8000000"/></AdaptationSet></Period></MPD>""".toByteArray()
            }
            path == "/fixture.mp4" -> { mime = "video/mp4"; mp4 }
            else -> { code = 404; ByteArray(0) }
        }
        if(path == "/fixture.mp4") {
            val range = headers["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
            val start = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val end = (range?.groupValues?.get(2)?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
            if(range != null && start in 0..end) { code = 206; extra = "Content-Range: bytes $start-$end/${bytes.size}\r\n"; bytes = bytes.copyOfRange(start, end + 1) }
        }
        calls += ProbeCall(path, credentials, bytes.size)
        if(path.startsWith("/slow-")) {
            active.incrementAndGet()
            try { releaseSlow.await(12, TimeUnit.SECONDS) } finally { active.decrementAndGet() }
        }
        socket.getOutputStream().apply {
            write("HTTP/1.1 $code Fixture\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nAccept-Ranges: bytes\r\n${extra}Connection: close\r\n\r\n".toByteArray())
            if(request[0] != "HEAD") write(bytes)
            flush()
        }
    }
    override fun close() { running.set(false); releaseSlow.countDown(); server.close(); sockets.forEach { runCatching { it.close() } } }
}
