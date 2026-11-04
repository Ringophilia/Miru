// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.browser.engine.BrowserPromptKind
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native Gecko date/color prompts and real DOM values/validity; public picker controls only. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3Api::class)
class FormValuesIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val browser get() = (compose.activity.application as MiruApplication).container.browser
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun title(id: String, expected: String) = compose.waitUntil(20000) { browser.state.value.tabs.firstOrNull { it.id == id }?.let { it.title == expected && !it.loading } == true }
    private fun page(origin: FormValueOrigin, type: String, isPrivate: Boolean = false): String {
        var id = ""
        compose.runOnIdle { id = browser.newTab(origin.url + "/" + type, isPrivate = isPrivate) }
        title(id, "Value ready $type"); return id
    }
    private fun open(type: String) {
        val label = "Pick $type"
        fun find(node: AccessibilityNodeInfo?, depth: Int): Rect? {
            if(node == null || depth > 40) return null
            if((node.text?.toString().orEmpty() + node.contentDescription?.toString().orEmpty()).contains(label) && node.isVisibleToUser) {
                val rect = Rect(); node.getBoundsInScreen(rect)
                if(rect.width() > 0 && rect.height() > 0) return rect
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
        compose.waitUntil(5000) {
            val title = browser.state.value.selectedTab?.title.orEmpty()
            if(title.startsWith("Picker error")) throw AssertionError(title)
            browser.prompts.prompt.value?.kind in setOf(BrowserPromptKind.DATE_TIME, BrowserPromptKind.COLOR)
        }
    }
    private fun date() = compose.onNodeWithTag("page_prompt_date_value")
    private fun color() = compose.onNodeWithTag("page_prompt_color_value")
    private fun confirm() = compose.onNodeWithTag("page_prompt_confirm").performScrollTo().performClick()

    @Test fun dateCalendarHonorsBoundsAndStepAndCancelAndClearReachTheRealInput() = fixture { origin ->
        val id = page(origin, "date"); open("date")
        val spec = browser.prompts.prompt.value!!.dateInput!!
        assertEquals("2026-04-06", spec.minimum); assertEquals("2026-04-30", spec.maximum); assertEquals("2", spec.step)
        compose.onNodeWithTag("page_prompt_cancel").performScrollTo().performClick()
        assertEquals("Value ready date", browser.state.value.selectedTab?.title)
        open("date")
        date().performTextReplacement("2026-02-30")
        compose.onNodeWithTag("page_prompt_confirm").assertIsNotEnabled()
        date().performTextReplacement("2026-04-10")
        compose.onNodeWithTag("page_prompt_pick_date").performScrollTo().performClick()
        val day = DatePickerDefaults.dateFormatter().formatDate(LocalDate.of(2026, 4, 26).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), Locale.getDefault(), forContentDescription = true)!!
        try {
            compose.waitUntil(5000) { compose.onAllNodesWithTag("page_prompt_date_picker_confirm").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5000) { compose.onAllNodesWithText(day).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            // Only this owned ordinary date fixture; private screens are never captured.
            if(browser.state.value.selectedTab?.isPrivate != true) {
                val directory = compose.activity.getExternalFilesDir(null)
                automation.takeScreenshot()?.let { bitmap -> File(directory, "date-picker-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                File(directory, "date-picker-tree.txt").writeText(roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString() })
            }
            throw failure
        }
        compose.onNodeWithText(day).performClick()
        compose.onNodeWithTag("page_prompt_date_picker_confirm").performClick()
        confirm(); title(id, "Value 2026-04-26 true")
        open("date")
        compose.onNodeWithTag("page_prompt_date_clear").performScrollTo().performClick()
        title(id, "Value empty true")
    }

    @Test fun timeKeepsFractionalSecondsAndARealTimePickerEditsItsMinutes() = fixture { origin ->
        val id = page(origin, "time"); open("time")
        val spec = browser.prompts.prompt.value!!.dateInput!!
        assertEquals("23:00", spec.minimum); assertEquals("01:00", spec.maximum); assertEquals("0.001", spec.step)
        date().performTextReplacement("12:00")
        compose.onNodeWithTag("page_prompt_confirm").assertIsNotEnabled()
        date().performTextReplacement("00:30:15.125")
        compose.onNodeWithTag("page_prompt_pick_time").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("30")).performTextReplacement("45")
        compose.onNodeWithTag("page_prompt_time_picker_confirm").performClick()
        confirm(); title(id, "Value 00:45:15.125 true")
    }

    @Test fun monthWeekAndLocalDatetimeKeepTheirOwnFormatsAndRanges() = fixture { origin ->
        for((type, invalid, valid) in listOf(Triple("month", "2026-04", "2026-05"), Triple("week", "2021-W53", "2021-W02"),
            Triple("datetime-local", "2026-03-08T02:31", "2026-03-08T02:30"))) {
            val id = page(origin, type); open(type)
            date().performTextReplacement(invalid)
            compose.onNodeWithTag("page_prompt_confirm").assertIsNotEnabled()
            date().performTextReplacement(valid); confirm()
            title(id, "Value $valid true")
        }
    }

    @Test fun colorHexAndRgbSliderReturnTheActualColorAndCancelDoesNotChangeIt() = fixture { origin ->
        val id = page(origin, "color"); open("color")
        color().performTextReplacement("#bad")
        compose.onNodeWithTag("page_prompt_confirm").assertIsNotEnabled()
        color().performTextReplacement("#12AB34")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.page_prompt_blue)).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(128f)) }
        confirm(); title(id, "Value #12ab80 true")
        open("color"); color().performTextReplacement("#abcdef")
        compose.onNodeWithTag("page_prompt_cancel").performScrollTo().performClick()
        assertEquals("Value #12ab80 true", browser.state.value.selectedTab?.title)
    }

    @Test fun recreationDoesNotSaveDateDraftsAndClosedPrivateColorCannotAnswerAnotherPage() = fixture { origin ->
        val normal = page(origin, "date"); open("date")
        date().performTextReplacement("2026-04-26")
        compose.activityRule.scenario.recreate()
        assertEquals("2026-04-10", date().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        val token = browser.prompts.prompt.value!!.token
        compose.runOnIdle { browser.navigate(origin.url + "/quiet"); browser.prompts.answerDate(token, "2026-04-26") }
        title(normal, "Value quiet")
        val private = page(origin, "color", isPrivate = true); open("color")
        val old = browser.prompts.prompt.value!!.token
        compose.runOnIdle { browser.closeTab(private); browser.prompts.answerColor(old, "#aabbcc") }
        compose.waitUntil(3000) { browser.prompts.prompt.value == null }
        assertEquals(normal, browser.state.value.selectedTabId)
        assertEquals("Value quiet", browser.state.value.selectedTab?.title)
    }
    private fun fixture(block: (FormValueOrigin) -> Unit) {
        compose.waitUntil(30000) { browser.state.value.ready }
        val tabs = BrowserTabFixture(); compose.runOnIdle { tabs.capture(browser) }
        FormValueOrigin().use { origin ->
            try { block(origin) }
            finally { compose.runOnIdle { browser.prompts.prompt.value?.let { browser.prompts.dismiss(it.token) }; tabs.closeCreatedTabs(browser) } }
        }
    }
}

