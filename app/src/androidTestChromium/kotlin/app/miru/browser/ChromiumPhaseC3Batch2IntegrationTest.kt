// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.browser.engine.BrowserContextMenuKind
import app.miru.browser.engine.BrowserEngineViews
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Live Chromium 159 C3.2 context menu, ReferrerPolicy isolated-world probe, and NotifyContextMenuClosed
 * multi-long-press verification on emulator/device.
 */
@RunWith(AndroidJUnit4::class)
class ChromiumPhaseC3Batch2IntegrationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = (compose.activity.application as MiruApplication).container
    private val browser get() = container.browser
    private val tabs = mutableListOf<String>()

    @Before
    fun dismissIntroAndAwaitReady() {
        val skip = compose.activity.getString(R.string.intro_skip)
        compose.waitUntil(15_000) {
            runBlocking { container.settings.settings.first().introCompleted } ||
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText(skip).performClick()
        }
        compose.waitUntil(30_000) { browser.state.value.ready }
    }

    @After
    fun cleanupTabs() {
        compose.runOnIdle {
            browser.contextMenus.dismiss(null)
            browser.closePrivateTabsPermanently()
            tabs.filter { id -> browser.state.value.tabs.any { it.id == id } }.forEach(browser::closeTab)
            tabs.clear()
        }
    }

    @Test
    fun longPressLinkAndImageRespectReferrerPolicyAndSubsequentLongPressesNeverDeadlock() {
        ContextMenuServer().use { server ->
            var id = ""
            compose.runOnIdle {
                id = browser.newTab("${server.url}/page", isPrivate = false)
                tabs += id
            }
            compose.waitUntil(20_000) {
                browser.state.value.tabs.firstOrNull { it.id == id }?.let {
                    !it.loading && it.title == "Context Ready"
                } == true
            }

            waitForPaintedRectColor(16f, 16f, 0x13, 0x57, 0x9b)
            longPressEngineDp(60f, 36f)
            compose.waitUntil(10_000) { browser.contextMenus.state.value?.kind == BrowserContextMenuKind.LINK }
            val linkMenu = checkNotNull(browser.contextMenus.state.value)
            assertEquals("${server.url}/target", linkMenu.linkUrl)
            compose.runOnIdle { browser.contextMenus.dismiss(linkMenu.token) }
            compose.waitUntil(3_000) { browser.contextMenus.state.value == null }

            // Second long press on image must still arrive after NotifyContextMenuClosed().
            longPressEngineDp(60f, 160f)
            compose.waitUntil(10_000) { browser.contextMenus.state.value?.kind == BrowserContextMenuKind.IMAGE }
            val imgMenu = checkNotNull(browser.contextMenus.state.value)
            assertEquals("${server.url}/pic.png", imgMenu.imageUrl)

            val policy = runBlocking { browser.contextImagePolicy(imgMenu) }
            assertNotNull(policy)
            assertEquals("no-referrer", policy!!.policy)
            val headers = runBlocking { browser.contextHeaders(imgMenu, "${server.url}/pic.png", policy) }
            assertNotNull(headers)
            assertFalse("no-referrer policy must strip Referer header", headers!!.keys.any { it.equals("Referer", true) })

            compose.runOnIdle { browser.contextMenus.dismiss(imgMenu.token) }
            assertNull(browser.contextMenus.state.value)
        }
    }

    private fun engineBounds(): Rect {
        var result: Rect? = null
        compose.runOnIdle {
            val view = checkNotNull(BrowserEngineViews.findIn(compose.activity.window.decorView)).asView()
            val xy = IntArray(2)
            view.getLocationOnScreen(xy)
            result = Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
        }
        return checkNotNull(result)
    }

    private fun waitForPaintedRectColor(dpX: Float, dpY: Float, r: Int, g: Int, b: Int) {
        val density = compose.activity.resources.displayMetrics.density
        compose.waitUntil(10_000) {
            val bounds = engineBounds()
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
            try {
                val x = (bounds.left + dpX * density).toInt()
                val y = (bounds.top + dpY * density).toInt()
                if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return@waitUntil false
                val pixel = bitmap.getPixel(x, y)
                kotlin.math.abs(Color.red(pixel) - r) < 12 &&
                    kotlin.math.abs(Color.green(pixel) - g) < 12 &&
                    kotlin.math.abs(Color.blue(pixel) - b) < 12
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun longPressEngineDp(dpX: Float, dpY: Float) {
        val density = compose.activity.resources.displayMetrics.density
        val bounds = engineBounds()
        val x = bounds.left + dpX * density
        val y = bounds.top + dpY * density
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true))
        } finally {
            down.recycle()
        }
        SystemClock.sleep(850)
        val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true))
        } finally {
            up.recycle()
        }
    }
}

private class ContextMenuServer : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url: String get() = "http://127.0.0.1:${server.localPort}"
    private val running = AtomicBoolean(true)
    private val png1x1 = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        Base64.DEFAULT,
    )

    init {
        thread(isDaemon = true, name = "miru-c32-ctx-server") {
            while (running.get()) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    socket.use {
                        runCatching {
                            it.soTimeout = 5000
                            val reader = it.getInputStream().bufferedReader()
                            val path = reader.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                            while (reader.readLine().isNotEmpty()) {}
                            val out = it.getOutputStream()
                            if (path.startsWith("/pic")) {
                                out.write(
                                    ("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png1x1.size}\r\nConnection: close\r\n\r\n")
                                        .toByteArray(),
                                )
                                out.write(png1x1)
                            } else {
                                val html = (
                                    "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>" +
                                        "<title>Context Ready</title>" +
                                        "<style>body{margin:0}a{display:block;width:260px;height:100px;background:#13579b;color:#fff}" +
                                        "img{display:block;width:260px;height:140px;margin-top:16px}</style>" +
                                        "<a href='/target'>Open link</a>" +
                                        "<img src='/pic.png' referrerpolicy='no-referrer' alt='Sample'>"
                                    ).toByteArray()
                                out.write(
                                    ("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n")
                                        .toByteArray(),
                                )
                                out.write(html)
                            }
                            out.flush()
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        running.set(false)
        server.close()
    }
}
