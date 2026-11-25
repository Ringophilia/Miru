// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.adblock.rust

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RustAdblockEngineJniTest {
    private val sampleFilterList = """
        ! Miru Phase C0 JNI filter prototype
        ||doubleclick.net^
        ||ads.example.com^${'$'}third-party
        ||tracker.cn/beacon.js^${'$'}script
        ||important-ad.example.org^${'$'}important
        @@||ads.example.com/safe.js^
        example.com##.ad-banner
        example.com###promo-modal
    """.trimIndent()

    @Test fun networkBlockingExceptionsGlobalAndPerSiteToggleWorkThroughJni() {
        RustAdblockEngine.fromRules(sampleFilterList).use { engine ->
            val blocked = engine.checkNetworkRequest(
                url = "https://ads.example.com/track.js",
                sourceUrl = "https://news.example.net/story",
                requestType = "script",
            )
            assertTrue(blocked.blocked)

            val excepted = engine.checkNetworkRequest(
                url = "https://ads.example.com/safe.js",
                sourceUrl = "https://news.example.net/story",
                requestType = "script",
            )
            assertFalse(excepted.blocked)

            val important = engine.checkNetworkRequest(
                url = "https://important-ad.example.org/pixel.gif",
                sourceUrl = "https://example.com/",
                requestType = "image",
            )
            assertTrue(important.blocked)
            assertTrue(important.important)

            engine.setEnabled(false)
            assertFalse(engine.checkNetworkRequest("https://doubleclick.net/a.js", "https://example.com/", "script").blocked)
            engine.setEnabled(true)
            assertTrue(engine.checkNetworkRequest("https://doubleclick.net/a.js", "https://example.com/", "script").blocked)

            engine.setSiteAllowed("example.com", true)
            assertTrue(engine.isSiteAllowed("example.com"))
            assertTrue(engine.isSiteAllowed("m.example.com"))
            assertFalse(engine.checkNetworkRequest("https://doubleclick.net/a.js", "https://m.example.com/v", "script").blocked)
            assertTrue(engine.cosmeticResources("https://example.com/home").hideSelectors.isEmpty())

            engine.setSiteAllowed("example.com", false)
            assertFalse(engine.isSiteAllowed("example.com"))
            assertTrue(engine.checkNetworkRequest("https://doubleclick.net/a.js", "https://m.example.com/v", "script").blocked)
            assertTrue(".ad-banner" in engine.cosmeticResources("https://example.com/home").hideSelectors)
        }
    }

    @Test fun customElementPickerRulesAndSnapshotColdStartWorkThroughJni() {
        val rulesWithSyntheticLoad = buildString {
            appendLine(sampleFilterList)
            repeat(2_000) { i -> appendLine("||ad-net-$i.tracker.test^${'$'}third-party") }
        }
        val snapshot: ByteArray
        RustAdblockEngine.fromRules(rulesWithSyntheticLoad).use { engine ->
            assertTrue(engine.addCustomRule("video.example.org##.overlay-ad"))
            assertTrue("video.example.org##.overlay-ad" in engine.customRules())
            assertTrue(".overlay-ad" in engine.cosmeticResources("https://video.example.org/ep1").hideSelectors)
            assertTrue(engine.removeCustomRule("video.example.org##.overlay-ad"))
            assertFalse(".overlay-ad" in engine.cosmeticResources("https://video.example.org/ep1").hideSelectors)

            snapshot = engine.serializeSnapshot()
            assertTrue(snapshot.isNotEmpty())
        }

        val startNanos = System.nanoTime()
        RustAdblockEngine.fromSnapshot(snapshot).use { restored ->
            val loadMs = (System.nanoTime() - startNanos) / 1_000_000.0
            assertTrue("Snapshot load should be fast (<500ms), was ${loadMs}ms", loadMs < 500.0)
            var blockedCount = 0
            repeat(500) { i ->
                val url = if (i % 2 == 0) "https://ad-net-$i.tracker.test/ad.js" else "https://cdn.clean.test/$i.js"
                if (restored.checkNetworkRequest(url, "https://example.com/", "script").blocked) blockedCount++
            }
            assertEquals(250, blockedCount)
        }
    }

    @Test fun unifiedRustCoreMediaClassifierHevcProbeAndUrlPoliciesWorkThroughJni() {
        NativeAdblockBridge.ensureLoaded()
        assertEquals("HLS", NativeAdblockBridge.nativeClassifyMedia("https://cdn.example.com/live/index.m3u8?sign=1", "", 0L))
        assertEquals("DASH", NativeAdblockBridge.nativeClassifyMedia("https://cdn.example.com/stream", "application/dash+xml", 0L))
        assertEquals("VIDEO", NativeAdblockBridge.nativeClassifyMedia("https://cdn.example.com/v.mp4", "video/mp4", 4_000_000L))
        assertNull(NativeAdblockBridge.nativeClassifyMedia("https://cdn.example.com/seg01.ts", "video/mp2t", 1_000_000L))
        assertNull(NativeAdblockBridge.nativeClassifyMedia("https://pubads.doubleclick.net/ad.mp4", "video/mp4", 4_000_000L))

        assertEquals(
            "https://cdn.example.com/seg01.ts",
            NativeAdblockBridge.nativeStripCacheKeySignatures("https://cdn.example.com/seg01.ts?Expires=1700000&Signature=abc"),
        )
        assertEquals(
            "https://cdn.example.com/index.m3u8?token=abc",
            NativeAdblockBridge.nativeStripCacheKeySignatures("https://cdn.example.com/index.m3u8?token=abc"),
        )
        assertEquals("https://example.com", NativeAdblockBridge.nativeSiteOrigin("HTTPS://Example.COM:443/path?q=1"))
        assertNull(NativeAdblockBridge.nativeSiteOrigin("https://user:pass@example.com/"))

        val repoRoot = File(checkNotNull(System.getProperty("miru.repo.root")))
        val hevcBytes = File(repoRoot, "feature/media-detect/src/test/resources/resolution/hevc.ts").readBytes()
        val probed = NativeAdblockBridge.nativeProbeMediaBytesJson(hevcBytes)
        assertTrue("Expected non-empty HEVC probe JSON, got $probed", probed != null && probed.contains("\"width\"") && probed.contains("\"height\""))
    }
}
