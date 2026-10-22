// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.media

import org.junit.Assert.*
import org.junit.Test

class AutomaticTakeoverTest {
    private fun item(kind: String = "VIDEO", duration: Double = 60.0, drm: Boolean = false, playing: Boolean = true, width: Int = 320, url: String = "https://example.test/video.mp4") =
        DetectedMedia("video", "tab", url, "https://example.test/page", "Video", kind, duration = duration, width = width, height = 180, playing = playing, drm = drm)
    @Test fun requiresPlayingVideoWithFiniteTimeline() {
        assertTrue(item().canAutoTakeover)
        assertTrue(item(kind = "HLS").canAutoTakeover)
        assertFalse(item(playing = false).canAutoTakeover)
        assertFalse(item(kind = "AUDIO").canAutoTakeover)
        assertFalse(item(width = 0).canAutoTakeover)
    }
    @Test fun protectedUnknownAndLiveSourcesRemainOnThePage() {
        assertFalse(item(drm = true).canAutoTakeover)
        assertFalse(item(duration = 0.0).canAutoTakeover)
        assertFalse(item(duration = Double.POSITIVE_INFINITY).canAutoTakeover)
        assertFalse(item(url = "blob:https://example.test/uuid").canAutoTakeover)
    }
}
