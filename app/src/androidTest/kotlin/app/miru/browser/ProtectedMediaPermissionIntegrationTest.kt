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
import app.miru.browser.engine.SitePermissionChoice
import app.miru.browser.engine.SitePermissionKind
import app.miru.data.BrowsingDataClearRequest
import app.miru.data.BrowsingDataRange
import app.miru.data.BrowsingDataType
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual EME/Widevine requests, native site consent, and real MediaKeys attachment. */
@RunWith(AndroidJUnit4::class)
class ProtectedMediaPermissionIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity
    private val browser get() = (context.application as MiruApplication).container.browser
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun title(id: String, expected: String) = compose.waitUntil(30000) { browser.state.value.tabs.firstOrNull { it.id == id }?.let {
        if (it.title.startsWith("Protected error")) throw AssertionError(it.title)
        it.title == expected && !it.loading
    } == true }
    private fun page(origin: ProtectedMediaOrigin, private: Boolean = false): String {
        var id = ""
        compose.runOnIdle { id = browser.newTab(origin.url, isPrivate = private) }
        title(id, "Protected page ready"); return id
    }
    private fun request() {
        tap()
        compose.waitUntil(10000) {
            val title = browser.state.value.selectedTab?.title.orEmpty()
            if (title.startsWith("Protected error")) throw AssertionError(title)
            browser.permissions.prompt.value != null
        }
        assertEquals(setOf(SitePermissionKind.PROTECTED_MEDIA), browser.permissions.prompt.value!!.kinds)
    }
    private fun state(id: String) {
        compose.runOnIdle { browser.permissions.refreshSite() }
        compose.waitUntil(10000) { browser.permissions.siteState.value?.let { it.tabId == id && !it.busy && !it.error } == true }
    }
    private fun answer(allow: Boolean) = compose.onNodeWithText(context.getString(if (allow) R.string.site_permission_allow else R.string.site_permission_block)).performClick()

    @Test fun denyCanBeChangedToAllowAndResetForActualWidevineMediaKeys() = fixture { origin ->
        val id = page(origin); request(); answer(false)
        title(id, "Protected denied")
        state(id)
        assertEquals(SitePermissionChoice.BLOCK, browser.permissions.siteState.value!!.choices[SitePermissionKind.PROTECTED_MEDIA])
        compose.onNodeWithContentDescription(context.getString(app.miru.toolbar.R.string.toolbar_site_info)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.site_permission_change, context.getString(R.string.site_permission_protected_media))).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.site_permission_allow)).performClick()
        compose.waitUntil(10000) { browser.permissions.siteState.value?.let { !it.busy && !it.error && it.choices[SitePermissionKind.PROTECTED_MEDIA] == SitePermissionChoice.ALLOW } == true }
        compose.runOnIdle { context.onBackPressedDispatcher.onBackPressed() }
        title(id, "Protected page ready"); tap(); title(id, "Protected keys attached")
        assertNull(browser.permissions.prompt.value)
        compose.onNodeWithContentDescription(context.getString(app.miru.toolbar.R.string.toolbar_site_info)).performClick()
        compose.onNodeWithText(context.getString(R.string.site_permission_reset)).performScrollTo().performClick()
        compose.waitUntil(10000) { browser.permissions.siteState.value?.let { !it.busy && it.choices[SitePermissionKind.PROTECTED_MEDIA] == SitePermissionChoice.ASK } == true }
        compose.runOnIdle { context.onBackPressedDispatcher.onBackPressed() }
        title(id, "Protected page ready"); request(); answer(false)
    }

    @Test fun ordinaryAndPrivateDecisionsStaySeparateAndPrivateCloseForgetsThem() = fixture { origin ->
        assertTrue("Controlled fixture requires no pre-existing private tabs", browser.state.value.tabs.none { it.isPrivate })
        val ordinary = page(origin); request(); answer(false); title(ordinary, "Protected denied")
        val private = page(origin, private = true); request(); answer(true); title(private, "Protected keys attached")
        compose.runOnIdle { browser.selectTab(ordinary) }; state(ordinary)
        assertEquals(SitePermissionChoice.BLOCK, browser.permissions.siteState.value!!.choices[SitePermissionKind.PROTECTED_MEDIA])
        compose.runOnIdle { browser.closePrivateTabsPermanently() }
        page(origin, private = true); request(); answer(false)
        compose.runOnIdle { browser.closePrivateTabsPermanently() }
    }

    @Test fun aRetiredMediaRequestCannotBeGrantedOnAnotherPage() = fixture { origin ->
        val owner = page(origin); request()
        val old = browser.permissions.prompt.value!!
        var other = ""
        compose.runOnIdle { other = browser.newTab(); browser.permissions.answer(old.token, true, true) }
        compose.waitUntil(5000) { browser.permissions.prompt.value == null }
        assertEquals(other, browser.state.value.selectedTabId)
        compose.runOnIdle { browser.selectTab(owner) }; title(owner, "Protected denied"); state(owner)
        assertNotEquals(SitePermissionChoice.ALLOW, browser.permissions.siteState.value!!.choices[SitePermissionKind.PROTECTED_MEDIA])
    }

    private fun tap() {
        fun find(node: AccessibilityNodeInfo?, depth: Int): Rect? {
            if (node == null || depth > 40) return null
            if (node.isVisibleToUser && (node.text?.toString().orEmpty() + node.contentDescription?.toString().orEmpty()).contains("Request protected media")) {
                val rect = Rect(); node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) return rect
            }
            for (index in 0 until node.childCount) find(node.getChild(index), depth + 1)?.let { return it }
            return null
        }
        var bounds: Rect? = null
        compose.waitUntil(8000) { bounds = find(automation.rootInActiveWindow, 0); bounds != null }
        val rect = bounds!!; val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60)
        }
    }
    private fun fixture(block: (ProtectedMediaOrigin) -> Unit) {
        compose.waitUntil(30000) { browser.state.value.ready }
        val tabs = BrowserTabFixture(); compose.runOnIdle { tabs.capture(browser) }
        ProtectedMediaOrigin().use { origin ->
            try { block(origin) }
            finally {
                compose.runOnIdle { browser.permissions.prompt.value?.let { browser.permissions.dismiss(it.token) }; tabs.closeCreatedTabs(browser) }
                assertTrue(runBlocking { browser.clearBrowsingData(BrowsingDataClearRequest(setOf(BrowsingDataType.SITE_SETTINGS), BrowsingDataRange.ALL_TIME)) }.successful)
            }
        }
    }
}

