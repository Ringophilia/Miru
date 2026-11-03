// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.design

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import app.miru.common.AccentColor

/** Fixed seeds from spec 6.2; tone 10/20/30/80/90 from Material Color Utilities 0.3.0 TonalPalette.fromInt.
 * Default red retains the spec's original hand-tuned companion colors.
 * https://github.com/material-foundation/material-color-utilities/tree/main/typescript/palettes
 * Precomputed values keep palette generation and extra libraries out of the app runtime.
 */
private data class Tones(val seed: Long, val t10: Long, val t20: Long, val t30: Long, val t80: Long, val t90: Long)
private fun AccentColor.tones() = when (this) {
    AccentColor.RINGO_RED -> Tones(0xFFBE1625, 0xFF410007, 0xFF680010, 0xFF8F0F22, 0xFFFFB3AE, 0xFFFFDAD7)
    AccentColor.LEAF_GREEN -> Tones(0xFF47643B, 0xFF072102, 0xFF1C3713, 0xFF324E27, 0xFFAFD09E, 0xFFCAECB8)
    AccentColor.NAVY -> Tones(0xFF2E4A7D, 0xFF001A41, 0xFF0E2F61, 0xFF2A4679, 0xFFADC6FF, 0xFFD8E2FF)
    AccentColor.TEAL -> Tones(0xFF00897B, 0xFF00201C, 0xFF003731, 0xFF005048, 0xFF70D8C8, 0xFF8DF5E4)
    AccentColor.PURPLE -> Tones(0xFF7C4DFF, 0xFF20005F, 0xFF370096, 0xFF4F00D0, 0xFFCDBDFF, 0xFFE8DEFF)
    AccentColor.GRAPHITE -> Tones(0xFF455A64, 0xFF071E27, 0xFF1E333C, 0xFF354A53, 0xFFB4CAD6, 0xFFCFE6F2)
}

fun accentPreviewColor(accent: AccentColor): Color = Color(accent.tones().seed)

internal fun ColorScheme.withAccent(accent: AccentColor, dark: Boolean): ColorScheme {
    val tones = accent.tones()
    return copy(
        primary = Color(if (dark) tones.t80 else tones.seed),
        // The specified teal seed is too light for small white button labels.
        onPrimary = if (dark) Color(tones.t20) else if (accent == AccentColor.TEAL) Color.Black else Color.White,
        primaryContainer = Color(if (dark) tones.t30 else tones.t90),
        onPrimaryContainer = Color(if (dark) tones.t90 else tones.t10),
        inversePrimary = Color(if (dark) tones.seed else tones.t80),
        surfaceTint = Color(if (dark) tones.t80 else tones.seed),
    )
}
