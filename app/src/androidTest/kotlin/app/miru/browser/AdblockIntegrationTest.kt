// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.os.SystemClock
import android.net.ConnectivityManager
import android.provider.Settings
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.browser.engine.AdblockStatus
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
        val testStarted = SystemClock.elapsedRealtime()
        val requireNoNetwork = InstrumentationRegistry.getArguments().getString("miru.requireNoNetwork") == "true"
        Log.i("MiruM1Acceptance", "uBO test started wallMs=${System.currentTimeMillis()} elapsedMs=$testStarted pid=${android.os.Process.myPid()} requireNoNetwork=$requireNoNetwork")
        if(requireNoNetwork) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            assertEquals("The explicit offline install run must have airplane mode enabled", 1,
                Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0))
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            assertNull("The explicit offline install run must have no active network", connectivity.activeNetwork)
            Log.i("MiruM1Acceptance", "uBO offline preconditions verified airplaneMode=1 activeNetwork=null elapsedSinceTestMs=${SystemClock.elapsedRealtime() - testStarted}")
        }
        AdblockTestServer().use { server ->
            // A page opened while the add-on is still being installed must not silently become
            // an apparently protected zero-count page once installation finishes.
            val early = server.page("early")
            val earlyTab = openPage(early, 30_000)
            val earlyPageWasAllowed = selectedPage(earlyTab, early, early.allowedTitle)
            compose.runOnIdle { browser.setAdblockEnabled(true) }
            // Gecko may await its remote add-on blocklist on a clean profile even for a local signed XPI.
            // This bounds test setup only; it does not extend the browser's 1.5-second page-loading gate.
            val readyWaitStarted = SystemClock.elapsedRealtime()
            Log.i("MiruM1Acceptance", "uBO READY wait started elapsedMs=$readyWaitStarted elapsedSinceTestMs=${readyWaitStarted - testStarted} status=${browser.adblock.value.status}")
            try { compose.waitUntil(300_000) { browser.adblock.value.status in setOf(AdblockStatus.READY, AdblockStatus.FAILED) } }
            catch (failure: Throwable) { throw AssertionError("Extension startup timed out: ${browser.adblock.value}; browserReady=${browser.state.value.ready}, error=${browser.state.value.error}", failure) }
            finally { Log.i("MiruM1Acceptance", "uBO READY wait finished waitedMs=${SystemClock.elapsedRealtime() - readyWaitStarted} elapsedSinceTestMs=${SystemClock.elapsedRealtime() - testStarted} status=${browser.adblock.value.status}") }
            val installed = browser.adblock.value
            assertEquals("Bundled extension did not become ready: $installed", AdblockStatus.READY, installed.status)
            assertTrue(installed.installed)
            assertTrue("The signed extension must be allowed in private sessions", installed.privateAllowed)
            assertFalse(installed.version.isNullOrBlank())
            if(earlyPageWasAllowed) assertTrue("A page loaded before blocking was ready must retain its reload warning", installed.pageNeedsReload)

            val protectedPage = server.page("protected-first")
            val beforeProtectedPage = server.adRequests(protectedPage)
            val protectedTab = openPage(protectedPage, 30_000)
            if(selectedPage(protectedTab, protectedPage, protectedPage.allowedTitle)) {
                // uBO deliberately permits traffic while first installation initializes its lists.
                // Its own '!' badge must be surfaced truthfully; this is not a no-leak startup assertion.
                compose.waitUntil(30_000) { browser.adblock.value.pageNeedsReload }
                android.util.Log.i("MiruM1Acceptance", "Initial installation page required reload before filtering applied")
                val deadline = SystemClock.elapsedRealtime() + 120_000
                var lastReload = 0L
                var requestsBeforeReload = server.adRequests(protectedPage)
                // '!' can be set before uBO has finished loading its lists. Observe the real
                // server on each retry instead of treating that warning as a filter-ready event.
                while(!selectedPage(protectedTab, protectedPage, protectedPage.blockedTitle) || browser.adblock.value.blockedCount == 0) {
                    if(SystemClock.elapsedRealtime() >= deadline) throw AssertionError("uBO never finished filtering: ${browser.adblock.value}")
                    if(browser.state.value.selectedTab?.loading == false && SystemClock.elapsedRealtime() - lastReload >= 2_000) {
                        requestsBeforeReload = server.adRequests(protectedPage)
                        compose.runOnIdle { browser.reload() }
                        lastReload = SystemClock.elapsedRealtime()
                    }
                    SystemClock.sleep(100)
                }
                assertEquals("Reloaded blocked script must not reach its own case origin", requestsBeforeReload, server.adRequests(protectedPage))
            } else assertEquals("Blocked script must not reach its own case origin", beforeProtectedPage, server.adRequests(protectedPage))
            try { compose.waitUntil(10_000) { selectedPage(protectedTab, protectedPage, protectedPage.blockedTitle) && browser.adblock.value.blockedCount > 0 } }
            catch (failure: Throwable) { throw AssertionError("Page blocked the script but badge did not arrive: ${browser.adblock.value}", failure) }

            compose.runOnIdle { browser.setAdblockEnabled(false) }
            compose.waitUntil(15_000) { browser.adblock.value.status == AdblockStatus.DISABLED }
            val allowed = server.page("disabled")
            val beforeAllowed = server.adRequests(allowed)
            val allowedTab = openPage(allowed, 20_000)
            assertTrue("The disabled case must finish on its own selected page", selectedPage(allowedTab, allowed, allowed.allowedTitle))
            assertTrue("Disabling the extension must allow the exact same script path", server.adRequests(allowed) > beforeAllowed)

            compose.runOnIdle { browser.setAdblockEnabled(true) }
            compose.waitUntil(20_000) { browser.adblock.value.status == AdblockStatus.READY }
            val reenabled = server.page("reenabled")
            val before = server.adRequests(reenabled)
            var reenabledTab = ""
            compose.runOnIdle { reenabledTab = browser.newTab(reenabled.url) }
            compose.waitUntil(20_000) { selectedPage(reenabledTab, reenabled, reenabled.blockedTitle) && browser.adblock.value.blockedCount > 0 }
            assertEquals("Reenabled script must not reach its own case origin", before, server.adRequests(reenabled))

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

    private fun openPage(page: AdblockTestPage, timeout: Long): String {
        var id = ""
        compose.runOnIdle { id = browser.newTab(page.url) }
        compose.waitUntil(timeout) { selectedPage(id, page) }
        return id
    }

    private fun selectedPage(id: String, page: AdblockTestPage, title: String? = null): Boolean {
        val selected = browser.state.value.selectedTab ?: return false
        return selected.id == id && selected.url == page.url && if(title == null) {
            selected.title == page.blockedTitle || selected.title == page.allowedTitle
        } else selected.title == title
    }
}

