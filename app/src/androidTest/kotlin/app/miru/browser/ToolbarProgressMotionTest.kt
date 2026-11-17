// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
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

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ToolbarProgressMotionTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(ToolbarState(tabId = "first", url = "https://example.test/"))
    private var reduced by mutableStateOf(false)
    private fun toolbar() = compose.onNodeWithTag("progress-test-toolbar")
    private fun progress() = compose.onNodeWithTag("toolbar-page-progress")

    @Before fun mountToolbar() {
        compose.setContent {
            MiruTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false, incognito = false) {
                Box(Modifier.testTag("progress-test-toolbar")) {
                    MiruToolbar(state, {}, {}, {}, {}, {}, {}, reducedMotion = reduced)
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    @Test fun springRetargetsAndCompletionHoldsTheFullBarBeforeFading() {
        val idleBounds = toolbar().fetchSemanticsNode().boundsInRoot
        val background = bottomOfToolbar()
        update { state = state.copy(loading = true, progress = 0) }
        assertEquals(0.1f, current(), 0.01f)
        assertEquals(idleBounds, toolbar().fetchSemanticsNode().boundsInRoot)

        update { state = state.copy(progress = 30) }
        assertTrue("The reported jump should be smoothed", current() < 0.3f)
        update { state = state.copy(progress = 82) }
        compose.mainClock.advanceTimeBy(110)
        assertTrue("New progress should interrupt the old 30 percent animation: ${current()}", current() > 0.35f)
        assertTrue("The spring should still be catching up", current() < 0.82f)

        update { state = state.copy(loading = false, progress = 100) }
        assertEquals(1f, current(), 0f)
        val full = bottomOfToolbar()
        assertTrue("A full bar should cover the toolbar sample", full < background - 0.1f)
        compose.mainClock.advanceTimeBy(90)
        assertEquals("Full-width paint should hold before the fade", full, bottomOfToolbar(), 0.005f)
        compose.mainClock.advanceTimeBy(125)
        val fading = bottomOfToolbar()
        assertTrue("Opacity should decrease while still painted: $full, $fading, $background", fading > full + 0.015f && fading < background - 0.015f)
        compose.mainClock.advanceTimeBy(180)
        progress().assertDoesNotExist()
        assertEquals(background, bottomOfToolbar(), 0.005f)
    }

    @Test fun startingAnotherLoadCancelsTheOldHoldAndEditingOrTabSwitchDropsIt() {
        update { state = state.copy(loading = true, progress = 0) }
        update { state = state.copy(loading = false, progress = 100) }
        assertEquals(1f, current(), 0f)
        update { state = state.copy(loading = true, progress = 0) }
        assertEquals(0.1f, current(), 0.01f)
        compose.mainClock.advanceTimeBy(400)
        assertEquals("The previous page's fade must not hide the new load", 0.1f, current(), 0.01f)

        update { state = state.copy(progress = 85) }
        compose.mainClock.advanceTimeBy(400)
        assertTrue(current() > 0.75f)
        update { state = state.copy(progress = 0) }
        assertEquals("A new page start can arrive while loading stays true", 0.1f, current(), 0.01f)

        update { state = state.copy(editing = true) }
        progress().assertDoesNotExist()
        update { state = state.copy(editing = false) }
        assertEquals(0.1f, current(), 0.01f)
        update { state = state.copy(tabId = "second", loading = false, progress = 100) }
        progress().assertDoesNotExist()
    }

    @Test fun reducedMotionJumpsToReportedProgressAndStillFadesAfterCompletion() {
        update { reduced = true; state = state.copy(loading = true, progress = 0) }
        assertEquals(0.1f, current(), 0.01f)
        update { state = state.copy(progress = 64) }
        assertEquals(0.64f, current(), 0.01f)
        compose.mainClock.advanceTimeBy(300)
        assertEquals(0.64f, current(), 0.01f)
        update { state = state.copy(loading = false, progress = 100) }
        assertEquals(1f, current(), 0f)
        compose.mainClock.advanceTimeBy(400)
        progress().assertDoesNotExist()
    }

    private fun update(change: () -> Unit) {
        compose.runOnUiThread(change)
        compose.mainClock.advanceTimeBy(32)
    }

    private fun current() = progress().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun bottomOfToolbar(): Float {
        val image = toolbar().captureToImage().toPixelMap()
        return image[image.width / 2, image.height - 2].luminance()
    }
}
