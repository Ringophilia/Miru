// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser

import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.menu.R as MenuStrings
import app.miru.toolbar.R as ToolbarStrings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarkMotionIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as MiruApplication).container
    private val star = hasTestTag("bookmark-menu-star")
    private val shownAsSaved get() = hasContentDescription(label(MenuStrings.string.menu_bookmark_remove))

    @Test fun savingARealBookmarkPulsesBeforeDismissingTheMenu() = verifySave("1", expectPulse = true)

    @Test fun systemAnimationScaleZeroKeepsTheStarAtItsRestingSize() = verifySave("0", expectPulse = false)

    private fun verifySave(scale: String, expectPulse: Boolean) {
        val skip = label(R.string.intro_skip)
        compose.waitUntil(20_000) {
            runBlocking { container.settings.settings.first().introCompleted } ||
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText(skip).performClick()
        compose.waitUntil(30_000) { container.browser.state.value.ready }
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        val previousScale = Settings.Global.getString(compose.activity.contentResolver, setting)
        val tabs = BrowserTabFixture()
        val browser = container.browser
        val library = container.library.bookmarks
        try {
            shell("settings put global $setting $scale")
            TestPages().use { pages ->
                val url = "${pages.baseUrl}/first"
                compose.runOnIdle { tabs.capture(browser); browser.newTab(url) }
                try {
                    compose.waitUntil(20_000) {
                        browser.state.value.selectedTab?.let { it.url == url && it.title == "Miru One" && !it.loading } == true
                    }
                    compose.onNodeWithContentDescription(label(ToolbarStrings.string.toolbar_menu)).performClick()
                    val add = compose.onNodeWithContentDescription(label(MenuStrings.string.menu_bookmark)).assertIsDisplayed()
                    val rest = starWidth()
                    compose.mainClock.autoAdvance = false
                    add.performClick()
                    compose.waitUntil(8_000) { runBlocking { library.find(url) } != null }
                    repeat(12) {
                        if (compose.onAllNodes(shownAsSaved).fetchSemanticsNodes().isEmpty()) compose.mainClock.advanceTimeByFrame()
                    }
                    compose.onNode(shownAsSaved).assertExists()
                    compose.mainClock.advanceTimeBy(96)
                    val peak = starWidth()
                    if (expectPulse) assertTrue("Star should approach its 1.25× peak: $peak / $rest", peak / rest in 1.20f..1.27f)
                    else assertEquals("Reduce motion must keep the icon in place", rest, peak, 0.5f)
                    compose.mainClock.advanceTimeBy(650)
                    compose.onNode(star, useUnmergedTree = true).assertDoesNotExist()
                    compose.mainClock.autoAdvance = true
                    compose.onNodeWithText(label(R.string.edit)).assertIsDisplayed()
                } finally {
                    compose.mainClock.autoAdvance = true
                    runBlocking { library.remove(url) }
                    compose.runOnIdle { tabs.closeCreatedTabs(browser) }
                }
            }
        } finally {
            compose.mainClock.autoAdvance = true
            shell(if (previousScale == null) "settings delete global $setting" else "settings put global $setting $previousScale")
        }
    }

    private fun starWidth(): Float {
        val star = compose.onNode(star, useUnmergedTree = true).fetchSemanticsNode().layoutInfo.coordinates
        return star.localToScreen(Offset(star.size.width.toFloat(), 0f)).x - star.localToScreen(Offset.Zero).x
    }

    private fun label(id: Int) = compose.activity.getString(id)

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
