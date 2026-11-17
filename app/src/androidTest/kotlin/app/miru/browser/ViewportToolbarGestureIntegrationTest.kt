// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.data.ToolbarPosition
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewportToolbarGestureIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as MiruApplication).container
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun toolbar() = compose.onNodeWithTag("browser-viewport-toolbar").fetchSemanticsNode()

    @Test fun realGeckoFollowsTheFingerAndSettlesOnEachSideOfHalfAtBothEdges() {
        val skip = compose.activity.getString(R.string.intro_skip)
        compose.waitUntil(15_000) {
            runBlocking { container.settings.settings.first().introCompleted } ||
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText(skip).performClick()
        compose.waitUntil(30_000) { container.browser.state.value.ready }
        val saved = runBlocking { container.settings.settings.first().toolbarPosition }
        val tabs = BrowserTabFixture()
        compose.runOnIdle { tabs.capture(container.browser) }
        try {
            TestPages().use { pages ->
                for (position in ToolbarPosition.entries) {
                    runBlocking { container.settings.setToolbarPosition(position) }
                    val url = "${pages.baseUrl}/long"
                    compose.runOnIdle { container.browser.newTab(url) }
                    compose.waitUntil(20_000) { container.browser.state.value.selectedTab?.let { it.title == "Miru long" && it.url == url && !it.loading } == true }
                    val viewport = compose.onNodeWithTag("browser-viewport").fetchSemanticsNode().boundsInRoot
                    compose.waitUntil(5_000) { (toolbar().boundsInRoot.center.y < viewport.center.y) == (position == ToolbarPosition.TOP) }
                    val initial = toolbar()
                    val start = initial.positionInRoot.y
                    val height = initial.size.height.toFloat()
                    dragToHiddenFraction(start, height, 0.24f..0.44f)
                    compose.waitUntil(5_000) { hidden(start) <= 2f }
                    dragToHiddenFraction(start, height, 0.59f..0.86f)
                    compose.waitUntil(5_000) { hidden(start) >= height - 2f }
                }
            }
        } finally {
            runBlocking { container.settings.setToolbarPosition(saved) }
            compose.runOnIdle { tabs.closeCreatedTabs(container.browser) }
        }
    }

    private fun hidden(start: Float): Float = abs(toolbar().positionInRoot.y - start)

    private fun dragToHiddenFraction(start: Float, height: Float, range: ClosedFloatingPointRange<Float>) {
        val box = compose.onNodeWithTag("browser-viewport").fetchSemanticsNode().boundsInRoot
        val rootOnScreen = IntArray(2)
        compose.runOnIdle { compose.activity.window.decorView.getLocationOnScreen(rootOnScreen) }
        val x = box.center.x + rootOnScreen[0]
        var y = box.center.y + rootOnScreen[1]
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, x, y)
        try {
            var fraction = 0f
            for (step in 1..50) {
                y -= height * 0.04f
                event(down, MotionEvent.ACTION_MOVE, x, y)
                SystemClock.sleep(30)
                fraction = hidden(start) / height
                if (fraction >= range.start) break
            }
            assertTrue("While the finger is down, partial hiding $fraction should be in $range", fraction in range)
        } finally {
            event(down, MotionEvent.ACTION_UP, x, y)
        }
    }

    private fun event(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
    }
}
