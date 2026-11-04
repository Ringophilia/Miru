// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Notification.requestPermission, service worker alerts and Android-published actions. */
@RunWith(AndroidJUnit4::class)
class SiteNotificationIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val browser get() = (compose.activity.application as MiruApplication).container.browser
    private val context get() = compose.activity
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun posted(): List<StatusBarNotification> = manager.activeNotifications.filter { it.tag?.startsWith("site_notification:") == true }
    private fun title(id: String, expected: String) = compose.waitUntil(20000) { browser.state.value.tabs.firstOrNull { it.id == id }?.let { it.title == expected && !it.loading } == true }
    private fun page(origin: NotificationOrigin, private: Boolean = false, permission: String = "default"): String {
        var id = ""
        compose.runOnIdle { id = browser.newTab(origin.url, isPrivate = private) }
        title(id, "Notify ready $permission"); return id
    }
    private fun request(allow: Boolean) {
        tap("Request notification")
        compose.waitUntil(10000) { browser.permissions.prompt.value != null }
        assertEquals(setOf(SitePermissionKind.NOTIFICATIONS), browser.permissions.prompt.value!!.kinds)
        compose.onNodeWithText(context.getString(if (allow) R.string.site_permission_allow else R.string.site_permission_block)).performClick()
    }
    private fun alert(): StatusBarNotification {
        compose.waitUntil(8000) { posted().size == 1 }; return posted().single()
    }
    private fun noAlerts() = compose.waitUntil(8000) { posted().isEmpty() }
    private fun state(id: String) {
        compose.runOnIdle { browser.permissions.refreshSite() }
        compose.waitUntil(8000) { browser.permissions.siteState.value?.let { it.tabId == id && !it.busy && !it.error } == true }
    }
    private fun siteInfo(id: String) {
        compose.onNodeWithContentDescription(context.getString(app.miru.toolbar.R.string.toolbar_site_info)).performClick()
        state(id)
    }
    private fun back() = compose.runOnIdle { context.onBackPressedDispatcher.onBackPressed() }

    @Test fun allowShowsARealNotificationAndClickReturnsToThePageAndNativeWorker() = fixture { origin ->
        val id = page(origin); request(true); title(id, "Notify granted 1")
        val shown = alert().notification
        assertEquals("Miru site title", shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Miru body 1", shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(origin.url, shown.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        if (Build.VERSION.SDK_INT >= 31) { assertTrue(shown.contentIntent.isActivity); assertTrue(shown.contentIntent.isImmutable) }
        compose.runOnIdle { browser.newTab() }
        shown.contentIntent.send()
        compose.waitUntil(10000) { browser.state.value.selectedTabId == id && origin.events.contains("click:") }
        noAlerts()
        state(id)
        assertEquals(SitePermissionChoice.ALLOW, browser.permissions.siteState.value!!.choices[SitePermissionKind.NOTIFICATIONS])
    }

    @Test fun rememberedBlockCanBeChangedAndResetThroughSiteControls() = fixture { origin ->
        val id = page(origin); request(false); title(id, "Notify denied"); noAlerts()
        compose.runOnIdle { browser.navigate(origin.url + "/?reload=1") }; title(id, "Notify ready denied")
        tap("Request notification"); title(id, "Notify denied")
        assertNull(browser.permissions.prompt.value)
        siteInfo(id)
        compose.onNodeWithContentDescription(context.getString(R.string.site_permission_change, context.getString(R.string.site_permission_notifications)))
            .performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.site_permission_allow)).performClick()
        compose.waitUntil(8000) { browser.permissions.siteState.value?.let { !it.busy && it.choices[SitePermissionKind.NOTIFICATIONS] == SitePermissionChoice.ALLOW } == true }
        back(); title(id, "Notify ready granted"); tap("Show again"); title(id, "Notify granted 1"); alert()
        siteInfo(id)
        compose.onNodeWithText(context.getString(R.string.site_permission_reset)).performScrollTo().performClick()
        compose.waitUntil(8000) { browser.permissions.siteState.value?.let { !it.busy && it.choices[SitePermissionKind.NOTIFICATIONS] == SitePermissionChoice.ASK } == true }
        noAlerts(); back(); title(id, "Notify ready default")
        request(false); title(id, "Notify denied")
    }

    @Test fun replacementPageCloseDeleteActionAndClearingSiteSettingsRetireAlerts() = fixture { origin ->
        val id = page(origin); request(true); title(id, "Notify granted 1")
        val first = alert().tag
        tap("Show again"); title(id, "Notify granted 2")
        compose.waitUntil(8000) { posted().size == 1 && posted().single().tag != first }
        assertEquals("Miru body 2", alert().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        tap("Close notification"); noAlerts()
        tap("Show again"); title(id, "Notify granted 3")
        val shown = alert()
        if (Build.VERSION.SDK_INT >= 31) assertTrue(shown.notification.deleteIntent.isBroadcast)
        manager.cancel(shown.tag, shown.id); shown.notification.deleteIntent.send()
        compose.waitUntil(8000) { origin.events.contains("close:") }; noAlerts()
        tap("Show again"); title(id, "Notify granted 4"); alert()
        val cleared = runBlocking { browser.clearBrowsingData(BrowsingDataClearRequest(setOf(BrowsingDataType.SITE_SETTINGS), BrowsingDataRange.ALL_TIME)) }
        assertTrue(cleared.successful); noAlerts(); title(id, "Notify ready default")
        request(false); title(id, "Notify denied")
    }

    @Test fun privateChoicesAreIsolatedAndOnlyGenericContentSurvivesUntilPrivateTabsClose() = fixture { origin ->
        assertTrue("Controlled fixture requires no pre-existing private tabs", browser.state.value.tabs.none { it.isPrivate })
        val normal = page(origin); request(false); title(normal, "Notify denied")
        val private = page(origin, private = true); request(true); title(private, "Notify granted 1")
        val shown = alert().notification
        assertEquals(Notification.VISIBILITY_SECRET, shown.visibility)
        assertEquals(context.getString(app.miru.browser.engine.R.string.web_notification_private_title), shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse(shown.extras.toString().contains(origin.url)); assertFalse(shown.extras.toString().contains("Miru body"))
        compose.runOnIdle { browser.closePrivateTabsPermanently() }; noAlerts()
        state(normal)
        assertEquals(SitePermissionChoice.BLOCK, browser.permissions.siteState.value!!.choices[SitePermissionKind.NOTIFICATIONS])
        val fresh = page(origin, private = true)
        request(false); title(fresh, "Notify denied")
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

    private fun fixture(block: (NotificationOrigin) -> Unit) {
        if (Build.VERSION.SDK_INT >= 33) assertEquals("Grant POST_NOTIFICATIONS on the host before this class", PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        compose.waitUntil(30000) { browser.state.value.ready }
        val activity = compose.activity
        val originalIntent = Intent(activity.intent)
        val tabs = BrowserTabFixture(); compose.runOnIdle { tabs.capture(browser) }
        NotificationOrigin().use { origin ->
            try { block(origin) }
            finally {
                compose.runOnIdle {
                    // ActivityScenario matches lifecycle events against its launch Intent.
                    // A genuine notification changes it through MainActivity.onNewIntent.
                    activity.intent = originalIntent
                    browser.permissions.prompt.value?.let { browser.permissions.dismiss(it.token) }
                    browser.webNotifications.cancelAll(); tabs.closeCreatedTabs(browser)
                }
                assertTrue(runBlocking { browser.clearBrowsingData(BrowsingDataClearRequest(setOf(BrowsingDataType.SITE_SETTINGS), BrowsingDataRange.ALL_TIME)) }.successful)
            }
        }
    }
}

private class NotificationOrigin : Closeable {
    private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    val url get() = "http://127.0.0.1:${server.localPort}"
    val events = CopyOnWriteArrayList<String>()
    private val running = AtomicBoolean(true)
    init { thread(isDaemon = true) { while (running.get()) {
        val socket = try { server.accept() } catch (_: Exception) { break }
        thread(isDaemon = true) { socket.use { try {
            it.soTimeout = 5000
            val reader = it.getInputStream().bufferedReader(); val path = reader.readLine().split(' ')[1]
            while (reader.readLine().isNotEmpty()) { }
            val worker = path == "/worker.js"
            val body = when {
                worker -> """
                    self.addEventListener('install', e=>self.skipWaiting());
                    self.addEventListener('activate', e=>e.waitUntil(clients.claim()));
                    self.addEventListener('notificationclick', e=>{e.notification.close();e.waitUntil(fetch('/event?click:'+e.action));});
                    self.addEventListener('notificationclose', e=>e.waitUntil(fetch('/event?close:')));
                """.trimIndent()
                path.startsWith("/event?") -> { events += path.substringAfter('?'); "ok" }
                else -> """
                    <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                    <style>body{margin:0}button{display:block;width:280px;height:82px}</style>
                    <button onclick="request()">Request notification</button><button onclick="show()">Show again</button><button onclick="closeAll()">Close notification</button>
                    <script>
                    let reg, count=0;
                    async function show(){try{await reg.showNotification('Miru site title',{body:'Miru body '+(++count),tag:'miru-test'});document.title='Notify granted '+count}catch(e){document.title='Notify error '+e.name+':'+e.message}}
                    async function request(){const p=await Notification.requestPermission();if(p==='granted')await show();else document.title='Notify '+p}
                    async function closeAll(){for(const n of await reg.getNotifications())n.close()}
                    navigator.serviceWorker.register('/worker.js').then(()=>navigator.serviceWorker.ready).then(r=>{reg=r;setTimeout(()=>document.title='Notify ready '+Notification.permission,250)}).catch(e=>document.title='Notify setup error '+e.name+':'+e.message);
                    </script>
                """.trimIndent()
            }
            val bytes = body.toByteArray()
            val mime = if (worker) "application/javascript" else "text/html"
            it.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
        } catch (_: Exception) { } } }
    } } }
    override fun close() { running.set(false); server.close() }
}
