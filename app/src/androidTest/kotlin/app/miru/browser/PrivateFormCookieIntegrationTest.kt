// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.miru.data.BrowsingDataClearRequest
import app.miru.data.BrowsingDataRange
import app.miru.data.BrowsingDataType
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** FORM_DATA is independently selectable: replacing documents must retain the private cookie jar. */
@RunWith(AndroidJUnit4::class)
class PrivateFormCookieIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val browser get() = (compose.activity.application as MiruApplication).container.browser
    private val tabFixture = BrowserTabFixture()

    @Before fun requireAnIsolatedPrivateContextAndRememberExistingTabs() {
        compose.waitUntil(30_000) { browser.state.value.ready }
        compose.runOnIdle {
            tabFixture.capture(browser)
            assertTrue("Run last-private-context fixtures on a controlled device without existing private tabs",
                browser.state.value.tabs.none { it.isPrivate })
        }
    }

    @After fun closeOnlyThisMethodsTabsAndRestoreSelection() {
        compose.runOnIdle { tabFixture.closeCreatedTabs(browser) }
    }

    @Test fun clearingFormsFromANormalTabRetainsThePrivateCookieAfterAFreshPageLoad() {
        PrivateFormOrigin().use { origin ->
            var privateId = ""
            var normalId = ""
            try {
                compose.runOnIdle { privateId = browser.newTab("${origin.url}/seed", isPrivate = true) }
                compose.waitUntil(30_000) {
                    browser.state.value.selectedTab?.let {
                        it.id == privateId && it.title == "Private seed" && !it.loading
                    } == true
                }
                assertTrue("The real private jar must contain the HttpOnly seed", cookie(origin, true).contains(origin.cookiePair))
                assertFalse("The ordinary jar must remain separate", cookie(origin, false).contains(origin.cookieName))

                compose.runOnIdle { browser.navigate("${origin.url}/private") }
                awaitFreshPrivateDocument(origin, privateId, afterSequence = 0)
                assertTrue(origin.privateRequests.single())
                val beforeClear = origin.privateLoads.get()
                compose.runOnIdle { normalId = browser.newTab("${origin.url}/normal", isPrivate = false) }
                compose.waitUntil(20_000) {
                    browser.state.value.selectedTab?.let {
                        it.id == normalId && it.title == "Ordinary page" && !it.loading
                    } == true
                }
                assertEquals("Exactly one private tab exercises the last private context", listOf(privateId),
                    browser.state.value.tabs.filter { it.isPrivate }.map { it.id })

                val result = runBlocking {
                    browser.clearBrowsingData(BrowsingDataClearRequest(setOf(BrowsingDataType.FORM_DATA), BrowsingDataRange.ALL_TIME))
                }
                assertEquals(setOf(BrowsingDataType.FORM_DATA), result.completed)
                assertTrue(result.failed.isEmpty())
                assertEquals("Clearing forms must preserve the selected ordinary tab", normalId, browser.state.value.selectedTabId)
                assertEquals("The private tab identity and address must survive", "${origin.url}/private",
                    browser.state.value.tabs.single { it.id == privateId }.url)

                compose.runOnIdle { browser.selectTab(privateId) }
                // A new numbered title and a real HTTP request prove a post-clear document;
                // the copied title or a stale Java snapshot cannot satisfy this wait.
                awaitFreshPrivateDocument(origin, privateId, afterSequence = beforeClear)
                assertTrue("The post-clear private document must send its original cookie, without reseeding",
                    origin.privateRequests.drop(beforeClear).isNotEmpty() && origin.privateRequests.drop(beforeClear).all { it })
                repeat(6) {
                    assertTrue("The private cookie must remain after asynchronous session teardown", cookie(origin, true).contains(origin.cookiePair))
                    assertFalse("Retained private cookies must not appear in the ordinary jar", cookie(origin, false).contains(origin.cookieName))
                    SystemClock.sleep(100)
                }
            } finally {
                compose.runOnIdle {
                    listOf(privateId, normalId).filter(String::isNotEmpty).forEach { id ->
                        if(browser.state.value.tabs.any { it.id == id }) browser.closeTab(id)
                    }
                }
            }
        }
    }

    @Test fun aNewTabDuringNativePreparationAbortsBeforeRemovingAnyOriginalTab() {
        PrivateFormOrigin().use { origin ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            var privateId = ""
            var incomingId = ""
            try {
                compose.runOnIdle {
                    privateId = browser.newTab("${origin.url}/seed", isPrivate = true)
                }
                compose.waitUntil(30_000) {
                    browser.state.value.selectedTab?.let {
                        it.id == privateId && it.title == "Private seed" && !it.loading
                    } == true
                }
                assertTrue(cookie(origin, true).contains(origin.cookiePair))
                compose.runOnIdle { browser.navigate("${origin.url}/private") }
                awaitFreshPrivateDocument(origin, privateId, afterSequence = 0)
                val originalIds = browser.state.value.tabs.map { it.id }
                val originalLoads = origin.privateLoads.get()
                var clearing: kotlinx.coroutines.Deferred<app.miru.data.BrowsingDataClearResult>? = null
                compose.runOnIdle {
                    // Start on Main immediately through the first native-ready suspension. The
                    // incoming tab is then dispatched before Main can deliver the native callbacks;
                    // no sleep, production test hook or race with a timed background thread.
                    clearing = scope.async(start = CoroutineStart.UNDISPATCHED) {
                        browser.clearBrowsingData(BrowsingDataClearRequest(setOf(BrowsingDataType.FORM_DATA), BrowsingDataRange.ALL_TIME))
                    }
                    incomingId = browser.newTab("${origin.url}/normal", isPrivate = false)
                }
                val result = runBlocking { withTimeout(15_000) { checkNotNull(clearing).await() } }
                assertTrue("An outdated captured tab set must not be reported as cleared", result.completed.isEmpty())
                assertEquals(setOf(BrowsingDataType.FORM_DATA), result.failed)
                compose.waitUntil(20_000) {
                    browser.state.value.selectedTab?.let {
                        it.id == incomingId && it.title == "Ordinary page" && !it.loading
                    } == true
                }
                assertEquals("All original IDs must remain, followed by the newly arrived tab", originalIds + incomingId,
                    browser.state.value.tabs.map { it.id })
                assertEquals("The old private document must not have been replaced on abort", originalLoads, origin.privateLoads.get())
                assertEquals("${origin.url}/private", browser.state.value.tabs.single { it.id == privateId }.url)
                assertTrue("Closing only unused preparation contexts must retain the old private jar", cookie(origin, true).contains(origin.cookiePair))
                assertFalse(cookie(origin, false).contains(origin.cookieName))
            } finally {
                scope.cancel()
                compose.runOnIdle {
                    listOf(privateId, incomingId).filter(String::isNotEmpty).forEach { id ->
                        if(browser.state.value.tabs.any { it.id == id }) browser.closeTab(id)
                    }
                }
            }
        }
    }

    private fun awaitFreshPrivateDocument(origin: PrivateFormOrigin, tabId: String, afterSequence: Int) {
        compose.waitUntil(30_000) {
            origin.privateLoads.get() > afterSequence && browser.state.value.selectedTab?.let {
                it.id == tabId && it.url == "${origin.url}/private" && it.title == origin.latestPrivateTitle && !it.loading
            } == true
        }
        compose.waitForIdle()
    }

    private fun cookie(origin: PrivateFormOrigin, private: Boolean): String = runBlocking {
        browser.headersForMediaUrl(origin.url, private).entries.firstOrNull { it.key.equals("Cookie", true) }?.value.orEmpty()
    }
}

