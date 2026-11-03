// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.graphics.Rect
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real web anchor clicks and a separate exported BROWSABLE test app; no injected load callbacks. */
@RunWith(AndroidJUnit4::class)
class ExternalLinksIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val browser get() = (context.applicationContext as MiruApplication).container.browser
    private fun label(id: Int) = context.getString(id)
    private fun title(id: String, expected: String, expectedUrl: String? = null) = compose.waitUntil(20000) {
        browser.state.value.tabs.firstOrNull { it.id == id }?.let {
            it.title == expected && !it.loading && (expectedUrl == null || it.url == expectedUrl)
        } == true
    }
    private fun page(origin: ExternalOrigin, path: String, isPrivate: Boolean = false): String {
        var id = ""
        compose.runOnIdle { id = browser.newTab(origin.url + path, isPrivate = isPrivate) }
        title(id, "External ready", origin.url + path)
        return id
    }
    private fun request() {
        compose.waitUntil(5000) { browser.externalLinks.prompt.value != null }
    }
    private fun cancel() = compose.onNodeWithText(label(android.R.string.cancel)).performClick()
    private fun externalText(): String? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        if(root.packageName?.toString() != "app.miru.browser.test") return null
        return root.findAccessibilityNodeInfosByText("Miru external fixture").firstOrNull()?.text?.toString()
    }

    @Test fun customSchemeRequiresConfirmationCancelStaysOnThePageAndOpenReturnsNormally() = fixture { origin ->
        val id = page(origin, "/custom")
        touchLink(); request()
        assertEquals("Miru link fixture", browser.externalLinks.prompt.value!!.appName)
        assertNull(externalText())
        cancel()
        assertEquals(id, browser.state.value.selectedTabId)
        touchLink(); request()
        compose.onNodeWithText(label(R.string.external_link_open)).performClick()
        compose.waitUntil(10000) { externalText()?.contains("uri=miru-link-fixture://launch/custom") == true }
        shellBack()
        compose.waitUntil(5000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName }
        assertEquals(id, browser.state.value.selectedTabId)
        assertNull(browser.externalLinks.prompt.value)
    }

    @Test fun intentUriDropsComponentsExtrasAndGrantsAndThePromptSurvivesRecreation() = fixture { origin ->
        page(origin, "/intent")
        touchLink(); request()
        compose.activityRule.scenario.recreate()
        request()
        compose.onNodeWithText(label(R.string.external_link_open)).performClick()
        compose.waitUntil(10000) { externalText() != null }
        val text = externalText()!!
        assertTrue(text.contains("uri=miru-link-fixture://launch/sanitized"))
        assertTrue(text.contains("action=android.intent.action.VIEW"))
        assertTrue(text.contains("browsable=true")); assertTrue(text.contains("grants=0"))
        assertTrue(text.contains("attack=false")); assertTrue(text.contains("fallback=false"))
        shellBack()
    }

    @Test fun missingAppShowsItsStateAndFallbackLoadsOnlyAfterTheUserChoosesIt() = fixture { origin ->
        val id = page(origin, "/missing")
        touchLink(); request()
        compose.onNodeWithText(label(R.string.external_link_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.external_link_open)).assertDoesNotExist()
        cancel()
        compose.runOnIdle { browser.navigate(origin.url + "/fallback-link") }
        title(id, "External ready", origin.url + "/fallback-link")
        touchLink(); request()
        assertFalse(browser.externalLinks.prompt.value!!.canOpenApp)
        assertTrue(browser.externalLinks.prompt.value!!.canOpenWeb)
        assertEquals(origin.url + "/fallback-link", browser.state.value.selectedTab?.url)
        compose.onNodeWithText(label(R.string.external_link_open_web)).performClick()
        title(id, "External fallback loaded")
    }

    @Test fun subframesCannotLaunchAndClosingAPrivateOwnerInvalidatesItsOldOpenAction() = fixture { origin ->
        page(origin, "/frame")
        SystemClock.sleep(500)
        assertNull(browser.externalLinks.prompt.value); assertNull(externalText())
        val id = page(origin, "/custom", isPrivate = true)
        touchLink(); request()
        compose.onNodeWithText(label(R.string.external_link_private)).assertIsDisplayed()
        val token = browser.externalLinks.prompt.value!!.token
        compose.runOnIdle { browser.closeTab(id) }
        compose.waitUntil(3000) { browser.externalLinks.prompt.value == null }
        compose.runOnIdle { assertFalse(browser.externalLinks.open(token, compose.activity)) }
        assertNull(externalText())
    }

    private fun touchLink() {
        val path = java.net.URI(browser.state.value.selectedTab!!.url).path
        val text = "Open fixture app $path"
        var bounds: Rect? = null
        fun find(node: AccessibilityNodeInfo?, depth: Int): Rect? {
            if(node == null || depth > 40) return null
            if((node.text?.toString().orEmpty() + node.contentDescription?.toString().orEmpty()).contains(text) && node.isVisibleToUser) {
                val rect = Rect(); node.getBoundsInScreen(rect)
                if(rect.width() > 0 && rect.height() > 0) return rect
            }
            for(index in 0 until node.childCount) find(node.getChild(index), depth + 1)?.let { return it }
            return null
        }
        try {
            compose.waitUntil(8000) {
                bounds = find(instrumentation.uiAutomation.rootInActiveWindow, 0)
                bounds != null
            }
        } catch (failure: ComposeTimeoutException) {
            if(browser.state.value.selectedTab?.isPrivate != true) {
                val suffix = path.substringAfterLast('/').replace(Regex("[^A-Za-z0-9-]"), "_")
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.getExternalFilesDir(null), "external-fixture-$suffix.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                val description = StringBuilder()
                fun describe(node: AccessibilityNodeInfo?, depth: Int) {
                    if(node == null || depth > 40 || description.length > 30000) return
                    val rect = Rect(); node.getBoundsInScreen(rect)
                    description.appendLine("${node.className} $rect text=${node.text} description=${node.contentDescription}")
                    for(index in 0 until node.childCount) describe(node.getChild(index), depth + 1)
                }
                describe(instrumentation.uiAutomation.rootInActiveWindow, 0)
                File(context.getExternalFilesDir(null), "external-fixture-$suffix.txt").writeText(description.toString())
            }
            throw failure
        }
        val target = checkNotNull(bounds)
        val down = SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, target.exactCenterX(), target.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            if(action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60)
        }
    }
    private fun shellBack() {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 4")).bufferedReader().use { it.readText() }
        compose.waitUntil(5000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName }
    }
    private fun fixture(block: (ExternalOrigin) -> Unit) {
        compose.waitUntil(30000) { browser.state.value.ready }
        val tabs = BrowserTabFixture(); compose.runOnIdle { tabs.capture(browser) }
        ExternalOrigin().use { origin ->
            try { block(origin) }
            finally {
                if(externalText() != null) shellBack()
                compose.runOnIdle { browser.externalLinks.prompt.value?.let { browser.externalLinks.dismiss(it.token) }; tabs.closeCreatedTabs(browser) }
            }
        }
    }
}

