// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import app.miru.superres.SuperResFit
import app.miru.superres.SuperResImageRect
import app.miru.superres.VideoZoom
import kotlin.math.abs
import kotlin.math.max

internal class PlayerZoom(private val base: SuperResImageRect, private val width: Int, private val height: Int) {
    val coverScale = max(width / base.width, height / base.height)

    fun next(current: VideoZoom, ratio: Float, previousX: Float, previousY: Float, x: Float, y: Float): VideoZoom {
        val bounded = current.clamp(base, width, height)
        val scale = (bounded.scale * ratio).coerceIn(1f, 3f)
        val applied = scale / bounded.scale
        // Scale around the previous centroid, then translate that point to the new centroid.
        val centerX = base.left + base.width / 2f
        val centerY = base.top + base.height / 2f
        return VideoZoom(
            scale,
            (x - centerX - (previousX - centerX - bounded.panX * width) * applied) / width,
            (y - centerY - (previousY - centerY - bounded.panY * height) * applied) / height,
        ).clamp(base, width, height)
    }

    fun snap(scale: Float): SuperResFit? = when {
        abs(scale - 1f) <= .06f -> SuperResFit.CONTAIN
        coverScale > 1.06f && coverScale <= 3f && abs(scale / coverScale - 1f) <= .06f -> SuperResFit.COVER
        else -> null
    }
}
