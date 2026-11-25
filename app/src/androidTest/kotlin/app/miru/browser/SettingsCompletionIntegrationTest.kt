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
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.miru.common.SearchEngine
import app.miru.data.ToolbarPosition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsCompletionIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val container get() = (compose.activity.application as MiruApplication).container
    private fun label(id: Int) = compose.activity.getString(id)

    private fun openSettings() {
        compose.runOnIdle { container.browser.newTab() }
        compose.onAllNodesWithText(label(app.miru.ntp.R.string.ntp_search_hint))[1].performClick()
        compose.onNode(hasSetTextAction()).performTextInput("miru://settings")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNodeWithText(label(app.miru.settings.R.string.settings_title)).assertIsDisplayed()
    }

    @Test fun toolbarPositionCardsCustomSearchShortcutsAndExitQuitWorkEndToEnd() {
        compose.waitUntil(30_000) { container.browser.state.value.ready }
        val tabs = BrowserTabFixture()
        compose.runOnIdle { tabs.capture(container.browser) }
        val before = runBlocking { container.settings.settings.first() }
        try {
            openSettings()

            // Visual toolbar position cards switch TOP <-> BOTTOM.
            compose.onNodeWithTag("toolbar-position-${ToolbarPosition.BOTTOM.name}").performScrollTo().performClick()
            compose.waitUntil(5_000) { runBlocking { container.settings.settings.first() }.toolbarPosition == ToolbarPosition.BOTTOM }
            compose.onNodeWithTag("toolbar-position-${ToolbarPosition.TOP.name}").performScrollTo().performClick()
            compose.waitUntil(5_000) { runBlocking { container.settings.settings.first() }.toolbarPosition == ToolbarPosition.TOP }

            // Custom search rejects invalid templates and saves valid templates.
            compose.onNodeWithText(label(app.miru.settings.R.string.search_engine)).performScrollTo().performClick()
            compose.onNode(
                hasText(label(app.miru.settings.R.string.search_custom)) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
            ).performClick()
            compose.onNodeWithTag("custom-search-input").performTextReplacement("https://example.org/search")
            compose.onNodeWithTag("custom-search-save").performClick()
            compose.onNodeWithText(label(app.miru.settings.R.string.search_custom_invalid)).assertIsDisplayed()
            compose.onNodeWithTag("custom-search-input").performTextReplacement("https://example.org/search?q=%s")
            compose.onNodeWithTag("custom-search-save").performClick()
            compose.waitUntil(5_000) {
                val updated = runBlocking { container.settings.settings.first() }
                updated.searchEngine == SearchEngine.CUSTOM && updated.customSearchUrl == "https://example.org/search?q=%s"
            }
            compose.onNodeWithText(label(app.miru.settings.R.string.search_suggestions_custom_disabled)).performScrollTo().assertIsDisplayed()

            // Clear on exit shows Quit in overflow menu; NTP shortcuts toggle hides NTP grid.
            val exitLabel = label(app.miru.settings.R.string.clear_on_exit)
            compose.onNodeWithContentDescription(exitLabel).performScrollTo().performClick()
            compose.waitUntil(5_000) { runBlocking { container.settings.settings.first() }.clearDataOnExit }
            compose.onNodeWithText(label(app.miru.settings.R.string.clear_on_exit_types)).performScrollTo().assertIsDisplayed()

            val shortcutsLabel = label(app.miru.settings.R.string.ntp_show_shortcuts)
            compose.onNodeWithContentDescription(shortcutsLabel).performScrollTo().performClick()
            compose.waitUntil(5_000) { !runBlocking { container.settings.settings.first() }.ntpShowShortcuts }

            // Return to NTP: shortcuts hidden, menu shows Quit.
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText(label(app.miru.ntp.R.string.shortcut_google)).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_menu)).performClick()
            compose.onNodeWithText(label(app.miru.menu.R.string.menu_quit)).assertIsDisplayed()
        } finally {
            runBlocking {
                container.settings.setToolbarPosition(before.toolbarPosition)
                container.settings.setSearchEngine(if (before.searchEngine == SearchEngine.CUSTOM) SearchEngine.GOOGLE else before.searchEngine)
                container.settings.setSearchSuggestions(before.searchSuggestions)
                container.settings.setWebZoomPercent(before.webZoomPercent)
                container.settings.setNtpShowShortcuts(before.ntpShowShortcuts)
                container.settings.setClearDataOnExit(before.clearDataOnExit)
                container.settings.setClearOnExitTypes(before.clearOnExitTypes)
            }
            compose.runOnIdle { tabs.closeCreatedTabs(container.browser) }
        }
    }

    @Test fun siteSettingsListsSavedPermissionAndResetsCleanlyAndAddressLongPressShowsMenu() {
        compose.waitUntil(30_000) { container.browser.state.value.ready }
        val tabs = BrowserTabFixture()
        compose.runOnIdle { tabs.capture(container.browser) }
        TestPages().use { server ->
            val pageUrl = "${server.baseUrl}/first"
            val origin = server.baseUrl
            try {
                // Enable a real persistent site permission via Site Info UI.
                compose.runOnIdle { container.browser.newTab(pageUrl) }
                compose.waitUntil(20_000) {
                    container.browser.state.value.selectedTab?.let { it.title == "Miru One" && !it.loading } == true
                }
                compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_site_info)).performClick()
                compose.onNodeWithContentDescription(label(R.string.popup_site_allow)).performScrollTo().performClick()
                compose.waitUntil(5_000) { runBlocking { container.browser.listSavedSites() }.any { it.origin == origin } }
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

                // Open Site Settings, verify saved origin appears, and reset it.
                openSettings()
                compose.onNodeWithTag("settings-site-settings").performScrollTo().performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText(origin.removePrefix("http://")).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("site-reset-$origin").performClick()
                compose.waitUntil(5_000) { runBlocking { container.browser.listSavedSites() }.none { it.origin == origin } }

                // Verify Web zoom preference updates engine cleanly and restores.
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithText(label(app.miru.settings.R.string.web_zoom)).performScrollTo().performClick()
                compose.onNodeWithTag("web-zoom-reset").assertIsDisplayed()
                runBlocking { container.settings.setWebZoomPercent(130) }
                assertEquals(130, runBlocking { container.settings.settings.first() }.webZoomPercent)
                compose.onNodeWithTag("web-zoom-reset").performClick()
                compose.onNodeWithTag("web-zoom-save").performClick()
                compose.waitUntil(5_000) { runBlocking { container.settings.settings.first() }.webZoomPercent == 100 }

                // Return to web page and verify resting address bar long-press reveals Copy link.
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.runOnIdle { container.browser.navigate(pageUrl) }
                compose.waitUntil(10_000) {
                    container.browser.state.value.selectedTab?.url == pageUrl
                }
                compose.onNodeWithTag("address-rest-box").performTouchInput { longClick() }
                compose.onNodeWithText(label(app.miru.toolbar.R.string.toolbar_copy_link)).assertIsDisplayed().performClick()
                compose.onNodeWithText(label(app.miru.toolbar.R.string.toolbar_link_copied)).assertIsDisplayed()
            } finally {
                runBlocking {
                    container.browser.resetSavedSite(origin)
                    container.settings.setWebZoomPercent(100)
                }
                compose.runOnIdle { tabs.closeCreatedTabs(container.browser) }
            }
        }
    }
}
