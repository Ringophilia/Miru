// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.media

/** A measured picture size, never a quality word guessed from a URL or page title. */
data class MediaResolution(val width: Int, val height: Int) {
    val label: String get() = when (minOf(width, height)) {
        in 2160..Int.MAX_VALUE -> "4K"
        in 1440..2159 -> "2K"
        in 1080..1439 -> "1080P"
        in 720..1079 -> "720P"
        else -> "480P"
    }
    internal val pixels: Long get() = width.toLong() * height
    companion object {
        internal fun valid(width: Int, height: Int): MediaResolution? =
            if (width in 1..16384 && height in 1..16384) MediaResolution(width, height) else null
    }
}
