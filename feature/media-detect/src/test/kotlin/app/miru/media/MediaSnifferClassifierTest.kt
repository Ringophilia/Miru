// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSnifferClassifierTest {
    @Test fun classifiesHlsDashDirectVideoAndLargeAudio() {
        assertEquals("HLS", MediaSnifferClassifier.classify("https://cdn.example.com/live/master.m3u8?token=abc"))
        assertEquals("HLS", MediaSnifferClassifier.classify("https://cdn.example.com/stream", "application/vnd.apple.mpegurl; charset=utf-8"))
        assertEquals("DASH", MediaSnifferClassifier.classify("https://cdn.example.com/v/manifest.mpd"))
        assertEquals("DASH", MediaSnifferClassifier.classify("https://cdn.example.com/v/play", "application/dash+xml"))
        assertEquals("VIDEO", MediaSnifferClassifier.classify("https://cdn.example.com/video.mp4", "video/mp4", 5_000_000))
        assertEquals("AUDIO", MediaSnifferClassifier.classify("https://cdn.example.com/track", "audio/mpeg", 2_000_000))
    }

    @Test fun ignoresSegmentsSmallPreviewsSmallAudioAdsAndNonHttp() {
        assertNull(MediaSnifferClassifier.classify("https://cdn.example.com/seg-001.ts", "video/mp2t", 1_000_000))
        assertNull(MediaSnifferClassifier.classify("https://cdn.example.com/chunk.m4s", "video/iso.segment", 1_000_000))
        assertNull(MediaSnifferClassifier.classify("https://cdn.example.com/preview.mp4", "video/mp4", 150_000))
        assertNull(MediaSnifferClassifier.classify("https://cdn.example.com/click.mp3", "audio/mpeg", 120_000))
        assertNull(MediaSnifferClassifier.classify("https://pubads.doubleclick.net/video.mp4", "video/mp4", 5_000_000))
        assertNull(MediaSnifferClassifier.classify("blob:https://example.com/1234", "video/mp4", 5_000_000))
        assertTrue(MediaSnifferClassifier.isVastPayload("application/xml", "<VAST version=\"4.0\">"))
        assertFalse(MediaSnifferClassifier.isVastPayload("video/mp4", "<VAST"))
    }

    @Test fun deduplicatesSignedUrlsAndPreservesNewestAllowedHeadersOnly() {
        val coordinator = MediaSnifferCoordinator()
        val first = coordinator.onNetworkMedia(
            tabId = "tab-1",
            pageUrl = "https://watch.example.com/ep1",
            title = "Episode 1",
            isPrivate = false,
            mediaUrl = "https://cdn.example.com/ep1/index.m3u8?sign=old",
            contentType = "application/vnd.apple.mpegurl",
            headers = mapOf("Referer" to "https://watch.example.com/ep1", "X-Internal" to "drop-me"),
        )
        assertNotNull(first)
        val second = coordinator.onNetworkMedia(
            tabId = "tab-1",
            pageUrl = "https://watch.example.com/ep1",
            title = "Episode 1",
            isPrivate = false,
            mediaUrl = "https://cdn.example.com/ep1/index.m3u8?sign=fresh",
            contentType = "application/vnd.apple.mpegurl",
            headers = mapOf("Cookie" to "sid=new", "Referer" to "https://watch.example.com/ep1", "X-Ignored" to "nope"),
        )
        assertNotNull(second)

        val tabMedia = coordinator.state.value["tab-1"].orEmpty()
        assertEquals(1, tabMedia.size)
        assertEquals("https://cdn.example.com/ep1/index.m3u8?sign=fresh", tabMedia.single().url)
        assertEquals(setOf("Cookie", "Referer"), tabMedia.single().headers.keys)

        coordinator.onNetworkMedia(
            tabId = "tab-1",
            pageUrl = "https://watch.example.com/ep1",
            title = "Episode 1",
            isPrivate = false,
            mediaUrl = "https://cdn.example.com/ep1/index.m3u8?sign=fresh",
            headers = emptyMap(),
        )
        assertEquals(setOf("Cookie", "Referer"), coordinator.state.value["tab-1"].orEmpty().single().headers.keys)

        coordinator.onNavigation("tab-1")
        assertTrue(coordinator.state.value["tab-1"].isNullOrEmpty())
    }
}
