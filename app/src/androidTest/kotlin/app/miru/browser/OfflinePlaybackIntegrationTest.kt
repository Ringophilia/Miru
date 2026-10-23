// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.browser

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.offline.OfflineCoordinator
import app.miru.offline.OfflineException
import app.miru.offline.OfflineItem
import app.miru.offline.OfflineSelection
import app.miru.offline.OfflineSource
import app.miru.player.PlaybackCache
import app.miru.player.PlaybackRequest
import app.miru.player.PlaybackRequests
import app.miru.player.PlayerActivity
import app.miru.player.PlayerController
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real DownloadManager, disk cache and decoder; HTTP is stopped before every offline playback. */
@RunWith(AndroidJUnit4::class)
class OfflinePlaybackIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val coordinator get() = OfflineCoordinator.get(context)

    @Test fun selectedHlsQualityIncludesEveryLanguageAndPlaysToEndWithoutAnOrigin() {
        OfflineOrigin(instrumentation.context).use { origin ->
            val ids = mutableListOf<String>()
            try {
                val selection = prepare(origin, "/hls/master.m3u8")
                assertEquals(setOf(160 to 90, 320 to 180), selection.qualities.map { it.width to it.height }.toSet())
                val low = selection.qualities.single { it.width == 160 && it.height == 90 }
                val beforeDownload = origin.calls.size
                val id = onMain { coordinator.enqueue(selection, low.id) }.also(ids::add)
                val complete = completed(id, origin)
                assertTrue(complete.downloadedBytes > 0)
                val downloaded = origin.calls.drop(beforeDownload)
                assertTrue("All low-resolution segments must be cached", (0..2).all { index -> downloaded.any { it.path == "/hls/low-%02d.ts".format(index) } })
                assertTrue("Unselected high-resolution media must not be downloaded", downloaded.none { it.path.startsWith("/hls/video-") })
                assertTrue("Both language renditions must be downloaded", downloaded.any { it.path.startsWith("/hls/english-") } && downloaded.any { it.path.startsWith("/hls/japanese-") })
                assertTrue("The subtitle media must be downloaded", downloaded.any { it.path == "/hls/embedded.vtt" })
                assertTrue(origin.calls.all { it.headersValid })
                assertCredentialsAbsentOnDisk(origin)

                origin.close()
                val scenario = launch(complete)
                var controller: PlayerController? = null
                scenario.onActivity { controller = it.playback }
                val playback = controller!!
                try {
                    ready(playback, 160, 90)
                    compose.waitUntil(10_000) { playback.trackOptions.value.audio.size == 2 && playback.trackOptions.value.subtitles.isNotEmpty() }
                    val japanese = playback.trackOptions.value.audio.single { it.language == "ja" }
                    val englishSubtitle = playback.trackOptions.value.subtitles.first { it.language == "en" }
                    onMain { playback.selectAudio(japanese.id); playback.selectSubtitle(englishSubtitle.id); playback.seek(3_000) }
                    compose.waitUntil(10_000) {
                        selectedLanguages(playback, C.TRACK_TYPE_AUDIO).contains("ja") && playback.subtitleText.value.contains("Miru embedded subtitle")
                    }
                    // Start over after checking seek/track selection. Completion below consumes
                    // the full twelve-second presentation with no HTTP server available.
                    onMain { playback.seek(0); playback.resume() }
                    ended(playback)
                    assertTrue(playback.state.value.duration in 11_500..12_500)
                    assertTrue(playback.state.value.position >= 11_500)
                    val english = playback.trackOptions.value.audio.single { it.language == "en" }
                    onMain { playback.selectAudio(english.id); playback.seek(1_500); playback.resume() }
                    compose.waitUntil(10_000) {
                        playback.state.value.playing && playback.state.value.position >= 2_000 &&
                            selectedLanguages(playback, C.TRACK_TYPE_AUDIO).contains("en") && playback.subtitleText.value.contains("Miru embedded subtitle")
                    }
                    assertFalse(playback.state.value.error)
                } finally { scenario.close() }
            } finally { cleanup(ids) }
        }
    }

    @Test fun dashAudioAndVideoRemainPlayableThroughTheEndAfterServerShutdown() {
        OfflineOrigin(instrumentation.context).use { origin ->
            val ids = mutableListOf<String>()
            try {
                val selection = prepare(origin, "/dash/dash-manifest.mpd", "application/dash+xml")
                val quality = selection.qualities.single { it.width == 320 && it.height == 180 }
                val id = onMain { coordinator.enqueue(selection, quality.id) }.also(ids::add)
                val complete = completed(id, origin)
                assertTrue(origin.calls.any { it.path == "/dash/dash-init-0.m4s" })
                assertTrue(origin.calls.any { it.path == "/dash/dash-init-1.m4s" })
                assertTrue(origin.calls.any { it.path.contains("dash-segment-0-") } && origin.calls.any { it.path.contains("dash-segment-1-") })
                assertTrue(origin.calls.all { it.headersValid })
                origin.close()
                val scenario = launch(complete)
                var controller: PlayerController? = null
                scenario.onActivity { controller = it.playback }
                val playback = controller!!
                try {
                    ready(playback, 320, 180)
                    onMain { playback.seek(0); playback.resume() }
                    ended(playback)
                    assertTrue(playback.state.value.position >= 11_500)
                    onMain { playback.seek(9_000); playback.resume() }
                    compose.waitUntil(10_000) { playback.state.value.position >= 9_500 && playback.state.value.playing }
                    assertFalse(playback.state.value.error)
                } finally { scenario.close() }
            } finally { cleanup(ids) }
        }
    }

    @Test fun pausedDownloadsResumeAndDeletingOneCopyOrClearingStreamCacheKeepsTheOtherCopy() {
        OfflineOrigin(instrumentation.context).use { origin ->
            val ids = mutableListOf<String>()
            try {
                val selection = prepare(origin, "/hls/master.m3u8")
                val quality = selection.qualities.single { it.width == 160 && it.height == 90 }
                origin.holdSegments.set(true)
                val firstId = onMain { coordinator.enqueue(selection, quality.id, "First offline copy") }.also(ids::add)
                compose.waitUntil(15_000) { origin.segmentStarted.get() }
                onMain { coordinator.pause(firstId) }
                compose.waitUntil(10_000) { coordinator.items.value.any { it.id == firstId && it.status == "paused" } }
                assertNull("A partial download must never be advertised as playable offline", runBlocking { coordinator.playback(firstId) })
                origin.holdSegments.set(false); origin.releaseSegments.countDown()
                onMain { coordinator.resume(firstId) }
                completed(firstId, origin)

                val callsBeforeSecond = origin.calls.size
                val secondId = onMain { coordinator.enqueue(selection, quality.id, "Second offline copy") }.also(ids::add)
                assertNotEquals(firstId, secondId)
                val second = completed(secondId, origin)
                assertTrue("The second ID owns an independent set of media cache keys", origin.calls.drop(callsBeforeSecond).any { it.path.startsWith("/hls/low-") })
                onMain { coordinator.remove(firstId) }
                compose.waitUntil(15_000) { coordinator.items.value.none { it.id == firstId } }
                assertNull(runBlocking { coordinator.playback(firstId) })
                runBlocking(Dispatchers.IO) { PlaybackCache.clear(context) }
                assertNotNull("Clearing ordinary stream cache must preserve the offline cache", runBlocking { coordinator.playback(secondId) })
                origin.close()
                val scenario = launch(second)
                var controller: PlayerController? = null
                scenario.onActivity { controller = it.playback }
                val playback = controller!!
                try {
                    ready(playback, 160, 90)
                    onMain { playback.seek(9_000); playback.resume() }
                    compose.waitUntil(10_000) { playback.state.value.position >= 9_500 && playback.state.value.playing }
                    assertFalse("Removing one ID must not remove another ID's final segment", playback.state.value.error)
                } finally { scenario.close() }
            } finally { origin.releaseSegments.countDown(); cleanup(ids) }
        }
    }

    @Test fun redirectedPrivateDownloadsNeverSendCredentialsToAnotherOriginOrPersistThem() {
        OfflineOrigin(instrumentation.context, requireHeaders = false).use { destination ->
            OfflineOrigin(instrumentation.context).use { source ->
                val ids = mutableListOf<String>()
                try {
                    source.redirectTo = "${destination.url}/hls/master.m3u8"
                    val selection = prepare(source, "/redirect/master.m3u8", private = true)
                    val quality = selection.qualities.single { it.width == 160 && it.height == 90 }
                    val id = onMain { coordinator.enqueue(selection, quality.id) }.also(ids::add)
                    val complete = completed(id, source)
                    assertTrue(complete.isPrivate)
                    assertTrue(source.calls.isNotEmpty() && source.calls.all { it.credentialsPresent && it.headersValid })
                    assertTrue(destination.calls.isNotEmpty())
                    assertTrue("A changed port is a changed origin, including manifest redirects", destination.calls.none { it.credentialsPresent })
                    assertCredentialsAbsentOnDisk(source)
                    source.close(); destination.close()
                    val scenario = launch(complete)
                    var controller: PlayerController? = null
                    scenario.onActivity { controller = it.playback }
                    try { ready(controller!!, 160, 90); onMain { controller!!.seek(9_000); controller!!.resume() }; ended(controller!!) }
                    finally { scenario.close() }
                } finally { cleanup(ids) }
            }
        }
    }

    @Test fun cancellingAnActiveDownloadRemovesPartialCacheAndNeverContinuesWriting() {
        OfflineOrigin(instrumentation.context).use { origin ->
            val ids = mutableListOf<String>()
            try {
                val selection = prepare(origin, "/hls/master.m3u8")
                val quality = selection.qualities.single { it.width == 160 && it.height == 90 }
                origin.holdSegments.set(true)
                val id = onMain { coordinator.enqueue(selection, quality.id, "Cancelled offline fixture") }.also(ids::add)
                compose.waitUntil(15_000) { origin.segmentStarted.get() }
                compose.waitUntil(10_000) { runBlocking { coordinator.cachedBytes(id) } > 0 }
                onMain { coordinator.cancel(id) }
                compose.waitUntil(10_000) { coordinator.items.value.any { it.id == id && it.status == "cancelled" } }
                assertNull("Cancelled downloads must never be advertised for offline playback", runBlocking { coordinator.playback(id) })
                // Let the server finish its blocked body. Cancellation must still stop the old
                // writer and remove its partition after that writer has actually terminated.
                origin.holdSegments.set(false); origin.releaseSegments.countDown()
                compose.waitUntil(15_000) { runBlocking { coordinator.cachedBytes(id) } == 0L }
                val calls = origin.calls.size
                SystemClock.sleep(1_500)
                assertEquals("Cancellation must not restart requests after the old response unblocks", calls, origin.calls.size)
                assertEquals("The cancelled writer must not repopulate its removed cache partition", 0L, runBlocking { coordinator.cachedBytes(id) })
                assertEquals("cancelled", coordinator.items.value.single { it.id == id }.status)
                assertNull(runBlocking { coordinator.playback(id) })
            } finally { origin.releaseSegments.countDown(); cleanup(ids) }
        }
    }

    @Test fun cancellingAfterNativeCompletionPreservesPlaybackEvenBeforeTheUiReceivesCompletion() {
        val database = StandaloneDatabaseProvider(context)
        val index = DefaultDownloadIndex(database, "miru_offline")
        try {
            OfflineOrigin(instrumentation.context).use { origin ->
                val ids = mutableListOf<String>()
                try {
                    val selection = prepare(origin, "/hls/master.m3u8")
                    val quality = selection.qualities.single { it.width == 160 && it.height == 90 }
                    origin.holdSegments.set(true)
                    val id = onMain { coordinator.enqueue(selection, quality.id, "Native completion boundary") }.also(ids::add)
                    compose.waitUntil(15_000) { origin.segmentStarted.get() }
                    // Open this independent index reader off the main thread first. Holding
                    // the application looper below delays native completion callbacks and
                    // the Compose projection while Media3's worker/index finish normally.
                    assertNotNull(runBlocking(Dispatchers.IO) { index.getDownload(id) })
                    onMain {
                        origin.holdSegments.set(false); origin.releaseSegments.countDown()
                        val deadline = SystemClock.uptimeMillis() + 4_000
                        while(index.getDownload(id)?.state != Download.STATE_COMPLETED && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(5)
                        assertEquals("The actual persisted native state must already be completed", Download.STATE_COMPLETED, index.getDownload(id)?.state)
                        assertFalse("The test must exercise a stale UI projection", coordinator.items.value.any { it.id == id && it.status == "completed" })
                        coordinator.cancel(id)
                    }
                    val complete = completed(id, origin)
                    assertNotNull("A stale cancel action must not poison an already completed UUID", runBlocking { coordinator.playback(id) })
                    assertTrue(runBlocking { coordinator.cachedBytes(id) } > 0)
                    origin.close()
                    val scenario = launch(complete)
                    var controller: PlayerController? = null
                    scenario.onActivity { controller = it.playback }
                    try {
                        ready(controller!!, 160, 90)
                        onMain { controller!!.seek(9_000); controller!!.resume() }
                        ended(controller!!)
                        assertFalse(controller!!.state.value.error)
                    } finally { scenario.close() }
                } finally { origin.releaseSegments.countDown(); cleanup(ids) }
            }
        } finally { database.close() }
    }

    @Test fun liveDrmAndDirectFilesAreRejectedBeforeBeingAddedToOfflineDownloads() {
        OfflineOrigin(instrumentation.context).use { origin ->
            val initialIds = coordinator.items.value.map { it.id }.toSet()
            val drm = runCatching { runBlocking { coordinator.prepare(OfflineSource("${origin.url}/hls/master.m3u8", "DRM fixture", drm = true)) } }.exceptionOrNull()
            assertTrue(drm is OfflineException); assertEquals("DRM_UNSUPPORTED", (drm as OfflineException).code)
            assertTrue("Known DRM must be rejected before a network request", origin.calls.isEmpty())
            val live = runCatching { prepare(origin, "/hls/live.m3u8") }.exceptionOrNull()
            assertTrue("A live playlist must not be accepted as a finite offline download", live is OfflineException)
            assertEquals("LIVE_UNSUPPORTED", (live as OfflineException).code)
            val direct = runCatching { prepare(origin, "/movie.mp4", "video/mp4") }.exceptionOrNull()
            assertTrue(direct is OfflineException); assertEquals("DIRECT_FILE", (direct as OfflineException).code)
            assertEquals(initialIds, coordinator.items.value.map { it.id }.toSet())
        }
    }

    private fun prepare(origin: OfflineOrigin, path: String, mime: String = "application/vnd.apple.mpegurl", private: Boolean = false): OfflineSelection {
        if(Build.VERSION.SDK_INT >= 33) runCatching { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS) }
        return runBlocking { coordinator.prepare(OfflineSource("${origin.url}$path", "Miru synthetic offline fixture", "${origin.url}/page", mime, origin.headers(), isPrivate = private)) }
    }

    private fun completed(id: String, origin: OfflineOrigin): OfflineItem {
        compose.waitUntil(60_000) {
            origin.failure.get()?.let { throw AssertionError("Offline fixture failed: $it") }
            val item = coordinator.items.value.firstOrNull { it.id == id }
            if(item?.status == "failed") throw AssertionError("Offline download failed: ${item.error}")
            item?.status == "completed"
        }
        return coordinator.items.value.single { it.id == id }
    }

    private fun launch(item: OfflineItem): ActivityScenario<PlayerActivity> = ActivityScenario.launch(
        PlaybackRequests.intent(context, PlaybackRequest(item.url, item.title, mimeType = item.mimeType, isPrivate = item.isPrivate, offlineId = item.id)),
    )

    private fun ready(playback: PlayerController, width: Int, height: Int) {
        compose.waitUntil(25_000) {
            val state = playback.state.value
            if(state.error || state.timedOut) throw AssertionError("Cached source failed to produce real decoded video")
            state.ready && state.playing && state.position >= 200 && state.width == width && state.height == height
        }
    }

    private fun ended(playback: PlayerController) {
        compose.waitUntil(25_000) {
            if(playback.state.value.error || playback.state.value.timedOut) throw AssertionError("Offline media failed before its final segment")
            onMain { playback.player?.playbackState == Player.STATE_ENDED }
        }
    }

    private fun selectedLanguages(playback: PlayerController, type: Int): List<String?> = onMain {
        playback.player!!.currentTracks.groups.filter { it.type == type }.flatMap { group ->
            (0 until group.length).filter(group::isTrackSelected).map { group.getTrackFormat(it).language }
        }
    }

    private fun assertCredentialsAbsentOnDisk(origin: OfflineOrigin) {
        val roots = listOf(File(context.filesDir, "offline"), File(context.applicationInfo.dataDir, "databases"), File(context.applicationInfo.dataDir, "shared_prefs"))
        val needles = origin.secretValues.map { it.toByteArray(Charsets.UTF_8) }
        assertTrue("An actual offline cache directory must have been created", roots.first().isDirectory)
        for(root in roots) for(file in root.walkTopDown().filter(File::isFile)) {
            // The synthetic assets are small; scan exact bytes without decoding SQLite or media.
            val bytes = file.readBytes()
            assertTrue("Offline credentials must never appear in ${file.name}", needles.none { needle -> bytes.containsSequence(needle) })
        }
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
        if(needle.isEmpty() || needle.size > size) return false
        for(start in 0..size - needle.size) {
            var matches = true
            for(index in needle.indices) if(this[start + index] != needle[index]) { matches = false; break }
            if(matches) return true
        }
        return false
    }

    private fun cleanup(ids: List<String>) {
        onMain { ids.forEach(coordinator::remove) }
        compose.waitUntil(20_000) { coordinator.items.value.none { it.id in ids } }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
}

