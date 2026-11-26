// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.miru.browser.engine.AdblockBackendKind
import app.miru.browser.engine.ChromiumEngineController
import app.miru.browser.engine.NEW_TAB_URL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Live Chromium 159 integration verification on emulator/device:
 * - Real Chromium UA (`Chrome/159.0`) & WebContents rendering
 * - Multi-tab switching + persistent normal history (`BrowsingLibrary`)
 * - OffTheRecord (`OTRProfile`) storage isolation & teardown
 * - Native C++ `URLLoaderThrottle` `adblock-rust` synchronous blocking + cosmetic hiding
 */
@RunWith(AndroidJUnit4::class)
class ChromiumEngineIntegrationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val container get() = (compose.activity.application as MiruApplication).container
    private val chromium get() = container.browser as ChromiumEngineController
    private val createdTabIds = mutableListOf<String>()

    @Before
    fun waitUntilEngineReady() {
        compose.waitUntil(30_000) { chromium.state.value.ready }
    }

    @After
    fun cleanupTabs() {
        compose.runOnIdle {
            chromium.closePrivateTabsPermanently()
            createdTabIds.forEach { chromium.closeTab(it) }
            createdTabIds.clear()
            chromium.restoreDefaultAdblockRules()
        }
    }

    @Test
    fun rendersPageWithChrome159UserAgentAndBlocksAdWithCosmeticHiding() {
        LocalTestServer().use { server ->
            compose.runOnIdle {
                chromium.setAdblockEnabled(true)
                chromium.setAdblockRules("||127.0.0.1^*/ad-banner.js\n127.0.0.1###ad-slot")
                val id = chromium.newTab("${server.baseUrl}/", isPrivate = false)
                createdTabIds += id
            }

            compose.waitUntil(25_000) {
                val tab = chromium.state.value.selectedTab
                tab != null && !tab.loading && tab.title == "Miru Chromium C1"
            }

            val resultJson = evalJsSync(
                "(() => JSON.stringify({ ua: navigator.userAgent, adLoaded: !!window.__adLoaded, " +
                    "adDisplay: getComputedStyle(document.getElementById('ad-slot')).display }))()",
            )
            val parsed = JSONObject(unquoteJsJson(resultJson))
            assertTrue("Expected Chrome/159.0 UA, got ${parsed.getString("ua")}", parsed.getString("ua").contains("Chrome/159.0"))
            assertFalse("Blocked script should never execute", parsed.getBoolean("adLoaded"))
            assertEquals("Ad-banner network request must be canceled before server hit", 0, server.adBannerHits.get())
            assertEquals("none", parsed.getString("adDisplay"))
            assertEquals(AdblockBackendKind.NATIVE_RUST, chromium.adblock.value.backend)
            assertTrue("Expected native adblock blockedCount >= 1", chromium.adblock.value.blockedCount >= 1)
        }
    }

    @Test
    fun multiTabHistoryAndOffTheRecordIsolation() {
        LocalTestServer().use { server ->
            var normalId = ""
            var privateId = ""
            compose.runOnIdle {
                normalId = chromium.newTab("${server.baseUrl}/normal", isPrivate = false)
                createdTabIds += normalId
            }
            compose.waitUntil(20_000) {
                val tab = chromium.state.value.tabs.firstOrNull { it.id == normalId }
                tab != null && !tab.loading && tab.title == "Miru Chromium C1"
            }
            evalJsSync("(() => { localStorage.setItem('miru_scope', 'normal'); return 'ok'; })()")

            compose.runOnIdle {
                privateId = chromium.newTab("${server.baseUrl}/private-secret", isPrivate = true)
            }
            compose.waitUntil(20_000) {
                val tab = chromium.state.value.tabs.firstOrNull { it.id == privateId }
                tab != null && !tab.loading && tab.title == "Miru Chromium C1"
            }
            val inPrivateBefore = evalJsSync("localStorage.getItem('miru_scope')", tabId = privateId)
            assertEquals("null", inPrivateBefore)
            evalJsSync("(() => { localStorage.setItem('miru_scope', 'otr'); return 'ok'; })()", tabId = privateId)

            compose.runOnIdle { chromium.closePrivateTabsPermanently() }

            var freshPrivateId = ""
            compose.runOnIdle {
                freshPrivateId = chromium.newTab("${server.baseUrl}/private-fresh", isPrivate = true)
            }
            compose.waitUntil(20_000) {
                val tab = chromium.state.value.tabs.firstOrNull { it.id == freshPrivateId }
                tab != null && !tab.loading && tab.title == "Miru Chromium C1"
            }
            val inFreshPrivate = evalJsSync("localStorage.getItem('miru_scope')", tabId = freshPrivateId)
            assertEquals("Fresh OTR profile must have cleared storage", "null", inFreshPrivate)

            val historyUrls = runBlocking { container.library.history.allUrls() }
            assertTrue("Expected normal URL in history", historyUrls.any { it.contains("/normal") })
            assertFalse("Private URL must never enter history", historyUrls.any { it.contains("/private-secret") })
        }
    }

    private fun evalJsSync(script: String, tabId: String? = null): String {
        val latch = CountDownLatch(1)
        val out = AtomicReference("")
        compose.runOnIdle {
            chromium.evaluateJavaScript(tabId, script) { res ->
                out.set(res)
                latch.countDown()
            }
        }
        assertTrue("JS evaluation timed out", latch.await(10, TimeUnit.SECONDS))
        return out.get()
    }

    private fun unquoteJsJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return JSONObject("{\"v\":$trimmed}").getString("v")
        }
        return trimmed
    }

    private class LocalTestServer : Closeable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        val adBannerHits = AtomicInteger(0)
        val requestedPaths = CopyOnWriteArrayList<String>()
        val baseUrl = "http://127.0.0.1:${socket.localPort}"

        private val threadHandle = thread(name = "miru-chromium-test-http") {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(name = "miru-chromium-test-conn") {
                    client.use { conn ->
                        val reader = BufferedReader(InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))
                        val requestLine = reader.readLine() ?: return@use
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val path = requestLine.split(' ').getOrNull(1).orEmpty()
                        requestedPaths += path
                        val (mime, body) = when {
                            path.startsWith("/ad-banner.js") -> {
                                adBannerHits.incrementAndGet()
                                "application/javascript" to "window.__adLoaded = true;"
                            }
                            path.startsWith("/app.js") -> {
                                "application/javascript" to "document.title = 'Miru Chromium C1';"
                            }
                            else -> "text/html" to """
                                <!doctype html>
                                <html>
                                  <head><meta charset="utf-8"><title>Pending</title></head>
                                  <body>
                                    <div id="ad-slot" style="width:100px;height:40px;background:red;">ad</div>
                                    <script src="/ad-banner.js"></script>
                                    <script src="/app.js"></script>
                                  </body>
                                </html>
                            """.trimIndent()
                        }
                        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
                        val headers = "HTTP/1.1 200 OK\r\nContent-Type: $mime; charset=utf-8\r\n" +
                            "Content-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
                        conn.getOutputStream().apply {
                            write(headers.toByteArray(StandardCharsets.US_ASCII))
                            write(bodyBytes)
                            flush()
                        }
                    }
                }
            }
        }

        override fun close() {
            running.set(false)
            runCatching { socket.close() }
            threadHandle.join(2_000)
        }
    }
}
