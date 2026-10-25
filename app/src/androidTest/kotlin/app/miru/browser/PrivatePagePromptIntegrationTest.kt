// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.browser.engine.BrowserPromptButton
import app.miru.browser.engine.BrowserPromptKind
import app.miru.browser.engine.BrowserPromptState
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import mozilla.components.concept.engine.EngineView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated fresh AVD only: preserves ordinary tabs and never screenshots protected content. */
@RunWith(AndroidJUnit4::class)
class PrivatePagePromptIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val browser get() = (compose.activity.application as MiruApplication).container.browser
    private val fixtureTabs = mutableListOf<String>()
    private var originalOrdinaryTabs = emptySet<String>()
    private var originalSelection: String? = null

    @Before fun requireAnIsolatedPrivateContextWithoutRemovingUserTabs() {
        compose.waitUntil(30_000) { browser.state.value.ready }
        compose.runOnIdle {
            // The close-all case must never silently remove somebody else's private state.
            assertTrue("Run this class on a controlled AVD without existing private tabs",
                browser.state.value.tabs.none { it.isPrivate })
            originalOrdinaryTabs = browser.state.value.tabs.filterNot { it.isPrivate }.mapTo(mutableSetOf()) { it.id }
            originalSelection = browser.state.value.selectedTabId
        }
    }

    @After fun closeOnlyFixtureTabsAndRestoreTheOrdinarySelection() {
        compose.runOnIdle {
            browser.prompts.prompt.value?.takeIf { it.tabId in fixtureTabs }?.let { browser.prompts.dismiss(it.token) }
            fixtureTabs.filter { id -> browser.state.value.tabs.any { it.id == id } }.forEach(browser::closeTab)
            originalSelection?.takeIf { id -> browser.state.value.tabs.any { it.id == id } }?.let(browser::selectTab)
            assertTrue("All pre-existing ordinary tabs must remain",
                browser.state.value.tabs.map { it.id }.containsAll(originalOrdinaryTabs))
        }
    }

    @Test fun privateTextUsesTheRealUiAndScriptThenClosingItsLastTabClearsThePrompt() {
        PrivatePromptOrigin().use { origin ->
            val owner = page(origin, "/text", isPrivate = true)
            touchWebButton()
            val request = awaitPrompt(origin, owner, BrowserPromptKind.TEXT, isPrivate = true)
            assertSecureWindow()
            val nonce = "synthetic-private-" + UUID.randomUUID().toString()
            compose.onNodeWithTag("page_prompt_text").performTextReplacement(nonce)
            assertFalse("Prompt diagnostics must not disclose the actual private origin", request.toString().contains(origin.url))
            compose.onNodeWithTag("page_prompt_confirm").performClick()

            // Keep comparisons boolean: failure diagnostics must not print the private nonce/title.
            awaitTitle(owner, "Private text $nonce", "${origin.url}/text")
            assertTrue("The real JS result must arrive in the server's POST body", origin.result("text") == nonce)
            assertEquals("Exactly one actual script result must reach the origin", 1, origin.resultCount("text"))
            assertEquals("The private document must not be reloaded", 1, origin.pageCount("/text"))
            awaitNoPrompt()

            compose.runOnIdle {
                assertEquals("This fixture must own the last private tab", listOf(owner),
                    browser.state.value.tabs.filter { it.isPrivate }.map { it.id })
                browser.closeTab(owner)
            }
            compose.waitUntil(10_000) {
                browser.state.value.tabs.none { it.isPrivate } && browser.prompts.prompt.value == null
            }
            assertNull(browser.prompts.prompt.value)
        }
    }

    @Test fun closingAllPrivateTabsMakesTheirLateAnswerHarmlessToAnOrdinaryRequest() {
        PrivatePromptOrigin().use { origin ->
            val retiredOwner = page(origin, "/private-confirm", isPrivate = true)
            touchWebButton()
            val retired = awaitPrompt(origin, retiredOwner, BrowserPromptKind.CONFIRM, isPrivate = true)
            assertSecureWindow()
            compose.runOnIdle { browser.closeAllTabs(isPrivate = true) }
            compose.waitUntil(10_000) {
                browser.state.value.tabs.none { it.isPrivate } && browser.prompts.prompt.value == null
            }
            assertFalse(browser.state.value.tabs.any { it.id == retiredOwner })

            val ordinaryOwner = page(origin, "/ordinary-confirm", isPrivate = false)
            touchWebButton()
            val current = awaitPrompt(origin, ordinaryOwner, BrowserPromptKind.CONFIRM, isPrivate = false)
            assertNotEquals(retired.token, current.token)
            // This is the only intentional direct answer: it targets a retired private token.
            compose.runOnIdle { browser.prompts.answerConfirm(retired.token, BrowserPromptButton.POSITIVE) }
            assertEquals("A stale private answer must leave the ordinary request pending", current.token,
                browser.prompts.prompt.value?.token)
            assertEquals("The ordinary JS must still be waiting for its own UI answer", 0,
                origin.resultCount("ordinary-confirm"))
            compose.onNodeWithTag("browser_prompt_dialog").assertIsDisplayed()
            compose.onNodeWithTag("page_prompt_cancel").performClick()
            awaitTitle(ordinaryOwner, "Ordinary confirm false", "${origin.url}/ordinary-confirm")
            assertTrue("The ordinary site's real confirm must return false", origin.result("ordinary-confirm") == "false")
            assertEquals(1, origin.resultCount("ordinary-confirm"))
            awaitNoPrompt()
            assertTrue(browser.state.value.tabs.none { it.isPrivate })
        }
    }

    private fun page(origin: PrivatePromptOrigin, path: String, isPrivate: Boolean): String {
        var id = ""
        compose.runOnIdle {
            id = browser.newTab(origin.url + path, isPrivate = isPrivate)
            fixtureTabs += id
        }
        // The ordinary web page acknowledges DOMContentLoaded and two real rAFs over HTTP.
        compose.waitUntil(30_000) {
            origin.readyCount(path.removePrefix("/")) == 1 && browser.state.value.selectedTab?.let {
                it.id == id && it.url == origin.url + path && it.title == "Prompt fixture ready" && !it.loading
            } == true
        }
        if(isPrivate) assertSecureWindow()
        return id
    }

    private fun awaitTitle(tabId: String, expected: String, expectedUrl: String) {
        compose.waitUntil(30_000) {
            browser.state.value.tabs.firstOrNull { it.id == tabId }?.let {
                it.title == expected && it.url == expectedUrl && !it.loading
            } == true
        }
    }

    private fun awaitPrompt(origin: PrivatePromptOrigin, tabId: String, kind: BrowserPromptKind,
        isPrivate: Boolean): BrowserPromptState {
        compose.waitUntil(20_000) {
            browser.prompts.prompt.value?.let { it.tabId == tabId && it.kind == kind && it.isPrivate == isPrivate } == true
        }
        return checkNotNull(browser.prompts.prompt.value).also {
            assertEquals(tabId, it.tabId)
            assertEquals(kind, it.kind)
            assertEquals(isPrivate, it.isPrivate)
            assertEquals(origin.url, it.topOrigin)
            assertNull("The fixed script prompt API does not expose the requesting frame's origin", it.origin)
            compose.onNodeWithTag("browser_prompt_dialog").assertIsDisplayed()
        }
    }

    private fun awaitNoPrompt() = compose.waitUntil(10_000) { browser.prompts.prompt.value == null }

    private fun assertSecureWindow() {
        compose.runOnIdle {
            assertTrue("Private fixtures must keep FLAG_SECURE throughout the test",
                compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
    }

    private fun engineBounds(): Rect {
        var result: Rect? = null
        compose.runOnIdle {
            fun find(view: View): EngineView? = if(view is EngineView) view else (view as? ViewGroup)?.let {
                (0 until it.childCount).firstNotNullOfOrNull { index -> find(it.getChildAt(index)) }
            }
            val view = checkNotNull(find(compose.activity.window.decorView)).asView()
            val xy = IntArray(2)
            view.getLocationOnScreen(xy)
            result = Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
        }
        return checkNotNull(result)
    }

    private fun touchWebButton() {
        // Readiness was proved by the document/HTTP beacon. Never take a private screenshot.
        val density = compose.activity.resources.displayMetrics.density
        val bounds = engineBounds()
        assertTrue("The real EngineView must have room for the fixture button",
            bounds.width() > 80 * density && bounds.height() > 80 * density)
        val down = SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.left + 40 * density, bounds.top + 40 * density, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if(action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60)
        }
    }
}