private class FormValueOrigin : Closeable {
    private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    val url get() = "http://127.0.0.1:${server.localPort}"
    private val running = AtomicBoolean(true)
    init { thread(isDaemon = true) { while(running.get()) {
        val socket = try { server.accept() } catch (_: Exception) { break }
        thread(isDaemon = true) { socket.use { try {
            it.soTimeout = 5000
            val reader = it.getInputStream().bufferedReader(); val type = reader.readLine().split(' ')[1].removePrefix("/")
            while(reader.readLine().isNotEmpty()) { }
            val attributes = when(type) {
                "date" -> "value=2026-04-10 min=2026-04-06 max=2026-04-30 step=2"
                "time" -> "value=23:30:15.125 min=23:00 max=01:00 step=0.001"
                "month" -> "value=2026-01 min=2026-01 max=2026-12 step=2"
                "week" -> "value=2020-W51 min=2020-W51 max=2021-W04 step=2"
                "datetime-local" -> "value=2026-03-08T01:30 min=2026-03-08T01:00 max=2026-03-08T04:00 step=900"
                else -> "value=#112233"
            }
            val body = if(type == "quiet") "<title>Value quiet</title><h1>Quiet page</h1>" else """
                <meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0}button{width:280px;height:100px}input{display:block;width:280px;height:48px}</style>
                <button onclick="try{field.showPicker()}catch(e){document.title='Picker error '+e.name+':'+e.message}">Pick $type</button><input id=field type="$type" $attributes>
                <script>const field=document.getElementById('field');field.onchange=()=>document.title='Value '+(field.value||'empty')+' '+field.checkValidity();requestAnimationFrame(()=>requestAnimationFrame(()=>setTimeout(()=>document.title='Value ready $type',250)));</script>
            """.trimIndent()
            val bytes = ("<!doctype html>" + body).toByteArray()
            it.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush() }
        } catch (_: Exception) { } } }
    } } }
    override fun close() { running.set(false); server.close() }
}
