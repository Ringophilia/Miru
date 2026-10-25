// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.browser

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.miru.downloads.DownloadRequest
import app.miru.offline.OfflineSource
import app.miru.player.PlayerActivity
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises both real download backends through their shared Compose list and cached playback. */
@RunWith(AndroidJUnit4::class)
class OfflineDownloadsUiIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (compose.activity.application as MiruApplication).container
    private fun label(id: Int) = context.getString(id)

    @Test fun offlineAndRegularDownloadsShareFilteringRenamingUndoAndPlayback() {
        OfflineDownloadsUiOrigin(instrumentation.context).use { origin ->
            val offline = container.offline
            val ordinary = container.downloads
            val title = "Miru offline UI ${System.nanoTime()}"
            val renamed = "$title renamed"
            val ordinaryName = "miru-ordinary-ui-${System.nanoTime()}.bin"
            val originalPreferences = ordinary.settings.value
            var id: String? = null
            var ordinaryId: String? = null
            var player: PlayerActivity? = null
            try {
                ordinary.updatePreferences { it.copy(directoryUri = null, deleteFileWithRecord = true) }
                compose.runOnIdle { container.browser.newTab() }
                val selection = runBlocking { withTimeout(40_000) {
                    offline.prepare(OfflineSource("${origin.url}/master.m3u8", title, mimeType = "application/vnd.apple.mpegurl", headers = origin.headers()))
                } }
                assertTrue("A real adaptive quality must be available", selection.qualities.isNotEmpty())
                id = offline.enqueue(selection, selection.qualities.first().id, title)
                ordinaryId = ordinary.enqueue(DownloadRequest("${origin.url}/regular.bin", fileName = ordinaryName))
                val offlineId = id
                val regularId = ordinaryId
                compose.waitUntil(45_000) {
                    val item = offline.items.value.firstOrNull { it.id == offlineId }
                    if(item?.status == "failed") throw AssertionError("Offline UI fixture failed: ${item.error}")
                    item?.status == "completed" && ordinary.downloads.value.any { it.id == regularId && it.status == "completed" }
                }
                assertEquals("Every adaptive fixture request must preserve its source headers", 0, origin.rejected.get())
                compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_menu)).performClick()
                compose.onNodeWithText(label(app.miru.menu.R.string.menu_downloads)).performClick()
                compose.onNodeWithText(title).assertIsDisplayed()
                compose.onNodeWithText(ordinaryName).assertIsDisplayed()

                compose.onNodeWithText(label(app.miru.downloads.R.string.download_video)).performClick()
                compose.onNodeWithText(title).assertIsDisplayed()
                compose.onNodeWithText(ordinaryName).assertDoesNotExist()
                compose.onNodeWithText(label(app.miru.downloads.R.string.download_all)).performClick()
                compose.onNodeWithText(ordinaryName).assertIsDisplayed()

                // IconButton merges its Icon's description with its own click action.
                // In the unmerged tree these belong to separate nodes.
                val more = hasAnyAncestor(hasTestTag("offline_download_$offlineId")) and hasContentDescription(label(app.miru.downloads.R.string.download_more)) and hasClickAction()
                compose.onNode(more).performClick()
                compose.onNodeWithText(label(app.miru.downloads.R.string.download_rename)).performClick()
                compose.onNode(hasSetTextAction() and hasText(title)).performTextReplacement(renamed)
                compose.onNodeWithText(label(app.miru.downloads.R.string.download_save)).performClick()
                compose.waitUntil(5_000) { offline.items.value.any { it.id == offlineId && it.title == renamed } }
                compose.onNodeWithText(renamed).assertIsDisplayed()
                compose.onNode(hasSetTextAction()).performTextReplacement("renamed")
                compose.onNodeWithText(renamed).assertIsDisplayed()
                compose.onNodeWithText(ordinaryName).assertDoesNotExist()
                compose.onNode(hasSetTextAction()).performTextClearance()

                compose.onNodeWithText(renamed).performTouchInput { longClick() }
                compose.onNodeWithText(ordinaryName).performClick()
                compose.onNodeWithText(context.getString(app.miru.downloads.R.string.download_selected, 2)).assertIsDisplayed()
                compose.onNode(hasContentDescription(label(app.miru.downloads.R.string.download_delete)) and hasClickAction()).performClick()
                compose.onNodeWithText(label(app.miru.downloads.R.string.download_delete_offline)).assertIsDisplayed()
                val undoStartedAt = SystemClock.elapsedRealtime()
                fun undoDiagnostic(stage: String): String {
                    val now = SystemClock.elapsedRealtime()
                    val offlineRow = offline.items.value.firstOrNull { it.id == offlineId }
                    val ordinaryRow = ordinary.downloads.value.firstOrNull { it.id == regularId }
                    // Keep this snapshot safe for device logs: no IDs, names or request data.
                    return "backend=test stage=$stage monotonicMs=$now elapsedMs=${now - undoStartedAt} offlinePending=${offlineId in offline.pendingDeletionIds.value} ordinaryPending=${regularId in ordinary.pendingDeletionIds.value} offlinePendingCount=${offline.pendingDeletionIds.value.size} ordinaryPendingCount=${ordinary.pendingDeletionIds.value.size} offlineRecordExists=${offlineRow != null} ordinaryRecordExists=${ordinaryRow != null} offlineStatus=${offlineRow?.status} ordinaryStatus=${ordinaryRow?.status}"
                        .also { Log.i("MiruDeleteUndo", it) }
                }
                var undoStage = "before_delete"
                try {
                    undoDiagnostic(undoStage)
                    compose.onNodeWithText(label(app.miru.downloads.R.string.download_keep_ordinary_files)).performClick()
                    undoStage = "wait_staged"
                    compose.waitUntil(3_000) { offlineId in offline.pendingDeletionIds.value && regularId in ordinary.pendingDeletionIds.value }
                    undoDiagnostic("staged_observed")
                    compose.onNodeWithText(renamed).assertDoesNotExist()
                    compose.onNodeWithText(ordinaryName).assertDoesNotExist()
                    undoStage = "before_undo"
                    undoDiagnostic(undoStage)
                    compose.onNodeWithText(label(app.miru.downloads.R.string.download_undo)).performClick()
                    undoStage = "after_undo_click"
                    undoDiagnostic(undoStage)
                    compose.waitUntil(3_000) { offlineId !in offline.pendingDeletionIds.value && regularId !in ordinary.pendingDeletionIds.value }
                    undoStage = "undo_settled"
                    undoDiagnostic(undoStage)
                    compose.onNodeWithText(renamed).assertIsDisplayed()
                    compose.onNodeWithText(ordinaryName).assertIsDisplayed()
                    assertTrue(offline.items.value.any { it.id == offlineId && it.status == "completed" })
                    assertTrue(ordinary.downloads.value.any { it.id == regularId && it.status == "completed" })
                } catch(failure: Throwable) {
                    throw AssertionError("Mixed download Undo failed: ${undoDiagnostic(undoStage)}", failure)
                }

                compose.onNodeWithText(label(app.miru.downloads.R.string.download_video)).performClick()
                compose.onNodeWithText(renamed).assertIsDisplayed()
                compose.waitForIdle(); instrumentation.waitForIdleSync(); SystemClock.sleep(500)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    context.openFileOutput("offline-downloads.png", Context.MODE_PRIVATE).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                val countBeforePlayback = origin.calls.get()
                origin.close()
                compose.onNodeWithText(renamed).performClick()
                try {
                    compose.waitUntil(20_000) {
                        var resumedPlayer: PlayerActivity? = null
                        instrumentation.runOnMainSync {
                            resumedPlayer = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>().firstOrNull()
                            resumedPlayer?.let { player = it }
                        }
                        resumedPlayer?.playback?.state?.value?.let { it.ready && it.playing && it.position > 300 && it.width == 320 && it.height == 180 } == true
                    }
                } catch(failure: Throwable) {
                    // Keep the actual decoded-playback assertion and its deadline. Record
                    // lifecycle/decoder/cache facts before finally tears the player down;
                    // never print media URLs, request headers or native error messages.
                    var playbackDiagnostic = "playerAbsent"
                    instrumentation.runOnMainSync {
                        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                        val active = monitor.getActivitiesInStage(Stage.RESUMED)
                        val current = active.filterIsInstance<PlayerActivity>().firstOrNull() ?: player
                        playbackDiagnostic = buildString {
                            append("resumed="); append(active.map { it.javaClass.simpleName })
                            append(", playerStage="); append(current?.let(monitor::getLifecycleStageOf))
                            append(", snapshot="); append(current?.playback?.state?.value)
                            current?.playback?.player?.let { native ->
                                append(", nativeState="); append(native.playbackState)
                                append(", playWhenReady="); append(native.playWhenReady)
                                append(", suppression="); append(native.playbackSuppressionReason)
                                append(", isPlaying="); append(native.isPlaying)
                                append(", position="); append(native.currentPosition)
                                append(", buffered="); append(native.bufferedPosition)
                                append(", videoSize="); append(native.videoSize.width); append('x'); append(native.videoSize.height)
                                append(", renderedVideo="); append(native.videoDecoderCounters?.renderedOutputBufferCount)
                                append(", renderedAudio="); append(native.audioDecoderCounters?.renderedOutputBufferCount)
                                append(", errorCode="); append(native.playerError?.errorCode)
                                append(", errorType="); append(native.playerError?.javaClass?.simpleName)
                            }
                        }
                    }
                    val row = offline.items.value.firstOrNull { it.id == offlineId }
                    val cacheBytes = runCatching { runBlocking { offline.cachedBytes(offlineId) } }.getOrNull()
                    throw AssertionError("Offline UI playback failed: $playbackDiagnostic; id=$offlineId, rowStatus=${row?.status}, rowError=${row?.error}, downloaded=${row?.downloadedBytes}, cacheBytes=$cacheBytes, pendingDeletion=${offlineId in offline.pendingDeletionIds.value}, ordinaryPendingDeletion=${regularId in ordinary.pendingDeletionIds.value}, originCalls=${origin.calls.get() - countBeforePlayback}", failure)
                }
                assertEquals("Clicking a completed offline item must use cache-only playback", countBeforePlayback, origin.calls.get())
                assertFalse(player!!.playback.state.value.error)
            } finally {
                instrumentation.runOnMainSync { player?.finish() }
                id?.let(offline::remove)
                ordinaryId?.let { ordinary.delete(it, deleteFile = true) }
                ordinary.updatePreferences { originalPreferences }
            }
        }
    }
}

