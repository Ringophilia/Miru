// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.miru.player.PlayerActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Actual DOM events + Gecko fullscreen state; no JavaScript execution hooks in the product. */
@RunWith(AndroidJUnit4::class)
class WebMediaInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = (compose.activity.application as MiruApplication).container
    private val browser get() = container.browser
    private fun waitFor(predicate: () -> Boolean) {
        try { compose.waitUntil(30_000) { predicate() } }
        catch(error: Throwable) { throw AssertionError("Web media condition timed out: ${browser.state.value.selectedTab?.title}", error) }
    }
    private fun webBounds(): Rect {
        var result: Rect? = null
        fun visit(view: View) {
            if(generateSequence<Class<*>>(view.javaClass) { it.superclass }.any { it.name == "org.mozilla.geckoview.GeckoView" }) { val bounds = Rect(); if(view.getGlobalVisibleRect(bounds)) result = bounds }
            if(view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        instrumentation.runOnMainSync { visit(compose.activity.window.decorView) }
        return checkNotNull(result) { "Visible GeckoView was not found" }
    }
    private fun touch(action: Int, down: Long, yCss: Float) {
        val bounds = webBounds()
        val density = compose.activity.resources.displayMetrics.density
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.top + yCss * density, 0).also {
            it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(it, true)); it.recycle()
        }
    }
    private fun tap(yCss: Float) { val time = SystemClock.uptimeMillis(); touch(MotionEvent.ACTION_DOWN, time, yCss); touch(MotionEvent.ACTION_UP, time, yCss) }

    @Test fun webHoldRestoresRateAndFullscreenBackPreservesThePage() {
        val original = runBlocking { container.playerPreferences.settings.first() }
        WebMediaOrigin(instrumentation.context).use { origin -> try {
            runBlocking { container.playerPreferences.update { it.copy(webHoldSpeed = true, holdSpeed = 2.5f, autoTakeover = false) } }
            compose.runOnIdle { browser.newTab("${origin.url}/page") }
            waitFor { browser.media.value[browser.state.value.selectedTabId]?.any { it.duration > 30 } == true }
            compose.waitForIdle()
            tap(24f)
            waitFor { browser.state.value.selectedTab?.title == "rate:1.0:playing" }
            val down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, down, 120f)
            try { waitFor { browser.state.value.selectedTab?.title == "rate:2.5:playing" }; SystemClock.sleep(350); assertEquals("rate:2.5:playing", browser.state.value.selectedTab?.title) }
            finally { touch(MotionEvent.ACTION_UP, down, 120f) }
            waitFor { browser.state.value.selectedTab?.title == "rate:1.0:playing" }
            runBlocking { container.playerPreferences.update { it.copy(webHoldSpeed = false) } }
            SystemClock.sleep(500)
            val disabled = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, disabled, 120f)
            try { SystemClock.sleep(800); assertEquals("rate:1.0:playing", browser.state.value.selectedTab?.title) }
            finally { touch(MotionEvent.ACTION_UP, disabled, 120f) }
            val id = browser.state.value.selectedTabId
            tap(310f)
            waitFor { browser.state.value.selectedTab?.fullScreen == true }
            compose.onNodeWithContentDescription(compose.activity.getString(app.miru.toolbar.R.string.toolbar_menu)).assertDoesNotExist()
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor { browser.state.value.selectedTab?.fullScreen == false }
            assertEquals(id, browser.state.value.selectedTabId)
            assertEquals("${origin.url}/page", browser.state.value.selectedTab?.url)
            compose.onNodeWithContentDescription(compose.activity.getString(app.miru.toolbar.R.string.toolbar_menu)).assertIsDisplayed()
        } finally { runBlocking { container.playerPreferences.update { original } } } }
    }

    @Test fun automaticTakeoverIsOptInAndPausesTheWebVideo() {
        val original = runBlocking { container.playerPreferences.settings.first() }
        var player: PlayerActivity? = null
        WebMediaOrigin(instrumentation.context).use { origin -> try {
            runBlocking { container.playerPreferences.update { it.copy(autoTakeover = false) } }
            compose.runOnIdle { browser.newTab("${origin.url}/page") }
            waitFor { browser.media.value[browser.state.value.selectedTabId]?.any { it.duration > 30 } == true }
            compose.waitForIdle()
            tap(24f)
            waitFor { browser.media.value[browser.state.value.selectedTabId]?.any { it.canAutoTakeover } == true }
            SystemClock.sleep(500)
            instrumentation.runOnMainSync { player = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>().firstOrNull() }
            assertNull("Automatic takeover must be off by default", player)
            runBlocking { container.playerPreferences.update { it.copy(autoTakeover = true) } }
            waitFor { instrumentation.runOnMainSync { player = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>().firstOrNull() }; player != null }
            waitFor { player!!.playback.state.value.ready && player!!.playback.state.value.playing }
            waitFor { browser.state.value.selectedTab?.title?.endsWith(":paused") == true }
        } finally {
            runBlocking { container.playerPreferences.update { original } }
            instrumentation.runOnMainSync { player?.finish() }
        } }
    }
}

private class WebMediaOrigin(context: Context) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val open = AtomicBoolean(true)
    private val movie = context.assets.open("media/fixture.mp4").use { it.readBytes() }
    val url = "http://127.0.0.1:${server.localPort}"
    init { thread(isDaemon = true, name = "miru-web-media-origin") { while(open.get()) runCatching {
        val socket = server.accept()
        thread(isDaemon = true) { socket.use { connection -> runCatching {
            connection.soTimeout = 10_000
            val reader = connection.getInputStream().bufferedReader()
            val request = reader.readLine()?.split(' ') ?: return@runCatching
            val headers = mutableMapOf<String, String>()
            while(true) { val header = reader.readLine()?.takeIf(String::isNotBlank) ?: break; headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim() }
            val page = request.getOrNull(1)?.substringBefore('?') == "/page"
            val data = if(page) """
                <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Web media fixture</title>
                <style>html,body{margin:0}button{display:block;width:100%;height:48px}video{display:block;width:100%;height:230px;object-fit:cover}#full{margin-top:8px}</style>
                <button onclick="v.play()">Start fixture</button><video id="v" muted playsinline preload="metadata" src="/movie.mp4"></video>
                <button id="full" onclick="v.requestFullscreen()">Fullscreen fixture</button>
                <script>const state=()=>document.title='rate:'+v.playbackRate.toFixed(1)+':'+(v.paused?'paused':'playing');['play','pause','ratechange'].forEach(e=>v.addEventListener(e,state));</script>
            """.trimIndent().toByteArray() else movie
            val range = Regex("bytes=(\\d+)-(\\d*)").matchEntire(headers["range"].orEmpty())
            val start = range?.groupValues?.get(1)?.toInt() ?: 0
            val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(data.lastIndex) ?: data.lastIndex
            val code = if(range == null) "200 OK" else "206 Partial Content"
            connection.getOutputStream().apply {
                write("HTTP/1.1 $code\r\nContent-Type: ${if(page) "text/html" else "video/mp4"}\r\nContent-Length: ${end-start+1}\r\nAccept-Ranges: bytes\r\n${if(range != null) "Content-Range: bytes $start-$end/${data.size}\r\n" else ""}Connection: close\r\n\r\n".toByteArray())
                if(request[0] != "HEAD") write(data, start, end-start+1)
                flush()
            }
        } } }
    } } }
    override fun close() { open.set(false); server.close() }
}
