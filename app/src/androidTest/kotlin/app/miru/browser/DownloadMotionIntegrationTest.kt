// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser

import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.data.ToolbarPosition
import app.miru.downloads.R as DownloadStrings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadMotionIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as MiruApplication).container
    private val arrow = hasTestTag("download-arrow-flight")
    private val dot = hasTestTag("toolbar-download-dot")
    private val menu = hasTestTag("browser-overflow-anchor")

    @Test fun confirmedDownloadArcsTowardTheTopOrBottomMenu() = withSettings("1") {
        for (toolbar in listOf(ToolbarPosition.TOP, ToolbarPosition.BOTTOM)) {
            runBlocking { container.settings.setToolbarPosition(toolbar) }
            await(menu)
            val viewport = bounds(hasTestTag("browser-viewport"))
            compose.waitUntil(8_000) {
                (bounds(menu).center.y > viewport.center.y) == (toolbar == ToolbarPosition.BOTTOM)
            }
            val target = bounds(menu).center
            withConfirmation {
                compose.mainClock.autoAdvance = false
                confirm()
                advanceUntilPresent(arrow)
                val first = bounds(arrow).center
                assertTrue("Arrow should start around the viewport center: $first", (first - viewport.center).getDistance() < 12f)
                compose.onNode(dot, useUnmergedTree = true).assertDoesNotExist()

                compose.mainClock.advanceTimeBy(240)
                val middle = bounds(arrow).center
                val fraction = (middle.x - first.x) / (target.x - first.x)
                assertTrue("Arrow should be part way to the menu: $middle", fraction in 0.05f..0.95f)
                val straightY = first.y + (target.y - first.y) * fraction
                assertTrue("Arrow should arc above the direct path", middle.y < straightY - 15f)
                compose.onNode(dot, useUnmergedTree = true).assertDoesNotExist()

                compose.mainClock.advanceTimeBy(400)
                compose.onNode(arrow, useUnmergedTree = true).assertDoesNotExist()
                assertDot(target)
            }
        }
    }

    @Test fun systemAnimationScaleZeroShowsTheDotWithoutAFlight() = withSettings("0") {
        withConfirmation {
            val target = bounds(menu).center
            confirm()
            await(dot)
            compose.onNode(arrow, useUnmergedTree = true).assertDoesNotExist()
            assertDot(target)
        }
    }

    private fun assertDot(menuCenter: Offset) {
        val badge = bounds(dot)
        val density = compose.activity.resources.displayMetrics.density
        assertEquals(6 * density, badge.width, 2f)
        assertEquals(6 * density, badge.height, 2f)
        assertTrue("Dot should sit above the menu center", badge.center.y < menuCenter.y)
    }

    private fun confirm() = compose.onNodeWithText(compose.activity.getString(DownloadStrings.string.download_start)).performClick()

    private fun withConfirmation(test: () -> Unit) {
        DownloadTestServer(size = 24L * 1024 * 1024, delayMillis = 110).use { origin ->
            val browser = container.browser
            val tabs = BrowserTabFixture()
            compose.runOnIdle { tabs.capture(browser); browser.newTab("${origin.origin}/download") }
            try {
                await(hasText(compose.activity.getString(DownloadStrings.string.download_confirm_title)))
                compose.onNode(arrow, useUnmergedTree = true).assertDoesNotExist()
                test()
            } finally {
                compose.mainClock.autoAdvance = true
                container.downloads.downloads.value.filter { it.url.startsWith(origin.origin) }
                    .forEach { container.downloads.delete(it.id, deleteFile = true) }
                compose.waitUntil(10_000) { container.downloads.downloads.value.none { it.url.startsWith(origin.origin) } }
                compose.runOnIdle { tabs.closeCreatedTabs(browser) }
                await(menu)
                compose.waitUntil(5_000) { compose.onAllNodes(dot, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            }
        }
    }

    private fun await(matcher: SemanticsMatcher) = compose.waitUntil(25_000) {
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun advanceUntilPresent(matcher: SemanticsMatcher) {
        repeat(24) {
            if (compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) return
            compose.mainClock.advanceTimeByFrame()
        }
        compose.onNode(matcher, useUnmergedTree = true).assertExists()
    }

    private fun bounds(matcher: SemanticsMatcher): Rect =
        compose.onNode(matcher, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun withSettings(animationScale: String, test: () -> Unit) {
        val skip = compose.activity.getString(R.string.intro_skip)
        compose.waitUntil(20_000) {
            runBlocking { container.settings.settings.first().introCompleted } ||
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText(skip).performClick()
        compose.waitUntil(30_000) { container.browser.state.value.ready }
        val previousToolbar = runBlocking { container.settings.settings.first().toolbarPosition }
        val previousDownloadSettings = container.downloads.settings.value
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        val previousScale = Settings.Global.getString(compose.activity.contentResolver, setting)
        try {
            shell("settings put global $setting $animationScale")
            container.downloads.updatePreferences { it.copy(askBeforeDownload = true) }
            compose.waitUntil(10_000) { container.downloads.settings.value.askBeforeDownload }
            test()
        } finally {
            compose.mainClock.autoAdvance = true
            runBlocking { container.settings.setToolbarPosition(previousToolbar) }
            container.downloads.updatePreferences { previousDownloadSettings }
            shell(if (previousScale == null) "settings delete global $setting" else "settings put global $setting $previousScale")
        }
    }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
