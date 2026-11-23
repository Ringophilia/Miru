// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser

import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.design.MiruSnackbarHost
import app.miru.design.MiruTheme
import app.miru.design.showMessage
import app.miru.design.showUndo
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnackbarMotionTest {
    @get:Rule val compose = createComposeRule()
    private val host = SnackbarHostState()
    private var undoCount = 0
    private val snackbar = hasTestTag("miru-snackbar")

    @Test fun ordinaryMessageSlidesUpAndClosesAfterFourSeconds() = withScale("1") {
        display()
        compose.onNodeWithText("Show message").performClick()
        compose.mainClock.advanceTimeBy(80)
        val entering = position()
        compose.mainClock.advanceTimeBy(256)
        val resting = position()
        assertTrue("Snackbar should rise from below: $entering to $resting", entering.y > resting.y + 1f)
        assertEquals(resting.x, entering.x, 1f)
        compose.mainClock.advanceTimeBy(3_400)
        compose.onNodeWithText("File saved").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(544)
        compose.onNode(snackbar, useUnmergedTree = true).assertDoesNotExist()
        assertNull(host.currentSnackbarData)
    }

    @Test fun undoRemainsActionableAfterFourSecondsAndExpiresAtSix() = withScale("1") {
        display()
        compose.onNodeWithText("Show undo").performClick()
        compose.mainClock.advanceTimeBy(4_400)
        compose.onNodeWithText("Undo").performClick()
        compose.mainClock.advanceTimeBy(272)
        compose.onNode(snackbar, useUnmergedTree = true).assertDoesNotExist()
        assertEquals(1, undoCount)

        compose.onNodeWithText("Show undo").performClick()
        compose.mainClock.advanceTimeBy(5_650)
        compose.onNodeWithText("Undo").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(672)
        compose.onNode(snackbar, useUnmergedTree = true).assertDoesNotExist()
        assertEquals(1, undoCount)
    }

    @Test fun reducedMotionKeepsTheSnackbarInPlaceAndReplacementRetiresUndo() = withScale("0") {
        display()
        compose.onNodeWithText("Show undo").performClick()
        compose.mainClock.advanceTimeBy(64)
        val entering = position()
        compose.mainClock.advanceTimeBy(208)
        assertEquals(entering, position())
        compose.onNodeWithText("Show message").performClick()
        compose.mainClock.advanceTimeBy(416)
        compose.onNodeWithText("File saved").assertIsDisplayed()
        compose.onNodeWithText("Undo").assertDoesNotExist()
        assertEquals(0, undoCount)
    }

    private fun display() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            MiruTheme {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.align(Alignment.Center)) {
                        Button(onClick = { scope.launch { host.showMessage("File saved") } }) { Text("Show message") }
                        Button(onClick = { scope.launch { if (host.showUndo("File removed", "Undo")) undoCount++ } }) { Text("Show undo") }
                    }
                    MiruSnackbarHost(host, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun position(): Offset =
        compose.onNode(snackbar, useUnmergedTree = true).fetchSemanticsNode().layoutInfo.coordinates.localToScreen(Offset.Zero)

    private fun withScale(value: String, test: () -> Unit) {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        val original = Settings.Global.getString(resolver, setting)
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
