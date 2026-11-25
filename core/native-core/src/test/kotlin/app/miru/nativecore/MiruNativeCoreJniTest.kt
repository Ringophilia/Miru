// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.nativecore

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiruNativeCoreJniTest {
    @Test fun loadsUnifiedCoreAndExercisesMediaUrlAndOmniboxPaths() {
        MiruNativeBridge.ensureLoaded()
        assertTrue(MiruNativeCore.isAvailable)

        assertEquals("HLS", MiruNativeCore.classifyMedia("https://cdn.example/live.m3u8?t=1", "application/vnd.apple.mpegurl"))
        assertNull(MiruNativeCore.classifyMedia("https://cdn.example/seg.ts", "video/mp2t", 1_000_000L))
        assertEquals("https://cdn.example/live.m3u8", MiruNativeCore.mediaDedupKey("https://cdn.example/live.m3u8?token=abc"))
        assertEquals(1920 to 1080, MiruNativeCore.probeHls("#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080\nhigh.m3u8"))

        val repoRoot = File(System.getProperty("miru.repo.root") ?: "../..")
        val hevc = File(repoRoot, "feature/media-detect/src/test/resources/resolution/hevc.ts")
        assertTrue(hevc.isFile)
        val size = MiruNativeCore.probeMediaBytes(hevc.readBytes())
        assertNotNull(size)
        assertTrue(size!!.first >= 64 && size.second >= 64)

        assertEquals("https://example.test", MiruNativeCore.siteOrigin("HTTPS://EXAMPLE.TEST:443/path"))
        assertEquals("https://cdn.example/a.ts", MiruNativeCore.normalizedCacheUrl("https://cdn.example/a.ts?token=a&expires=1", false))
        assertEquals("https://cdn.example/a.ts?quality=hd", MiruNativeCore.normalizedCacheUrl("https://cdn.example/a.ts?quality=hd", false))
        assertEquals("https://a.test/", MiruNativeCore.contextReferrer("https://a.test/p?q=1", "https://b.test/img.png"))
        assertNull(MiruNativeCore.contextReferrer("https://a.test/p", "https://a.test/img.png", ""))
        assertTrue(MiruNativeCore.externalDataAllowed("weixin://open", false) == true)
        assertFalse(MiruNativeCore.externalDataAllowed("intent://evil", true) == true)

        assertEquals(
            NativeInlineDomain("Google.com", "https://www.google.com"),
            MiruNativeCore.completeInlineDomain("Go", listOf("https://www.google.com/search?q=1")),
        )

        RustOmniboxIndex().use { index ->
            index.replaceHistory(
                listOf(
                    NativeHistoryEntry("https://github.com/miru", "Miru Repo", 100L, 5),
                    NativeHistoryEntry("https://zh.wikipedia.org/wiki/浏览器", "开源浏览器", 200L, 2),
                )
            )
            index.recordVisit("https://rust-lang.org/", "Rust Language", 300L)
            assertEquals("https://rust-lang.org", index.completeDomain("ru")?.url)
            assertEquals("开源浏览器", index.suggest("浏览器", 3).single().title)
        }
    }
}
