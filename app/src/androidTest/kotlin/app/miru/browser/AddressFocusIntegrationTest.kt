// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddressFocusIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as MiruApplication).container
    private val tabs = BrowserTabFixture()
    private fun label(id: Int) = compose.activity.getString(id)
    private fun search() = compose.onNodeWithTag("new-tab-search")
    private fun editor() = compose.onNodeWithTag("address-editor-capsule")

    @Before fun prepare() {
        val skip = label(R.string.intro_skip)
        compose.waitUntil(15_000) {
            runBlocking { container.settings.settings.first().introCompleted } ||
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText(skip).performClick()
        compose.waitUntil(30_000) { container.browser.state.value.ready }
        compose.runOnIdle { tabs.capture(container.browser) }
    }

    @After fun restore() {
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { tabs.closeCreatedTabs(container.browser) }
    }

    @Test fun newTabSearchMovesTheLiveEditorAndAcceptsInputWhileMoving() {
        compose.runOnIdle { container.browser.newTab() }
        val source = search().fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        search().performClick()
        val early = firstEditorBounds()
        val address = "http://127.0.0.1/"
        compose.onNode(hasSetTextAction()).performTextInput(address)
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(hasSetTextAction()).assert(hasText(address))
        compose.mainClock.advanceTimeBy(450)
        val settled = editor().fetchSemanticsNode().boundsInRoot
        assertTrue("Initial editor $early should start near the search capsule $source", early.center.y > settled.center.y)
        assertTrue("The editor should finish above its new-tab entry", settled.bottom < source.top)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_cancel_edit)).performClick()
        search().assertIsDisplayed()
    }

    @Test fun webAddressExpandsAndSystemZeroScaleKeepsTheHomeEditorInPlace() {
        TestPages().use { pages ->
            compose.runOnIdle { container.browser.newTab("${pages.baseUrl}/first") }
            compose.waitUntil(20_000) { container.browser.state.value.selectedTab?.let { it.title == "Miru One" && !it.loading } == true }
            val collapsed = compose.onNodeWithTag("address-rest-capsule").fetchSemanticsNode().boundsInRoot
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("127.0.0.1").performClick()
            val early = firstEditorBounds()
            compose.mainClock.advanceTimeBy(450)
            val settled = editor().fetchSemanticsNode().boundsInRoot
            assertTrue("The expanded web editor should be wider than the resting capsule", settled.width > collapsed.width)
            assertTrue("Width should grow across frames: $early to $settled", settled.width > early.width)
            compose.mainClock.autoAdvance = true
            compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_cancel_edit)).performClick()
        }

        val context = compose.activity.applicationContext
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        val previous = Settings.Global.getString(context.contentResolver, setting)
        try {
            shell("settings put global $setting 0")
            compose.runOnIdle { container.browser.newTab() }
            compose.waitUntil(5_000) { Settings.Global.getFloat(context.contentResolver, setting, 1f) == 0f }
            val source = search().fetchSemanticsNode().boundsInRoot
            compose.mainClock.autoAdvance = false
            search().performClick()
            val first = firstEditorBounds()
            compose.mainClock.advanceTimeBy(190)
            val afterFade = editor().fetchSemanticsNode().boundsInRoot
            assertTrue("Reduced motion should place the editor directly above its source: $first", first.bottom < source.top)
            assertEquals(first.center.y, afterFade.center.y, 1f)
            assertEquals(first.width, afterFade.width, 1f)
            compose.mainClock.autoAdvance = true
            compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_cancel_edit)).performClick()
        } finally {
            compose.mainClock.autoAdvance = true
            val saved = previous?.toFloatOrNull()
            shell(if (saved == null) "settings delete global $setting" else "settings put global $setting $saved")
        }
    }

    private fun firstEditorBounds(): Rect {
        repeat(8) {
            compose.mainClock.advanceTimeByFrame()
            val bounds = compose.onAllNodesWithTag("address-editor-capsule").fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            if (bounds != null && bounds.width > 0f) return bounds
        }
        error("Editor capsule did not enter the layout")
    }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
