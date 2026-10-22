// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.browser

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.media3.common.C
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.player.PlaybackRequest
import app.miru.player.PlaybackRequests
import app.miru.player.PlayerActivity
import app.miru.player.PlayerController
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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

/** Decodes synthetic adaptive media in the real Activity; no public streaming service is involved. */
@RunWith(AndroidJUnit4::class)
class M3TracksIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun hlsAudioAndEmbeddedSubtitlesCanBeSelectedAndDisabledThroughThePanel() {
        M3TracksOrigin(instrumentation.context).use { origin ->
            val scenario = launch(origin, isPrivate = false)
            var controller: PlayerController? = null
            scenario.onActivity { controller = it.playback }
            val playback = controller!!
            val originalStyle = playback.preferences.value
            try {
                awaitPlayback(origin, playback) {
                    it.ready && it.playing && it.position >= 300 && it.width == 320 && it.height == 180
                }
                compose.waitUntil(15_000) {
                    playback.trackOptions.value.let { it.audio.size == 2 && it.subtitles.isNotEmpty() }
                }
                assertTrue("Both audio renditions must be advertised by the actual Media3 track groups", onMain {
                    playback.player!!.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.sumOf { it.length } >= 2
                })
                assertTrue("The default HLS audio track must actually be selected", selectedLanguages(playback, C.TRACK_TYPE_AUDIO).contains("en"))

                // A real Back returns to portrait, where the tracks button remains available below the video.
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                compose.waitUntil(10_000) {
                    var portrait = false
                    scenario.onActivity { portrait = it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
                    portrait
                }
                compose.onNodeWithTag("player_tracks").performScrollTo().performClick()
                val japaneseIndex = playback.trackOptions.value.audio.indexOfFirst { it.language == "ja" }
                assertTrue("The fixture's Japanese audio rendition must be selectable", japaneseIndex >= 0)
                compose.onNodeWithTag("player_audio_$japaneseIndex").performScrollTo().performClick()
                compose.waitUntil(10_000) { selectedLanguages(playback, C.TRACK_TYPE_AUDIO).contains("ja") }
                compose.onNodeWithTag("player_audio_$japaneseIndex").assertIsSelected()

                val subtitleIndex = playback.trackOptions.value.subtitles.indexOfFirst { it.language == "en" }
                assertTrue("The HLS subtitle rendition must be exposed", subtitleIndex >= 0)
                compose.onNodeWithTag("player_subtitle_$subtitleIndex").performScrollTo().performClick()
                onMain { playback.seek(3_000) }
                compose.waitUntil(10_000) {
                    selectedLanguages(playback, C.TRACK_TYPE_TEXT).contains("en") &&
                        playback.subtitleText.value.contains("Miru embedded subtitle")
                }
                assertTrue("The real subtitle playlist and WebVTT media must have been requested", origin.calls.any { it.path == "/subtitles.m3u8" } && origin.calls.any { it.path == "/embedded.vtt" })
                onMain { playback.pause() }
                compose.onNodeWithText(context.getString(app.miru.player.R.string.player_options_done)).performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("player_subtitle_off").assertDoesNotExist()
                screenshot("m3-embedded-subtitle.png")
                compose.onNodeWithTag("player_tracks").performScrollTo().performClick()
                compose.onNodeWithTag("player_audio_$japaneseIndex").performScrollTo()
                compose.waitForIdle()
                screenshot("m3-tracks-panel.png")
                compose.onNodeWithTag("player_subtitle_off").performScrollTo().performClick()
                compose.waitUntil(5_000) {
                    playback.trackOptions.value.subtitlesOff && playback.subtitleText.value.isEmpty() &&
                        selectedLanguages(playback, C.TRACK_TYPE_TEXT).isEmpty()
                }
                compose.onNodeWithTag("player_subtitle_off").assertIsSelected()
                compose.onNodeWithTag("player_subtitle_import").performScrollTo().assertIsDisplayed()
                val size = compose.onNodeWithContentDescription(context.getString(app.miru.player.R.string.player_subtitle_size))
                size.performScrollTo().assertIsDisplayed()
                assertNotNull("Subtitle font size must expose an accessible range", size.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
                size.performSemanticsAction(SemanticsActions.SetProgress) { it(28f) }
                compose.waitUntil(5_000) { playback.preferences.value.subtitleSizeSp == 28f }
                for(label in listOf(app.miru.player.R.string.player_subtitle_margin, app.miru.player.R.string.player_subtitle_background)) {
                    val slider = compose.onNodeWithContentDescription(context.getString(label))
                    slider.performScrollTo().assertIsDisplayed()
                    assertNotNull("Subtitle style control must expose an accessible range", slider.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
                }
                assertTrue("Every actual adaptive request must retain the supplied origin headers", origin.calls.isNotEmpty() && origin.calls.all { it.headersValid && it.status in 200..299 })
                assertFalse(playback.state.value.error)
            } finally {
                onMain { playback.subtitleStyle(originalStyle.subtitleSizeSp, originalStyle.subtitleBottomMargin, originalStyle.subtitleBackgroundOpacity) }
                compose.waitUntil(5_000) { playback.preferences.value.subtitleSizeSp == originalStyle.subtitleSizeSp }
                scenario.close()
            }
        }
    }

    @Test fun localSrtWebVttAndAssFollowPlaybackTimeAndSurviveDecoderRebuild() {
        M3TracksOrigin(instrumentation.context).use { origin ->
            val scenario = launch(origin)
            var controller: PlayerController? = null
            scenario.onActivity { controller = it.playback }
            val playback = controller!!
            try {
                awaitPlayback(origin, playback) { it.ready && it.playing && it.position >= 300 && it.width == 320 && it.height == 180 }
                compose.waitUntil(10_000) { playback.trackOptions.value.audio.any { it.language == "ja" } }
                val japanese = playback.trackOptions.value.audio.first { it.language == "ja" }
                onMain { playback.selectAudio(japanese.id) }
                compose.waitUntil(10_000) { selectedLanguages(playback, C.TRACK_TYPE_AUDIO).contains("ja") }
                for(extension in listOf("srt", "vtt", "ass")) {
                    val name = "local.$extension"
                    val bytes = instrumentation.context.assets.open("media/m3-tracks/$name").use { it.readBytes() }
                    runBlocking { withContext(Dispatchers.Main) { playback.loadSubtitle(bytes, name) } }
                    onMain { playback.seek(3_000) }
                    compose.waitUntil(10_000) {
                        playback.trackOptions.value.localSubtitleName == name &&
                            playback.subtitleText.value.contains("Miru local ${extension.uppercase()}")
                    }
                    compose.onNodeWithTag("player_subtitle_text").assertIsDisplayed()
                    assertFalse("ASS styling directives must not appear as caption text", playback.subtitleText.value.contains("\\i"))
                    assertTrue("Selecting a local subtitle must disable the adaptive text renderer", selectedLanguages(playback, C.TRACK_TYPE_TEXT).isEmpty())
                    onMain { playback.seek(10_000) }
                    compose.waitUntil(5_000) { playback.state.value.position >= 9_500 && playback.subtitleText.value.isEmpty() }
                }

                onMain { playback.seek(3_000); playback.decoder("software") }
                awaitPlayback(origin, playback) { it.ready && it.playing && it.width == 320 && it.height == 180 && it.position in 2_500..7_000 }
                compose.waitUntil(5_000) { playback.subtitleText.value.contains("Miru local ASS") }
                assertEquals("A decoder rebuild must retain the active in-memory local subtitle", "local.ass", playback.trackOptions.value.localSubtitleName)
                assertTrue("A decoder rebuild must retain the selected real audio rendition", selectedLanguages(playback, C.TRACK_TYPE_AUDIO).contains("ja"))
                onMain { playback.selectSubtitle(null) }
                compose.waitUntil(5_000) { playback.trackOptions.value.subtitlesOff && playback.subtitleText.value.isEmpty() }
                compose.onNodeWithTag("player_subtitle_text").assertDoesNotExist()
                assertFalse(playback.state.value.error)
            } finally { scenario.close() }
        }
    }

    private fun launch(origin: M3TracksOrigin, isPrivate: Boolean = true): ActivityScenario<PlayerActivity> = ActivityScenario.launch(
        PlaybackRequests.intent(context, PlaybackRequest(
            url = "${origin.url}/master.m3u8", title = "Miru synthetic track fixture",
            mimeType = "application/vnd.apple.mpegurl", headers = origin.headers(), isPrivate = isPrivate,
        )),
    )

    private fun awaitPlayback(origin: M3TracksOrigin, playback: PlayerController, predicate: (app.miru.player.PlayerSnapshot) -> Boolean) {
        compose.waitUntil(30_000) {
            origin.failure.get()?.let { throw AssertionError("M3 fixture server failed: $it") }
            val state = playback.state.value
            if(state.error || state.timedOut) throw AssertionError("M3 fixture did not reach a playable state")
            predicate(state)
        }
    }

    private fun selectedLanguages(playback: PlayerController, type: Int): List<String?> = onMain {
        playback.player!!.currentTracks.groups.filter { it.type == type }.flatMap { group ->
            (0 until group.length).filter(group::isTrackSelected).map { group.getTrackFormat(it).language }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun screenshot(name: String) {
        // Dialog window animations run outside Compose's idling resource. Keep a paused frame
        // on screen long enough for SurfaceFlinger to retire the old dialog surface.
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "No screenshot was returned" }
        try { File(context.filesDir, name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { screenshot.recycle() }
    }
}

private data class M3TracksCall(val path: String, val status: Int, val headersValid: Boolean)

/** Request observations retain only synthetic fixture paths and a header-equality result. */
private class M3TracksOrigin(private val assets: Context) : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val content = ConcurrentHashMap<String, ByteArray>()
    val calls = CopyOnWriteArrayList<M3TracksCall>()
    val failure = AtomicReference<String?>(null)
    val url = "http://127.0.0.1:${server.localPort}"

    fun headers() = mapOf(
        "Cookie" to "miru_tracks=synthetic",
        "Authorization" to "Bearer miru-synthetic-tracks",
        "Referer" to "$url/source-page",
        "Origin" to url,
        "User-Agent" to "Miru-M3-Tracks-Fixture",
    )

    init { thread(isDaemon = true, name = "miru-m3-tracks-origin") {
        while(running.get()) {
            val socket = try { server.accept() } catch(_: java.io.IOException) { break }
            sockets += socket
            thread(isDaemon = true, name = "miru-m3-tracks-response") {
                socket.use {
                    try { serve(it) }
                    catch(_: java.io.IOException) { /* Media3 cancels obsolete requests during track switches. */ }
                    catch(error: Exception) { failure.compareAndSet(null, error.javaClass.simpleName) }
                    finally { sockets -= it }
                }
            }
        }
    } }

    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val reader = socket.getInputStream().bufferedReader()
        val request = reader.readLine()?.split(' ') ?: return
        val path = request.getOrNull(1)?.substringBefore('?') ?: return
        val received = linkedMapOf<String, String>()
        var headerCount = 0
        while(true) {
            val line = reader.readLine()?.takeIf(String::isNotBlank) ?: break
            check(++headerCount <= 64)
            received[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        val valid = headers().all { (name, value) -> received[name.lowercase()] == value }
        if(!valid) { calls += M3TracksCall(path, 403, false); respond(socket, 403, ByteArray(0), "application/octet-stream", request[0], null); return }
        val asset = path.removePrefix("/").takeIf { it.matches(Regex("(master|video|english|japanese|subtitles)\\.m3u8|embedded\\.vtt|(video|english|japanese)-\\d{2}\\.ts")) }
        if(asset == null) { calls += M3TracksCall(path, 404, true); respond(socket, 404, ByteArray(0), "application/octet-stream", request[0], null); return }
        val bytes = content.getOrPut(asset) { assets.assets.open("media/m3-tracks/$asset").use { it.readBytes() } }
        val range = received["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val from = range?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val to = range?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(bytes.lastIndex.toLong()) ?: bytes.lastIndex.toLong()
        if(from > to || from >= bytes.size) { calls += M3TracksCall(path, 416, true); respond(socket, 416, ByteArray(0), "application/octet-stream", request[0], null); return }
        val status = if(range == null) 200 else 206
        val mime = when { asset.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"; asset.endsWith(".vtt") -> "text/vtt"; else -> "video/mp2t" }
        calls += M3TracksCall(path, status, true)
        respond(socket, status, bytes, mime, request[0], if(range == null) null else from.toInt()..to.toInt())
    }

    private fun respond(socket: Socket, status: Int, bytes: ByteArray, mime: String, method: String, range: IntRange?) {
        val from = range?.first ?: 0
        val length = range?.let { it.last - it.first + 1 } ?: bytes.size
        val description = when(status) { 200 -> "OK"; 206 -> "Partial Content"; 403 -> "Forbidden"; 416 -> "Range Not Satisfiable"; else -> "Not Found" }
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
