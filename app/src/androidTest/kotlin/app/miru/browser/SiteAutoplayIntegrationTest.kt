// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.browser.engine.SiteAutoplayChoice
import app.miru.data.BrowsingDataClearRequest
import app.miru.data.BrowsingDataRange
import app.miru.data.BrowsingDataType
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Gecko media playback before a gesture, after a gesture, and after site policy changes. */
@RunWith(AndroidJUnit4::class)
class SiteAutoplayIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as MiruApplication).container
    private val browser get() = container.browser
    private val context get() = compose.activity
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun title(id: String, expected: String) = compose.waitUntil(20000) { browser.state.value.tabs.firstOrNull { it.id == id }?.let { it.title == expected && !it.loading } == true }
    private fun outcome(muted: Boolean, playing: Boolean) = "Auto ${if (playing) "playing" else "blocked"} ${if (muted) "muted" else "audible"}"
    private fun page(origin: AutoplayOrigin, muted: Boolean, private: Boolean = false, playing: Boolean = muted): String {
        var id = ""
        compose.runOnIdle { id = browser.newTab(origin.page(muted), isPrivate = private) }
        title(id, outcome(muted, playing)); return id
    }
    private fun state(id: String, choice: SiteAutoplayChoice) {
        compose.runOnIdle { browser.permissions.refreshSite() }
        compose.waitUntil(8000) { browser.permissions.siteState.value?.let { it.tabId == id && !it.busy && !it.error && it.autoplay == choice } == true }
    }
    private fun set(id: String, choice: SiteAutoplayChoice, muted: Boolean) {
        compose.waitUntil(8000) { browser.permissions.siteState.value?.let { it.tabId == id && !it.busy && !it.error } == true }
        compose.onNodeWithContentDescription(context.getString(app.miru.toolbar.R.string.toolbar_site_info)).performClick()
        // Opening site information starts a fresh native permission read, disabling the menu while it runs.
        val autoplayMenu = hasContentDescription(context.getString(R.string.site_autoplay_title)) and isEnabled()
        compose.waitUntil(8_000) { compose.onAllNodes(autoplayMenu).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(autoplayMenu).performScrollTo().assertIsDisplayed().performClick()
        val text = when (choice) {
            SiteAutoplayChoice.ALLOW_ALL -> R.string.site_autoplay_allow_all
            SiteAutoplayChoice.BLOCK_AUDIBLE -> R.string.site_autoplay_block_audible
            SiteAutoplayChoice.BLOCK_ALL -> R.string.site_autoplay_block_all
        }
        val option = context.getString(text)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(option).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(option).performClick()
        compose.waitUntil(8000) { browser.permissions.siteState.value?.let { !it.busy && !it.error && it.autoplay == choice } == true }
        compose.runOnIdle { context.onBackPressedDispatcher.onBackPressed() }
        title(id, outcome(muted, choice.allows(muted)))
    }

    @Test fun defaultPermitsMutedAutoplayAndManualPlayStillWorksAfterAudibleAutoplayWasBlocked() = fixture { origin ->
        page(origin, muted = true)
        val audible = page(origin, muted = false)
        assertNull("Autoplay must not interrupt page load with a permission dialog", browser.permissions.prompt.value)
        tap("Play manually")
        title(audible, outcome(muted = false, playing = true))
    }

    @Test fun aBackgroundTabUsesTheSitePolicyAndDoesNotOpenAPermissionSheet() = fixture { origin ->
        var foreground = ""
        var background = ""
        compose.runOnIdle {
            foreground = browser.newTab()
            background = browser.newTab(origin.page(muted = true))
            browser.selectTab(foreground)
        }
        compose.waitUntil(20000) { browser.state.value.tabs.firstOrNull { it.id == background }?.title in
            setOf("Auto permitted muted", "Auto playing muted") }
        assertEquals(foreground, browser.state.value.selectedTabId)
        assertNull(browser.permissions.prompt.value)
        compose.runOnIdle { browser.selectTab(background) }
        title(background, outcome(muted = true, playing = true))
    }

    @Test fun allThreeChoicesApplyToRealPlaybackAfterReloadAndSurviveActivityRecreation() = fixture { origin ->
        val id = page(origin, muted = false)
        set(id, SiteAutoplayChoice.ALLOW_ALL, muted = false)
        set(id, SiteAutoplayChoice.BLOCK_ALL, muted = false)
        compose.runOnIdle { browser.navigate(origin.page(muted = true)) }
        title(id, outcome(muted = true, playing = false))
        set(id, SiteAutoplayChoice.BLOCK_AUDIBLE, muted = true)
        compose.activityRule.scenario.recreate()
        state(id, SiteAutoplayChoice.BLOCK_AUDIBLE)
        assertNull(browser.permissions.prompt.value)
    }

    @Test fun privateChoicesStaySeparateAndLastPrivateCloseAndClearSettingsRestoreDefaults() = fixture { origin ->
        assertTrue("Controlled fixture requires no pre-existing private tabs", browser.state.value.tabs.none { it.isPrivate })
        val normal = page(origin, muted = false)
        set(normal, SiteAutoplayChoice.ALLOW_ALL, muted = false)
        val private = page(origin, muted = false, private = true)
        compose.runOnIdle { browser.navigate(origin.page(muted = true)) }
        title(private, outcome(muted = true, playing = true))
        set(private, SiteAutoplayChoice.BLOCK_ALL, muted = true)
        compose.runOnIdle { browser.selectTab(normal) }; state(normal, SiteAutoplayChoice.ALLOW_ALL)
        compose.runOnIdle { browser.closePrivateTabsPermanently() }
        val fresh = page(origin, muted = true, private = true)
        state(fresh, SiteAutoplayChoice.BLOCK_AUDIBLE)
        compose.runOnIdle { browser.selectTab(normal) }
        assertTrue(runBlocking { browser.clearBrowsingData(BrowsingDataClearRequest(setOf(BrowsingDataType.SITE_SETTINGS), BrowsingDataRange.ALL_TIME)) }.successful)
        state(normal, SiteAutoplayChoice.BLOCK_AUDIBLE); title(normal, outcome(muted = false, playing = false))
        compose.runOnIdle { browser.closePrivateTabsPermanently() }
    }

    private fun tap(label: String) {
        fun find(node: AccessibilityNodeInfo?, depth: Int): Rect? {
            if (node == null || depth > 40) return null
            if (node.isVisibleToUser && (node.text?.toString().orEmpty() + node.contentDescription?.toString().orEmpty()).contains(label)) {
                val rect = Rect(); node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) return rect
            }
            for (index in 0 until node.childCount) find(node.getChild(index), depth + 1)?.let { return it }
            return null
        }
        var bounds: Rect? = null
        compose.waitUntil(8000) { bounds = find(automation.rootInActiveWindow, 0); bounds != null }
        val target = bounds!!; val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, target.exactCenterX(), target.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60)
        }
    }

    private fun fixture(block: (AutoplayOrigin) -> Unit) {
        val skip = context.getString(R.string.intro_skip)
        compose.waitUntil(15_000) {
            runBlocking { container.settings.settings.first().introCompleted } ||
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText(skip).performClick()
        compose.waitUntil(30000) { browser.state.value.ready }
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("browser-viewport").fetchSemanticsNodes().isNotEmpty() }
        val tabs = BrowserTabFixture(); compose.runOnIdle { tabs.capture(browser) }
        AutoplayOrigin().use { origin ->
            try { block(origin) }
            finally {
                compose.runOnIdle { tabs.closeCreatedTabs(browser) }
                assertTrue(runBlocking { browser.clearBrowsingData(BrowsingDataClearRequest(setOf(BrowsingDataType.SITE_SETTINGS), BrowsingDataRange.ALL_TIME)) }.successful)
            }
        }
    }
}