private class PrivateFormOrigin : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val sockets = CopyOnWriteArrayList<Socket>()
    val url = "http://127.0.0.1:${server.localPort}"
    val cookieName = "miru_private_forms_${System.nanoTime()}"
    val cookiePair = "$cookieName=retained"
    val privateLoads = AtomicInteger()
    val privateRequests = CopyOnWriteArrayList<Boolean>()
    @Volatile var latestPrivateTitle = ""
        private set

    init {
        thread(isDaemon = true, name = "miru-private-form-origin") {
            while(running.get()) runCatching {
                val socket = server.accept()
                sockets.add(socket)
                thread(isDaemon = true) {
                    try { socket.use { client -> runCatching {
                        client.soTimeout = 5_000
                        val reader = client.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(' ')?.getOrNull(1)?.substringBefore('?').orEmpty()
                        var requestCookie = ""
                        while(true) {
                            val line = reader.readLine() ?: break
                            if(line.isEmpty()) break
                            if(line.substringBefore(':').equals("Cookie", true)) requestCookie = line.substringAfter(':').trim()
                        }
                        val title = when(path) {
                            "/seed" -> "Private seed"
                            "/normal" -> "Ordinary page"
                            "/private" -> {
                                val present = requestCookie.split(';').any { it.trim() == cookiePair }
                                privateRequests.add(present)
                                val sequence = privateLoads.incrementAndGet()
                                "Private read $sequence ${if(present) "kept" else "missing"}".also { latestPrivateTitle = it }
                            }
                            else -> "Private fixture"
                        }
                        val body = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>$title</title>$title".toByteArray()
                        val seed = if(path == "/seed") "Set-Cookie: $cookiePair; Path=/; HttpOnly; SameSite=Lax\r\n" else ""
                        client.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n$seed\r\n").toByteArray())
                            write(body)
                            flush()
                        }
                    } } } finally { sockets.remove(socket) }
                }
            }
        }
    }

    override fun close() {
        running.set(false)
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }
}
