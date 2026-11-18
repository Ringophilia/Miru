// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.data.ToolbarPosition
import app.miru.design.AnchoredMenu
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverflowMenuMotionTest {
    @get:Rule val compose = createComposeRule()
    private var expanded by mutableStateOf(false)
    private fun panel() = compose.onNodeWithTag("test-overflow")
    private fun row(title: String) = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot

    @Test fun wholeMenuScalesFromItsTopRightAndClosesWithShorterTiming() {
        showMenu(reducedMotion = false)
        changeExpanded(true, frames = 2)
        val early = panel().fetchSemanticsNode().boundsInRoot
        val earlyGap = row("Second").center.y - row("First").center.y
        compose.mainClock.advanceTimeBy(224)
        val open = panel().fetchSemanticsNode().boundsInRoot
        val openGap = row("Second").center.y - row("First").center.y
        val scale = early.width / open.width
        assertTrue("Opening scale was $scale", scale in 0.84f..0.98f)
        assertEquals(open.right, early.right, 1f)
        assertEquals(open.top, early.top, 1f)
        assertEquals(scale * openGap, earlyGap, 2f)

        changeExpanded(false, frames = 3)
        val closing = panel().fetchSemanticsNode().boundsInRoot
        assertTrue(closing.width < open.width)
        compose.mainClock.advanceTimeBy(96)
        panel().assertDoesNotExist()
    }

    @Test fun reducedMotionKeepsMenuAtItsFinalSize() {
        showMenu(reducedMotion = true)
        changeExpanded(true, frames = 2)
        val early = panel().fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(176)
        val open = panel().fetchSemanticsNode().boundsInRoot
        assertEquals(open, early)

        changeExpanded(false, frames = 3)
        assertEquals(open, panel().fetchSemanticsNode().boundsInRoot)
        compose.mainClock.advanceTimeBy(176)
        panel().assertDoesNotExist()
    }

    private fun showMenu(reducedMotion: Boolean) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                    Box {
                        Text("Anchor")
                        AnchoredMenu(expanded, { expanded = false }, reducedMotion, Modifier.width(240.dp).testTag("test-overflow")) {
                            DropdownMenuItem(text = { Text("First") }, onClick = {})
                            DropdownMenuItem(text = { Text("Second") }, onClick = {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun changeExpanded(value: Boolean, frames: Int) {
        compose.runOnUiThread { expanded = value }
        repeat(frames) { compose.mainClock.advanceTimeByFrame() }
    }
}

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OverflowMenuIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as MiruApplication).container
    private fun label(id: Int) = compose.activity.getString(id)

    @Test fun browserMenuFollowsBothToolbarPositionsAndKeepsItsActions() {
        startBrowser()
        val settings = container.settings
        val original = runBlocking { settings.settings.first().toolbarPosition }
        val tabs = BrowserTabFixture().also { compose.runOnIdle { it.capture(container.browser) } }
        val menuButton = hasContentDescription(label(app.miru.toolbar.R.string.toolbar_menu))
        val menuAnchor = hasTestTag("browser-overflow-anchor")
        val menu = hasTestTag("browser-overflow-menu")
        val screenMiddle = compose.activity.resources.displayMetrics.heightPixels / 2f
        try {
            for (position in listOf(ToolbarPosition.TOP, ToolbarPosition.BOTTOM)) {
                runBlocking { settings.setToolbarPosition(position) }
                compose.waitUntil(8_000) {
                    val button = compose.onAllNodes(menuAnchor).fetchSemanticsNodes().singleOrNull()?.screenBounds()
                    button != null && (button.center.y > screenMiddle) == (position == ToolbarPosition.BOTTOM)
                }
                val anchor = compose.onNode(menuAnchor).fetchSemanticsNode().screenBounds()
                compose.onNode(menuButton).performClick()
                compose.waitUntilAtLeastOneExists(menu, 8_000)
                assertPlacement(anchor, popupBounds(), above = position == ToolbarPosition.BOTTOM)
                compose.onNodeWithText(label(app.miru.menu.R.string.menu_new_tab)).assertIsDisplayed()
                pressBack()
                compose.waitUntilDoesNotExist(menu, 8_000)
            }

            val browser = container.browser
            val previousIds = browser.state.value.tabs.map { it.id }.toSet()
            compose.onNode(menuButton).performClick()
            compose.onNodeWithText(label(app.miru.menu.R.string.menu_new_tab)).performClick()
            compose.waitUntilDoesNotExist(menu, 8_000)
            compose.waitUntil(8_000) { browser.state.value.selectedTab?.let { it.id !in previousIds && it.isNewTab } == true }
            assertEquals(1, browser.state.value.tabs.count { it.id !in previousIds })
        } finally {
            runBlocking { settings.setToolbarPosition(original) }
            compose.runOnIdle { tabs.closeCreatedTabs(container.browser) }
        }
    }

    @Test fun tabMenuStaysWithTheTrayAndHonorsTheActiveGroup() {
        startBrowser()
        compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_tabs)).performClick()
        val menuButton = compose.onNodeWithContentDescription(label(app.miru.tabs.R.string.tabs_menu))
        val closeAll = compose.onNodeWithText(label(app.miru.tabs.R.string.tabs_close_all))
        val menu = hasTestTag("tabs-overflow-menu")
        val anchor = compose.onNodeWithTag("tabs-overflow-anchor").fetchSemanticsNode().screenBounds()
        menuButton.performClick()
        compose.waitUntilAtLeastOneExists(menu, 8_000)
        assertPlacement(anchor, popupBounds(), above = false)
        closeAll.assertIsDisplayed().assertIsEnabled()
        pressBack()
        compose.waitUntilDoesNotExist(menu, 8_000)
        compose.onNodeWithTag("tabs-tray").assertIsDisplayed()

        compose.onNodeWithText(label(app.miru.tabs.R.string.tabs_incognito)).performClick()
        menuButton.performClick()
        closeAll.assertIsDisplayed().assertIsNotEnabled()
        pressBack()
        compose.waitUntilDoesNotExist(menu, 8_000)
    }

    private fun startBrowser() {
        val skip = label(R.string.intro_skip)
        compose.waitUntil(15_000) {
            runBlocking { container.settings.settings.first().introCompleted } ||
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText(skip).performClick()
        val browser = container.browser
        compose.waitUntil(45_000) { browser.state.value.let { it.ready || it.error != null } }
        if (!browser.state.value.ready) {
            // A cold Gecko permission migration can time out while the native profile is starting.
            // Relaunch the browser UI once so the menu test starts with a restored browser session.
            compose.activityRule.scenario.recreate()
            compose.waitUntil(45_000) { browser.state.value.ready }
        }
        compose.waitUntilAtLeastOneExists(hasContentDescription(label(app.miru.toolbar.R.string.toolbar_menu)), 15_000)
    }

    private fun pressBack() {
        val pipe = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
    }

    private fun popupBounds(): Rect = compose.onNode(isPopup(), useUnmergedTree = true).fetchSemanticsNode().screenBounds()

    private fun SemanticsNode.screenBounds(): Rect {
        val coordinates = layoutInfo.coordinates
        val end = Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
        return Rect(coordinates.localToScreen(Offset.Zero), coordinates.localToScreen(end))
    }

    private fun assertPlacement(anchor: Rect, popup: Rect, above: Boolean) {
        val message = "Anchor $anchor, popup $popup, above=$above"
        assertEquals(message, anchor.right, popup.right, 1f)
        if (above) assertEquals(message, anchor.top, popup.bottom, 1f)
        else assertEquals(message, anchor.bottom, popup.top, 1f)
    }
}
