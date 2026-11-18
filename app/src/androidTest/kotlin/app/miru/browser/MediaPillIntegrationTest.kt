// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaPillIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as MiruApplication).container

    @Test fun realPageAddsAnotherVideoAndThePillUpdatesOnThatTab() {
        val skip = compose.activity.getString(R.string.intro_skip)
        compose.waitUntil(15_000) {
            runBlocking { container.settings.settings.first().introCompleted } ||
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText(skip).performClick()
        compose.waitUntil(30_000) { container.browser.state.value.ready }
        val name = compose.activity.getString(app.miru.toolbar.R.string.toolbar_media)
        val tabs = BrowserTabFixture()
        compose.runOnIdle { tabs.capture(container.browser) }
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        try {
            ProbeOrigin(assets.open("media/fixture.mp4").use { it.readBytes() }).use { origin ->
                val id = compose.runOnIdle { container.browser.newTab("${origin.url}/media-count") }
                awaitCount(name, 1)
                origin.releaseExtraVideo.countDown()
                awaitCount(name, 2)
                compose.onNodeWithContentDescription(name).performClick()
                val sheetTitle = compose.activity.getString(app.miru.media.R.string.media_title)
                compose.onNodeWithText(sheetTitle).assertIsDisplayed()
                val back = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4")
                ParcelFileDescriptor.AutoCloseInputStream(back).use { it.readBytes() }
                compose.waitUntil(5_000) { compose.onAllNodesWithText(sheetTitle).fetchSemanticsNodes().isEmpty() }
                assertEquals(id, container.browser.state.value.selectedTabId)

                compose.runOnIdle { container.browser.newTab() }
                compose.waitUntil(5_000) { container.browser.state.value.selectedTab?.isNewTab == true }
                compose.onNodeWithContentDescription(name).assertDoesNotExist()
                compose.runOnIdle { container.browser.selectTab(id) }
                compose.waitUntil(5_000) { container.browser.state.value.selectedTabId == id }
                awaitCount(name, 2)
            }
        } finally { compose.runOnIdle { tabs.closeCreatedTabs(container.browser) } }
    }

    private fun awaitCount(name: String, count: Int) {
        val description = compose.activity.resources.getQuantityString(app.miru.toolbar.R.plurals.toolbar_media_count, count, count)
        val match = hasContentDescription(name) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)
        compose.waitUntil(20_000) { compose.onAllNodes(match).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(match).assertIsDisplayed()
    }
}
