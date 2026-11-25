// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.player

import app.miru.superres.SuperResFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPreferencesDefaultsTest {
    @Test fun playbackDefaultsAndDecoderFallbacksAreBounded() {
        val defaults = PlayerPreferences()
        assertEquals(1f, defaults.defaultSpeed)
        assertTrue(defaults.rememberProgress)
        assertEquals(SuperResFit.CONTAIN, defaults.defaultVideoFit)
        assertEquals("auto", defaults.defaultDecoder)

        val bounded = PlayerPreferences(defaultSpeed = 4.0f, defaultDecoder = "invalid").bounded()
        assertEquals(2f, bounded.defaultSpeed)
        assertEquals("auto", bounded.defaultDecoder)

        val lowSpeed = PlayerPreferences(defaultSpeed = 0.1f, defaultDecoder = "hardware").bounded()
        assertEquals(0.5f, lowSpeed.defaultSpeed)
        assertEquals("hardware", lowSpeed.defaultDecoder)
    }
}
