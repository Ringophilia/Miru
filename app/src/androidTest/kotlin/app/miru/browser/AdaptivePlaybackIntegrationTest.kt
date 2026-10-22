// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.browser

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.player.PlaybackRequest
import app.miru.player.PlayerController
import app.miru.player.PlayerActivity
import app.miru.player.PlaybackRequests
import androidx.test.core.app.ActivityScenario
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Media3 parsing/decoding over device loopback; no web service or user credentials. */
@RunWith(AndroidJUnit4::class)
class AdaptivePlaybackIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun await(origin: AdaptivePlaybackOrigin, timeout: Long = 30_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while(!condition()) {
            origin.failure.get()?.let { throw AssertionError("Playback fixture failed: $it") }
            if(SystemClock.elapsedRealtime() >= deadline) throw AssertionError("Adaptive playback condition timed out")
            SystemClock.sleep(50)
        }
    }

    @Test fun dashManifestAndAudioVideoSegmentsCarryHeadersAndActuallyPlay() {
        AdaptivePlaybackOrigin(instrumentation.context).use { origin ->
            val fallbacks = AtomicInteger()
            var controller: PlayerController? = null
            val scenario = ActivityScenario.launch<PlayerActivity>(PlaybackRequests.intent(context, PlaybackRequest(
                    url = "${origin.url}/dash/dash-manifest.mpd", title = "Synthetic DASH fixture",
                    mimeType = "application/dash+xml", headers = origin.headers(fresh = true),
                    fromWeb = true, isPrivate = true,
                    onReturn = { failed -> if(failed) fallbacks.incrementAndGet() },
                )).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            scenario.onActivity { controller = it.playback }
            compose.waitForIdle()
            try {
                compose.waitUntil(30_000) {
                    val state = controller!!.state.value
                    if(state.error || state.timedOut) throw AssertionError("DASH did not reach playable state: $state")
                    state.ready && state.playing && state.position >= 500 && state.width == 320 && state.height == 180
                }
                val calls = origin.calls.toList()
                assertTrue("The actual MPD must be requested", calls.any { it.path.endsWith(".mpd") && it.status in 200..299 })
                assertTrue("DASH video initialization must be requested", calls.any { it.path.endsWith("dash-init-0.m4s") && it.status in 200..299 })
                assertTrue("DASH audio initialization must be requested", calls.any { it.path.endsWith("dash-init-1.m4s") && it.status in 200..299 })
                assertTrue("DASH video media must be requested", calls.any { it.path.contains("dash-segment-0-") && it.status in 200..299 })
                assertTrue("DASH audio media must be requested", calls.any { it.path.contains("dash-segment-1-") && it.status in 200..299 })
                assertTrue("No DASH response should reject its request headers", calls.none { it.status in setOf(401, 403) })
                assertTrue("The same required headers must reach both manifest and segments", calls.filter { it.status in 200..299 }.all { it.headersValid })
                assertTrue("The MPD must expose a real twelve-second presentation", controller!!.state.value.duration in 11_500..12_500)
                assertEquals(0, fallbacks.get())
            } finally { scenario.close() }
        }
    }

    @Test fun unauthorizedAndForbiddenResponsesRefreshHeadersOnceThenPlay() {
        for(status in listOf(401, 403)) {
            AdaptivePlaybackOrigin(instrumentation.context, rejectionStatus = status).use { origin ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val refreshes = AtomicInteger()
                val fallbacks = AtomicInteger()
                var controller: PlayerController? = null
                instrumentation.runOnMainSync {
                    controller = PlayerController(context, PlaybackRequest(
                        url = "${origin.url}/protected.mp4", title = "Synthetic authentication fixture",
                        headers = origin.headers(fresh = false), fromWeb = true, isPrivate = true,
                        refreshHeaders = { refreshes.incrementAndGet(); origin.headers(fresh = true) },
                    ), scope) { fallbacks.incrementAndGet() }
                }
                try {
                    await(origin) {
                        val state = controller!!.state.value
                        if(state.error || state.timedOut) throw AssertionError("Authentication refresh did not recover HTTP $status")
                        state.ready && state.playing && state.position >= 300
                    }
                    assertTrue("A real HTTP rejection must precede the refreshed response", origin.calls.any { it.status == status })
                    assertTrue("Refreshed credentials must reach the media origin", origin.calls.any { it.status in setOf(200, 206) && it.headersValid })
                    assertEquals("Only one native credential refresh is allowed", 1, refreshes.get())
                    assertEquals(0, fallbacks.get())
                } finally { instrumentation.runOnMainSync { controller?.release() }; scope.cancel() }
            }
        }
    }

    @Test fun repeatedForbiddenResponseReturnsToWebWithoutARefreshLoop() {
        AdaptivePlaybackOrigin(instrumentation.context, rejectionStatus = 403, rejectFresh = true).use { origin ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val refreshes = AtomicInteger()
            val fallbacks = AtomicInteger()
            var controller: PlayerController? = null
            instrumentation.runOnMainSync {
                controller = PlayerController(context, PlaybackRequest(
                    url = "${origin.url}/protected.mp4", title = "Synthetic forbidden fixture",
                    headers = origin.headers(fresh = false), fromWeb = true, isPrivate = true,
                    refreshHeaders = { refreshes.incrementAndGet(); origin.headers(fresh = true) },
                ), scope) { fallbacks.incrementAndGet() }
            }
            try {
                await(origin) { fallbacks.get() > 0 }
                assertEquals(1, refreshes.get())
                assertEquals(1, fallbacks.get())
                assertTrue("The refreshed request must also be rejected", origin.calls.any { it.status == 403 && it.headersValid })
                val requestsAfterFallback = origin.calls.size
                SystemClock.sleep(1_500)
                assertEquals("A terminal fallback must not issue more HTTP requests", requestsAfterFallback, origin.calls.size)
                assertEquals("No additional native refresh may be scheduled", 1, refreshes.get())
                assertEquals("The fallback must only fire once", 1, fallbacks.get())
            } finally { instrumentation.runOnMainSync { controller?.release() }; scope.cancel() }
        }
    }

    @Test fun releaseDuringSlowHeaderRefreshDoesNotRecreateThePlayer() {
        AdaptivePlaybackOrigin(instrumentation.context, rejectionStatus = 403).use { origin ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val releaseRefresh = CompletableDeferred<Unit>()
            val refreshStarted = AtomicBoolean()
            val fallbacks = AtomicInteger()
            var controller: PlayerController? = null
            instrumentation.runOnMainSync {
                controller = PlayerController(context, PlaybackRequest(
                    url = "${origin.url}/protected.mp4", title = "Synthetic delayed refresh fixture",
                    headers = origin.headers(fresh = false), fromWeb = true, isPrivate = true,
                    refreshHeaders = {
                        refreshStarted.set(true)
                        releaseRefresh.await()
                        origin.headers(fresh = true)
                    },
                ), scope) { fallbacks.incrementAndGet() }
            }
            try {
                await(origin) { refreshStarted.get() }
                instrumentation.runOnMainSync { controller!!.release() }
                val requestsAtRelease = origin.calls.size
                // Keep the owner's scope alive intentionally: release() must protect its own lifecycle.
                releaseRefresh.complete(Unit)
                SystemClock.sleep(1_500)
                instrumentation.runOnMainSync { assertNull("A released controller must not create a new ExoPlayer", controller!!.player) }
                assertEquals("A released controller must not fetch with the refreshed credentials", requestsAtRelease, origin.calls.size)
                assertEquals(0, fallbacks.get())
            } finally { releaseRefresh.complete(Unit); instrumentation.runOnMainSync { controller?.release() }; scope.cancel() }
        }
    }

    @Test fun failingHeaderRefreshReturnsToWebOnceWithoutCrashing() {
        AdaptivePlaybackOrigin(instrumentation.context, rejectionStatus = 401).use { origin ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val refreshes = AtomicInteger()
            val fallbacks = AtomicInteger()
            var controller: PlayerController? = null
            instrumentation.runOnMainSync {
                controller = PlayerController(context, PlaybackRequest(
                    url = "${origin.url}/protected.mp4", title = "Synthetic failed refresh fixture",
                    headers = origin.headers(fresh = false), fromWeb = true, isPrivate = true,
                    refreshHeaders = { refreshes.incrementAndGet(); throw java.io.IOException("Synthetic refresh failure") },
                ), scope) { fallbacks.incrementAndGet() }
            }
            try {
                await(origin) { fallbacks.get() > 0 }
                val requestsAfterFallback = origin.calls.size
                SystemClock.sleep(1_500)
                assertEquals(1, refreshes.get())
                assertEquals(1, fallbacks.get())
                assertEquals(requestsAfterFallback, origin.calls.size)
            } finally { instrumentation.runOnMainSync { controller?.release() }; scope.cancel() }
        }
    }
}

