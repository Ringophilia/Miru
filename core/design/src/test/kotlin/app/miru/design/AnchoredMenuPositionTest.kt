// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.design

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class AnchoredMenuPositionTest {
    private val position = AnchoredMenuPosition(verticalMargin = 48)
    private val screen = IntSize(400, 800)
    private val menu = IntSize(260, 380)

    @Test fun followsTopAndBottomButtons() {
        assertEquals(IntOffset(112, 96), position.calculatePosition(IntRect(324, 48, 372, 96), screen, LayoutDirection.Ltr, menu))
        assertEquals(IntOffset(112, 324), position.calculatePosition(IntRect(324, 704, 372, 752), screen, LayoutDirection.Ltr, menu))
        assertEquals(IntOffset(112, 415), position.calculatePosition(IntRect(324, 795, 372, 843), screen, LayoutDirection.Ltr, menu))
    }

    @Test fun alignsToLogicalEndAndKeepsWideMenuOnScreen() {
        val anchor = IntRect(24, 48, 72, 96)
        assertEquals(IntOffset(24, 96), position.calculatePosition(anchor, screen, LayoutDirection.Rtl, menu))
        assertEquals(IntOffset(0, 96), position.calculatePosition(anchor, screen, LayoutDirection.Ltr, menu))
    }

    @Test fun tallPopupUsesAvailableWindowWhenNeitherSideFits() {
        val anchor = IntRect(324, 400, 372, 448)
        assertEquals(IntOffset(0, 5), position.calculatePosition(anchor, screen, LayoutDirection.Ltr, IntSize(400, 790)))
    }
}
