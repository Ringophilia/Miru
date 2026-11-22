// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser

import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.design.MiruBottomSheet
import app.miru.design.MiruTheme
import app.miru.design.ThemeMode
import app.miru.design.animationsReduced
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BottomSheetMotionTest {
    @get:Rule val compose = createComposeRule()
    private var open by mutableStateOf(false)
    private var dismissed = 0
    private var confirmed = 0
    private var viewportHeight = 0
    private var reducedOverride by mutableStateOf<Boolean?>(null)
    private val tag = "miru-bottom-sheet"
    private fun sheet() = compose.onNodeWithTag(tag, useUnmergedTree = true)

    @After fun resumeClock() { compose.mainClock.autoAdvance = true }

    @Test fun entersFromTheBottomAndDismissesWithTheFasterExit() = withAnimationScale("1") {
        display()
        show()
        compose.mainClock.advanceTimeBy(64)
        val entering = bounds()
        val enteringScrim = redAt(viewportHeight / 4)
        compose.mainClock.advanceTimeBy(320)
        val rest = bounds()
        assertTrue("Entry should move from below toward the resting position", entering.top > rest.top + 1f)
        assertTrue(entering.top < viewportHeight)
        val settledScrim = redAt(viewportHeight / 4)
        assertTrue("The scrim should darken during entry: early=$enteringScrim, final=$settledScrim", settledScrim < enteringScrim)

        dismissWithScrim()
        compose.mainClock.advanceTimeBy(64)
        assertTrue("Exit should move down", bounds().top > rest.top + 1f)
        compose.mainClock.advanceTimeBy(208)
        sheet().assertDoesNotExist()
        assertEquals(1, dismissed)
    }

    @Test fun systemAnimationScaleZeroFadesWithoutMovingThePanel() = withAnimationScale("0") {
        display()
        show()
        compose.mainClock.advanceTimeBy(32)
        val fadingIn = bounds()
        val redBody = (fadingIn.top + fadingIn.height * 0.6f).toInt()
        val earlyGreen = greenAt(redBody)
        compose.mainClock.advanceTimeBy(176)
        val rest = bounds()
        assertEquals(rest, fadingIn)
        val finalGreen = greenAt(redBody)
        assertTrue("The panel should fade from white toward red: early=$earlyGreen, final=$finalGreen, bodyY=$redBody, bounds=$rest", finalGreen < earlyGreen)

        dismissWithScrim()
        compose.mainClock.advanceTimeBy(48)
        assertEquals(rest, bounds())
        assertEquals("The dismissal callback must wait for the fade", 0, dismissed)
        assertTrue("The red body should fade toward the page", greenAt(redBody) > 0)
        compose.mainClock.advanceTimeBy(176)
        sheet().assertDoesNotExist()
        assertEquals(1, dismissed)
    }

    @Test fun dismissingDuringEntryFinishesOnceAndTheNextOpenWorks() = withAnimationScale("1") {
        display()
        show()
        compose.mainClock.advanceTimeBy(32)
        dismissWithScrim()
        compose.mainClock.advanceTimeBy(320)
        sheet().assertDoesNotExist()
        assertEquals(1, dismissed)
        show()
        compose.mainClock.advanceTimeBy(352)
        sheet().assertIsDisplayed()
        dismissWithScrim()
        compose.mainClock.advanceTimeBy(256)
        sheet().assertDoesNotExist()
        assertEquals(2, dismissed)
    }

    @Test fun switchingToReducedMotionDuringExitStillDismissesExactlyOnce() = withAnimationScale("1") {
        reducedOverride = false
        display()
        show()
        compose.mainClock.advanceTimeBy(352)
        dismissWithScrim()
        compose.mainClock.advanceTimeBy(32)
        compose.runOnUiThread { reducedOverride = true }
        compose.mainClock.advanceTimeBy(320)
        assertEquals(1, dismissed)
        sheet().assertDoesNotExist()
    }

    @Test fun confirmRunsOnlyAfterSlidingOut() = withAnimationScale("1") {
        confirmAndCheckExit(reduced = false)
    }

    @Test fun confirmWithSystemAnimationsOffWaitsForTheFade() = withAnimationScale("0") {
        confirmAndCheckExit(reduced = true)
    }

    private fun confirmAndCheckExit(reduced: Boolean) {
        display()
        show()
        compose.mainClock.advanceTimeBy(352)
        val resting = bounds()
        val confirm = compose.onNodeWithText("Confirm")
        confirm.performClick()
        if (compose.onAllNodesWithText("Confirm").fetchSemanticsNodes().isNotEmpty()) {
            confirm.performClick()
        }
        dismissWithScrim()
        compose.mainClock.advanceTimeBy(64)
        assertEquals(0, confirmed)
        val exiting = bounds()
        if (reduced) assertEquals(resting, exiting)
        else assertTrue("Confirmation should slide the panel down", exiting.top > resting.top)
        compose.mainClock.advanceTimeBy(240)
        sheet().assertDoesNotExist()
        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
    }

    private fun display() {
        compose.setContent {
            viewportHeight = LocalWindowInfo.current.containerSize.height
            MiruTheme(themeMode = ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(Color.White))
                if (open) MiruBottomSheet(
                    onDismissRequest = { open = false; dismissed++ },
                    skipPartiallyExpanded = true,
                    reducedMotion = reducedOverride ?: animationsReduced(),
                ) { closeWith ->
                    Box(Modifier.fillMaxWidth().height(180.dp).background(Color.Red)) {
                        Button(onClick = { closeWith { open = false; confirmed++ } }) { Text("Confirm") }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun show() {
        compose.runOnUiThread { open = true }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        sheet().assertExists()
    }

    private fun bounds(): Rect {
        val node = sheet().fetchSemanticsNode()
        val coordinates = node.layoutInfo.coordinates
        return Rect(coordinates.localToScreen(Offset.Zero), coordinates.localToScreen(Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())))
    }

    private fun dismissWithScrim() {
        val root = isRoot() and hasAnyDescendant(hasTestTag(tag))
        compose.onNode(root, useUnmergedTree = true).performTouchInput { click(Offset(width / 2f, height / 5f)) }
    }

    private fun redAt(y: Int) = pixel(y) { android.graphics.Color.red(it) }
    private fun greenAt(y: Int) = pixel(y) { android.graphics.Color.green(it) }
    private fun pixel(y: Int, channel: (Int) -> Int): Int {
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        return try { channel(bitmap.getPixel(bitmap.width / 2, y)) } finally { bitmap.recycle() }
    }

    private fun withAnimationScale(value: String, block: () -> Unit) {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        val original = Settings.Global.getString(resolver, setting)
        try {
            shell("settings put global $setting $value")
            block()
        } finally {
            compose.mainClock.autoAdvance = true
            shell(if (original == null) "settings delete global $setting" else "settings put global $setting $original")
        }
    }

    private fun shell(command: String) {
        val pipe = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
    }
}
