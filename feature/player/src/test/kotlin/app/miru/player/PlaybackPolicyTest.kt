// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    @Test fun playbackOptionsBoundInvalidValuesAndDataSaverKeepsValidBuffers() {
        val invalid = PlayerPreferences(cacheMb = -1, bufferSeconds = -1, doubleTapSeconds = 999, holdSpeed = Float.NaN, dragRangeSeconds = -1).bounded()
        assertEquals(128, invalid.cacheMb)
        assertEquals(60, invalid.doubleTapSeconds)
        assertEquals(2f, invalid.holdSpeed)
        assertEquals(30, invalid.dragRangeSeconds)
        assertEquals(15_000 to 15_000, invalid.bufferMillis(false))
        assertEquals(30_000 to 60_000, PlayerPreferences().bufferMillis(false))
        assertEquals(15_000 to 30_000, PlayerPreferences(bufferSeconds = 180).bufferMillis(true))
        assertTrue(PlayerPreferences.dataSaverActive(true, android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED))
        assertTrue(PlayerPreferences.dataSaverActive(true, android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED))
        assertFalse(PlayerPreferences.dataSaverActive(true, android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED))
        assertFalse(PlayerPreferences.dataSaverActive(false, android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED))
    }
    @Test fun refreshableSegmentSignatureCanShareCache() {
        assertEquals("https://cdn.example/a.ts", PlaybackPolicy.normalizedCacheUrl("https://cdn.example/a.ts?token=a&expires=1"))
        assertEquals("https://cdn.example/movie.mp4", PlaybackPolicy.normalizedCacheUrl("https://cdn.example/movie.mp4?Signature=a&Expires=2", range = true))
    }
    @Test fun manifestsAndContentSelectingQueriesStayDistinct() {
        for(url in listOf("https://cdn.example/a.m3u8?token=a", "https://cdn.example/a.mpd?token=a", "https://cdn.example/a.ts?quality=hd&token=a", "https://cdn.example/stream?token=a")) assertEquals(url, PlaybackPolicy.normalizedCacheUrl(url))
    }
    @Test fun onlyUsefulPositionsResumeAndProgressKeysDoNotStoreSignedUrls() {
        assertEquals(8_000, PlaybackPolicy.resume(8_000, 60_000))
        assertEquals(0, PlaybackPolicy.resume(4_000, 60_000))
        assertEquals(0, PlaybackPolicy.resume(50_000, 60_000))
        assertEquals(0, PlaybackPolicy.resume(50_000, 0))
        assertEquals(PlaybackPolicy.progressKey("https://site/watch", "https://cdn/video.mp4?token=one"), PlaybackPolicy.progressKey("https://site/watch", "https://cdn/video.mp4?token=two"))
        assertNotEquals(PlaybackPolicy.progressKey("https://site/other", "https://cdn/video.mp4"), PlaybackPolicy.progressKey("https://site/watch", "https://cdn/video.mp4"))
    }
}
