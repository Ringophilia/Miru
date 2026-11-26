// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.browser.engine.AdblockBackendKind
import app.miru.browser.engine.ChromiumEngineController
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Live Chromium 159 Phase C2 integration verification on emulator/device:
 * - Zero-config cold-start bundled `adblock-rust` blocking + site toggle + custom rules + picker activation
 * - C++ network-layer sub-iframe video sniffing + DOM metadata/pause handoff + profile-isolated cookies + OTR cleanup
 */
@RunWith(AndroidJUnit4::class)
class ChromiumPhaseC2IntegrationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val container get() = (compose.activity.application as MiruApplication).container
    private val chromium get() = container.browser as ChromiumEngineController
    private val createdTabIds = mutableListOf<String>()

    @Before
    fun waitUntilEngineReady() {
        compose.waitUntil(30_000) { chromium.state.value.ready }
        compose.runOnIdle { chromium.restoreDefaultAdblockRules() }
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
    fun coldStartDefaultRulesSiteToggleAndCustomRule() {
        val mp4Bytes = loadFixtureMp4()
        LocalPhaseC2Server(mp4Bytes).use { server ->
            compose.runOnIdle {
                chromium.setAdblockEnabled(true)
                chromium.setSiteAdblockEnabled("127.0.0.1", true)
                createdTabIds += chromium.newTab("${server.baseUrl}/ads", isPrivate = false)
            }

            compose.waitUntil(25_000) {
                val tab = chromium.state.value.selectedTab
                tab != null && !tab.loading && tab.title == "Miru C2 Ads"
            }

            assertTrue(chromium.adblock.value.nativeControlsAvailable)
            assertEquals(AdblockBackendKind.NATIVE_RUST, chromium.adblock.value.backend)
            assertEquals("adblock-rust", chromium.adblock.value.version)
            assertTrue(chromium.nativeAdblock.value.filterLists.size == 4)
            assertEquals(0, server.pageAdHits.get())
            assertEquals("false", evalJsSync("String(!!window.__pageAdLoaded)").trim('"'))
            waitForStyleDisplay("#ad-slot", "none")

            // Site allowlist OFF -> reload allows script + unhides cosmetic ad slot.
            compose.runOnIdle {
                assertTrue(chromium.setSiteAdblockEnabled("127.0.0.1", false))
                chromium.reload()
            }
            compose.waitUntil(25_000) {
                val tab = chromium.state.value.selectedTab
                server.pageAdHits.get() >= 1 && tab != null && !tab.loading && tab.title == "Miru C2 Ads"
            }
            waitForStyleDisplay("#ad-slot", "block")

            // Site allowlist back ON -> reload blocks script again.
            val hitsBefore = server.pageAdHits.get()
            compose.runOnIdle {
                assertTrue(chromium.setSiteAdblockEnabled("127.0.0.1", true))
                chromium.reload()
            }
            compose.waitUntil(25_000) {
                val tab = chromium.state.value.selectedTab
                tab != null && !tab.loading && tab.title == "Miru C2 Ads"
            }
            waitForStyleDisplay("#ad-slot", "none")
            assertEquals(hitsBefore, server.pageAdHits.get())

            // Picker activation & custom selector rule hides #custom-banner.
            compose.runOnIdle {
                assertTrue(chromium.startAdblockElementPicker())
            }
            assertTrue(chromium.nativeAdblock.value.pickerActive)
            compose.runOnIdle {
                assertTrue(chromium.addAdblockCustomRule("127.0.0.1###custom-banner"))
                chromium.reload()
            }
            compose.waitUntil(25_000) {
                val tab = chromium.state.value.selectedTab
                tab != null && !tab.loading && tab.title == "Miru C2 Ads"
            }
            waitForStyleDisplay("#custom-banner", "none")
            assertTrue(chromium.nativeAdblock.value.customRules.contains("127.0.0.1###custom-banner"))
        }
    }

    @Test
    fun cppNetworkSubIframeVideoSniffingCookiesPrepareAndOtrIsolation() {
        val mp4Bytes = loadFixtureMp4()
        LocalPhaseC2Server(mp4Bytes).use { server ->
            var normalId = ""
            compose.runOnIdle {
                normalId = chromium.newTab("${server.baseUrl}/video-page", isPrivate = false)
                createdTabIds += normalId
            }

            try {
                compose.waitUntil(25_000) {
                    val items = chromium.media.value[normalId].orEmpty()
                    items.any { it.playableVideo && it.url.contains("/video/fixture.mp4") && it.duration > 0.0 && it.position >= 1.0 }
                }
            } catch (t: Throwable) {
                val items = chromium.media.value[normalId].orEmpty()
                val detail = items.joinToString(" | ") {
                    "url=${it.url} kind=${it.kind} dur=${it.duration} pos=${it.position} playing=${it.playing} w=${it.width} h=${it.height} hdrs=${it.headers}"
                }
                val domDiag = runCatching {
                    evalJsSync(
                        "(() => { const f = document.querySelector('iframe'); const v = f?.contentDocument?.getElementById('v'); " +
                            "return v ? JSON.stringify({rs:v.readyState,ns:v.networkState,err:v.error?.code||0,dur:v.duration,ct:v.currentTime,paused:v.paused,seeking:v.seeking}) : 'no-video'; })()",
                    )
                }.getOrElse { "eval-err:${it.message}" }
                val tab = chromium.state.value.selectedTab
                throw AssertionError(
                    "Timed out waiting for subframe video with position>=1.0; items=[$detail], dom=$domDiag, capturedVideoReqs=${server.capturedVideoHeaders.size}, tab=$tab",
                    t,
                )
            }

            val video = chromium.media.value[normalId]!!.first { it.url.contains("/video/fixture.mp4") }
            assertEquals("VIDEO", video.kind)
            assertFalse(video.isPrivate)
            assertTrue(video.headers["Referer"].orEmpty().contains("/embed"))

            val prepared = runBlocking { chromium.prepareMedia(normalId, video.id) }
            assertNotNull(prepared)
            assertTrue("Expected non-negative resume position, got ${prepared!!.positionMs}", prepared.positionMs >= 1_000L)
            assertTrue("Expected Chrome/159.0 User-Agent", prepared.headers["User-Agent"].orEmpty().contains("Chrome/159.0"))
            assertTrue("Expected normal session Cookie in prepared headers", prepared.headers["Cookie"].orEmpty().contains("miru_sess=ok_1"))

            compose.runOnIdle { chromium.restoreWebMedia(normalId) }

            // OTR private video tab sets private-only cookie and remains strictly isolated from normal profile.
            var privateId = ""
            compose.runOnIdle {
                privateId = chromium.newTab("${server.baseUrl}/private-video", isPrivate = true)
            }
            compose.waitUntil(25_000) {
                chromium.media.value[privateId].orEmpty().any { it.url.contains("/video/private.mp4") }
            }
            val privMedia = chromium.media.value[privateId]!!.first { it.url.contains("/video/private.mp4") }
            assertTrue(privMedia.isPrivate)

            val privHeaders = runBlocking { chromium.headersForMediaUrl(privMedia.url, isPrivate = true) }
            val normalHeaders = runBlocking { chromium.headersForMediaUrl(privMedia.url, isPrivate = false) }
            assertTrue("Private profile must retain OTR cookie", privHeaders["Cookie"].orEmpty().contains("miru_priv=otr_only"))
            assertFalse("Normal profile must never see OTR cookie", normalHeaders["Cookie"].orEmpty().contains("miru_priv=otr_only"))

            compose.runOnIdle { chromium.closePrivateTabsPermanently() }
            assertTrue(chromium.media.value[privateId].isNullOrEmpty())
        }
    }

    private fun waitForStyleDisplay(selector: String, expected: String) {
        compose.waitUntil(15_000) {
            evalJsSync("getComputedStyle(document.querySelector('$selector')).display")
                .trim('"') == expected
        }
    }

    private fun evalJsSync(script: String): String {
        val latch = CountDownLatch(1)
        val out = AtomicReference("")
        compose.runOnIdle {
            chromium.evaluateJavaScript(null, script) { res ->
                out.set(res)
                latch.countDown()
            }
        }
        assertTrue("JS evaluation timed out", latch.await(10, TimeUnit.SECONDS))
        return out.get()
    }

    private fun loadFixtureMp4(): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("media/fixture.mp4").use { it.readBytes() }

    private class LocalPhaseC2Server(private val mp4Bytes: ByteArray) : Closeable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        val pageAdHits = AtomicInteger(0)
        val capturedVideoHeaders = CopyOnWriteArrayList<Map<String, String>>()
        val baseUrl = "http://127.0.0.1:${socket.localPort}"

        private val acceptThread = thread(name = "miru-c2-http") {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(name = "miru-c2-conn") { handleClient(client) }
            }
        }

        private fun handleClient(client: java.net.Socket) {
            client.use { conn ->
                val reader = BufferedReader(InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
                val path = requestLine.split(' ').getOrNull(1).orEmpty()
                when {
                    path.startsWith("/pagead.js") -> {
                        pageAdHits.incrementAndGet()
                        writeText(conn, "application/javascript; charset=utf-8", "window.__pageAdLoaded = true;")
                    }
                    path.startsWith("/ads-ok.js") -> {
                        writeText(conn, "application/javascript; charset=utf-8", "document.title = 'Miru C2 Ads';")
                    }
                    path.startsWith("/ads") -> {
                        writeText(
                            conn,
                            "text/html; charset=utf-8",
                            """
                            <!doctype html>
                            <html>
                              <head><meta charset="utf-8"><title>Pending</title></head>
                              <body>
                                <div id="ad-slot" style="width:100px;height:40px;">ad</div>
                                <div id="custom-banner" style="width:120px;height:40px;">custom</div>
                                <script src="/pagead.js"></script>
                                <script src="/ads-ok.js"></script>
                              </body>
                            </html>
                            """.trimIndent(),
                        )
                    }
                    path.startsWith("/video/fixture.mp4") || path.startsWith("/video/private.mp4") -> {
                        capturedVideoHeaders += headers
                        writeMedia(conn, headers["range"])
                    }
                    path.startsWith("/embed") -> {
                        writeText(
                            conn,
                            "text/html; charset=utf-8",
                            """
                            <!doctype html>
                            <html><body style="margin:0">
                              <video id="v" src="/video/fixture.mp4" autoplay muted playsinline preload="auto" controls style="width:320px;height:180px;"></video>
                              <script>
                                const v = document.getElementById('v');
                                const seekToTwo = () => {
                                  if (v.currentTime < 2.0) v.currentTime = 2.0;
                                  v.play().catch(() => {});
                                };
                                v.addEventListener('loadedmetadata', seekToTwo);
                                v.addEventListener('canplay', seekToTwo, { once: true });
                                if (v.readyState >= 1) seekToTwo();
                              </script>
                            </body></html>
                            """.trimIndent(),
                        )
                    }
                    path.startsWith("/video-page") -> {
                        writeText(
                            conn,
                            "text/html; charset=utf-8",
                            """<!doctype html><html><head><title>Miru Video</title></head><body><iframe src="/embed" style="width:340px;height:200px;"></iframe></body></html>""",
                            extraHeaders = listOf("Set-Cookie: miru_sess=ok_1; Path=/"),
                        )
                    }
                    path.startsWith("/private-video") -> {
                        writeText(
                            conn,
                            "text/html; charset=utf-8",
                            """<!doctype html><html><head><title>Miru Private Video</title></head><body><video src="/video/private.mp4" playsinline muted controls style="width:320px;height:180px;"></video></body></html>""",
                            extraHeaders = listOf("Set-Cookie: miru_priv=otr_only; Path=/"),
                        )
                    }
                    else -> writeText(conn, "text/plain; charset=utf-8", "ok")
                }
            }
        }

        private fun writeText(conn: java.net.Socket, contentType: String, body: String, extraHeaders: List<String> = emptyList()) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val sb = StringBuilder()
                .append("HTTP/1.1 200 OK\r\nContent-Type: ").append(contentType)
                .append("\r\nContent-Length: ").append(bytes.size)
                .append("\r\nConnection: close\r\n")
            extraHeaders.forEach { sb.append(it).append("\r\n") }
            sb.append("\r\n")
            conn.getOutputStream().apply {
                write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
                write(bytes)
                flush()
            }
        }

        private fun writeMedia(conn: java.net.Socket, rangeHeader: String?) {
            val total = mp4Bytes.size
            val match = Regex("bytes=(\\d+)-(\\d*)").find(rangeHeader.orEmpty())
            if (match != null) {
                val start = match.groupValues[1].toInt().coerceIn(0, total - 1)
                val end = match.groupValues[2].toIntOrNull()?.coerceIn(start, total - 1) ?: (total - 1)
                val sliceLen = end - start + 1
                val header = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\n" +
                    "Content-Range: bytes $start-$end/$total\r\nContent-Length: $sliceLen\r\nConnection: close\r\n\r\n"
                conn.getOutputStream().apply {
                    write(header.toByteArray(StandardCharsets.US_ASCII))
                    write(mp4Bytes, start, sliceLen)
                    flush()
                }
            } else {
                val header = "HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\n" +
                    "Content-Length: $total\r\nConnection: close\r\n\r\n"
                conn.getOutputStream().apply {
                    write(header.toByteArray(StandardCharsets.US_ASCII))
                    write(mp4Bytes)
                    flush()
                }
            }
        }

        override fun close() {
            running.set(false)
            runCatching { socket.close() }
            acceptThread.join(2_000)
        }
    }
}
