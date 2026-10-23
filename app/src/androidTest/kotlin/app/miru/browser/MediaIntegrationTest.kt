// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.browser

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.miru.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class MediaIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val browser get() = (compose.activity.application as MiruApplication).container.browser
    private val context get() = instrumentation.targetContext
    private fun waitFor(timeout: Long = 30_000, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while(!predicate()) { if(SystemClock.elapsedRealtime() > until) throw AssertionError("Timed out waiting for media condition"); SystemClock.sleep(100) }
    }

    @Test fun geckoDetectsNetworkTypesHeadersAndTransfersTheWebPosition() {
        MediaOrigin(instrumentation.context).use { origin ->
            compose.runOnIdle { browser.newTab("${origin.url}/page") }
            try { waitFor { browser.media.value[browser.state.value.selectedTabId]?.let { list -> list.size == 3 && list.any { it.kind == "HLS" } && list.any { it.kind == "DASH" } && list.any { it.kind == "VIDEO" && it.position >= 8 } } == true } }
            catch(error: AssertionError) { throw AssertionError("Detection did not converge: ready=${browser.state.value.ready}, title=${browser.state.value.selectedTab?.title}, media=${browser.media.value[browser.state.value.selectedTabId]?.map { "${it.kind}:${it.position}:${it.duration}" }}, calls=${origin.calls.map { it.path }}", error) }
            val items = browser.media.value[browser.state.value.selectedTabId]!!
            assertEquals("Segments must never appear as videos", 3, items.size)
            assertTrue(items.all { it.headers.entries.any { h -> h.key.equals("Cookie", true) && h.value.contains("miru_media=fixture") } })
            val hls = items.single { it.kind == "HLS" }
            assertEquals("Bearer miru-media-fixture", hls.headers.entries.single { it.key.equals("Authorization", true) }.value)
            val video = items.single { it.kind == "VIDEO" }
            val prepared = runBlocking(Dispatchers.Main) { browser.prepareMedia(video.tabId, video.id) }
            assertNotNull("Content port must acknowledge web pause and current time", prepared)
            assertTrue("Web timestamp should be 8 seconds", prepared!!.positionMs in 7_900..8_500)
            assertTrue(prepared.headers.keys.any { it.equals("Referer", true) })
            compose.onNodeWithContentDescription(context.getString(app.miru.toolbar.R.string.toolbar_media)).performClick()
            compose.onNodeWithText(context.getString(app.miru.media.R.string.media_title)).assertIsDisplayed()
            compose.onAllNodesWithContentDescription(context.getString(app.miru.media.R.string.media_play))[0].performClick()
            compose.waitForIdle()
            var activity: PlayerActivity? = null
            waitFor { instrumentation.runOnMainSync { activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>().firstOrNull() }; activity != null }
            val target = activity!!
            val preferences = (compose.activity.application as MiruApplication).container.playerPreferences
            val originalOptions = runBlocking { preferences.settings.first() }
            try {
                // STATE_READY can precede Media3's separate first video-size callback. Keep the
                // exact dimensions assertions below, after a real video format has arrived.
                waitFor { target.playback.state.value.let { it.ready && it.playing && it.width > 0 && it.height > 0 } }
                val state = target.playback.state.value
                assertTrue("Native playback must resume within two seconds of the web time: ${state.position}", state.position in 7_000..10_000)
                assertEquals(320, state.width); assertEquals(180, state.height)
                assertTrue("The native request must carry the cookie and page referrer", origin.calls.any { it.path == "/fixture.mp4" && it.headers["cookie"]?.contains("miru_media=fixture") == true && it.headers["referer"]?.endsWith("/page") == true })
                runBlocking { preferences.update { it.copy(holdSpeed = 3f, doubleTapSeconds = 5, autoPip = true) } }
                waitFor { target.playback.preferences.value.let { it.holdSpeed == 3f && it.doubleTapSeconds == 5 } }
                instrumentation.runOnMainSync { target.playback.speed(2f) }
                waitFor { target.playback.state.value.speed == 2f }
                instrumentation.runOnMainSync { target.playback.speed(1f); target.playback.seek(20_000) }
                waitFor { target.playback.state.value.let { it.position in 19_500..22_000 && it.playing && !it.buffering } }
                compose.waitForIdle()
                var width = 0; var height = 0
                waitFor { instrumentation.runOnMainSync { width = target.window.decorView.width; height = target.window.decorView.height }; width > height }
                fun event(action: Int, down: Long, x: Float, y: Float) {
                    android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x * width, y * height, 0).also {
                        it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        instrumentation.uiAutomation.injectInputEvent(it, true); it.recycle()
                    }
                }
                val held = SystemClock.uptimeMillis()
                event(android.view.MotionEvent.ACTION_DOWN, held, 0.8f, 0.35f)
                try {
                    SystemClock.sleep(600)
                    waitFor(2_000) { target.playback.state.value.speed == 3f }
                } catch(error: AssertionError) {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/miru-m2-failed.png")).use { it.readBytes() }
                    throw AssertionError("Hold did not apply: ${target.playback.state.value}; bounds=${width}x$height", error)
                } finally { event(android.view.MotionEvent.ACTION_UP, held, 0.8f, 0.35f) }
                waitFor(2_000) { target.playback.state.value.speed == 1f }
                val beforeTap = target.playback.state.value.position
                repeat(2) {
                    val down = SystemClock.uptimeMillis()
                    event(android.view.MotionEvent.ACTION_DOWN, down, 0.8f, 0.35f)
                    event(android.view.MotionEvent.ACTION_UP, down, 0.8f, 0.35f)
                    SystemClock.sleep(80)
                }
                waitFor(2_000) { target.playback.state.value.position >= beforeTap + 4_500 }
                compose.waitForIdle()
                instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                    context.openFileOutput("m2-player.png", Context.MODE_PRIVATE).use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                }
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/miru-m2-player.png")).use { it.readBytes() }
                instrumentation.runOnMainSync { target.enterPip() }
                waitFor(5_000) { target.isInPictureInPictureMode }
                assertTrue("Playback must continue in picture-in-picture", target.playback.state.value.playing)
            } finally { instrumentation.runOnMainSync { target.finish() }; runBlocking { preferences.update { originalOptions } } }
        }
    }

    @Test fun hlsManifestsAlwaysUseNetworkWhileSegmentsReplayFromDiskAndPrivateBypassesCache() {
        MediaOrigin(instrumentation.context).use { origin ->
            val headers = mapOf("Cookie" to "miru_media=fixture", "Authorization" to "Bearer miru-media-fixture", "Referer" to "${origin.url}/page")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            fun play(private: Boolean) {
                var controller: PlayerController? = null
                instrumentation.runOnMainSync { controller = PlayerController(context, PlaybackRequest("${origin.url}/playlist.m3u8", "HLS fixture", mimeType = "application/x-mpegURL", headers = headers, isPrivate = private), scope, {}) }
                try { waitFor(45_000) { val state = controller!!.state.value; if(state.error) throw AssertionError("HLS playback failed"); state.ready && state.buffered > 40_000 } }
                finally { instrumentation.runOnMainSync { controller!!.release() } }
            }
            try {
                play(false)
                val segments = origin.calls.count { it.path.endsWith(".ts") }
                val manifests = origin.calls.count { it.path == "/playlist.m3u8" }
                assertTrue(segments >= 10)
                play(false)
                assertEquals("A second normal playback must read the existing media segments", segments, origin.calls.count { it.path.endsWith(".ts") })
                assertTrue("Manifests are never cached", origin.calls.count { it.path == "/playlist.m3u8" } > manifests)
                play(true)
                assertTrue("Private playback must fetch segments rather than read normal disk cache", origin.calls.count { it.path.endsWith(".ts") } >= segments * 2)
                assertTrue(origin.calls.filter { it.path.endsWith(".ts") }.all { it.headers["cookie"] == "miru_media=fixture" && it.headers["authorization"] == "Bearer miru-media-fixture" })
            } finally { scope.cancel() }
        }
    }

    @Test fun privateFrameMediaUsesItsOwnTabAndExpiresWhenClosed() {
        MediaOrigin(instrumentation.context).use { origin ->
            var tabId = ""
            compose.runOnIdle { tabId = browser.newTab("${origin.url}/framed", isPrivate = true) }
            waitFor { browser.media.value[tabId]?.any { it.kind == "VIDEO" && it.position >= 8 } == true }
            val item = browser.media.value[tabId]!!.single()
            assertTrue(item.isPrivate)
            assertEquals(tabId, item.tabId)
            val result = runBlocking(Dispatchers.Main) { browser.prepareMedia(tabId, item.id) }
            assertNotNull("Native preparation must reach the iframe content port", result)
            assertTrue(result!!.positionMs in 7_900..8_500)
            compose.runOnIdle { browser.closeTab(tabId) }
            waitFor { tabId !in browser.media.value }
        }
    }

    @Test fun progressResumesFromRoomAndPrivatePlaybackDoesNotReadIt() {
        MediaOrigin(instrumentation.context).use { origin ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val headers = mapOf("Cookie" to "miru_media=fixture", "Referer" to "${origin.url}/page")
            var controller: PlayerController? = null
            fun open(private: Boolean) {
                instrumentation.runOnMainSync { controller = PlayerController(context, PlaybackRequest("${origin.url}/fixture.mp4", "Resume fixture", pageUrl = "${origin.url}/page", headers = headers, isPrivate = private), scope, {}) }
                waitFor { controller!!.state.value.ready }
            }
            try {
                open(false)
                instrumentation.runOnMainSync { controller!!.seek(16_000); controller!!.release() }
                // Wait for the real asynchronous Room commit, not an in-memory retained controller.
                val key = PlaybackPolicy.progressKey("${origin.url}/page", "${origin.url}/fixture.mp4")
                waitFor { runBlocking { PlaybackHistory.get(context).progress().get(key)?.position ?: 0 } >= 16_000 }
                open(false)
                assertTrue(controller!!.state.value.resumed)
                assertTrue(controller!!.state.value.position in 15_500..18_000)
                instrumentation.runOnMainSync { controller!!.release() }
                open(true)
                assertFalse(controller!!.state.value.resumed)
                assertTrue("Private playback must start independently of normal progress", controller!!.state.value.position < 3_000)
                instrumentation.runOnMainSync { controller!!.seek(25_000); controller!!.release() }
                SystemClock.sleep(300)
                val stored = runBlocking { PlaybackHistory.get(context).progress().get(key) }
                assertTrue("Private progress must not replace the normal record", stored!!.position < 20_000)
            } finally { instrumentation.runOnMainSync { controller?.release() }; scope.cancel() }
        }
    }

    @Test fun redirectToAnotherOriginDropsCookieAndAuthorization() {
        MediaOrigin(instrumentation.context).use { target -> MediaOrigin(instrumentation.context, "${target.url}/fixture.mp4").use { origin ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            var controller: PlayerController? = null
            instrumentation.runOnMainSync { controller = PlayerController(context, PlaybackRequest("${origin.url}/redirect", "Redirect fixture", headers = mapOf("Cookie" to "miru_media=fixture", "Authorization" to "Bearer miru-media-fixture")), scope, {}) }
            try {
                waitFor { target.calls.any { it.path == "/fixture.mp4" } }
                val redirected = target.calls.first { it.path == "/fixture.mp4" }
                assertNull("Cookie must not follow a cross-origin redirect", redirected.headers["cookie"])
                assertNull("Authorization must not follow a cross-origin redirect", redirected.headers["authorization"])
            } finally { instrumentation.runOnMainSync { controller!!.release() }; scope.cancel() }
        } }
    }

    @Test fun settingsPersistResizeAndClearCacheWithoutDeletingDownloads() {
        val repository = (compose.activity.application as MiruApplication).container.playerPreferences
        val original = runBlocking { repository.settings.first() }
        val downloads = (compose.activity.application as MiruApplication).container.downloads
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var controller: PlayerController? = null
        var downloadId: String? = null
        MediaOrigin(instrumentation.context).use { origin ->
            try {
                runBlocking { repository.update { PlayerPreferences() } }
                val headers = mapOf("Cookie" to "miru_media=fixture", "Referer" to "${origin.url}/page")
                instrumentation.runOnMainSync { controller = PlayerController(context, PlaybackRequest("${origin.url}/fixture.mp4", "Cache settings fixture", headers = headers), scope, {}) }
                waitFor { controller!!.state.value.let { it.ready && it.buffered > 40_000 } }
                assertTrue(PlaybackCache.usedBytes(context) > 0)
                val priorLimit = PlaybackCache.limitBytes()
                PlaybackCache.setLimit(512 * 1024)
                assertTrue("Shrinking the LRU budget must evict committed bytes immediately", PlaybackCache.usedBytes(context) <= 512 * 1024)
                PlaybackCache.setLimit(priorLimit)
                assertTrue("Cache eviction must preserve the player instance", controller!!.state.value.ready)
                instrumentation.runOnMainSync {
                    controller!!.release()
                    controller = PlayerController(context, PlaybackRequest("${origin.url}/fixture.mp4", "Cache settings fixture", headers = headers), scope, {})
                }
                waitFor { controller!!.state.value.let { it.ready && it.buffered > 40_000 } && PlaybackCache.usedBytes(context) > 0 }
                downloadId = downloads.enqueue(app.miru.downloads.DownloadRequest("${origin.url}/fixture.mp4", fileName = "miru-kept-cache-test.mp4", headers = headers))
                waitFor { downloads.downloads.value.any { it.id == downloadId && it.status == "completed" } }
                val file = downloads.downloads.value.single { it.id == downloadId }
                val uri = android.net.Uri.parse(file.destUri!!)
                val savedBytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                compose.onNodeWithContentDescription(context.getString(app.miru.toolbar.R.string.toolbar_menu)).performClick()
                compose.onNodeWithText(context.getString(app.miru.menu.R.string.menu_settings)).performClick()
                compose.onNodeWithText(context.getString(app.miru.settings.R.string.player)).performScrollTo().performClick()
                compose.onNodeWithText(context.getString(app.miru.player.R.string.player_cache_limit)).performScrollTo().performClick()
                compose.onNodeWithText(context.getString(app.miru.player.R.string.player_megabytes, 512)).performClick()
                waitFor { runBlocking { repository.settings.first() }.cacheMb == 512 && PlaybackCache.limitBytes() == 512 * 1024L * 1024 }
                compose.onNodeWithText(context.getString(app.miru.player.R.string.player_double_tap)).performScrollTo().performClick()
                compose.onNodeWithText(context.getString(app.miru.player.R.string.player_seconds, 15)).performClick()
                compose.onNodeWithText(context.getString(app.miru.player.R.string.player_hold)).performScrollTo().performClick()
                compose.onNodeWithText("3.0x").performClick()
                waitFor { runBlocking { repository.settings.first() }.let { it.doubleTapSeconds == 15 && it.holdSpeed == 3f } }
                waitFor { controller!!.preferences.value.let { it.doubleTapSeconds == 15 && it.holdSpeed == 3f } }
                compose.onNodeWithContentDescription(context.getString(app.miru.player.R.string.player_back)).performClick()
                compose.onNodeWithText(context.getString(app.miru.settings.R.string.player)).performScrollTo().performClick()
                compose.onNodeWithText(context.getString(app.miru.player.R.string.player_hold)).performScrollTo()
                compose.onNodeWithText("3.0x").assertIsDisplayed()
                compose.onAllNodesWithText(context.getString(app.miru.player.R.string.player_clear))[0].performScrollTo().performClick()
                compose.onNodeWithText(context.getString(app.miru.player.R.string.player_confirm_clear)).performClick()
                waitFor { PlaybackCache.usedBytes(context) == 0L }
                assertArrayEquals("Clearing streamed cache must preserve the published download", savedBytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                instrumentation.runOnMainSync { controller!!.release() }
                val progressKey = PlaybackPolicy.progressKey("", "${origin.url}/fixture.mp4")
                waitFor { runBlocking { PlaybackHistory.get(context).progress().get(progressKey) } != null }
                compose.mainClock.advanceTimeBy(5_000)
                compose.waitForIdle()
                compose.onAllNodesWithText(context.getString(app.miru.player.R.string.player_clear))[1].performScrollTo().performClick()
                compose.onNodeWithText(context.getString(app.miru.player.R.string.player_confirm_clear)).performClick()
                waitFor { runBlocking { PlaybackHistory.get(context).progress().get(progressKey) } == null }
                compose.waitForIdle()
                waitFor {
                    var lightBars = false
                    instrumentation.runOnMainSync { lightBars = androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).isAppearanceLightStatusBars }
                    lightBars
                }
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/miru-m2-settings.png")).use { it.readBytes() }
            } finally {
                instrumentation.runOnMainSync { controller?.release() }
                scope.cancel()
                downloadId?.let { downloads.delete(it, deleteFile = true) }
                runBlocking { repository.update { original } }
            }
        }
    }
}

