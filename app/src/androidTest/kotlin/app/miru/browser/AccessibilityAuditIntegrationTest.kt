// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityAuditIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val container get() = (compose.activity.application as MiruApplication).container
    private fun label(id: Int) = compose.activity.getString(id)
    private fun min48Px() = compose.density.density * 48f - 1.5f

    private fun openSettings() {
        compose.runOnIdle { container.browser.newTab() }
        compose.onAllNodesWithText(label(app.miru.ntp.R.string.ntp_search_hint))[1].performClick()
        compose.onNode(hasSetTextAction()).performTextInput("miru://settings")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNodeWithText(label(app.miru.settings.R.string.settings_title)).assertIsDisplayed()
    }

    @Test fun settingsPlayerDownloadsAndAdblockExposeHeadingsRadioGroupsSwitchDescriptionsAnd48dpTargets() {
        compose.waitUntil(30_000) { container.browser.state.value.ready }
        val tabs = BrowserTabFixture()
        compose.runOnIdle { tabs.capture(container.browser) }
        try {
            compose.runOnIdle { container.browser.newTab() }
            val minPx = min48Px()
            val restAddress = compose.onNodeWithTag("address-rest-box").fetchSemanticsNode()
            val tabsBtn = compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_tabs)).fetchSemanticsNode()
            val menuBtn = compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_menu)).fetchSemanticsNode()
            assertTrue(restAddress.touchBoundsInRoot.height >= minPx)
            assertTrue(tabsBtn.touchBoundsInRoot.height >= minPx && tabsBtn.touchBoundsInRoot.width >= minPx)
            assertTrue(menuBtn.touchBoundsInRoot.height >= minPx && menuBtn.touchBoundsInRoot.width >= minPx)

            openSettings()

            // Settings headings and selectable toolbar cards.
            listOf(
                app.miru.settings.R.string.basic,
                app.miru.settings.R.string.appearance,
                app.miru.settings.R.string.privacy_and_downloads,
                app.miru.settings.R.string.downloads_and_player,
            ).forEach { headingId ->
                compose.onNode(
                    hasText(label(headingId)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
                ).performScrollTo().assertIsDisplayed()
            }
            compose.onNodeWithTag("toolbar-position-TOP").performScrollTo().let { node ->
                val bounds = node.fetchSemanticsNode()
                assertTrue(bounds.touchBoundsInRoot.height >= minPx)
            }

            // ChoiceDialog exposes heading, SelectableGroup, Role.RadioButton, and >= 48dp rows.
            compose.onNodeWithText(label(app.miru.settings.R.string.theme)).performScrollTo().performClick()
            compose.onNode(
                hasText(label(app.miru.settings.R.string.theme_dark)) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
            ).let { radio ->
                assertTrue(radio.fetchSemanticsNode().touchBoundsInRoot.height >= minPx)
            }
            compose.onNodeWithText(label(app.miru.settings.R.string.close)).performClick()

            // Adblock switch contentDescription.
            compose.onNodeWithText(label(app.miru.settings.R.string.adblock)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(label(app.miru.adblock.R.string.global_switch)).assertIsDisplayed()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

            // Download settings switch & slider descriptions.
            compose.onNodeWithText(label(app.miru.settings.R.string.downloads)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(label(app.miru.downloads.R.string.download_ask)).assertIsDisplayed()
            compose.onNodeWithContentDescription(label(app.miru.downloads.R.string.download_video_wifi_only)).assertIsDisplayed()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

            // Player settings headings & MiruChoiceDialog radio buttons.
            compose.onNodeWithText(label(app.miru.settings.R.string.player)).performScrollTo().performClick()
            compose.onNode(
                hasText(label(app.miru.player.R.string.player_quality)) and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            ).assertIsDisplayed()
            compose.onNode(
                hasText(label(app.miru.player.R.string.player_behavior)) and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            ).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(label(app.miru.player.R.string.player_default_decoder)).performScrollTo().performClick()
            compose.onNode(
                hasText(label(app.miru.player.R.string.player_decoder_hardware)) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
            ).let { radio ->
                assertTrue(radio.fetchSemanticsNode().touchBoundsInRoot.height >= minPx)
            }
            compose.onNodeWithText(label(app.miru.player.R.string.player_cancel)).performClick()
        } finally {
            compose.runOnIdle { tabs.closeCreatedTabs(container.browser) }
        }
    }
}
