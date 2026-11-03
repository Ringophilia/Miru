// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.miru.common.AccentColor
import app.miru.data.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real settings dialogs, persisted preferences and the rendered Material primary color. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class AccentThemeIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as MiruApplication).container
    private fun label(id: Int) = compose.activity.getString(id)
    private fun accentRow() = compose.onNode(hasText(label(app.miru.settings.R.string.accent_color)) and hasClickAction())
    private fun openSettings() {
        compose.runOnIdle { container.browser.newTab() }
        compose.onAllNodesWithText(label(app.miru.ntp.R.string.ntp_search_hint))[1].performClick()
        compose.onNode(hasSetTextAction()).performTextInput("miru://settings")
        compose.onNode(hasSetTextAction()).performImeAction()
        accentRow().performScrollTo()
    }
    private fun choose(accent: AccentColor, id: Int) {
        accentRow().performScrollTo().performClick()
        compose.onNode(hasText(label(id)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
        compose.waitUntil(5000) { runBlocking { container.settings.settings.first() }.accent == accent }
    }
    private fun renderedPrimary(): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label(app.miru.settings.R.string.appearance)).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style.color.toArgb()
    }
    private fun assertPrimary(argb: Long) {
        compose.waitUntil(5000) { runCatching { renderedPrimary() == argb.toInt() }.getOrDefault(false) }
        assertEquals(argb.toInt(), renderedPrimary())
    }

    @Test fun allSixPresetsChangeTheVisibleThemeAndDynamicColorRestoresTheSavedChoice() = fixture {
        openSettings()
        val choices = listOf(
            Triple(AccentColor.RINGO_RED, app.miru.settings.R.string.accent_ringo_red, 0xFFBE1625),
            Triple(AccentColor.LEAF_GREEN, app.miru.settings.R.string.accent_leaf_green, 0xFF47643B),
            Triple(AccentColor.NAVY, app.miru.settings.R.string.accent_navy, 0xFF2E4A7D),
            Triple(AccentColor.TEAL, app.miru.settings.R.string.accent_teal, 0xFF00897B),
            Triple(AccentColor.PURPLE, app.miru.settings.R.string.accent_purple, 0xFF7C4DFF),
            Triple(AccentColor.GRAPHITE, app.miru.settings.R.string.accent_graphite, 0xFF455A64),
        )
        choices.forEach { (accent, id, color) -> choose(accent, id); assertPrimary(color) }
        val dynamic = compose.onNodeWithContentDescription(label(app.miru.settings.R.string.dynamic_color))
        dynamic.performScrollTo().performClick()
        compose.waitUntil(3000) { runBlocking { container.settings.settings.first() }.dynamicColor }
        accentRow().assertIsNotEnabled()
        assertPrimary(dynamicLightColorScheme(compose.activity).primary.toArgb().toLong() and 0xffffffffL)
        dynamic.performClick()
        accentRow().assertIsEnabled()
        assertPrimary(0xFF455A64)
        assertEquals(AccentColor.GRAPHITE, runBlocking { container.settings.settings.first() }.accent)
    }

    @Test fun selectionSurvivesActivityRecreationAndPrivateTabsUseItsDarkPalette() = fixture {
        openSettings(); choose(AccentColor.PURPLE, app.miru.settings.R.string.accent_purple)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { container.browser.state.value.ready }
        // The saved route returns to settings and reloads the stored accent.
        accentRow().performScrollTo(); assertPrimary(0xFF7C4DFF)
        compose.onNodeWithText(label(app.miru.settings.R.string.theme)).performScrollTo().performClick()
        compose.onNodeWithText(label(app.miru.settings.R.string.theme_dark)).performClick()
        assertPrimary(0xFFCDBDFF)
        compose.onNodeWithText(label(app.miru.settings.R.string.theme)).performClick()
        compose.onNodeWithText(label(app.miru.settings.R.string.theme_light)).performClick()
        assertPrimary(0xFF7C4DFF)
        compose.runOnIdle { container.browser.newTab(isPrivate = true) }
        assertPrimary(0xFFCDBDFF)
        compose.runOnIdle { container.browser.closeTab(container.browser.state.value.selectedTabId!!) }
        assertPrimary(0xFF7C4DFF)
    }

    private fun fixture(block: () -> Unit) {
        compose.waitUntil(30000) { container.browser.state.value.ready }
        val tabs = BrowserTabFixture()
        val previous = runBlocking { container.settings.settings.first() }
        compose.runOnIdle { tabs.capture(container.browser) }
        runBlocking { container.settings.setTheme(ThemeMode.LIGHT); container.settings.setDynamicColor(false) }
        try { block() }
        finally {
            compose.runOnIdle { tabs.closeCreatedTabs(container.browser) }
            runBlocking { container.settings.setTheme(previous.theme); container.settings.setAccent(previous.accent); container.settings.setDynamicColor(previous.dynamicColor) }
        }
    }
}