private data class AdblockTestPage(val token: String, val url: String) {
    val allowedTitle get() = "Miru default filter $token allowed"
    val blockedTitle get() = "Miru default filter $token blocked"
}

private class AdblockTestServer : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val open = AtomicBoolean(true)
    private val requests = ConcurrentHashMap<String, AtomicInteger>()
    val origin = "http://127.0.0.1:${server.localPort}"
    fun page(phase: String): AdblockTestPage {
        val token = "$phase-${UUID.randomUUID()}"
        requests[token] = AtomicInteger()
        return AdblockTestPage(token, "$origin/page?case=$token")
    }
    fun adRequests(page: AdblockTestPage): Int = requests.getValue(page.token).get()
    init {
        thread(isDaemon = true,name = "miru-adblock-test-origin") {
            while(open.get()) runCatching {
                server.accept().use { socket ->
                    socket.soTimeout=5_000
                    val reader=socket.getInputStream().bufferedReader()
                    val target=reader.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                    val uri = URI(target)
                    val path = uri.path.orEmpty()
                    val token = uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("case=") }?.removePrefix("case=")
                        ?.takeIf { it.matches(Regex("[A-Za-z0-9-]+")) }.orEmpty()
                    while(!reader.readLine().isNullOrEmpty()) { }
                    val isAd=path=="/ads/custom_ads.js"
                    if(isAd) requests.computeIfAbsent(token) { AtomicInteger() }.incrementAndGet()
                    val body=if(isAd) "window.miruAdAllowedToken='$token';" else """
                        <!doctype html><meta name="viewport" content="width=device-width"><title>Miru fixture loading $token</title>
                        <h1>Miru uBlock Origin fixture</h1><script src="/ads/custom_ads.js?case=$token"></script>
                        <script>document.title=window.miruAdAllowedToken==='$token'?'Miru default filter $token allowed':'Miru default filter $token blocked';</script>
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
