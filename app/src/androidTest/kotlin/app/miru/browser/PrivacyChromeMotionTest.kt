// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser

import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.design.MiruTheme
import app.miru.design.ThemeMode
import app.miru.design.animationsReduced
import app.miru.design.rememberBrowserChromeColors
import app.miru.ntp.NewTabPage
import app.miru.toolbar.MiruToolbar
import app.miru.toolbar.ToolbarState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic chrome surfaces only. The real private Activity is covered without pixel capture. */
@RunWith(AndroidJUnit4::class)
class PrivacyChromeMotionTest {
    @get:Rule val compose = createComposeRule()
    private var incognito by mutableStateOf(false)
    private val messages = SnackbarHostState()
    private val surfaces = listOf("privacy-toolbar", "privacy-ntp")

    @Test fun bothBackgroundsTransitionAndReturnToTheOriginalColors() = verifyColors("1", 112)

    @Test fun bothBackgroundsStillFadeWithSystemAnimationsOff() = verifyColors("0", 64)

    private fun verifyColors(scale: String, sampleTime: Long) {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        val originalScale = Settings.Global.getString(resolver, setting)
        try {
            shell("settings put global $setting $scale")
            compose.setContent {
                MiruTheme(themeMode = ThemeMode.LIGHT, incognito = incognito) {
                    val reduced = animationsReduced()
                    val colors = rememberBrowserChromeColors(incognito, reduced)
                    MaterialTheme(colorScheme = colors.value) {
                        Column(Modifier.fillMaxSize()) {
                            MiruToolbar(
                                ToolbarState(incognito = incognito), {}, {}, {}, {}, {}, {},
                                modifier = Modifier.fillMaxWidth().testTag(surfaces[0]),
                                reducedMotion = reduced,
                            )
                            NewTabPage(
                                incognito, {}, {}, messages,
                                modifier = Modifier.weight(1f).testTag(surfaces[1]),
                                reducedMotion = reduced,
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            // Wait for the newly launched Activity window to present its first Compose surface frame
            // (distinct from the #FAFAFA platform windowBackground) before freezing the clock.
            compose.waitUntil(5_000) {
                backgrounds().all { it < 0.94f }
            }
            compose.mainClock.autoAdvance = false
            val light = backgrounds()
            compose.runOnUiThread { incognito = true }
            compose.mainClock.advanceTimeBy(sampleTime)
            val middle = backgrounds()
            compose.mainClock.advanceTimeBy(300)
            val dark = backgrounds()
            surfaces.indices.forEach { index ->
                assertTrue("${surfaces[index]} must have a drawn frame between the two colors",
                    middle[index] in (dark[index] + 0.03f)..(light[index] - 0.03f))
            }

            compose.runOnUiThread { incognito = false }
            compose.mainClock.advanceTimeBy(sampleTime)
            val returning = backgrounds()
            compose.mainClock.advanceTimeBy(300)
            val final = backgrounds()
            surfaces.indices.forEach { index ->
                assertTrue("${surfaces[index]} must fade back through an intermediate color",
                    returning[index] in (dark[index] + 0.03f)..(light[index] - 0.03f))
                assertEquals(light[index], final[index], 0.01f)
            }
        } finally {
            compose.mainClock.autoAdvance = true
            shell(if (originalScale == null) "settings delete global $setting" else "settings put global $setting $originalScale")
        }
    }

    private fun backgrounds(): List<Float> {
        val samples = surfaces.map { tag ->
            val coordinates = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().layoutInfo.coordinates
            coordinates.localToScreen(Offset(3f, coordinates.size.height * 0.5f))
        }
        compose.waitForIdle()
        val frame = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        return try {
            samples.map { point -> Color(frame.getPixel(point.x.toInt(), point.y.toInt())).luminance() }
        } finally {
            frame.recycle()
        }
    }

    private fun shell(command: String) {
        val pipe = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
    }
}
