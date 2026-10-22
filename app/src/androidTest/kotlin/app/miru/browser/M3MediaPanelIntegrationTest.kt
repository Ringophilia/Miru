// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M3MediaPanelIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mediaPanelProbesAndShowsManifestDimensions() {
        ProbeOrigin().use { origin ->
            val container = (compose.activity.application as MiruApplication).container
            compose.runOnIdle { container.browser.newTab("${origin.url}/page") }
            compose.waitUntil(60_000) {
                val id = container.browser.state.value.selectedTabId
                container.browser.media.value[id]?.any { it.kind == "HLS" } == true
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            compose.onNodeWithContentDescription(context.getString(app.miru.toolbar.R.string.toolbar_media)).performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText("1080P", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("1920 × 1080", substring = true).assertIsDisplayed()
            assertTrue("The visible panel must issue its own bounded manifest probe", origin.calls.count { it.path == "/master.m3u8" } >= 2)
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        }
    }
}
