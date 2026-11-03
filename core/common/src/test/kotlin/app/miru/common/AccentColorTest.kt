// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.common

import org.junit.Assert.*
import org.junit.Test

class AccentColorTest {
    @Test fun existingAndUnknownPreferencesUseTheDefaultRed() {
        for (value in listOf(null, "", "removed-color")) assertEquals(AccentColor.RINGO_RED, AccentColor.fromPreference(value))
        assertEquals("ringoRed", AccentColor.RINGO_RED.preferenceValue)
    }
    @Test fun savedPresetsRoundTripWithoutAmbiguousKeys() {
        assertEquals(6, AccentColor.entries.map { it.preferenceValue }.toSet().size)
        AccentColor.entries.forEach { assertEquals(it, AccentColor.fromPreference(it.preferenceValue)) }
    }
}