private data class AdaptivePlaybackCall(val path: String, val status: Int, val headersValid: Boolean)

/** Stores only pass/fail header observations. Header values never enter logs or persistent test output. */
private class AdaptivePlaybackOrigin(
    private val assets: Context,
    private val rejectionStatus: Int = 403,
    private val rejectFresh: Boolean = false,
) : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val assetBytes = ConcurrentHashMap<String, ByteArray>()
    val calls = CopyOnWriteArrayList<AdaptivePlaybackCall>()
    val failure = AtomicReference<String?>(null)
    val url = "http://127.0.0.1:${server.localPort}"

    fun headers(fresh: Boolean): Map<String, String> = mapOf(
        "Cookie" to if(fresh) "miru_adaptive=fresh-fixture" else "miru_adaptive=stale-fixture",
        "Authorization" to if(fresh) "Bearer fresh-adaptive-fixture" else "Bearer stale-adaptive-fixture",
        "Referer" to "$url/source-page",
        "Origin" to url,
        "User-Agent" to "Miru-Adaptive-Playback-Fixture",
    )

    init { thread(isDaemon = true, name = "miru-adaptive-origin") {
        while(running.get()) {
            val socket = try { server.accept() } catch(_: java.io.IOException) { break }
            sockets += socket
            thread(isDaemon = true, name = "miru-adaptive-response") {
                socket.use {
                    try { serve(it) }
                    catch(_: java.io.IOException) { /* Player cancellation closes in-flight media responses. */ }
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
        val valid = headers(fresh = true).all { (key, value) -> received[key.lowercase()] == value }
        if(!valid || rejectFresh) {
            calls += AdaptivePlaybackCall(path, rejectionStatus, valid)
            respond(socket, rejectionStatus, ByteArray(0), "application/octet-stream", request[0], null)
            return
        }
        val asset = when {
            path == "/protected.mp4" -> "media/fixture.mp4"
            path.startsWith("/dash/") && path.substringAfterLast('/').matches(Regex("dash-(manifest\\.mpd|init-[01]\\.m4s|segment-[01]-\\d{5}\\.m4s)")) -> "media$path"
            else -> null
        }
        if(asset == null) {
            calls += AdaptivePlaybackCall(path, 404, true)
            respond(socket, 404, ByteArray(0), "application/octet-stream", request[0], null)
            return
        }
        val bytes = assetBytes.getOrPut(asset) { assets.assets.open(asset).use { it.readBytes() } }
        val range = received["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val from = range?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val to = range?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(bytes.lastIndex.toLong()) ?: bytes.lastIndex.toLong()
        if(from > to || from >= bytes.size) {
            calls += AdaptivePlaybackCall(path, 416, true)
            socket.getOutputStream().apply { write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nContent-Range: bytes */${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            return
        }
        val status = if(range == null) 200 else 206
        calls += AdaptivePlaybackCall(path, status, true)
        val mime = if(path.endsWith(".mpd")) "application/dash+xml" else if(path.contains("dash-init-1") || path.contains("dash-segment-1")) "audio/mp4" else "video/mp4"
        respond(socket, status, bytes, mime, request[0], if(range == null) null else from.toInt()..to.toInt())
    }

    private fun respond(socket: Socket, status: Int, bytes: ByteArray, mime: String, method: String, range: IntRange?) {
        val from = range?.first ?: 0
        val length = range?.let { it.last - it.first + 1 } ?: bytes.size
        val description = when(status) { 200 -> "OK"; 206 -> "Partial Content"; 401 -> "Unauthorized"; 403 -> "Forbidden"; else -> "Not Found" }
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status $description\r\nContent-Type: $mime\r\nContent-Length: $length\r\nAccept-Ranges: bytes\r\nCache-Control: no-store\r\n${range?.let { "Content-Range: bytes ${it.first}-${it.last}/${bytes.size}\r\n" }.orEmpty()}Connection: close\r\n\r\n".toByteArray())
            if(method != "HEAD" && length > 0) write(bytes, from, length)
            flush()
        }
    }

    override fun close() {
        running.set(false)
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }
}