/** A bounded synthetic HLS source plus one ordinary file; no public network is involved. */
private class OfflineDownloadsUiOrigin(private val assets: Context) : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val content = ConcurrentHashMap<String, ByteArray>()
    val calls = AtomicInteger()
    val rejected = AtomicInteger()
    val url = "http://127.0.0.1:${server.localPort}"
    fun headers() = mapOf("Cookie" to "miru_offline_ui=fixture", "Authorization" to "Bearer offline-ui-fixture")
    init { thread(isDaemon = true, name = "miru-offline-ui-origin") {
        while(running.get()) {
            val socket = try { server.accept() } catch(_: java.io.IOException) { break }
            sockets += socket
            thread(isDaemon = true, name = "miru-offline-ui-response") {
                socket.use { try { serve(it) } catch(_: java.io.IOException) { } finally { sockets -= it } }
            }
        }
    } }
    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val reader = socket.getInputStream().bufferedReader()
        val request = reader.readLine()?.split(' ') ?: return
        val path = request.getOrNull(1)?.substringBefore('?')?.removePrefix("/") ?: return
        val received = linkedMapOf<String, String>()
        var count = 0
        while(true) {
            val line = reader.readLine()?.takeIf(String::isNotBlank) ?: break
            if(++count > 64) return
            received[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        calls.incrementAndGet()
        if(path != "regular.bin" && !headers().all { (key, value) -> received[key.lowercase()] == value }) {
            rejected.incrementAndGet(); respond(socket, 403, ByteArray(0), "application/octet-stream", request[0], null); return
        }
        val bytes = when {
            path == "regular.bin" -> ByteArray(65536) { (it * 7).toByte() }
            path.matches(Regex("(master|video|english|japanese|subtitles)\\.m3u8|embedded\\.vtt|(video|english|japanese)-\\d{2}\\.ts")) -> content.getOrPut(path) { assets.assets.open("media/m3-tracks/$path").use { it.readBytes() } }
            else -> { respond(socket, 404, ByteArray(0), "application/octet-stream", request[0], null); return }
        }
        val range = received["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val start = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val end = (range?.groupValues?.get(2)?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
        if(start !in 0..end) { respond(socket, 416, ByteArray(0), "application/octet-stream", request[0], null); return }
        val mime = when { path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"; path.endsWith(".vtt") -> "text/vtt"; path.endsWith(".ts") -> "video/mp2t"; else -> "application/octet-stream" }
        respond(socket, if(range == null) 200 else 206, bytes, mime, request[0], if(range == null) null else start..end)
    }
    private fun respond(socket: Socket, status: Int, bytes: ByteArray, mime: String, method: String, range: IntRange?) {
        val start = range?.first ?: 0
        val length = range?.let { it.last - it.first + 1 } ?: bytes.size
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status Fixture\r\nContent-Type: $mime\r\nContent-Length: $length\r\nAccept-Ranges: bytes\r\nETag: \"miru-offline-ui\"\r\n${range?.let { "Content-Range: bytes ${it.first}-${it.last}/${bytes.size}\r\n" }.orEmpty()}Connection: close\r\n\r\n".toByteArray())
            if(method != "HEAD") write(bytes, start, length)
            flush()
        }
    }
    override fun close() { if(running.compareAndSet(true, false)) { server.close(); sockets.forEach { runCatching { it.close() } } } }
}
