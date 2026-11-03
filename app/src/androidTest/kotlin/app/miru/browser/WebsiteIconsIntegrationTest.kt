// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
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

/** Local page-declared icons through Gecko, shared list UI, and private-cache isolation. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class WebsiteIconsIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as MiruApplication).container

    @Test fun pageDeclaredIconAppearsInHistoryAndCanBeLoadedWithoutAnotherDownload() = fixture { origin ->
        val url = origin.url + "/page"
        compose.runOnIdle { container.browser.newTab(url) }
        compose.waitUntil(20000) { container.browser.state.value.selectedTab?.icon?.let { isGreen(it) } == true }
        val count = origin.icons
        assertTrue(isGreen(runBlocking { container.browser.websiteIcons.load(url) }!!))
        assertEquals(count, origin.icons)
        // Favicon discovery and history recording are independent engine callbacks.
        compose.waitUntil(5000) { runBlocking { container.library.history.search(url, 1) }.any { it.url == url } }
        // Open the actual full-screen history route and inspect the rendered icon, not a fake UI.
        compose.runOnIdle { container.browser.newTab() }
        compose.onAllNodesWithText(compose.activity.getString(app.miru.ntp.R.string.ntp_search_hint))[1].performClick()
        compose.onNode(hasSetTextAction()).performTextInput("miru://history")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNodeWithContentDescription(compose.activity.getString(app.miru.history.R.string.history_search)).performTextInput(url)
        try {
            compose.waitUntil(5000) { compose.onAllNodesWithTag("site-icon:$url", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: ComposeTimeoutException) {
            val recorded = runBlocking { container.library.history.search(url, 1) }.any { it.url == url }
            val titleVisible = compose.onAllNodesWithText("Icon local page").fetchSemanticsNodes().isNotEmpty()
            val pageIcon = container.browser.state.value.tabs.any { it.url == url && it.icon != null }
            throw AssertionError("Owned history recorded=$recorded titleVisible=$titleVisible pageIcon=$pageIcon", timeout)
        }
        val pixels = compose.onNodeWithTag("site-icon:$url", useUnmergedTree = true).captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue(center.green > 0.7f && center.red < 0.2f)
    }

    @Test fun privateIconDoesNotSeedNormalCacheAndMissingIconFallsBack() = fixture { origin ->
        val url = origin.url + "/private"
        compose.runOnIdle { container.browser.newTab(url, isPrivate = true) }
        compose.waitUntil(20000) { container.browser.state.value.selectedTab?.icon?.let { isGreen(it) } == true }
        compose.runOnIdle { container.browser.closeTab(container.browser.state.value.selectedTabId!!) }
        // The declared private icon lives at /green.png. Normal lookup must not remember that mapping.
        assertNull(runBlocking { container.browser.websiteIcons.load(url) })
        assertNull(runBlocking { container.browser.websiteIcons.load("about:newtab") })
    }

    private fun isGreen(bitmap: Bitmap) = Color.green(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 200 &&
        Color.red(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) < 30

    private fun fixture(block: (IconOrigin) -> Unit) {
        compose.waitUntil(30000) { container.browser.state.value.ready }
        val tabs = BrowserTabFixture()
        compose.runOnIdle { tabs.capture(container.browser); container.browser.websiteIcons.clearMemory() }
        IconOrigin().use { origin ->
            try { block(origin) }
            finally {
                compose.runOnIdle { tabs.closeCreatedTabs(container.browser); container.browser.websiteIcons.clearMemory() }
                runBlocking { container.library.history.delete(origin.url + "/page") }
            }
        }
    }
}

private class IconOrigin : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val url get() = "http://127.0.0.1:${server.localPort}"
    @Volatile var icons = 0
    private val running = AtomicBoolean(true)
    private val png = ByteArrayOutputStream().apply {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(0, 230, 0)); bitmap.compress(Bitmap.CompressFormat.PNG, 100, this); bitmap.recycle()
    }.toByteArray()
    init { thread(isDaemon = true) { while (running.get()) {
        val socket = try { server.accept() } catch (_: Exception) { break }
        thread(isDaemon = true) { socket.use {
            try {
                it.soTimeout = 5000
                val reader = it.getInputStream().bufferedReader()
                val path = reader.readLine().split(' ')[1]
                while (reader.readLine().isNotEmpty()) { }
                val image = path == "/green.png"
                val missing = path == "/favicon.ico"
                if (image) icons++
                val bytes = if (image) png else "<!doctype html><title>Icon local page</title><link rel=\"icon\" href=\"/green.png\"><h1>Website icon</h1>".toByteArray()
                it.getOutputStream().apply {
                    write("HTTP/1.1 ${if (missing) "404 Not Found" else "200 OK"}\r\nContent-Type: ${if (image) "image/png" else "text/html"}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
            } catch (_: Exception) { }
        } }
    } } }
    override fun close() { running.set(false); server.close() }
}