private class ExternalOrigin : Closeable {
    private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    val url get() = "http://127.0.0.1:${server.localPort}"
    private val running = AtomicBoolean(true)
    init { thread(isDaemon = true) { while(running.get()) {
        val socket = try { server.accept() } catch (_: Exception) { break }
        thread(isDaemon = true) { socket.use { try {
            it.soTimeout = 5000
            val reader = it.getInputStream().bufferedReader(); val path = reader.readLine().split(' ')[1]
            while(reader.readLine().isNotEmpty()) { }
            val fallback = URLEncoder.encode("$url/fallback", "UTF-8")
            val link = when(path) {
                "/intent" -> "intent://launch/sanitized#Intent;scheme=miru-link-fixture;package=app.miru.browser.test;component=app.miru.browser/app.miru.browser.UploadPickerActivity;action=android.intent.action.DELETE;launchFlags=0x10000003;S.attack=blocked;S.browser_fallback_url=$fallback;end"
                "/missing" -> "miru-no-installed-fixture://launch/missing"
                "/fallback-link" -> "intent://launch/missing#Intent;scheme=miru-no-installed-fixture;package=not.installed.fixture;S.browser_fallback_url=$fallback;end"
                else -> "miru-link-fixture://launch/custom"
            }
            val body = if(path == "/fallback") "<title>External fallback loaded</title><h1>Fallback webpage</h1>" else {
                val content = if(path == "/frame") "<iframe src=\"miru-link-fixture://launch/frame\"></iframe>" else
                    "<a href=\"${link.replace("&", "&amp;").replace("\"", "&quot;")}\">Open fixture app $path</a>"
                val color = "#%06x".format(externalButtonColor(path) and 0xffffff)
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>body{margin:0}a{display:block;width:280px;height:100px;background:$color;color:white}</style>$content<script>requestAnimationFrame(()=>requestAnimationFrame(()=>setTimeout(()=>{document.title='External ready';},250)));</script>"
            }
            val bytes = ("<!doctype html>" + body).toByteArray()
            it.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush() }
        } catch (_: Exception) { } } }
    } } }
    override fun close() { running.set(false); server.close() }
}

private fun externalButtonColor(path: String): Int = when(path) {
    "/fallback-link" -> Color.rgb(0x13, 0x57, 0x9b)
    "/custom" -> Color.rgb(0x39, 0x75, 0x43)
    "/intent" -> Color.rgb(0x57, 0x41, 0x84)
    else -> Color.rgb(0x24, 0x5a, 0x7c)
}
