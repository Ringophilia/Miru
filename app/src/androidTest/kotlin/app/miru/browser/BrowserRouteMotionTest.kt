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
import app.miru.settings.R as SettingsStrings
import app.miru.toolbar.R as ToolbarStrings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserRouteMotionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun allSettingsChildrenUseTheHorizontalAxisInBothDirections() = withScale("1") {
        menu().performClick()
        text(MenuStrings.string.menu_settings).performClick()
        val settled = position("SETTINGS")
        val children = listOf(
            "DOWNLOAD_SETTINGS" to SettingsStrings.string.downloads,
            "PLAYER_SETTINGS" to SettingsStrings.string.player,
            "CLEAR_DATA" to SettingsStrings.string.clear_browsing_data,
            "ADBLOCK" to SettingsStrings.string.adblock,
            "ABOUT" to SettingsStrings.string.about,
        )
        for ((route, label) in children) {
            val entry = text(label).performScrollTo().assertIsDisplayed()
            compose.mainClock.autoAdvance = false
            entry.performClick()
            compose.mainClock.advanceTimeBy(128)
            val incoming = position(route) - settled
            val outgoing = position("SETTINGS") - settled
            assertTrue("$route should enter from the right: $incoming", incoming.x > 1f)
            assertTrue("Settings should leave to the left: $outgoing", outgoing.x < -1f)
            assertEquals(0f, incoming.y, 1f)
            assertEquals(0f, outgoing.y, 1f)
            compose.mainClock.advanceTimeBy(320)
            assertEquals(settled, position(route))
            pressBack()
            compose.mainClock.advanceTimeBy(128)
            assertTrue("Settings should return from the left", position("SETTINGS").x < settled.x - 1f)
            assertTrue("$route should return to the right", position(route).x > settled.x + 1f)
            compose.mainClock.advanceTimeBy(320)
            assertEquals(settled, position("SETTINGS"))
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun browserLibraryPagesEnterFromBelow() = withScale("1") {
        val browser = position("BROWSER")
        for ((route, label) in listOf(
            "HISTORY" to MenuStrings.string.menu_history,
            "BOOKMARKS" to MenuStrings.string.menu_bookmarks,
            "DOWNLOADS" to MenuStrings.string.menu_downloads,
        )) {
            menu().performClick()
            val entry = text(label).assertIsDisplayed()
            compose.mainClock.autoAdvance = false
            entry.performClick()
            compose.mainClock.advanceTimeBy(96)
            val incoming = position(route) - browser
            assertEquals(0f, incoming.x, 1f)
            assertTrue("$route should enter from below: $incoming", incoming.y > 1f)
            compose.mainClock.advanceTimeBy(256)
            assertEquals(browser, position(route))
            pressBack()
            compose.mainClock.advanceTimeBy(272)
            compose.mainClock.autoAdvance = true
            menu().assertIsDisplayed()
        }
    }

    @Test fun scaleZeroCrossfadesInPlaceAndAllowsAnInterruptedReturn() = withScale("0") {
        menu().performClick()
        text(MenuStrings.string.menu_settings).performClick()
        val settled = position("SETTINGS")
        val entry = text(SettingsStrings.string.about).performScrollTo()
        compose.mainClock.autoAdvance = false
        entry.performClick()
        compose.mainClock.advanceTimeBy(48)
        assertEquals(settled, position("ABOUT"))
        assertEquals(settled, position("SETTINGS"))
        pressBack()
        compose.mainClock.advanceTimeBy(416)
        route("ABOUT").assertDoesNotExist()
        assertEquals(settled, position("SETTINGS"))
        compose.mainClock.autoAdvance = true
        text(SettingsStrings.string.about).assertIsDisplayed()
    }

    private fun text(id: Int) = compose.onNodeWithText(compose.activity.getString(id))
    private fun menu() = compose.onNodeWithContentDescription(compose.activity.getString(ToolbarStrings.string.toolbar_menu))
    private fun route(name: String) = compose.onNodeWithTag("route-$name", useUnmergedTree = true)
    private fun position(name: String): Offset =
        route(name).fetchSemanticsNode().layoutInfo.coordinates.localToScreen(Offset.Zero)

    private fun pressBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun withScale(value: String, test: () -> Unit) {
        val settings = (compose.activity.application as MiruApplication).container.settings
        val skip = compose.activity.getString(R.string.intro_skip)
        compose.waitUntil(20_000) {
            runBlocking { settings.settings.first().introCompleted } ||
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText(skip).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("route-BROWSER").fetchSemanticsNodes().isNotEmpty() }
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        val original = Settings.Global.getString(compose.activity.contentResolver, setting)
        try {
            shell("settings put global $setting $value")
            test()
        } finally {
            compose.mainClock.autoAdvance = true
            shell(if (original == null) "settings delete global $setting" else "settings put global $setting $original")
        }
    }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
