// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.miru.browser.engine.AdblockStatus
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A bundled default EasyList rule blocks a reachable loopback resource, then disabling uBO permits it. */
@RunWith(AndroidJUnit4::class)
class AdblockIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val browser get() = (compose.activity.application as MiruApplication).container.browser

    @Test fun signedBundledExtensionBlocksDefaultRuleAndGlobalToggleIsReal() {
        AdblockTestServer().use { server ->
            // A page opened while the add-on is still being installed must not silently become
            // an apparently protected zero-count page once installation finishes.
            compose.runOnIdle { browser.newTab("${server.origin}/page?early=1") }
            compose.waitUntil(30_000) { browser.state.value.selectedTab?.title?.startsWith("Miru default filter ") == true }
            val earlyPageWasAllowed = browser.state.value.selectedTab?.title == "Miru default filter allowed"
            compose.runOnIdle { browser.setAdblockEnabled(true) }
            // Gecko may await its remote add-on blocklist on a clean profile even for a local signed XPI.
            // This bounds test setup only; it does not extend the browser's 1.5-second page-loading gate.
            try { compose.waitUntil(300_000) { browser.adblock.value.status in setOf(AdblockStatus.READY, AdblockStatus.FAILED) } }
            catch (failure: Throwable) { throw AssertionError("Extension startup timed out: ${browser.adblock.value}; browserReady=${browser.state.value.ready}, error=${browser.state.value.error}", failure) }
            val installed = browser.adblock.value
            assertEquals("Bundled extension did not become ready: $installed", AdblockStatus.READY, installed.status)
            assertTrue(installed.installed)
            assertTrue("The signed extension must be allowed in private sessions", installed.privateAllowed)
            assertFalse(installed.version.isNullOrBlank())
            if(earlyPageWasAllowed) assertTrue("A page loaded before blocking was ready must retain its reload warning", installed.pageNeedsReload)

            val beforeProtectedPage = server.adRequests.get()
            compose.runOnIdle { browser.newTab("${server.origin}/page?blocked=1") }
            compose.waitUntil(30_000) { browser.state.value.selectedTab?.title?.startsWith("Miru default filter ") == true }
            if(browser.state.value.selectedTab?.title == "Miru default filter allowed") {
                // uBO deliberately permits traffic while first installation initializes its lists.
                // Its own '!' badge must be surfaced truthfully; this is not a no-leak startup assertion.
                compose.waitUntil(30_000) { browser.adblock.value.pageNeedsReload }
                android.util.Log.i("MiruM1Acceptance", "Initial installation page required reload before filtering applied")
                val deadline = SystemClock.elapsedRealtime() + 120_000
                var lastReload = 0L
                var requestsBeforeReload = server.adRequests.get()
                // '!' can be set before uBO has finished loading its lists. Observe the real
                // server on each retry instead of treating that warning as a filter-ready event.
                while(browser.state.value.selectedTab?.title != "Miru default filter blocked" || browser.adblock.value.blockedCount == 0) {
                    if(SystemClock.elapsedRealtime() >= deadline) throw AssertionError("uBO never finished filtering: ${browser.adblock.value}")
                    if(browser.state.value.selectedTab?.loading == false && SystemClock.elapsedRealtime() - lastReload >= 2_000) {
                        requestsBeforeReload = server.adRequests.get()
                        compose.runOnIdle { browser.reload() }
                        lastReload = SystemClock.elapsedRealtime()
                    }
                    SystemClock.sleep(100)
                }
                assertEquals("Reloaded blocked script must not reach its origin", requestsBeforeReload, server.adRequests.get())
            } else assertEquals("Blocked script must not reach its origin", beforeProtectedPage, server.adRequests.get())
            val blockedRequests = server.adRequests.get()
            try { compose.waitUntil(10_000) { browser.adblock.value.blockedCount > 0 } }
            catch (failure: Throwable) { throw AssertionError("Page blocked the script but badge did not arrive: ${browser.adblock.value}", failure) }

            compose.runOnIdle { browser.setAdblockEnabled(false) }
            compose.waitUntil(15_000) { browser.adblock.value.status == AdblockStatus.DISABLED }
            compose.runOnIdle { browser.newTab("${server.origin}/page?allowed=1") }
            compose.waitUntil(20_000) { browser.state.value.selectedTab?.title == "Miru default filter allowed" }
            assertTrue("Disabling the extension must allow the exact same script", server.adRequests.get() > blockedRequests)

            compose.runOnIdle { browser.setAdblockEnabled(true) }
            compose.waitUntil(20_000) { browser.adblock.value.status == AdblockStatus.READY }
            val before = server.adRequests.get()
            compose.runOnIdle { browser.newTab("${server.origin}/page?blocked=2") }
            compose.waitUntil(20_000) { browser.state.value.selectedTab?.title == "Miru default filter blocked" && browser.adblock.value.blockedCount > 0 }
            assertEquals(before,server.adRequests.get())

            compose.waitUntil(10_000) { browser.adblock.value.popupAvailable }
            compose.runOnIdle { assertTrue("The real extension action must open its popup session", browser.openAdblockPopup()) }
            compose.waitUntil(10_000) { browser.adblockPopup.value != null }
            // Session creation precedes Compose's sheet/recomposition and animation frames.
            // Assert the visible panel before capturing a screenshot or claiming UI coverage.
            compose.onNodeWithContentDescription(compose.activity.getString(app.miru.adblock.R.string.close_and_reload)).assertIsDisplayed()
            compose.waitForIdle()
            SystemClock.sleep(1_000)
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(instrumentation.targetContext.filesDir, "m1-ublock-popup.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            // Retain a shell-owned QA screenshot after Gradle uninstalls its test application.
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/miru-m1-ublock-popup.png")
            ).use { it.readBytes() }
            compose.runOnIdle { browser.closeAdblockPopup() }
        }
    }
}

private class AdblockTestServer : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val open = AtomicBoolean(true)
    val adRequests = AtomicInteger()
    val origin = "http://127.0.0.1:${server.localPort}"
    init {
        thread(isDaemon = true,name = "miru-adblock-test-origin") {
            while(open.get()) runCatching {
                server.accept().use { socket ->
                    socket.soTimeout=5_000
                    val reader=socket.getInputStream().bufferedReader()
                    val path=reader.readLine()?.split(' ')?.getOrNull(1)?.substringBefore('?').orEmpty()
                    while(!reader.readLine().isNullOrEmpty()) { }
                    val isAd=path=="/ads/custom_ads.js"
                    if(isAd) adRequests.incrementAndGet()
                    val body=if(isAd) "window.miruAdAllowed=true;" else """
                        <!doctype html><meta name="viewport" content="width=device-width"><title>Miru fixture loading</title>
                        <h1>Miru uBlock Origin fixture</h1><script src="/ads/custom_ads.js"></script>
                        <script>document.title=window.miruAdAllowed?'Miru default filter allowed':'Miru default filter blocked';</script>
                    """.trimIndent()
                    val data=body.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: ${if(isAd)"text/javascript" else "text/html"}; charset=utf-8\r\nContent-Length: ${data.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                        write(data); flush()
                    }
                }
            }
        }
    }
    override fun close() { open.set(false); server.close() }
}