private data class MediaCall(val path: String, val headers: Map<String, String>)

/** Synthetic assets generated locally by ffmpeg; no external service, copyrighted movie or credentials. */
private class MediaOrigin(private val assetsContext: Context, private val redirectTo: String? = null) : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    val calls = CopyOnWriteArrayList<MediaCall>()
    val url = "http://127.0.0.1:${server.localPort}"
    init { thread(isDaemon = true, name = "miru-media-origin") {
        while(running.get()) runCatching { val socket = server.accept(); thread(isDaemon = true) { socket.use { connection -> runCatching {
            connection.soTimeout = 10_000
            val reader = connection.getInputStream().bufferedReader()
            val line = reader.readLine()?.split(' ') ?: return@runCatching
            val path = line.getOrNull(1)?.substringBefore('?').orEmpty()
            val headers = linkedMapOf<String, String>()
            while(true) { val header = reader.readLine()?.takeIf(String::isNotBlank) ?: break; headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim() }
            calls += MediaCall(path, headers)
            if(path == "/redirect" && redirectTo != null) {
                connection.getOutputStream().apply { write("HTTP/1.1 302 Found\r\nLocation: $redirectTo\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
                return@runCatching
            }
            val page = """
                <!doctype html><meta name="viewport" content="width=device-width"><title>Miru media fixture</title>
                <h1>Miru media fixture</h1><video id="v" width="320" height="180" muted controls preload="metadata" src="/fixture.mp4?token=one"></video>
                <video src="/preview.mp4" preload="metadata"></video>
                <script>
                v.addEventListener('loadedmetadata',()=>{v.currentTime=8;document.title='Miru media ready';},{once:true});
                fetch('/playlist.m3u8?token=one',{headers:{Authorization:'Bearer miru-media-fixture'}});
                fetch('/manifest',{headers:{Authorization:'Bearer miru-media-fixture'}});
                fetch('/fragment.m4s');fetch('/fragment.ts');
                </script>
            """.trimIndent().toByteArray()
            val html = path in setOf("/page", "/framed", "/frame")
            val mime = when { html -> "text/html"; path == "/playlist.m3u8" -> "application/vnd.apple.mpegurl"; path == "/manifest" -> "application/dash+xml"; path.endsWith(".ts") -> "video/mp2t"; path.endsWith(".m4s") -> "video/mp4"; else -> "video/mp4" }
            val bytes = when {
                path == "/page" -> page
                path == "/framed" -> "<!doctype html><title>Miru iframe fixture</title><iframe src=\"/frame\"></iframe>".toByteArray()
                path == "/frame" -> "<!doctype html><title>Frame video</title><video id=\"v\" controls muted preload=\"metadata\" src=\"/fixture.mp4\"></video><script>v.addEventListener('loadedmetadata',()=>{v.currentTime=8},{once:true})</script>".toByteArray()
                path == "/manifest" -> "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"></MPD>".toByteArray()
                path.startsWith("/fragment") -> ByteArray(300_000)
                path == "/preview.mp4" -> ByteArray(10_000)
                else -> assetsContext.assets.open("media/${path.substringAfterLast('/')}").use { it.readBytes() }
            }
            val protected = path == "/fixture.mp4" || path == "/playlist.m3u8" || path.startsWith("/segment")
            val authorized = !protected || headers["cookie"]?.contains("miru_media=fixture") == true
            val range = Regex("bytes=(\\d+)-(\\d*)").matchEntire(headers["range"].orEmpty())
            val from = range?.groupValues?.get(1)?.toInt() ?: 0
            val to = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
            val code = if(!authorized) "403 Forbidden" else if(range != null) "206 Partial Content" else "200 OK"
            val size = if(authorized) (to - from + 1).coerceAtLeast(0) else 0
            connection.getOutputStream().apply {
                write("HTTP/1.1 $code\r\nContent-Type: $mime\r\nContent-Length: $size\r\nAccept-Ranges: bytes\r\nCache-Control: no-store\r\n${if(html) "Set-Cookie: miru_media=fixture; Path=/; SameSite=Lax\r\n" else ""}${if(range != null) "Content-Range: bytes $from-$to/${bytes.size}\r\n" else ""}Connection: close\r\n\r\n".toByteArray())
                if(authorized && line[0] != "HEAD") write(bytes, from, size)
                flush()
            }
        } } } }
    } }
    override fun close() { running.set(false); server.close() }
}