/** Synthetic values stay in memory; no request body, title or URI is written to diagnostics. */
private class PrivatePromptOrigin : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val pages = ConcurrentHashMap<String, AtomicInteger>()
    private val ready = ConcurrentHashMap<String, AtomicInteger>()
    private val resultCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val results = ConcurrentHashMap<String, String>()
    val url = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true, name = "miru-private-prompt-origin") {
            while(running.get()) runCatching {
                val socket = server.accept()
                sockets += socket
                thread(isDaemon = true, name = "miru-private-prompt-response") {
                    try { socket.use(::respond) } finally { sockets -= socket }
                }
            }
        }
    }

    fun pageCount(path: String) = pages[path]?.get() ?: 0
    fun readyCount(name: String) = ready[name]?.get() ?: 0
    fun resultCount(name: String) = resultCounts[name]?.get() ?: 0
    fun result(name: String) = results[name]

    private fun respond(socket: Socket) = runCatching {
        socket.soTimeout = 5_000
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = reader.readLine()?.takeIf { it.length <= 8192 }?.split(' ') ?: return@runCatching
        if(request.size < 2) return@runCatching
        val method = request[0]
        val path = request[1].substringBefore('?')
        var contentLength = 0
        var headerCount = 0
        while(true) {
            val line = reader.readLine() ?: break
            if(line.isEmpty()) break
            check(++headerCount <= 64 && line.length <= 8192)
            if(line.substringBefore(':').lowercase(Locale.ROOT) == "content-length") {
                contentLength = line.substringAfter(':').trim().toInt()
            }
        }
        check(contentLength in 0..4096)
        val body = CharArray(contentLength)
        var consumed = 0
        while(consumed < body.size) {
            val read = reader.read(body, consumed, body.size - consumed)
            check(read > 0)
            consumed += read
        }
        val response = when {
            path.startsWith("/ready/") -> {
                check(method == "POST" && String(body) == "ready")
                ready.getOrPut(path.removePrefix("/ready/")) { AtomicInteger() }.incrementAndGet()
                "ready"
            }
            path.startsWith("/result/") -> {
                check(method == "POST")
                val name = path.removePrefix("/result/")
                results[name] = String(body)
                resultCounts.getOrPut(name) { AtomicInteger() }.incrementAndGet()
                "received"
            }
            path in setOf("/text", "/private-confirm", "/ordinary-confirm") -> {
                check(method == "GET")
                pages.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                val name = path.removePrefix("/")
                val script = when(path) {
                    "/text" -> "const value=prompt('Miru private text fixture','');await report(value===null?'null':value);document.title='Private text '+(value===null?'null':value)"
                    "/private-confirm" -> "const value=confirm('Miru private confirm fixture');await report(String(value));document.title='Private confirm '+value"
                    else -> "const value=confirm('Miru ordinary confirm fixture');await report(String(value));document.title='Ordinary confirm '+value"
                }
                "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>" +
                    "<title>Prompt fixture loading</title><style>body{margin:0}button{width:220px;height:100px;border:0}</style>" +
                    "<button id=request disabled onclick='requestPrompt()'>Request fixture</button>" +
                    "<script>async function report(value){await fetch('/result/$name',{method:'POST',body:value})}" +
                    "async function requestPrompt(){document.title='Prompt fixture pending';$script}" +
                    "addEventListener('DOMContentLoaded',()=>requestAnimationFrame(()=>requestAnimationFrame(async()=>{" +
                    "await fetch('/ready/$name',{method:'POST',body:'ready'});document.getElementById('request').disabled=false;" +
                    "document.title='Prompt fixture ready'})))</script>"
            }
            else -> ""
        }.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${response.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            write(response)
            flush()
        }
    }

    override fun toString() = "PrivatePromptOrigin(synthetic fixture)"

    override fun close() {
        running.set(false)
        server.close()
        sockets.toList().forEach { runCatching { it.close() } }
        results.clear()
    }
}