internal data class OfflineCall(val path: String, val headersValid: Boolean, val credentialsPresent: Boolean)

/** Only boolean header observations are retained; fixtures contain no real accounts or secrets. */
internal class OfflineOrigin(private val assets: Context, private val requireHeaders: Boolean = true) : Closeable {
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val content = ConcurrentHashMap<String, ByteArray>()
    private val token = UUID.randomUUID().toString()
    val secretValues = listOf("miru_offline_cookie=$token", "Bearer miru-offline-$token")
    val calls = CopyOnWriteArrayList<OfflineCall>()
    val failure = AtomicReference<String?>(null)
    val holdSegments = AtomicBoolean(false)
    val segmentStarted = AtomicBoolean(false)
    val releaseSegments = CountDownLatch(1)
    @Volatile var redirectTo: String? = null
    val url = "http://127.0.0.1:${server.localPort}"
    fun headers() = mapOf("Cookie" to secretValues[0], "Authorization" to secretValues[1], "Referer" to "$url/page", "Origin" to url, "User-Agent" to "Miru-Offline-Fixture")

    init { thread(isDaemon = true, name = "miru-offline-origin") {
        while(running.get()) {
            val socket = try { server.accept() } catch(_: java.io.IOException) { break }
            sockets += socket
            thread(isDaemon = true, name = "miru-offline-response") {
                socket.use { try { serve(it) } catch(_: java.io.IOException) { } catch(error: Exception) { failure.compareAndSet(null, error.javaClass.simpleName) } finally { sockets -= it } }
            }
        }
    } }

