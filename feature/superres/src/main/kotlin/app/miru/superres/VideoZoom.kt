// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.superres

/** Translation is stored as a fraction of the viewport so it can be clamped after a resize. */
data class VideoZoom(val scale: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    private fun panLimit(image: Float, viewport: Int): Float =
        if (viewport > 0) ((image * scale / viewport - 1f) / 2f).coerceAtLeast(0f) else 0f

    fun clamp(base: SuperResImageRect, width: Int, height: Int): VideoZoom {
        val x = panLimit(base.width, width)
        val y = panLimit(base.height, height)
        return copy(
            panX = if (x == 0f) 0f else panX.coerceIn(-x, x),
            panY = if (y == 0f) 0f else panY.coerceIn(-y, y),
        )
    }

    fun imageRect(base: SuperResImageRect, width: Int, height: Int): SuperResImageRect {
        val bounded = clamp(base, width, height)
        val scaledWidth = base.width * scale
        val scaledHeight = base.height * scale
        return SuperResImageRect(
            base.left + (base.width - scaledWidth) / 2f + bounded.panX * width,
            base.top + (base.height - scaledHeight) / 2f + bounded.panY * height,
            scaledWidth,
            scaledHeight,
        )
    }
}
