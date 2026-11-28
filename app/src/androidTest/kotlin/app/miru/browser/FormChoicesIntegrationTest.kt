// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.browser.engine.BrowserPromptKind
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual HTML select controls, native Gecko choices and DOM change results. No synthetic prompt callbacks. */
@RunWith(AndroidJUnit4::class)
class FormChoicesIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val browser get() = (compose.activity.application as MiruApplication).container.browser
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun title(id: String, value: String) = compose.waitUntil(20000) { browser.state.value.tabs.firstOrNull { it.id == id }?.let { it.title == value && !it.loading } == true }
    private fun page(origin: ChoiceOrigin, kind: String, isPrivate: Boolean = false): String {
        var id = ""
        compose.runOnIdle { id = browser.newTab(origin.url + "/" + kind, isPrivate = isPrivate) }
        title(id, "Choice ready $kind")
        return id
    }
    private fun open(kind: String) {
        val label = "Pick $kind"
        fun find(node: AccessibilityNodeInfo?, depth: Int): Rect? {
            if(node == null || depth > 40) return null
            val compat = androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(node)
            val description = listOfNotNull(
                node.text,
                node.contentDescription,
                node.hintText,
                node.tooltipText,
                compat.supplementalDescription,
            ).joinToString(" ")
            if(description.contains(label) && node.isVisibleToUser) {
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                if(bounds.width() > 0 && bounds.height() > 0) return bounds
            }
            for(index in 0 until node.childCount) find(node.getChild(index), depth + 1)?.let { return it }
            return null
        }
        var bounds: Rect? = null
        compose.waitUntil(8000) { bounds = find(automation.rootInActiveWindow, 0); bounds != null }
        val target = bounds!!; val down = SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, target.exactCenterX(), target.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            if(action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60)
        }
        compose.waitUntil(5000) { browser.prompts.prompt.value?.choices?.isNotEmpty() == true }
        compose.onNodeWithTag("browser_prompt_dialog").assertIsDisplayed()
    }
    private fun confirm() = compose.onNodeWithTag("page_prompt_confirm").performClick()

    @Test fun singleChoiceGroupsDisabledOptionsCancelAndConfirmReturnTheActualSelectedValue() = fixture { origin ->
        val id = page(origin, "single")
        open("single")
        compose.onNodeWithText("Group one").assertIsDisplayed()
        compose.onNodeWithText("Blocked").assertIsNotEnabled()
        compose.onNodeWithTag("page_prompt_cancel").performClick()
        assertEquals("Choice ready single", browser.state.value.selectedTab?.title)
        open("single")
        compose.onNodeWithText("Beta").performClick(); confirm()
        title(id, "Choice result b")
    }

    @Test fun multipleChoicePreservesFixedDisabledSelectionsAndReturnsBothNativeOptionIds() = fixture { origin ->
        val id = page(origin, "multiple")
        open("multiple")
        assertEquals(BrowserPromptKind.MULTIPLE_CHOICE, browser.prompts.prompt.value!!.kind)
        compose.onNodeWithText("Fixed").assertIsNotEnabled()
        compose.onNodeWithText("Alpha").performClick()
        compose.onNodeWithText("Beta").performClick(); confirm()
        title(id, "Choice result b,d")
    }

    @Test fun liveOptionUpdateRetiresTheOldTokenAndConfirmsOnlyCurrentChoices() = fixture { origin ->
        val id = page(origin, "updating")
        open("updating")
        val old = browser.prompts.prompt.value!!
        val beta = old.choices.first { it.label == "Beta" }.id
        compose.waitUntil(7000) { browser.prompts.prompt.value?.let { it.token != old.token && it.choices.any { option -> option.label == "Updated" } } == true }
        compose.runOnIdle { browser.prompts.answerChoice(old.token, setOf(beta)) }
        compose.onNodeWithText("Beta").assertIsNotEnabled()
        compose.onNodeWithText("Updated").performClick(); confirm()
        title(id, "Choice result c")
    }

    @Test fun navigationAndClosingPrivateOwnerDiscardLateFormAnswers() = fixture { origin ->
        val normal = page(origin, "single")
        open("single")
        val old = browser.prompts.prompt.value!!
        val beta = old.choices.first { it.label == "Beta" }.id
        compose.runOnIdle { browser.navigate(origin.url + "/quiet") }
        title(normal, "Choice quiet")
        compose.runOnIdle { browser.prompts.answerChoice(old.token, setOf(beta)) }
        assertNull(browser.prompts.prompt.value)
        val private = page(origin, "single", isPrivate = true)
        open("single")
        val token = browser.prompts.prompt.value!!.token
        compose.runOnIdle { browser.closeTab(private); browser.prompts.answerChoice(token, setOf(beta)) }
        compose.waitUntil(3000) { browser.prompts.prompt.value == null }
        assertEquals(normal, browser.state.value.selectedTabId)
        assertEquals("Choice quiet", browser.state.value.selectedTab?.title)
    }

    private fun fixture(block: (ChoiceOrigin) -> Unit) {
        compose.waitUntil(30000) { browser.state.value.ready }
        val tabs = BrowserTabFixture(); compose.runOnIdle { tabs.capture(browser) }
        ChoiceOrigin().use { origin ->
            try { block(origin) }
            finally { compose.runOnIdle { browser.prompts.prompt.value?.let { browser.prompts.dismiss(it.token) }; tabs.closeCreatedTabs(browser) } }
        }
    }
}

private class ChoiceOrigin : Closeable {
    private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    val url get() = "http://127.0.0.1:${server.localPort}"
    private val running = AtomicBoolean(true)
    init { thread(isDaemon = true) { while(running.get()) {
        val socket = try { server.accept() } catch (_: Exception) { break }
        thread(isDaemon = true) { socket.use { try {
            it.soTimeout = 5000
            val reader = it.getInputStream().bufferedReader(); val kind = reader.readLine().split(' ')[1].removePrefix("/")
            while(reader.readLine().isNotEmpty()) { }
            val body = if(kind == "quiet") "<title>Choice quiet</title><h1>New document</h1>" else {
                val multiple = if(kind == "multiple") "multiple size=1" else ""
                val fixed = if(kind == "multiple") "<option value=d selected disabled>Fixed</option>" else ""
                val update = if(kind == "updating") "field.addEventListener('mousedown',()=>setTimeout(()=>{field.options[1].disabled=true;field.options[2].text='Updated';},1500),{once:true});" else ""
                """<meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0}select{width:280px;height:100px;font-size:18px}</style>
                <select id=field aria-label="Pick $kind" $multiple><optgroup label="Group one"><option value=a selected>Alpha</option><option value=b>Beta</option><option value=c>Gamma</option><option value=x disabled>Blocked</option>$fixed</optgroup><optgroup label="Disabled group" disabled><option value=z>Unavailable</option></optgroup></select>
                <script>const field=document.getElementById('field');field.onchange=()=>document.title='Choice result '+Array.from(field.selectedOptions).map(o=>o.value).join(',');$update requestAnimationFrame(()=>requestAnimationFrame(()=>setTimeout(()=>document.title='Choice ready $kind',250)));</script>""".trimIndent()
            }
            val bytes = ("<!doctype html>" + body).toByteArray()
            it.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush() }
        } catch (_: Exception) { } } }
    } } }
    override fun close() { running.set(false); server.close() }
}
