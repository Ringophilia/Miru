// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import mozilla.components.concept.engine.EngineView
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Gecko requests, native dialog clicks and DOM/network outcomes; no injected prompt callbacks. */
@RunWith(AndroidJUnit4::class)
class PagePromptIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val browser get() = (compose.activity.application as MiruApplication).container.browser
    private val tabs = mutableListOf<String>()

    @After fun closeFixtureTabs() {
        compose.runOnIdle {
            browser.prompts.prompt.value?.let { browser.prompts.dismiss(it.token) }
            tabs.filter { id -> browser.state.value.tabs.any { it.id == id } }.forEach(browser::closeTab)
        }
    }

    @Test fun javascriptAlertConfirmAndTextReturnActualWebsiteValues() {
        PagePromptOrigin().use { origin ->
            val alert = page(origin, "/alert")
            touchWebButton()
            awaitPrompt(origin, alert, BrowserPromptKind.ALERT)
            confirm()
            title(alert, "Prompt alert acknowledged")

            val yes = page(origin, "/confirm")
            touchWebButton()
            awaitPrompt(origin, yes, BrowserPromptKind.CONFIRM)
            confirm()
            title(yes, "Prompt confirm true")

            val no = page(origin, "/confirm?cancel=1")
            touchWebButton()
            awaitPrompt(origin, no, BrowserPromptKind.CONFIRM)
            cancel()
            title(no, "Prompt confirm false")

            val text = page(origin, "/text")
            touchWebButton()
            awaitPrompt(origin, text, BrowserPromptKind.TEXT)
            compose.onNodeWithTag("page_prompt_text").performTextReplacement("typed fixture value")
            confirm()
            title(text, "Prompt text typed fixture value")

            val nullText = page(origin, "/text?cancel=1")
            touchWebButton()
            awaitPrompt(origin, nullText, BrowserPromptKind.TEXT)
            cancel()
            title(nullText, "Prompt text null")
            assertNull(browser.prompts.prompt.value)
        }
    }

    @Test fun activityRecreationRetainsTheSameTextRequestUntilUiCancel() {
        PagePromptOrigin().use { origin ->
            val owner = page(origin, "/text")
            touchWebButton()
            val original = awaitPrompt(origin, owner, BrowserPromptKind.TEXT)
            assertEquals(1, origin.requests("/text"))

            compose.activityRule.scenario.recreate()

            val restored = awaitPrompt(origin, owner, BrowserPromptKind.TEXT)
            assertEquals("Activity recreation must retain the real document's request token", original.token, restored.token)
            assertEquals(original.origin, restored.origin)
            assertEquals(original.topOrigin, restored.topOrigin)
            assertEquals("Recreation must not fetch a replacement document", 1, origin.requests("/text"))
            assertEquals("The pending prompt must still belong to its original tab", owner, restored.tabId)
            cancel()
            title(owner, "Prompt text null")
            assertNull(browser.prompts.prompt.value)
            assertEquals(1, origin.requests("/text"))
        }
    }

    @Test fun stoppingRepeatedDialogsCompletesTheScriptAndResetsOnNewDocument() {
        PagePromptOrigin().use { origin ->
            val owner = page(origin, "/repeated-alerts")
            touchWebButton()
            awaitPrompt(origin, owner, BrowserPromptKind.ALERT)
            compose.onNodeWithTag("page_prompt_stop_dialogs").performClick().assertIsOn()
            confirm()
            // The second real native alert must be dismissed without another UI,
            // letting the original JS continue to its own completion title.
            title(owner, "Prompt repeated alerts completed")
            assertNull(browser.prompts.prompt.value)

            compose.runOnIdle { browser.reload() }
            title(owner, "Prompt ready", "${origin.url}/repeated-alerts")
            assertEquals(2, origin.requests("/repeated-alerts"))
            touchWebButton()
            val first = awaitPrompt(origin, owner, BrowserPromptKind.ALERT)
            confirm()
            val second = awaitPrompt(origin, owner, BrowserPromptKind.ALERT)
            assertNotEquals("New document must allow its second native request too", first.token, second.token)
            confirm()
            title(owner, "Prompt repeated alerts completed")
            assertNull(browser.prompts.prompt.value)

            val textOwner = page(origin, "/repeated-text")
            touchWebButton()
            awaitPrompt(origin, textOwner, BrowserPromptKind.TEXT)
            compose.onNodeWithTag("page_prompt_stop_dialogs").performClick().assertIsOn()
            cancel()
            title(textOwner, "Prompt repeated text null completed")
            assertNull(browser.prompts.prompt.value)
        }
    }

    @Test fun dismissingTheScrimKeepsTheFollowingNativeAlertVisible() {
        PagePromptOrigin().use { origin ->
            val owner = page(origin, "/repeated-alerts")
            touchWebButton()
            val first = awaitPrompt(origin, owner, BrowserPromptKind.ALERT)
            // Material3's real scrim action first hides the sheet, then calls
            // onDismissRequest. The following request needs a fresh sheet state.
            val scrim = compose.onNodeWithContentDescription(
                compose.activity.getString(androidx.compose.ui.R.string.close_sheet),
            )
            val scrimBounds = scrim.fetchSemanticsNode().boundsInRoot
            val sheetBounds = compose.onNodeWithTag("browser_prompt_dialog").fetchSemanticsNode().boundsInRoot
            assertTrue("The alert must leave a real scrim area: content=$sheetBounds, scrim=$scrimBounds",
                sheetBounds.top > scrimBounds.top + 2)
            scrim.performTouchInput { click(Offset(width / 2f, (sheetBounds.top - scrimBounds.top) / 2f)) }
            compose.waitUntil(20_000) {
                browser.prompts.prompt.value?.let { it.kind == BrowserPromptKind.ALERT && it.token != first.token } == true
            }
            awaitPrompt(origin, owner, BrowserPromptKind.ALERT)
            confirm()
            title(owner, "Prompt repeated alerts completed")
            assertEquals(1, origin.requests("/repeated-alerts"))
            assertNull(browser.prompts.prompt.value)
        }
    }

    @Test fun navigatingTheOwnerRetiresItsTokenBeforeTheNextPrompt() {
        PagePromptOrigin().use { origin ->
            val owner = page(origin, "/confirm")
            touchWebButton()
            val old = awaitPrompt(origin, owner, BrowserPromptKind.CONFIRM)
            compose.runOnIdle { browser.navigate("${origin.url}/idle") }
            title(owner, "Prompt ready", "${origin.url}/idle")
            awaitNoPrompt()

            compose.runOnIdle { browser.navigate("${origin.url}/confirm?new-document=1") }
            title(owner, "Prompt ready", "${origin.url}/confirm?new-document=1")
            touchWebButton()
            val current = awaitPrompt(origin, owner, BrowserPromptKind.CONFIRM)
            assertNotEquals(old.token, current.token)
            // Deliberately deliver a stale app-side answer; the new request must stay unanswered.
            compose.runOnIdle { browser.prompts.answerConfirm(old.token, BrowserPromptButton.POSITIVE) }
            assertEquals(current.token, browser.prompts.prompt.value?.token)
            compose.onNodeWithTag("browser_prompt_dialog").assertIsDisplayed()
            cancel()
            title(owner, "Prompt confirm false")
            assertEquals(2, origin.requests("/confirm"))
        }
    }

    @Test fun closingTheOwnerMakesItsLateAnswerHarmlessToAnotherTab() {
        PagePromptOrigin().use { origin ->
            val replacement = page(origin, "/confirm?replacement=1")
            val owner = page(origin, "/confirm?retired=1")
            touchWebButton()
            val old = awaitPrompt(origin, owner, BrowserPromptKind.CONFIRM)
            compose.runOnIdle { browser.closeTab(owner); browser.selectTab(replacement) }
            awaitNoPrompt()
            assertFalse(browser.state.value.tabs.any { it.id == owner })
            title(replacement, "Prompt ready")

            touchWebButton()
            val current = awaitPrompt(origin, replacement, BrowserPromptKind.CONFIRM)
            assertNotEquals(old.token, current.token)
            compose.runOnIdle { browser.prompts.answerConfirm(old.token, BrowserPromptButton.POSITIVE) }
            assertEquals(current.token, browser.prompts.prompt.value?.token)
            cancel()
            title(replacement, "Prompt confirm false")
        }
    }

    @Test fun beforeUnloadStayKeepsTheOriginalDocumentAndLeaveNavigatesOnce() {
        PagePromptOrigin().use { origin ->
            val owner = page(origin, "/before-unload")
            touchWebButton() // Genuine trusted activation before registering the real handler.
            title(owner, "Prompt armed 1")
            val originalUrl = "${origin.url}/before-unload"
            val destination = "${origin.url}/destination"
            compose.runOnIdle { browser.navigate(destination) }
            awaitPrompt(origin, owner, BrowserPromptKind.BEFORE_UNLOAD)
            assertEquals("A pending navigation must not replace the committed address", originalUrl, url(owner))
            cancel() // Stay.
            title(owner, "Prompt armed 1")
            assertEquals(originalUrl, url(owner))
            assertEquals(1, origin.requests("/before-unload"))
            assertEquals("Stay must not fetch the destination", 0, origin.requests("/destination"))

            compose.runOnIdle { browser.navigate(destination) }
            awaitPrompt(origin, owner, BrowserPromptKind.BEFORE_UNLOAD)
            confirm() // Leave.
            title(owner, "Prompt destination")
            assertEquals(destination, url(owner))
            assertEquals(1, origin.requests("/before-unload"))
            assertEquals(1, origin.requests("/destination"))
            assertNull(browser.prompts.prompt.value)
        }
    }

    @Test fun realPostReloadRequiresCancelOrExplicitResubmit() {
        PagePromptOrigin().use { origin ->
            val owner = page(origin, "/post-form")
            touchWebButton() // Submit an actual HTML form; not a substituted fetch or callback.
            title(owner, "Prompt posted 1")
            assertEquals(1, origin.postCount.get())

            compose.runOnIdle { browser.reload() }
            awaitPrompt(origin, owner, BrowserPromptKind.REPOST)
            assertEquals(1, origin.postCount.get())
            cancel()
            title(owner, "Prompt posted 1")
            assertEquals("Cancel must not resend the POST", 1, origin.postCount.get())

            compose.runOnIdle { browser.reload() }
            awaitPrompt(origin, owner, BrowserPromptKind.REPOST)
            confirm()
            title(owner, "Prompt posted 2")
            assertEquals("One explicit resubmit must cause exactly one new POST", 2, origin.postCount.get())
        }
    }

    @Test fun httpBasicAuthenticationUsesUiCredentialsAndSupportsCancel() {
        PagePromptOrigin().use { allowed ->
            val owner = page(allowed, "/auth-start")
            touchWebButton()
            awaitPrompt(allowed, owner, BrowserPromptKind.AUTHENTICATION)
            compose.onNodeWithTag("page_prompt_username").performTextReplacement(PagePromptOrigin.USERNAME)
            compose.onNodeWithTag("page_prompt_password").performTextReplacement(PagePromptOrigin.PASSWORD)
            confirm()
            title(owner, "Prompt authenticated")
            assertTrue(allowed.authChallenges.get() > 0)
            assertEquals(1, allowed.authenticatedRequests.get())
        }
        // A new port gives cancellation its own auth scope, independent of Gecko's credential cache.
        PagePromptOrigin().use { cancelled ->
            val owner = page(cancelled, "/auth-start")
            touchWebButton()
            awaitPrompt(cancelled, owner, BrowserPromptKind.AUTHENTICATION)
            cancel()
            title(owner, "Prompt authentication cancelled")
            assertTrue(cancelled.authChallenges.get() > 0)
            assertEquals("Cancel must never authorize a request", 0, cancelled.authenticatedRequests.get())
            assertNull(browser.prompts.prompt.value)
        }
    }

    private fun page(origin: PagePromptOrigin, path: String): String {
        var id = ""
        compose.runOnIdle { id = browser.newTab(origin.url + path); tabs += id }
        title(id, "Prompt ready", origin.url + path)
        return id
    }

    private fun url(id: String) = browser.state.value.tabs.firstOrNull { it.id == id }?.url

    private fun title(id: String, expected: String, expectedUrl: String? = null) {
        try {
            compose.waitUntil(30_000) {
                browser.state.value.tabs.firstOrNull { it.id == id }?.let {
                    it.title == expected && !it.loading && (expectedUrl == null || it.url == expectedUrl)
                } == true
            }
        } catch(failure: Throwable) {
            val current = browser.state.value.tabs.firstOrNull { it.id == id }
            throw AssertionError("Expected $expected at ${expectedUrl ?: "the current document"}; current title=${current?.title}, url=${current?.url}", failure)
        }
    }

    private fun awaitPrompt(origin: PagePromptOrigin, tabId: String, kind: BrowserPromptKind): BrowserPromptState {
        compose.waitUntil(20_000) { browser.prompts.prompt.value?.let { it.tabId == tabId && it.kind == kind } == true }
        return checkNotNull(browser.prompts.prompt.value).also {
            assertEquals(origin.url, it.topOrigin)
            if(kind == BrowserPromptKind.AUTHENTICATION) assertEquals(origin.url, it.origin)
            else assertNull("This engine API does not provide the requesting frame's origin", it.origin)
            assertEquals(tabId, it.tabId)
            assertEquals(kind, it.kind)
            compose.onNodeWithTag("browser_prompt_dialog").assertIsDisplayed()
        }
    }

    private fun awaitNoPrompt() {
        compose.waitUntil(10_000) { browser.prompts.prompt.value == null }
    }

    private fun confirm() = compose.onNodeWithTag("page_prompt_confirm").performClick()
    private fun cancel() = compose.onNodeWithTag("page_prompt_cancel").performClick()

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
        val density = compose.activity.resources.displayMetrics.density
        // Ordinary fixtures only. Never bypass private FLAG_SECURE to obtain this screenshot.
        compose.waitUntil(8_000) {
            val bounds = engineBounds()
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
            try {
                val x = (bounds.left + 12 * density).toInt()
                val y = (bounds.top + 12 * density).toInt()
                if(x !in 0 until bitmap.width || y !in 0 until bitmap.height) return@waitUntil false
                val pixel = bitmap.getPixel(x, y)
                kotlin.math.abs(Color.red(pixel) - 0x13) < 8 && kotlin.math.abs(Color.green(pixel) - 0x57) < 8 &&
                    kotlin.math.abs(Color.blue(pixel) - 0x9b) < 8
            } finally { bitmap.recycle() }
        }
        val bounds = engineBounds()
        val down = SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.left + 40 * density, bounds.top + 40 * density, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            if(action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60)
        }
    }
}

