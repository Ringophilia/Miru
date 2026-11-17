// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.data.ToolbarPosition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolbarTabSwipeIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as MiruApplication).container
    private val browser get() = container.browser
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val tabs = BrowserTabFixture()
    private fun toolbar() = compose.onNodeWithTag("browser-viewport-toolbar")
    private fun label(id: Int) = compose.activity.getString(id)

    @Before fun ready() {
        val skip = label(R.string.intro_skip)
        compose.waitUntil(15_000) {
            runBlocking { container.settings.settings.first().introCompleted } ||
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText(skip).performClick()
        compose.waitUntil(30_000) { browser.state.value.ready }
        compose.runOnIdle { tabs.capture(browser) }
    }

    @After fun restore() { compose.runOnIdle { tabs.closeCreatedTabs(browser) } }

    @Test fun capturedPagesFollowTheFingerAndDistanceOrSpeedSwitchesAdjacentTabs() {
        TestPages().use { pages ->
            val red = openPage("${pages.baseUrl}/red", "Red preview")
            val blue = openPage("${pages.baseUrl}/blue", "Blue preview")
            compose.runOnIdle { browser.selectTab(red) }
            awaitSelected(red)

            heldSwipe(-0.30f, duration = 450) {
                assertEquals("Selection should stay on the current tab until release", red, browser.state.value.selectedTabId)
                compose.waitUntil(4_000) { twoPreviewColorsAreVisible() }
            }
            SystemClock.sleep(500)
            assertEquals(red, browser.state.value.selectedTabId)
            heldSwipe(-0.64f, duration = 450)
            awaitSelected(blue)

            val menu = label(app.miru.toolbar.R.string.toolbar_menu)
            compose.onNodeWithContentDescription(menu).performTouchInput { click() }
            compose.onNodeWithText(label(app.miru.menu.R.string.menu_bookmarks)).assertIsDisplayed()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

            toolbar().performTouchInput {
                swipe(Offset(width * 0.16f, center.y), Offset(width * 0.47f, center.y), durationMillis = 55)
            }
            awaitSelected(red)
        }
    }

    @Test fun privateGesturesStayInTheirOwnGroupAndKeepTheWindowSecure() {
        val originalPosition = runBlocking { container.settings.settings.first().toolbarPosition }
        runBlocking { container.settings.setToolbarPosition(ToolbarPosition.BOTTOM) }
        try {
            TestPages().use { pages ->
                val ordinary = openPage("${pages.baseUrl}/red", "Red preview")
                val first = openPage("${pages.baseUrl}/red", "Red preview", private = true)
                val second = openPage("${pages.baseUrl}/blue", "Blue preview", private = true)
                val viewport = compose.onNodeWithTag("browser-viewport").fetchSemanticsNode().boundsInRoot
                compose.waitUntil(5_000) { toolbar().fetchSemanticsNode().boundsInRoot.center.y > viewport.center.y }
                heldSwipe(-0.65f, duration = 300) // No next private tab.
                SystemClock.sleep(500)
                assertEquals(second, browser.state.value.selectedTabId)
                heldSwipe(0.65f, duration = 300)
                awaitSelected(first)
                heldSwipe(0.65f, duration = 300) // The previous ordinary tab remains out of this group.
                SystemClock.sleep(500)
                assertEquals(first, browser.state.value.selectedTabId)
                assertTrue(browser.state.value.tabs.single { it.id == ordinary }.thumbnail != null)
                assertTrue(browser.state.value.tabs.filter { it.isPrivate }.all { it.thumbnail == null })
                compose.runOnIdle { assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
            }
        } finally { runBlocking { container.settings.setToolbarPosition(originalPosition) } }
    }

    @Test fun switchingOffAnimationInterruptsAnActiveSwipeAndTheNextSwipeCrossfades() {
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        val context = compose.activity.applicationContext
        val previous = Settings.Global.getString(context.contentResolver, setting)?.toFloatOrNull()
        try {
            TestPages().use { pages ->
                val red = openPage("${pages.baseUrl}/red", "Red preview")
                val blue = openPage("${pages.baseUrl}/blue", "Blue preview")
                compose.runOnIdle { browser.selectTab(red) }
                awaitSelected(red)
                heldSwipe(-0.65f, 350) {
                    compose.waitUntil(4_000) { twoPreviewColorsAreVisible() }
                    shell("settings put global $setting 0")
                    compose.waitUntil(5_000) { previewColors()?.let { (left, right) -> left == right && Color.red(left) > Color.blue(left) * 2 } == true }
                }
                assertEquals(red, browser.state.value.selectedTabId)
                heldSwipe(-0.65f, 350) {
                    compose.waitUntil(5_000) { previewColors()?.let { (left, right) -> left == right && Color.blue(left) > Color.red(left) } == true }
                }
                awaitSelected(blue)
            }
        } finally {
            shell(if (previous == null) "settings delete global $setting" else "settings put global $setting $previous")
        }
    }

    private fun openPage(url: String, title: String, private: Boolean = false): String {
        val id = compose.runOnIdle { browser.newTab(url, isPrivate = private) }
        compose.waitUntil(20_000) { browser.state.value.selectedTab?.let { it.id == id && it.title == title && !it.loading && (private || it.thumbnail != null) } == true }
        return id
    }

    private fun awaitSelected(id: String) = compose.waitUntil(5_000) { browser.state.value.selectedTabId == id }

    private fun heldSwipe(fraction: Float, duration: Long, inspect: () -> Unit = {}) {
        val bounds = toolbar().fetchSemanticsNode().boundsInRoot
        val root = rootPosition()
        val start = bounds.left + bounds.width * (if (fraction < 0) 0.85f else 0.15f) + root[0]
        val y = bounds.center.y + root[1]
        val end = start + bounds.width * fraction
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, start, y)
        try {
            for (step in 1..12) {
                SystemClock.sleep(duration / 12)
                event(down, MotionEvent.ACTION_MOVE, start + (end - start) * step / 12, y)
            }
            inspect()
        } finally { event(down, MotionEvent.ACTION_UP, end, y) }
    }

    private fun event(down: Long, kind: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), kind, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
    }

    private fun rootPosition(): IntArray = compose.runOnIdle { IntArray(2).also(compose.activity.window.decorView::getLocationOnScreen) }

    private fun twoPreviewColorsAreVisible(): Boolean = previewColors()?.let { (left, right) ->
        Color.red(left) > Color.blue(left) * 2 && Color.blue(right) > Color.red(right) * 2
    } == true

    private fun previewColors(): Pair<Int, Int>? {
        val root = rootPosition()
        val bounds = compose.onNodeWithTag("browser-viewport").fetchSemanticsNode().boundsInRoot
        val image = automation.takeScreenshot() ?: return null
        return try {
            val y = (bounds.center.y + root[1]).toInt()
            val left = image.getPixel((bounds.left + bounds.width * 0.15f + root[0]).toInt(), y)
            val right = image.getPixel((bounds.left + bounds.width * 0.88f + root[0]).toInt(), y)
            left to right
        } finally { image.recycle() }
    }

    private fun shell(command: String) {
        val descriptor = automation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
