// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import app.miru.superres.SuperResFit
import app.miru.superres.SuperResImageRect
import app.miru.superres.VideoZoom
import org.junit.Assert.*
import org.junit.Test

class PlayerZoomTest {
    private val base = SuperResImageRect(100f, 0f, 800f, 600f)
    private val geometry = PlayerZoom(base, 1000, 600)

    @Test fun limitsScaleAndKeepsTheImageOverTheViewportWhenPanning() {
        val enlarged = geometry.next(VideoZoom(), 8f, 500f, 300f, 500f, 300f)
        assertEquals(3f, enlarged.scale, .001f)
        val translated = geometry.next(enlarged, 1f, 500f, 300f, 2500f, -1700f)
        val image = translated.imageRect(base, 1000, 600)
        assertEquals(0f, image.left, .001f)
        assertEquals(600f, image.bottom, .001f)
        assertEquals(VideoZoom(), geometry.next(translated, .01f, 500f, 300f, 500f, 300f))
    }

    @Test fun keepsThePinchedPointUnderTheFingersAndSnapsNearFitScales() {
        val enlarged = geometry.next(VideoZoom(), 2f, 600f, 350f, 650f, 370f)
        val image = enlarged.imageRect(base, 1000, 600)
        assertEquals(650f, image.left + image.width * .625f, .001f)
        assertEquals(370f, image.top + image.height * (350f / 600f), .001f)
        assertEquals(SuperResFit.CONTAIN, geometry.snap(1.04f))
        assertEquals(SuperResFit.COVER, geometry.snap(1.28f))
        assertNull(geometry.snap(1.6f))
    }
}
