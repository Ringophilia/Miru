// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.design

import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.miru.common.AccentColor
import kotlin.math.abs
import kotlin.math.cbrt
import org.junit.Assert.*
import org.junit.Test

class AccentPaletteTest {
    @Test fun lightPrimariesMatchTheSixSpecifiedSeeds() {
        val expected = listOf(0xFFBE1625, 0xFF47643B, 0xFF2E4A7D, 0xFF00897B, 0xFF7C4DFF, 0xFF455A64)
        AccentColor.entries.zip(expected).forEach { (accent, argb) ->
            assertEquals(Color(argb), lightColorScheme().withAccent(accent, false).primary)
        }
    }
    @Test fun darkPrimariesAreTone80AndAllButtonAndContainerLabelsRemainReadable() {
        AccentColor.entries.forEach { accent ->
            for (dark in listOf(false, true)) {
                val scheme = (if (dark) darkColorScheme() else lightColorScheme()).withAccent(accent, dark)
                fun contrast(a: Color, b: Color): Double {
                    val l1 = a.luminance().toDouble(); val l2 = b.luminance().toDouble()
                    return (maxOf(l1, l2) + .05) / (minOf(l1, l2) + .05)
                }
                assertTrue("$accent/$dark primary text", contrast(scheme.primary, scheme.onPrimary) >= 4.5)
                assertTrue("$accent/$dark container text", contrast(scheme.primaryContainer, scheme.onPrimaryContainer) >= 4.5)
                if (dark) assertTrue("$accent dark tone", abs(116 * cbrt(scheme.primary.luminance().toDouble()) - 16 - 80) < 1)
            }
        }
    }
    @Test fun presetsKeepThePaperSurfacesAndStatusColors() {
        val base = lightColorScheme(surface = Color(0xFFFAF5EA), tertiary = Color(0xFF47643B))
        AccentColor.entries.forEach { accent ->
            val scheme = base.withAccent(accent, false)
            assertEquals(base.surface, scheme.surface)
            assertEquals(base.surfaceContainer, scheme.surfaceContainer)
            assertEquals(base.tertiary, scheme.tertiary)
            assertEquals(base.error, scheme.error)
        }
    }
}
