// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewportToolbarMotionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var toolbar: ViewportToolbarMotion

    @Before fun start() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            toolbar = remember(scope) { ViewportToolbarMotion(scope) { compose.mainClock.currentTime } }
            Box { }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            toolbar.configure(100f, forced = false, reducedMotion = false)
            toolbar.scroll(200f)
        }
    }

    @Test fun releaseCarriesVelocityButFlingCannotChangeTheChosenEndpoint() {
        moveWhileHeld(230f)
        assertEquals(30f, toolbar.hidden, 0f)
        compose.runOnUiThread { toolbar.touch(false) }
        compose.mainClock.advanceTimeBy(32)
        assertTrue("The first spring frames should carry the current velocity", toolbar.hidden > 30f)
        compose.runOnUiThread {
            val current = toolbar.hidden
            toolbar.scroll(380f)
            assertEquals("Fling may move the page while the toolbar settles", current, toolbar.hidden, 0f)
        }
        compose.mainClock.advanceTimeBy(900)
        assertEquals(0f, toolbar.hidden, 0.5f)

        moveWhileHeld(445f)
        assertEquals(65f, toolbar.hidden, 0.5f)
        compose.runOnUiThread {
            toolbar.touch(false)
            toolbar.scroll(340f)
        }
        compose.mainClock.advanceTimeBy(900)
        assertEquals(100f, toolbar.hidden, 0.5f)
        compose.runOnUiThread { toolbar.scroll(0f) }
        assertEquals("Reaching the document top always restores the toolbar", 0f, toolbar.hidden, 0f)
    }

    @Test fun newTouchTakesOverMidSpringAndReducedMotionSnapsAtHalf() {
        moveWhileHeld(240f)
        compose.runOnUiThread { toolbar.touch(false) }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnUiThread { toolbar.touch(true) }
        val interrupted = toolbar.hidden
        assertTrue(interrupted in 1f..99f)
        compose.mainClock.advanceTimeBy(600)
        assertEquals("A held finger should stop the existing spring", interrupted, toolbar.hidden, 0f)
        compose.runOnUiThread {
            toolbar.configure(100f, forced = false, reducedMotion = true)
            toolbar.reset()
            toolbar.scroll(200f)
            toolbar.touch(true)
            toolbar.scroll(250f)
            toolbar.touch(false)
        }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("At exactly half, the endpoint is hidden", 100f, toolbar.hidden, 0f)
    }

    private fun moveWhileHeld(position: Float) {
        compose.runOnUiThread { toolbar.touch(true) }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnUiThread { toolbar.scroll(position) }
    }
}
