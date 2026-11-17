// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.miru.design.MiruTheme
import app.miru.design.ThemeMode
import app.miru.toolbar.MiruToolbar
import app.miru.toolbar.ToolbarState
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AdblockBadgeMotionTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(ToolbarState(url = "https://first.test/", tabId = "first", adblockAvailable = true))
    private var reduced by mutableStateOf(false)
    private var muted = Color.Unspecified
    private var active = Color.Unspecified

    @Before fun displayToolbar() {
        compose.setContent {
            MiruTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false, incognito = false) {
                muted = MaterialTheme.colorScheme.onSurfaceVariant
                active = MiruTheme.colors.adblock
                MiruToolbar(state, {}, {}, {}, {}, {}, {}, reducedMotion = reduced)
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    @Test fun zeroChangesTintAndSubsequentCountsRollUpward() {
        digit("1").assertDoesNotExist()
        assertTrue(shieldDistance(muted) < 0.02f)
        update { state = state.copy(blockedCount = 1) }
        compose.mainClock.advanceTimeBy(50)
        assertTrue("The shield should be between its muted and active colors", shieldDistance(muted) > 0.04f && shieldDistance(active) > 0.04f)
        compose.mainClock.advanceTimeBy(190)
        assertTrue(shieldDistance(active) < 0.02f)
        val rest = y("1")

        update { state = state.copy(blockedCount = 4) }
        compose.mainClock.advanceTimeBy(64)
        assertTrue("The old number should exit upward", y("1") < rest)
        assertTrue("The new number should enter from below", y("4") > rest)
        compose.mainClock.advanceTimeBy(160)
        digit("1").assertDoesNotExist()
        assertEquals(rest, y("4"), 1f)
        update { state = state.copy(adblockNeedsReload = true, blockedCount = 0) }
        digit("4").assertDoesNotExist()
        compose.onNodeWithTag("toolbar-adblock-shield", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun reducedMotionFadesInPlaceAndSwitchingTabsDropsPreviousNumbers() {
        update { reduced = true; state = state.copy(blockedCount = 7) }
        compose.mainClock.advanceTimeBy(180)
        val rest = y("7")
        update { state = state.copy(blockedCount = 8) }
        compose.mainClock.advanceTimeBy(55)
        assertEquals(rest, y("7"), 1f)
        assertEquals(rest, y("8"), 1f)

        update { state = state.copy(tabId = "second", url = "https://second.test/", blockedCount = 0) }
        digit("7").assertDoesNotExist()
        digit("8").assertDoesNotExist()
        assertTrue(shieldDistance(muted) < 0.02f)
    }

    private fun digit(label: String) = compose.onNodeWithTag("toolbar-adblock-count-$label", useUnmergedTree = true)
    private fun y(label: String) = digit(label).fetchSemanticsNode().positionInRoot.y

    private fun update(change: () -> Unit) {
        compose.runOnUiThread(change)
        compose.mainClock.advanceTimeBy(32)
    }

    private fun shieldDistance(expected: Color): Float {
        val pixels = compose.onNodeWithTag("toolbar-adblock-shield", useUnmergedTree = true).captureToImage().toPixelMap()
        var difference = Float.POSITIVE_INFINITY
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            val distance = abs(color.red - expected.red) + abs(color.green - expected.green) + abs(color.blue - expected.blue)
            difference = minOf(difference, distance)
        }
        return difference
    }
}