    private fun serve(socket: Socket) {
        socket.soTimeout = 15_000
        val input = socket.getInputStream().bufferedReader()
        val request = input.readLine()?.split(' ') ?: return
        val path = request.getOrNull(1)?.substringBefore('?') ?: return
        val received = linkedMapOf<String, String>()
        var count = 0
        while(true) {
            val line = input.readLine()?.takeIf(String::isNotBlank) ?: break
            check(++count <= 64)
            received[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        val valid = !requireHeaders || headers().all { (name, value) -> received[name.lowercase()] == value }
        calls += OfflineCall(path, valid, received["cookie"] != null || received["authorization"] != null)
        if(!valid) { respond(socket, 403, ByteArray(0), "text/plain", request[0]); return }
        if(path == "/page") {
            respond(socket, 200, "<!doctype html><html><meta name=viewport content='width=device-width'><title>Miru offline media fixture</title><body><h1>Miru offline media fixture</h1><script>fetch('/hls/master.m3u8').then(r=>r.text())</script></body></html>".toByteArray(), "text/html", request[0])
            return
        }
        if(path == "/redirect/master.m3u8") {
            socket.getOutputStream().apply { write("HTTP/1.1 302 Found\r\nLocation: $redirectTo\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            return
        }
        val asset = when {
            path in setOf("/hls/master.m3u8", "/hls/low.m3u8", "/hls/live.m3u8") || path.matches(Regex("/hls/low-\\d{2}\\.ts")) -> "media/m3-offline/${path.substringAfterLast('/')}"
            path == "/hls/high.m3u8" -> "media/m3-tracks/video.m3u8"
            path.matches(Regex("/hls/(english|japanese|subtitles)\\.m3u8|/hls/embedded\\.vtt|/hls/(video|english|japanese)-\\d{2}\\.ts")) -> "media/m3-tracks/${path.substringAfterLast('/')}"
            path.matches(Regex("/dash/dash-(manifest\\.mpd|init-[01]\\.m4s|segment-[01]-\\d{5}\\.m4s)")) -> "media$path"
            else -> null
        }
        if(asset == null) { respond(socket, 404, ByteArray(0), "text/plain", request[0]); return }
        val bytes = content.getOrPut(asset) { assets.assets.open(asset).use { it.readBytes() } }
        val range = received["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val start = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val end = (range?.groupValues?.get(2)?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
        if(start !in 0..end) { respond(socket, 416, ByteArray(0), "text/plain", request[0]); return }
        val mime = when { path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"; path.endsWith(".mpd") -> "application/dash+xml"; path.endsWith(".vtt") -> "text/vtt"; path.endsWith(".ts") -> "video/mp2t"; else -> "video/mp4" }
        val contentRange = if(range != null) "Content-Range: bytes $start-$end/${bytes.size}\r\n" else ""
        respond(socket, if(range == null) 200 else 206, bytes, mime, request[0], start, end, contentRange, path.endsWith(".ts"))
    }

    private fun respond(socket: Socket, status: Int, bytes: ByteArray, mime: String, method: String, start: Int = 0, end: Int = bytes.lastIndex, extra: String = "", segment: Boolean = false) {
        val length = if(bytes.isEmpty()) 0 else end - start + 1
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status Fixture\r\nContent-Type: $mime\r\nContent-Length: $length\r\nAccept-Ranges: bytes\r\n${extra}Connection: close\r\n\r\n".toByteArray())
            if(method != "HEAD" && length > 0) {
                if(segment && holdSegments.get()) {
                    val prefix = minOf(length, 4096)
                    write(bytes, start, prefix); flush(); segmentStarted.set(true)
                    releaseSegments.await(15, TimeUnit.SECONDS)
                    if(length > prefix) write(bytes, start + prefix, length - prefix)
                } else write(bytes, start, length)
            }
            flush()
        }
    }

    override fun close() {
        if(!running.compareAndSet(true, false)) return
        releaseSegments.countDown(); server.close(); sockets.forEach { runCatching { it.close() } }
    }
}
