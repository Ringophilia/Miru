// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.miru.data.ToolbarPosition
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class BrowserLoadingProgressIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as MiruApplication).container
    private fun toolbar() = compose.onNodeWithTag("browser-viewport-toolbar")
    private fun progress() = compose.onNodeWithTag("toolbar-page-progress")

    @Test fun actualWebLoadsShowTwoDpAtTheLowerEdgeInBothToolbarPositions() {
        val skip = compose.activity.getString(R.string.intro_skip)
        compose.waitUntil(15_000) {
            runBlocking { container.settings.settings.first().introCompleted } ||
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText(skip).performClick()
        compose.waitUntil(30_000) { container.browser.state.value.ready }
        val originalPosition = runBlocking { container.settings.settings.first().toolbarPosition }
        val tabs = BrowserTabFixture()
        compose.runOnIdle { tabs.capture(container.browser) }
        try {
            for (position in ToolbarPosition.entries) {
                runBlocking { container.settings.setToolbarPosition(position) }
                HeldPage().use { page ->
                    compose.runOnIdle { container.browser.newTab(page.url) }
                    compose.waitUntil(15_000) {
                        val tab = container.browser.state.value.selectedTab
                        page.requested.count == 0L && tab?.loading == true &&
                            compose.onAllNodesWithTag("toolbar-page-progress").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.waitUntil(5_000) {
                        val center = compose.onNodeWithTag("browser-viewport").fetchSemanticsNode().boundsInRoot.center.y
                        val top = toolbar().fetchSemanticsNode().boundsInRoot.center.y < center
                        top == (position == ToolbarPosition.TOP)
                    }
                    val toolbarBounds = toolbar().fetchSemanticsNode().boundsInRoot
                    progress().assertIsDisplayed()
                    val loadingPixels = toolbar().captureToImage().toPixelMap()
                    page.finish()
                    compose.waitUntil(15_000) { container.browser.state.value.selectedTab?.let { it.title == "Held loading page" && !it.loading } == true }
                    compose.waitUntil(5_000) { compose.onAllNodesWithTag("toolbar-page-progress").fetchSemanticsNodes().isEmpty() }
                    assertEquals(toolbarBounds.height, toolbar().fetchSemanticsNode().boundsInRoot.height, 1f)
                    val settledPixels = toolbar().captureToImage().toPixelMap()
                    val trackHeight = (2 * compose.activity.resources.displayMetrics.density).roundToInt()
                    val bottom = loadingPixels.height - 1
                    for (x in listOf(loadingPixels.width / 20, loadingPixels.width * 19 / 20)) {
                        val drawnRows = (bottom - trackHeight * 2..bottom).count { y -> loadingPixels[x, y] != settledPixels[x, y] }
                        assertEquals("Painted thickness at x=$x ($position)", trackHeight.toFloat(), drawnRows.toFloat(), 1f)
                        assertNotEquals("The bottom row should be painted ($position)", settledPixels[x, bottom], loadingPixels[x, bottom])
                    }
                }
            }
        } finally {
            runBlocking { container.settings.setToolbarPosition(originalPosition) }
            compose.runOnIdle { tabs.closeCreatedTabs(container.browser) }
        }
    }
}

private class HeldPage : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val release = CountDownLatch(1)
    val requested = CountDownLatch(1)
    val url = "http://127.0.0.1:${server.localPort}/page"

    init {
        thread(isDaemon = true, name = "miru-held-loading-page") {
            while (!server.isClosed) runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    val firstLine = reader.readLine() ?: return@use
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    if (!firstLine.startsWith("GET /page ")) return@use
                    requested.countDown()
                    if (!release.await(20, TimeUnit.SECONDS)) return@use
                    val body = "<title>Held loading page</title><p>Fixture</p>".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
    }

    fun finish() = release.countDown()
    override fun close() { finish(); server.close() }
}
