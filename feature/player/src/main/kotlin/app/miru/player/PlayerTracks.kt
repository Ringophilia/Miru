// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

data class PlayerTrackOption(val id: String, val label: String, val language: String?, val selected: Boolean)

data class PlayerTrackOptions(
    val audio: List<PlayerTrackOption> = emptyList(),
    val subtitles: List<PlayerTrackOption> = emptyList(),
    val subtitlesOff: Boolean = true,
    val localSubtitleName: String? = null,
    val localSubtitleSelected: Boolean = false,
    val importing: Boolean = false,
    val importError: Boolean = false,
)

fun playerResolutionLabel(width: Int, height: Int): String {
    // Vertical video has the same effective resolution as a rotated landscape frame.
    val edge = minOf(width, height)
    return when {
        edge >= 2160 -> "4K"
        edge >= 1440 -> "2K"
        edge >= 1080 -> "1080P"
        edge >= 720 -> "720P"
        edge > 0 -> "480P"
        else -> ""
    }
}
