// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TabCardDismissIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as MiruApplication).container
    private val browser get() = container.browser
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun label(id: Int) = compose.activity.getString(id)
    private fun card(id: String) = compose.onNodeWithTag("tabs-card-$id")
    private fun bounds(id: String) = card(id).fetchSemanticsNode().boundsInRoot

    @Test fun swipingFadesTheCardAndReflowsTheGridWithUndoRestoringItsPage() {
        val skip = label(R.string.intro_skip)
        compose.waitUntil(15_000) {
            runBlocking { container.settings.settings.first().introCompleted } ||
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText(skip).performClick()
        compose.waitUntil(30_000) { browser.state.value.ready }
        val tabs = BrowserTabFixture()
        compose.runOnIdle { tabs.capture(browser) }
        try {
            TestPages().use { pages ->
                val red = compose.runOnIdle { browser.newTab("${pages.baseUrl}/first") }
                awaitPage(red, "Miru One")
                compose.runOnIdle { browser.navigate("${pages.baseUrl}/red") }
                awaitPage(red, "Red preview", requirePreview = true)
                compose.waitUntil(8_000) { browser.state.value.selectedTab?.canGoBack == true }
                val blue = compose.runOnIdle { browser.newTab("${pages.baseUrl}/blue") }
                awaitPage(blue, "Blue preview")
                compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_tabs)).performClick()
                val resting = bounds(red)
                val neighborBefore = bounds(blue).center
                val middle = compose.onNodeWithTag("tabs-tray").fetchSemanticsNode().boundsInRoot.center.x
                val direction = if (resting.center.x < middle) 1f else -1f
                val beforeColor = color(red)
                assertTrue("Fixture card should contain the actual red preview", beforeColor.red > beforeColor.green + 0.25f)

                swipe(resting, direction * 0.29f) {
                    val moved = bounds(red)
                    assertTrue("The card should move while the finger is down", abs(moved.center.x - resting.center.x) > resting.width * 0.12f)
                    assertTrue("The moved card should be more transparent", color(red).green > beforeColor.green + 0.035f)
                }
                compose.waitUntil(5_000) { abs(bounds(red).center.x - resting.center.x) <= 2f }

                swipe(resting, direction * 0.54f)
                compose.waitUntil(8_000) { browser.state.value.tabs.none { it.id == red } }
                compose.waitUntil(5_000) { (bounds(blue).center - neighborBefore).getDistance() > 40f }
                compose.onNodeWithText(label(R.string.undo)).performClick()
                compose.waitUntil(6_000) { browser.state.value.tabs.any { it.id == red } }
                card(red).performClick()
                awaitPage(red, "Red preview")
                // Undo restores the title before Gecko has linked and published the history stack.
                compose.waitUntil(12_000) { browser.state.value.selectedTab?.canGoBack == true }
                assertTrue(compose.runOnIdle { browser.goBack() })
                awaitPage(red, "Miru One")

                compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_tabs)).performClick()
                card(blue).performTouchInput {
                    // Below the distance threshold, but fast enough to close on velocity.
                    swipe(Offset(width * 0.2f, center.y), Offset(width * 0.58f, center.y), durationMillis = 45)
                }
                compose.waitUntil(8_000) { browser.state.value.tabs.none { it.id == blue } }
            }
        } finally { compose.runOnIdle { tabs.closeCreatedTabs(browser) } }
    }

    private fun awaitPage(id: String, title: String, requirePreview: Boolean = false) = compose.waitUntil(20_000) {
        browser.state.value.selectedTab?.let { it.id == id && it.title == title && !it.loading && (!requirePreview || it.thumbnail != null) } == true
    }

    private fun color(id: String): Color {
        val pixels = card(id).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height * 2 / 3]
    }

    private fun swipe(rect: Rect, fraction: Float, whileHeld: () -> Unit = {}) {
        val root = compose.runOnIdle { IntArray(2).also(compose.activity.window.decorView::getLocationOnScreen) }
        val x = rect.center.x + root[0]
        val y = rect.center.y + root[1]
        val distance = rect.width * fraction
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, x, y)
        try {
            for (step in 1..12) {
                SystemClock.sleep(37)
                event(down, MotionEvent.ACTION_MOVE, x + distance * step / 12, y)
            }
            whileHeld()
        } finally { event(down, MotionEvent.ACTION_UP, x + distance, y) }
    }

    private fun event(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
    }
}