private class ProtectedMediaOrigin : Closeable {
    private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    val url get() = "http://127.0.0.1:${server.localPort}/"
    private val running = AtomicBoolean(true)
    init { thread(isDaemon = true) { while (running.get()) {
        val socket = try { server.accept() } catch (_: Exception) { break }
        thread(isDaemon = true) { socket.use { try {
            it.soTimeout = 5000
            val reader = it.getInputStream().bufferedReader(); reader.readLine()
            while (reader.readLine().isNotEmpty()) { }
            val body = """
                <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                <style>body{margin:0}button{width:280px;height:100px}</style>
                <button onclick="request()">Request protected media</button><video id=v></video>
                <script>
                async function request(){
                  try {
                    if(typeof navigator.requestMediaKeySystemAccess!=='function')throw new Error('EME unavailable');
                    const access=await navigator.requestMediaKeySystemAccess('com.widevine.alpha',[{initDataTypes:['cenc'],sessionTypes:['temporary'],
                      audioCapabilities:[{contentType:'audio/mp4; codecs="mp4a.40.2"'}],videoCapabilities:[{contentType:'video/mp4; codecs="avc1.42E01E"'}]}]);
                    let keys;try{keys=await access.createMediaKeys();await document.getElementById('v').setMediaKeys(keys)}catch(e){document.title='Protected error create '+e.name;return}
                    document.title='Protected keys attached';
                  }catch(e){document.title=e.name==='NotAllowedError'||e.name==='NotSupportedError'?'Protected denied':'Protected error '+e.name+':'+e.message}
                }
                requestAnimationFrame(()=>requestAnimationFrame(()=>setTimeout(()=>document.title='Protected page ready',250)));
                </script>
            """.trimIndent().toByteArray()
            it.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nCache-Control: no-store\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
        } catch (_: Exception) { } } }
    } } }
    override fun close() { running.set(false); server.close() }
}
