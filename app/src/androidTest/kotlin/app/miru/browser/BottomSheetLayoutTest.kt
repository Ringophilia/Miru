// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.adblock.AdblockPopupSheet
import app.miru.design.MiruBottomSheet
import app.miru.design.MiruTheme
import app.miru.design.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class BottomSheetLayoutTest {
    @get:Rule val compose = createComposeRule()
    private var open by mutableStateOf(true)
    private var themeMode by mutableStateOf(ThemeMode.LIGHT)
    private var viewportHeight = 0
    private val sheetTag = "miru-bottom-sheet"
    private fun sheet() = compose.onNodeWithTag(sheetTag, useUnmergedTree = true)

    @Test fun expandedSheetPreservesScreenSpaceAndTallContentRemainsReachable() {
        compose.setContent {
            viewportHeight = LocalWindowInfo.current.containerSize.height
            MiruTheme(themeMode = themeMode) {
                Box(Modifier.fillMaxSize())
                if (open) MiruBottomSheet(onDismissRequest = { open = false }, skipPartiallyExpanded = true) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Text("First setting")
                        Spacer(Modifier.height(1_600.dp))
                        Text("Last setting")
                    }
                }
            }
        }
        compose.onNodeWithText("First setting").assertIsDisplayed()
        assertSheetHeight(0.9f)
        compose.onNodeWithText("Last setting").performScrollTo().assertIsDisplayed()
        pressBack()
        sheet().assertDoesNotExist()
    }

    @Test fun adblockPopupKeepsThirtyPercentOfThePageOutsideThePanel() {
        compose.setContent {
            viewportHeight = LocalWindowInfo.current.containerSize.height
            MiruTheme(themeMode = themeMode) {
                Box(Modifier.fillMaxSize())
                if (open) AdblockPopupSheet("example.test", 6, { open = false }, {}) {
                    Box(Modifier.fillMaxSize().testTag("popup-body"))
                }
            }
        }
        compose.onNodeWithText("example.test").assertIsDisplayed()
        compose.onNodeWithTag("popup-body").assertIsDisplayed()
        assertSheetHeight(0.7f)
        tapOutside()
        sheet().assertDoesNotExist()
    }

    @Test fun shortSheetStaysCompactAndUsesThemeScrims() {
        compose.setContent {
            viewportHeight = LocalWindowInfo.current.containerSize.height
            MiruTheme(themeMode = themeMode) {
                Box(Modifier.fillMaxSize().background(Color.White))
                if (open) MiruBottomSheet(onDismissRequest = { open = false }) {
                    Box(Modifier.fillMaxWidth().height(96.dp)) { Text("Compact panel") }
                }
            }
        }
        compose.onNodeWithText("Compact panel").assertIsDisplayed()
        assertTrue(sheet().fetchSemanticsNode().size.height < viewportHeight / 3)
        assertScrimOverWhite(alpha = 0.32f)
        compose.runOnIdle { themeMode = ThemeMode.DARK }
        assertScrimOverWhite(alpha = 0.50f)
        tapOutside()
        sheet().assertDoesNotExist()
    }

    private fun assertSheetHeight(fraction: Float) {
        val node = sheet().assertIsDisplayed().fetchSemanticsNode()
        val coordinates = node.layoutInfo.coordinates
        val bottom = coordinates.localToScreen(Offset(0f, coordinates.size.height.toFloat())).y
        assertEquals("The capped sheet should still end at the window bottom", viewportHeight.toFloat(), bottom, 2f)
        assertEquals("The height limit should include the handle and system bars", viewportHeight * fraction, node.size.height.toFloat(), 2f)
    }

    private fun assertScrimOverWhite(alpha: Float) {
        compose.waitForIdle()
        val screen = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val pixel = screen.getPixel(screen.width / 2, screen.height / 4)
            val expected = (255 * (1f - alpha)).roundToInt().toFloat()
            assertEquals(expected, AndroidColor.red(pixel).toFloat(), 3f)
            assertEquals(expected, AndroidColor.green(pixel).toFloat(), 3f)
            assertEquals(expected, AndroidColor.blue(pixel).toFloat(), 3f)
        } finally {
            screen.recycle()
        }
    }

    private fun tapOutside() {
        val root = isRoot() and hasAnyDescendant(hasTestTag(sheetTag))
        compose.onNode(root, useUnmergedTree = true).performTouchInput { click(Offset(width / 2f, height / 5f)) }
    }

    private fun pressBack() {
        val pipe = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
    }
}
