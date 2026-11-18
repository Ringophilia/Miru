// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.miru.design.MiruTheme
import app.miru.design.ThemeMode
import app.miru.toolbar.MiruToolbar
import app.miru.toolbar.ToolbarState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaPillMotionTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(ToolbarState(url = "https://media.test/", tabId = "media"))
    private var reduced by mutableStateOf(false)
    private fun slot() = compose.onNodeWithTag("toolbar-media-slot", useUnmergedTree = true)
    private fun addressWidth() = compose.onNodeWithTag("address-rest-capsule", useUnmergedTree = true).fetchSemanticsNode().size.width
    private fun iconHeight() = compose.onNodeWithTag("toolbar-media-icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
    private fun digit(number: Int) = compose.onNodeWithTag("toolbar-media-count-$number", useUnmergedTree = true)
    private fun digitY(number: Int) = digit(number).fetchSemanticsNode().positionInRoot.y

    @Before fun mount() {
        compose.setContent {
            MiruTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false, incognito = false) {
                MiruToolbar(state, {}, {}, {}, {}, {}, {}, reducedMotion = reduced)
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    @Test fun pillWidensAsTheAddressYieldsAndItsCountRolls() {
        slot().assertDoesNotExist()
        val before = addressWidth()
        update { state = state.copy(mediaCount = 1) }
        val earlySlot = slot().fetchSemanticsNode().size.width
        val earlyAddress = addressWidth()
        // At the first width sample, the expanding clip can still cover the arrow.
        compose.mainClock.advanceTimeBy(32)
        val earlyIcon = iconHeight()
        compose.mainClock.advanceTimeBy(550)
        val fullSlot = slot().fetchSemanticsNode().size.width
        assertTrue("Expected a partially expanded pill: $earlySlot of $fullSlot", earlySlot in 1 until fullSlot)
        assertTrue("Address should yield as the pill expands", earlyAddress in (addressWidth() + 1) until before)
        assertTrue("The arrow should grow during entry: $earlyIcon to ${iconHeight()}", earlyIcon in 1f..<iconHeight())

        val resting = digitY(1)
        update { state = state.copy(mediaCount = 2) }
        assertTrue("The old count should move up", digitY(1) < resting)
        assertTrue("The new count should enter from below", digitY(2) > resting)
        compose.mainClock.advanceTimeBy(170)
        digit(1).assertDoesNotExist()
        digit(2).assertExists()
        update { state = state.copy(mediaCount = 0) }
        compose.mainClock.advanceTimeBy(250)
        slot().assertDoesNotExist()
        assertEquals(before, addressWidth())
    }

    @Test fun reducedMotionReservesFinalWidthAndUpdatesInPlace() {
        update { reduced = true; state = state.copy(mediaCount = 3) }
        val width = slot().fetchSemanticsNode().size.width
        val earlyIcon = iconHeight()
        compose.mainClock.advanceTimeBy(180)
        assertEquals(width, slot().fetchSemanticsNode().size.width)
        assertEquals(earlyIcon, iconHeight(), 0.5f)
        val resting = digitY(3)
        update { state = state.copy(mediaCount = 4) }
        assertEquals(resting, digitY(3), 0.5f)
        assertEquals(resting, digitY(4), 0.5f)
        update { state = state.copy(tabId = "other", url = "https://other.test/", mediaCount = 0) }
        slot().assertDoesNotExist()
    }

    private fun update(change: () -> Unit) {
        compose.runOnUiThread(change)
        compose.mainClock.advanceTimeBy(48)
    }
}
