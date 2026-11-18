// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.tabs

import org.junit.Assert.assertEquals
import org.junit.Test

class TabDismissDirectionTest {
    @Test fun distanceMustExceedFortyPercentAndUsesTheDisplacementDirection() {
        assertEquals(0, tabDismissDirection(40f, 100f, 0f, 1_000f))
        assertEquals(1, tabDismissDirection(41f, 100f, -1_500f, 1_000f))
        assertEquals(-1, tabDismissDirection(-41f, 100f, 0f, 1_000f))
    }

    @Test fun fastShortSwipesUseTheirVelocityDirection() {
        assertEquals(0, tabDismissDirection(15f, 100f, 1_000f, 1_000f))
        assertEquals(1, tabDismissDirection(15f, 100f, 1_001f, 1_000f))
        assertEquals(-1, tabDismissDirection(-15f, 100f, -1_001f, 1_000f))
    }
}