private class AutoplayOrigin : Closeable {
    private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    private val sequence = AtomicInteger()
    private val running = AtomicBoolean(true)
    fun page(muted: Boolean) = "http://127.0.0.1:${server.localPort}/?muted=$muted&document=${sequence.incrementAndGet()}"
    private val wave = ByteBuffer.allocate(44 + 16000).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + 16000); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(16000)
        repeat(8000) { putShort((sin(it * 2.0 * Math.PI * 440 / 8000) * 3000).toInt().toShort()) }
    }.array()
    init { thread(isDaemon = true) { while (running.get()) {
        val socket = try { server.accept() } catch (_: Exception) { break }
        thread(isDaemon = true) { socket.use { try {
            it.soTimeout = 5000
            val reader = it.getInputStream().bufferedReader(); val path = reader.readLine().split(' ')[1]
            while (reader.readLine().isNotEmpty()) { }
            val audio = path.startsWith("/sound.wav")
            val muted = path.contains("muted=true")
            val body = if (audio) wave else """
                <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                <style>body{margin:0}button{width:280px;height:100px}</style>
                <button onclick="run()">Play manually</button><audio id=a loop ${if (muted) "muted" else ""} src="/sound.wav"></audio>
                <script>
                const muted=$muted,a=document.getElementById('a');document.title='Auto loading';
                function run(){a.play().then(()=>{document.title='Auto permitted '+(muted?'muted':'audible')}).catch(e=>{document.title=e.name==='NotAllowedError'?'Auto blocked '+(muted?'muted':'audible'):'Auto error '+e.name});}
                setInterval(()=>{if(!a.paused&&a.currentTime>0.1)document.title='Auto playing '+(muted?'muted':'audible')},50);
                requestAnimationFrame(()=>requestAnimationFrame(()=>setTimeout(run,100)));
                </script>
            """.trimIndent().toByteArray()
            val mime = if (audio) "audio/wav" else "text/html"
            it.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nCache-Control: no-store\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
        } catch (_: Exception) { } } }
    } } }
    override fun close() { running.set(false); server.close() }
}
