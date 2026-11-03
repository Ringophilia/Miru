// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import app.miru.data.BrowsingDataClearRequest
import app.miru.data.BrowsingDataRange
import app.miru.data.BrowsingDataType
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Gecko window.open requests, production Snackbar/permission switch and native child tabs. */
@RunWith(AndroidJUnit4::class)
class PopupWindowsIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val browser get() = (compose.activity.application as MiruApplication).container.browser
    private fun label(id: Int) = compose.activity.getString(id)
    private fun awaitTitle(id: String, title: String) = compose.waitUntil(20000) {
        browser.state.value.tabs.firstOrNull { it.id == id }?.title == title
    }
    private fun open(origin: PopupOrigin, isPrivate: Boolean = false): String {
        var id = ""
        compose.runOnIdle { id = browser.newTab(origin.url + "/source", isPrivate = isPrivate) }
        compose.waitUntil(10000) { browser.prompts.blockedPopup.value?.tabId == id }
        compose.onNodeWithText(label(R.string.popup_blocked)).assertIsDisplayed()
        return id
    }
    private fun allowAndAwaitChild(origin: PopupOrigin, source: String, isPrivate: Boolean = false): String {
        compose.onNodeWithText(label(R.string.popup_always_allow)).performClick()
        compose.waitUntil(15000) { browser.state.value.selectedTab?.let { it.id != source && it.title == "Popup child" } == true }
        val child = browser.state.value.selectedTab!!
        assertEquals(isPrivate, child.isPrivate)
        assertTrue(child.url.startsWith(origin.url))
        assertTrue(origin.children.get() > 0)
        return child.id
    }

    @Test fun blockedPopupTimesOutThenAlwaysAllowOpensTheRealChildAndSiteSwitchCanRevokeIt() = fixture { origin ->
        val source = open(origin)
        awaitTitle(source, "Popup denied")
        assertEquals(0, origin.children.get())
        compose.runOnIdle { browser.reload() }
        compose.waitUntil(10000) { browser.prompts.blockedPopup.value?.tabId == source }
        val child = allowAndAwaitChild(origin, source)
        compose.runOnIdle { browser.closeTab(child); browser.selectTab(source) }
        assertTrue(browser.prompts.popupSite.value!!.allowed)
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { browser.reload() }
        compose.waitUntil(15000) { browser.state.value.selectedTab?.title == "Popup child" && browser.state.value.selectedTabId != source }
        val second = browser.state.value.selectedTabId!!
        compose.runOnIdle { browser.closeTab(second); browser.selectTab(source) }
        compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_site_info)).performClick()
        compose.onNodeWithContentDescription(label(R.string.popup_site_allow)).performScrollTo().assertIsOn().performClick()
        compose.waitUntil(3000) { browser.prompts.popupSite.value?.allowed == false }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed(); browser.reload() }
        compose.waitUntil(10000) { browser.prompts.blockedPopup.value?.tabId == source }
        awaitTitle(source, "Popup denied")
        assertEquals(2, origin.children.get())
    }

    @Test fun ordinaryPermissionDoesNotGrantPrivateAndTheLastPrivateCloseForgetsItsChoice() = fixture { origin ->
        val normal = open(origin)
        val normalChild = allowAndAwaitChild(origin, normal)
        compose.runOnIdle { browser.closeTab(normalChild); browser.selectTab(normal) }
        val private = open(origin, isPrivate = true)
        val privateChild = allowAndAwaitChild(origin, private, isPrivate = true)
        compose.runOnIdle { browser.closeTab(privateChild); browser.closeTab(private); browser.selectTab(normal) }
        val fresh = open(origin, isPrivate = true)
        awaitTitle(fresh, "Popup denied")
        assertEquals(2, origin.children.get())
        compose.runOnIdle { browser.closeTab(fresh); browser.selectTab(normal) }
        assertTrue(browser.prompts.popupSite.value!!.allowed)
    }

    @Test fun navigationInvalidatesABlockedRequestsOldActionAndAnotherOriginStillAsks() = fixture { origin ->
        val first = open(origin)
        val stale = browser.prompts.blockedPopup.value!!.token
        compose.runOnIdle { browser.navigate(origin.url + "/quiet") }
        awaitTitle(first, "Popup quiet")
        compose.runOnIdle { browser.prompts.allowPopup(stale) }
        assertFalse(browser.prompts.popupSite.value!!.allowed)
        assertEquals(0, origin.children.get())
        PopupOrigin().use { other ->
            val id = open(other)
            awaitTitle(id, "Popup denied")
            assertEquals(0, other.children.get())
        }
    }

    @Test fun clearingAllSiteSettingsAlsoRemovesTheOrdinaryPopupGrant() = fixture { origin ->
        assertTrue("Global site-settings deletion is tested only on the controlled emulator", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val source = open(origin)
        val child = allowAndAwaitChild(origin, source)
        compose.runOnIdle { browser.closeTab(child); browser.selectTab(source) }
        val result = runBlocking { browser.clearBrowsingData(BrowsingDataClearRequest(setOf(BrowsingDataType.SITE_SETTINGS), BrowsingDataRange.ALL_TIME)) }
        assertTrue(result.successful)
        compose.waitUntil(10000) { browser.prompts.blockedPopup.value?.tabId == source }
        assertFalse(browser.prompts.popupSite.value!!.allowed)
        awaitTitle(source, "Popup denied")
        assertEquals(1, origin.children.get())
    }

    private fun fixture(block: (PopupOrigin) -> Unit) {
        compose.waitUntil(30000) { browser.state.value.ready }
        assertTrue("Controlled device must have no pre-existing private tabs", browser.state.value.tabs.none { it.isPrivate })
        val tabs = BrowserTabFixture()
        compose.runOnIdle { tabs.capture(browser) }
        PopupOrigin().use { origin ->
            try { block(origin) }
            finally {
                compose.runOnIdle {
                    browser.prompts.blockedPopup.value?.let { browser.prompts.dismissPopup(it.token) }
                    // Remove only this fixture's ordinary permission, preserving the user's other sites.
                    browser.state.value.tabs.firstOrNull { it.url.startsWith(origin.url) && !it.isPrivate }?.let {
                        browser.selectTab(it.id)
                        browser.prompts.popupSite.value?.let { state -> browser.prompts.setPopupAllowed(state, false) }
                    }
                    tabs.closeCreatedTabs(browser)
                }
            }
        }
    }
}

private class PopupOrigin : Closeable {
    private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    val url get() = "http://127.0.0.1:${server.localPort}"
    val children = AtomicInteger()
    private val running = AtomicBoolean(true)
    init { thread(isDaemon = true) { while (running.get()) {
        val socket = try { server.accept() } catch (_: Exception) { break }
        thread(isDaemon = true) { socket.use { try {
            it.soTimeout = 5000
            val reader = it.getInputStream().bufferedReader(); val path = reader.readLine().split(' ')[1]
            while(reader.readLine().isNotEmpty()) { }
            val body = when(path) {
                "/source" -> "<title>Popup source</title><script>setTimeout(()=>{const w=window.open('/child','_blank');document.title=w?'Popup opened':'Popup denied';},800)</script><h1>Source</h1>"
                "/child" -> { children.incrementAndGet(); "<title>Popup child</title><h1>Opened child window</h1>" }
                else -> "<title>Popup quiet</title><h1>No popup here</h1>"
            }
            val bytes = ("<!doctype html>" + body).toByteArray()
            it.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush() }
        } catch (_: Exception) { } } }
    } } }
    override fun close() { running.set(false); server.close() }
}
