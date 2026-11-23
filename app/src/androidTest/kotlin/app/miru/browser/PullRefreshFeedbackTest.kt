// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.data.ToolbarPosition
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PullRefreshFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as MiruApplication).container

    @Test fun realGeckoPullBuzzesOncePerGestureBeforeRelease() {
        val skip = compose.activity.getString(R.string.intro_skip)
        compose.waitUntil(20_000) {
            runBlocking { container.settings.settings.first().introCompleted } ||
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText(skip).performClick()
        val browser = container.browser
        compose.waitUntil(30_000) { browser.state.value.ready }
        val original = runBlocking { container.settings.settings.first().toolbarPosition }
        val tabs = BrowserTabFixture()
        compose.onNodeWithTag("browser-viewport").assertIsDisplayed()
        val id = compose.runOnIdle { tabs.capture(browser); browser.newTab() }
        try {
            compose.waitUntil(10_000) { browser.state.value.selectedTabId == id }
            for (position in ToolbarPosition.entries) {
                runBlocking { container.settings.setToolbarPosition(position) }
                TestPages().use { pages ->
                    val url = "${pages.baseUrl}/long"
                    compose.runOnIdle { browser.navigate(url) }
                    try {
                        compose.waitUntil(40_000) {
                            pages.longLoads.get() > 0 && browser.state.value.selectedTab?.let {
                                it.id == id && it.url == url && it.title == "Miru long" && !it.loading
                            } == true
                        }
                    } catch (failure: ComposeTimeoutException) {
                        val current = browser.state.value.selectedTab
                        throw AssertionError(
                            "Page did not load for $position, requests=${pages.longLoads.get()}, " +
                                "matchesTab=${current?.id == id}, matchesUrl=${current?.url == url}, " +
                                "title=${current?.title}, loading=${current?.loading}",
                            failure,
                        )
                    }
                    verifyGestures(pages, id)
                }
            }
        } finally {
            runBlocking { container.settings.setToolbarPosition(original) }
            compose.runOnIdle { tabs.closeCreatedTabs(browser) }
        }
    }

    private fun verifyGestures(pages: TestPages, id: String) {
        val layout = compose.runOnIdle { checkNotNull(refreshLayout(compose.activity.window.decorView)) }
        val haptics = CopyOnWriteArrayList<Int>()
        val fraction = AtomicReference(0f)
        val originalFeedback = compose.runOnIdle { layout.performThresholdFeedback }
        val originalIndicator = compose.runOnIdle { layout.onIndicator }
        compose.runOnIdle {
            layout.performThresholdFeedback = { type -> haptics += type; originalFeedback(type) }
            layout.onIndicator = { distance, refreshing ->
                fraction.set(distance)
                originalIndicator?.invoke(distance, refreshing)
            }
        }
        try {
            val location = IntArray(2)
            compose.runOnIdle { layout.getLocationOnScreen(location) }
            val x = location[0] + layout.width / 2f
            val top = location[1] + layout.height * 0.22f
            val edge = location[1] + layout.height * 0.80f
            val smallPull = top + 40 * compose.activity.resources.displayMetrics.density
            val before = pages.longLoads.get()
            val expected = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                else HapticFeedbackConstants.CONTEXT_CLICK

            gesture(x, top) { move ->
                move(smallPull)
                assertTrue(fraction.get() < 1f)
                assertTrue("A short pull must be quiet", haptics.isEmpty())
                move(edge)
                compose.waitUntil(5_000) { haptics.size == 1 && fraction.get() >= 1f }
                compose.onNodeWithTag("browser-pull-refresh").assertIsDisplayed()
                assertEquals("Crossing before release must not yet reload", before, pages.longLoads.get())
                move(smallPull)
                compose.waitUntil(5_000) { fraction.get() < 1f }
                move(edge)
                compose.waitUntil(5_000) { fraction.get() >= 1f }
                assertEquals(listOf(expected), haptics.toList())
                assertEquals(before, pages.longLoads.get())
            }
            compose.waitUntil(15_000) {
                pages.longLoads.get() > before && container.browser.state.value.selectedTab?.let { it.id == id && !it.loading } == true
            }
            assertEquals(before + 1, pages.longLoads.get())
            assertEquals(listOf(expected), haptics.toList())
        } finally {
            compose.runOnIdle {
                layout.performThresholdFeedback = originalFeedback
                layout.onIndicator = originalIndicator
            }
        }
    }

    private fun gesture(x: Float, startY: Float, block: (move: (Float) -> Unit) -> Unit) {
        val down = SystemClock.uptimeMillis()
        var y = startY
        var completed = false
        inject(down, MotionEvent.ACTION_DOWN, x, y)
        try {
            block { destination ->
                val start = y
                repeat(16) { step ->
                    y = start + (destination - start) * (step + 1) / 16f
                    inject(down, MotionEvent.ACTION_MOVE, x, y)
                    SystemClock.sleep(24)
                }
            }
            completed = true
        } finally {
            inject(down, if (completed) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL, x, y)
        }
    }

    private fun inject(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try { assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private fun refreshLayout(view: View): GeckoRefreshLayout? {
        if (view is GeckoRefreshLayout) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            refreshLayout(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
