// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.design.MiruFilterChip
import app.miru.design.MiruMotion
import app.miru.design.MiruTheme
import app.miru.design.ThemeMode
import app.miru.design.animationsReduced
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChipAndListMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun filterChipExpandsCheckIconFromZeroWidth() {
        verifyChip("1", expectMidExpansion = true)
    }

    @Test fun filterChipFadesCheckIconWhenAnimationsReduced() {
        verifyChip("0", expectMidExpansion = false)
    }

    @Test fun listItemDeletionSlidesRemainingItemIntoPlace() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        val original = Settings.Global.getString(resolver, setting)
        var entries by mutableStateOf(listOf("first", "second"))
        try {
            shell("settings put global $setting 1")
            compose.setContent {
                MiruTheme(themeMode = ThemeMode.LIGHT) {
                    val reduced = animationsReduced()
                    val fadeSpec = tween<Float>(if (reduced) MiruMotion.short2 else MiruMotion.medium1, easing = MiruMotion.standard)
                    val placeSpec = if (reduced) null else tween<IntOffset>(MiruMotion.medium1, easing = MiruMotion.standard)
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(entries, key = { it }) { id ->
                            Box(
                                Modifier.fillMaxWidth().height(64.dp).testTag("list-item-$id")
                                    .animateItem(fadeInSpec = fadeSpec, placementSpec = placeSpec, fadeOutSpec = fadeSpec),
                            ) {
                                Text(id)
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            val initialTop = compose.onNodeWithTag("list-item-second").fetchSemanticsNode().boundsInRoot.top
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { entries = listOf("second") }
            compose.mainClock.advanceTimeBy(112)
            val midTop = compose.onNodeWithTag("list-item-second").fetchSemanticsNode().boundsInRoot.top
            compose.mainClock.advanceTimeBy(250)
            val finalTop = compose.onNodeWithTag("list-item-second").fetchSemanticsNode().boundsInRoot.top
            assertTrue("Second item should slide toward top during deletion ($midTop in $finalTop..$initialTop)",
                midTop in (finalTop + 1f)..(initialTop - 1f))
        } finally {
            compose.mainClock.autoAdvance = true
            shell(if (original == null) "settings delete global $setting" else "settings put global $setting $original")
        }
    }

    private fun verifyChip(scale: String, expectMidExpansion: Boolean) {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        val original = Settings.Global.getString(resolver, setting)
        var selected by mutableStateOf(false)
        try {
            shell("settings put global $setting $scale")
            compose.setContent {
                MiruTheme(themeMode = ThemeMode.LIGHT) {
                    MiruFilterChip(
                        selected = selected,
                        onClick = { selected = !selected },
                        label = { Text("视频") },
                        modifier = Modifier.padding(16.dp).testTag("test-filter-chip"),
                        reducedMotion = animationsReduced(),
                    )
                }
            }
            compose.waitForIdle()
            compose.onAllNodesWithTag("miru-chip-check", useUnmergedTree = true).fetchSemanticsNodes().let {
                assertTrue("Unselected chip must not show check icon", it.isEmpty())
            }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("test-filter-chip").performClick()
            compose.mainClock.advanceTimeBy(48)
            val midBounds = compose.onNodeWithTag("miru-chip-check", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            compose.mainClock.advanceTimeBy(200)
            val fullBounds = compose.onNodeWithTag("miru-chip-check", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            if (expectMidExpansion) {
                assertTrue("Check icon should expand horizontally ($midBounds vs $fullBounds)", midBounds.width < fullBounds.width)
            } else {
                assertTrue("Reduced motion should keep full width while fading", midBounds.width >= fullBounds.width - 1f)
            }
            compose.onNodeWithText("视频").assertIsDisplayed()
            compose.onNodeWithTag("test-filter-chip").performClick()
            compose.mainClock.advanceTimeBy(220)
            assertTrue(compose.onAllNodesWithTag("miru-chip-check", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
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
