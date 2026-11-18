// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class TabTrayMotionIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>(effectContext = StandardTestDispatcher())
    private val container get() = (compose.activity.application as MiruApplication).container
    private val browser get() = container.browser
    private val tabs = BrowserTabFixture()
    private fun label(id: Int) = compose.activity.getString(id)
    private fun trayButton() = compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_tabs))
    private fun preview() = compose.onNodeWithTag("tabs-container-preview", useUnmergedTree = true)
    private fun plus() = compose.onNodeWithTag("tabs-add-button")
    private fun newPreview() = compose.onNodeWithTag("tabs-new-page-preview", useUnmergedTree = true)
    private fun card(id: String) = compose.onNodeWithTag("tabs-card-$id")

    @Before fun startBrowser() {
        val skip = label(R.string.intro_skip)
        compose.waitUntil(15_000) {
            runBlocking { container.settings.settings.first().introCompleted } ||
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText(skip).performClick()
        compose.waitUntil(30_000) { browser.state.value.ready }
        compose.waitUntilAtLeastOneExists(hasTestTag("browser-viewport"), 15_000)
        compose.waitUntilAtLeastOneExists(hasContentDescription(label(app.miru.toolbar.R.string.toolbar_tabs)), 15_000)
        compose.runOnIdle { tabs.capture(browser) }
    }

    @Test fun newPageExpandsFromTheActualPlusButtonInBothGroups() {
        val layoutTolerance = 2f * compose.activity.resources.displayMetrics.density
        for (private in listOf(false, true)) {
            val oldIds = browser.state.value.tabs.mapTo(mutableSetOf()) { it.id }
            val viewport = compose.onNodeWithTag("browser-viewport").fetchSemanticsNode().boundsInRoot
            trayButton().performClick()
            compose.onNodeWithText(label(if (private) app.miru.tabs.R.string.tabs_incognito else app.miru.tabs.R.string.tabs_regular)).performClick()
            val anchor = plus().assertIsDisplayed().fetchSemanticsNode().boundsInRoot

            compose.mainClock.autoAdvance = false
            plus().performClick()
            val early = movingBounds("tabs-new-page-preview", afterStartMillis = 32)
            assertTrue("New page should expand between its button and the viewport", early.width in (anchor.width + 1)..(viewport.width - 1))
            // IconButton's visible semantics and touch target share a center but have different sizes.
            val travel = (early.center.y - anchor.center.y) / (viewport.center.y - anchor.center.y)
            val expectedCenter = lerp(anchor.center, viewport.center, travel)
            val bounds = "Button $anchor, viewport $viewport, preview $early"
            assertTrue(bounds, travel in 0f..1f)
            assertEquals(bounds, expectedCenter.x, early.center.x, layoutTolerance)
            compose.mainClock.advanceTimeBy(96)
            assertTrue("The new page should continue widening", newPreview().fetchSemanticsNode().boundsInRoot.width > early.width)
            compose.mainClock.advanceTimeBy(360)
            newPreview().assertDoesNotExist()
            compose.onNodeWithTag("tabs-tray").assertDoesNotExist()
            compose.mainClock.autoAdvance = true
            compose.waitUntil(8_000) { browser.state.value.selectedTab?.let { it.id !in oldIds && it.isNewTab && it.isPrivate == private } == true }
            compose.waitUntilAtLeastOneExists(hasTestTag("new-tab-search"), 8_000)
            compose.onNodeWithTag("new-tab-search").assertIsDisplayed()
            assertEquals(1, browser.state.value.tabs.count { it.id !in oldIds })
        }
    }

    @After fun restore() {
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { tabs.closeCreatedTabs(browser) }
    }

    @Test fun currentPageShrinksToItsCardAndClickedCardExpandsToItsPage() {
        TestPages().use { pages ->
            // Put the origin beyond the first row and keep a second visible candidate.
            compose.runOnIdle { repeat(6) { browser.newTab() } }
            val red = open("${pages.baseUrl}/red", "Red preview")
            val blue = open("${pages.baseUrl}/blue", "Blue preview")
            compose.runOnIdle { browser.selectTab(red) }
            compose.waitUntil(10_000) { browser.state.value.selectedTabId == red }
            val viewport = compose.onNodeWithTag("browser-viewport").fetchSemanticsNode().boundsInRoot

            compose.mainClock.autoAdvance = false
            trayButton().performClick()
            val opening = movingBounds()
            compose.mainClock.advanceTimeBy(450)
            preview().assertDoesNotExist()
            val redCard = card(red).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val blueCard = card(blue).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Opening preview should be between page and its card: $opening, $redCard", opening.width in (redCard.width + 1)..(viewport.width - 1))

            card(blue).performClick()
            val closing = movingBounds()
            assertTrue("Clicked card should expand toward the viewport: $closing, $blueCard", closing.width in (blueCard.width + 1)..(viewport.width - 1))
            assertTrue("Exit should start on the clicked card's side", (closing.center.x - viewport.center.x) * (blueCard.center.x - viewport.center.x) > 0)
            compose.mainClock.advanceTimeBy(450)
            preview().assertDoesNotExist()
            compose.onNodeWithTag("tabs-tray").assertDoesNotExist()
            compose.mainClock.autoAdvance = true
            compose.waitUntil(10_000) { browser.state.value.selectedTab?.let { it.id == blue && it.title == "Blue preview" } == true }
        }
    }

    @Test fun reducedMotionUsesOnlyAFadeAndPrivatePageKeepsProtectionBehindTheTray() {
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        val context = compose.activity.applicationContext
        val prior = Settings.Global.getString(context.contentResolver, setting)?.toFloatOrNull()
        try {
            shell("settings put global $setting 0")
            val id = compose.runOnIdle { browser.newTab(isPrivate = true) }
            compose.waitUntil(8_000) { browser.state.value.selectedTabId == id }
            compose.mainClock.autoAdvance = false
            trayButton().performClick()
            compose.mainClock.advanceTimeBy(75)
            preview().assertDoesNotExist()
            compose.mainClock.advanceTimeBy(220)
            card(id).assertIsDisplayed()
            compose.onNodeWithText(label(app.miru.tabs.R.string.tabs_regular)).performClick()
            compose.mainClock.advanceTimeByFrame()
            assertTrue(compose.runOnUiThread {
                compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            })
            compose.onNodeWithContentDescription(label(app.miru.tabs.R.string.tabs_dismiss)).performClick()
            compose.mainClock.advanceTimeBy(230)
            preview().assertDoesNotExist()
            compose.onNodeWithTag("tabs-tray").assertDoesNotExist()
            assertEquals(id, browser.state.value.selectedTabId)

            trayButton().performClick()
            compose.mainClock.advanceTimeBy(230)
            plus().performClick()
            compose.mainClock.advanceTimeBy(75)
            newPreview().assertDoesNotExist()
            compose.mainClock.advanceTimeBy(220)
            compose.mainClock.autoAdvance = true
            compose.waitUntil(8_000) { browser.state.value.selectedTab?.let { it.id != id && it.isPrivate && it.isNewTab } == true }
            compose.waitUntilAtLeastOneExists(hasTestTag("new-tab-search"), 8_000)
            compose.onNodeWithTag("new-tab-search").assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
            shell(if (prior == null) "settings delete global $setting" else "settings put global $setting $prior")
        }
    }

    private fun open(url: String, title: String): String {
        val id = compose.runOnIdle { browser.newTab(url) }
        compose.waitUntil(25_000) { browser.state.value.selectedTab?.let { it.id == id && it.title == title } == true }
        return id
    }

    private fun movingBounds(tag: String = "tabs-container-preview", afterStartMillis: Long = 96): Rect {
        repeat(10) {
            compose.mainClock.advanceTimeByFrame()
            val bounds = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            if (bounds != null && bounds.width > 0) {
                compose.mainClock.advanceTimeBy(afterStartMillis)
                return compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            }
        }
        error("The page did not enter a container transition")
    }

    private fun shell(command: String) {
        val pipe = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
    }
}