/** Loopback-only ordinary documents. Authorization is compared in memory and never logged or retained. */
private class PagePromptOrigin : Closeable {
    private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    val url = "http://127.0.0.1:${server.localPort}"
    val postCount = AtomicInteger()
    val authChallenges = AtomicInteger()
    val authenticatedRequests = AtomicInteger()

    init {
        thread(isDaemon = true, name = "miru-page-prompt-origin") {
            while(running.get()) runCatching {
                val socket = server.accept()
                sockets += socket
                thread(isDaemon = true, name = "miru-page-prompt-response") {
                    try { socket.use(::respond) } finally { sockets -= socket }
                }
            }
        }
    }

    fun requests(path: String) = counts[path]?.get() ?: 0

    private fun respond(socket: Socket) = runCatching {
        socket.soTimeout = 5_000
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = reader.readLine()?.takeIf { it.length <= 8192 }?.split(' ') ?: return@runCatching
        if(request.size < 2) return@runCatching
        val method = request[0]
        val path = request[1].substringBefore('?')
        var contentLength = 0
        var authorization: String? = null
        var headerCount = 0
        while(true) {
            val line = reader.readLine() ?: break
            if(line.isEmpty()) break
            check(++headerCount <= 64 && line.length <= 8192)
            val name = line.substringBefore(':').lowercase(Locale.ROOT)
            val value = line.substringAfter(':', "").trim()
            if(name == "content-length") contentLength = value.toInt()
            if(name == "authorization") authorization = value
        }
        check(contentLength in 0..4096)
        val body = CharArray(contentLength)
        var consumed = 0
        while(consumed < body.size) {
            val read = reader.read(body, consumed, body.size - consumed)
            check(read > 0)
            consumed += read
        }
        counts.getOrPut(path) { AtomicInteger() }.incrementAndGet()
        var status = "200 OK"
        var extraHeaders = ""
        val html = when(path) {
            "/post" -> {
                check(method == "POST")
                check(String(body) == "intent=synthetic")
                document("Prompt posted ${postCount.incrementAndGet()}")
            }
            "/auth" -> {
                val expected = "Basic " + Base64.encodeToString("$USERNAME:$PASSWORD".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                if(authorization == expected) {
                    authenticatedRequests.incrementAndGet()
                    document("Prompt authenticated")
                } else {
                    authChallenges.incrementAndGet()
                    status = "401 Unauthorized"
                    extraHeaders = "WWW-Authenticate: Basic realm=\"Miru synthetic prompt\"\r\n"
                    document("Prompt authentication cancelled")
                }
            }
            "/destination" -> document("Prompt destination")
            "/post-form" -> document("Prompt ready", "<form method=post action='/post'><input type=hidden name=intent value=synthetic><button type=submit>Submit fixture</button></form>")
            else -> {
                val script = when(path) {
                    "/alert" -> "alert('Miru alert fixture');document.title='Prompt alert acknowledged'"
                    "/repeated-alerts" -> "alert('Miru first repeated fixture');alert('Miru second repeated fixture');document.title='Prompt repeated alerts completed'"
                    "/repeated-text" -> "const value=prompt('Miru repeated text fixture');alert('Miru following text fixture');document.title='Prompt repeated text '+(value===null?'null':value)+' completed'"
                    "/confirm" -> "document.title='Prompt confirm '+confirm('Miru confirm fixture')"
                    "/text" -> "const value=prompt('Miru text fixture','initial fixture value');document.title='Prompt text '+(value===null?'null':value)"
                    "/before-unload" -> "window.promptArmed=(window.promptArmed||0)+1;addEventListener('beforeunload',e=>{e.preventDefault();e.returnValue=''});document.title='Prompt armed '+window.promptArmed"
                    "/auth-start" -> "location.href='/auth'"
                    else -> "document.title='Prompt ready'"
                }
                document("Prompt ready", "<button onclick='requestPrompt()'>Request fixture</button><script>function requestPrompt(){document.title='Prompt pending';$script}</script>")
            }
        }.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" + extraHeaders +
                "Content-Length: ${html.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            write(html)
            flush()
        }
    }

    private fun document(title: String, content: String = "") =
        "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>$title</title>" +
            "<style>body{margin:0}button{background:#13579b;color:white;width:220px;height:100px;border:0}</style>$content"

    override fun close() {
        running.set(false)
        server.close()
        sockets.toList().forEach { runCatching { it.close() } }
    }

    companion object {
        const val USERNAME = "miru-synthetic-user"
        const val PASSWORD = "synthetic-prompt-password"
    }
}
