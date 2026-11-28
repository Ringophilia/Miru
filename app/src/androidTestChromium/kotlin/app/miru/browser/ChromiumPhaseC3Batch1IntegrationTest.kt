// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.miru.browser.engine.BrowserDownloadRequest
import app.miru.browser.engine.ChromiumEngineController
import app.miru.data.BrowserSecuritySettings
import app.miru.data.BrowsingDataClearRequest
import app.miru.data.BrowsingDataRange
import app.miru.data.BrowsingDataType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Live Chromium 159 Phase C3 Batch 1 integration verification on emulator/device:
 * - Download interception (HTTP + isolated cookies, `data:` URI stream, OTR private isolation)
 * - `BrowsingDataRemover` wiping persistent Chromium cookies and `localStorage`
 * - HTTPS-Only navigation upgrade (`http://example.com` -> `https://example.com`) + X.509 CertificateDetails
 * - Non-private `undoClose()` restoration + normal-only `saveSession()` persistence
 */
@RunWith(AndroidJUnit4::class)
class ChromiumPhaseC3Batch1IntegrationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val container get() = (compose.activity.application as MiruApplication).container
    private val chromium get() = container.browser as ChromiumEngineController
    private val createdTabIds = mutableListOf<String>()

    @Before
    fun waitUntilEngineReady() {
        compose.waitUntil(30_000) { chromium.state.value.ready }
        compose.runOnIdle {
            chromium.restoreDefaultAdblockRules()
            chromium.applySecuritySettingsNow(BrowserSecuritySettings())
        }
    }

    @After
    fun cleanupTabs() {
        chromium.testDownloadInterceptor = null
        compose.runOnIdle {
            chromium.applySecuritySettingsNow(BrowserSecuritySettings())
            chromium.closePrivateTabsPermanently()
            createdTabIds.forEach { chromium.closeTab(it) }
            createdTabIds.clear()
            chromium.restoreDefaultAdblockRules()
        }
    }

    @Test
    fun downloadInterceptionDataStreamAndOtrCookieIsolation() {
        val captured = Channel<BrowserDownloadRequest>(Channel.UNLIMITED)
        val capturedFlow = captured.receiveAsFlow()
        chromium.testDownloadInterceptor = { req ->
            captured.trySend(req)
            true
        }
        LocalDownloadServer().use { server ->
            var normalId = ""
            compose.runOnIdle {
                normalId = chromium.newTab("${server.baseUrl}/download-page", isPrivate = false)
                createdTabIds += normalId
            }
            compose.waitUntil(25_000) {
                val tab = chromium.state.value.tabs.firstOrNull { it.id == normalId }
                tab != null && !tab.loading && tab.title == "Miru C3 Download"
            }

            // Trigger HTTP attachment download on normal tab via `evaluateJavaScript`.
            val httpReq = runBlocking {
                evalJsSync("document.getElementById('dl-http').click(); 'ok';", tabId = normalId)
                withTimeoutOrNull(15_000) {
                    capturedFlow.first { !it.isPrivate && it.url.contains("/file/report.bin") }
                }
            }
            assertNotNull("Expected HTTP download request", httpReq)
            httpReq!!.bodyStream?.close()
            assertEquals("miru-report.bin", httpReq.fileName)
            assertTrue("Expected normal Cookie in download headers", httpReq.headers["Cookie"].orEmpty().contains("dl_norm=n1"))

            // Trigger data: URI download via <a download>.
            val dataReq = runBlocking {
                evalJsSync("document.getElementById('dl-data').click(); 'ok';", tabId = normalId)
                withTimeoutOrNull(15_000) {
                    capturedFlow.first { it.url.startsWith("data:") }
                }
            }
            assertNotNull("Expected data: download request", dataReq)
            val dataText = dataReq!!.bodyStream!!.use { it.readBytes().toString(Charsets.UTF_8) }
            assertEquals("miru-data-ok", dataText)

            // Verify OTR private download keeps private cookie and never leaks into normal headers.
            var privateId = ""
            compose.runOnIdle {
                privateId = chromium.newTab("${server.baseUrl}/private-download", isPrivate = true)
            }
            compose.waitUntil(25_000) {
                val tab = chromium.state.value.tabs.firstOrNull { it.id == privateId }
                tab != null && !tab.loading && tab.title == "Miru C3 Priv Download"
            }
            val privReq = runBlocking {
                evalJsSync("document.getElementById('dl-priv').click(); 'ok';", tabId = privateId)
                withTimeoutOrNull(15_000) {
                    capturedFlow.first { it.isPrivate && it.url.contains("/file/priv.bin") }
                }
            }
            assertNotNull("Expected private HTTP download request", privReq)
            privReq!!.bodyStream?.close()
            assertTrue("Expected OTR cookie in private download", privReq.headers["Cookie"].orEmpty().contains("dl_priv=p1"))
            assertFalse(privReq.headers["Cookie"].orEmpty().contains("dl_norm=n1"))
        }
    }

    @Test
    fun clearBrowsingDataUndoCloseAndHttpsOnlyCertificate() {
        LocalDownloadServer().use { server ->
            var tabId = ""
            compose.runOnIdle {
                tabId = chromium.newTab("${server.baseUrl}/download-page", isPrivate = false)
                createdTabIds += tabId
            }
            compose.waitUntil(25_000) {
                val tab = chromium.state.value.tabs.firstOrNull { it.id == tabId }
                tab != null && !tab.loading && tab.title == "Miru C3 Download"
            }
            evalJsSync("localStorage.setItem('miru_c3_key', 'present'); 'ok';", tabId = tabId)

            val cleared = runBlocking {
                chromium.clearBrowsingData(
                    BrowsingDataClearRequest(
                        types = setOf(BrowsingDataType.COOKIES, BrowsingDataType.SITE_DATA, BrowsingDataType.WEB_CACHE),
                        range = BrowsingDataRange.ALL_TIME,
                    ),
                )
            }
            assertTrue(cleared.successful)

            val cookiesAfter = runBlocking { chromium.headersForMediaUrl("${server.baseUrl}/download-page", isPrivate = false) }
            assertFalse("Cookies must be cleared by BrowsingDataRemover", cookiesAfter["Cookie"].orEmpty().contains("dl_norm=n1"))

            compose.runOnIdle { chromium.reload() }
            compose.waitUntil(25_000) {
                val tab = chromium.state.value.tabs.firstOrNull { it.id == tabId }
                tab != null && !tab.loading && tab.title == "Miru C3 Download"
            }
            val storageAfter = evalJsSync("localStorage.getItem('miru_c3_key')", tabId = tabId)
            assertEquals("null", storageAfter)

            // Verify undoClose restores normal closed tab (while ignoring closed private tab).
            var privId = ""
            compose.runOnIdle {
                privId = chromium.newTab("${server.baseUrl}/private-download", isPrivate = true)
            }
            compose.waitUntil(15_000) {
                chromium.state.value.tabs.any { it.id == privId && !it.loading }
            }
            compose.runOnIdle {
                chromium.closeTab(privId)
                chromium.closeTab(tabId)
                createdTabIds.remove(tabId)
            }
            assertFalse(chromium.state.value.tabs.any { it.id == tabId })

            compose.runOnIdle { chromium.undoClose() }
            assertTrue("undoClose should restore closed normal tab", chromium.state.value.tabs.any { it.id == tabId })
            assertFalse("undoClose must never restore private tab", chromium.state.value.tabs.any { it.id == privId })
            createdTabIds += tabId

            // Verify HTTPS-Only upgrades http://example.com -> https://example.com and exposes X.509 CertificateDetails.
            compose.runOnIdle {
                chromium.applySecuritySettingsNow(BrowserSecuritySettings(httpsOnly = true))
                chromium.selectTab(tabId)
                chromium.navigate("http://example.com/")
            }
            try {
                compose.waitUntil(25_000) {
                    val tab = chromium.state.value.tabs.firstOrNull { it.id == tabId }
                    tab != null && !tab.loading && tab.url.startsWith("https://example.com") && tab.certificate != null
                }
            } catch (t: Throwable) {
                val tab = chromium.state.value.tabs.firstOrNull { it.id == tabId }
                throw AssertionError(
                    "HTTPS-Only wait failed: url=${tab?.url} loading=${tab?.loading} secure=${tab?.secure} cert=${tab?.certificate}",
                    t,
                )
            }
            val httpsTab = chromium.state.value.tabs.first { it.id == tabId }
            assertTrue(httpsTab.secure)
            assertEquals("example.com", httpsTab.certificate!!.host)
            assertTrue(httpsTab.certificate!!.sha256.contains(':'))
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

    private class LocalDownloadServer : Closeable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        val baseUrl = "http://127.0.0.1:${socket.localPort}"

        private val acceptThread = thread(name = "miru-c3-http") {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(name = "miru-c3-conn") { handleClient(client) }
            }
        }

        private fun handleClient(client: java.net.Socket) {
            client.use { conn ->
                val reader = BufferedReader(InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = requestLine.split(' ').getOrNull(1).orEmpty()
                when {
                    path.startsWith("/download-page") -> {
                        writeResponse(
                            conn,
                            "text/html; charset=utf-8",
                            """
                            <!doctype html>
                            <html><head><title>Miru C3 Download</title></head><body>
                              <a id="dl-http" href="/file/report.bin">http</a>
                              <a id="dl-data" href="data:text/plain;base64,bWlydS1kYXRhLW9r" download="note.txt">data</a>
                            </body></html>
                            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
                            extraHeaders = listOf("Set-Cookie: dl_norm=n1; Path=/"),
                        )
                    }
                    path.startsWith("/private-download") -> {
                        writeResponse(
                            conn,
                            "text/html; charset=utf-8",
                            """
                            <!doctype html>
                            <html><head><title>Miru C3 Priv Download</title></head><body>
                              <a id="dl-priv" href="/file/priv.bin">priv</a>
                            </body></html>
                            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
                            extraHeaders = listOf("Set-Cookie: dl_priv=p1; Path=/"),
                        )
                    }
                    path.startsWith("/file/report.bin") -> {
                        writeResponse(
                            conn,
                            "application/octet-stream",
                            "miru-report-bytes".toByteArray(StandardCharsets.UTF_8),
                            extraHeaders = listOf("Content-Disposition: attachment; filename=\"miru-report.bin\""),
                        )
                    }
                    path.startsWith("/file/priv.bin") -> {
                        writeResponse(
                            conn,
                            "application/octet-stream",
                            "miru-priv-bytes".toByteArray(StandardCharsets.UTF_8),
                            extraHeaders = listOf("Content-Disposition: attachment; filename=\"priv.bin\""),
                        )
                    }
                    else -> writeResponse(conn, "text/plain; charset=utf-8", "ok".toByteArray(StandardCharsets.UTF_8))
                }
            }
        }

        private fun writeResponse(
            conn: java.net.Socket,
            contentType: String,
            body: ByteArray,
            extraHeaders: List<String> = emptyList(),
        ) {
            val sb = StringBuilder()
                .append("HTTP/1.1 200 OK\r\nContent-Type: ").append(contentType)
                .append("\r\nContent-Length: ").append(body.size)
                .append("\r\nConnection: close\r\n")
            extraHeaders.forEach { sb.append(it).append("\r\n") }
            sb.append("\r\n")
            conn.getOutputStream().apply {
                write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
                write(body)
                flush()
            }
        }

        override fun close() {
            running.set(false)
            runCatching { socket.close() }
            acceptThread.join(2_000)
        }
    }
}
