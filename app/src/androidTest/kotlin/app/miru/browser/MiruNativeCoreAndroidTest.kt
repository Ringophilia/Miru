// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.adblock.rust.RustAdblockEngine
import app.miru.browser.engine.ContextImagePolicy
import app.miru.browser.engine.contextReferrer
import app.miru.browser.engine.externalDataAllowed
import app.miru.browser.engine.siteOrigin
import app.miru.common.InlineDomain
import app.miru.common.InlineDomainCompletion
import app.miru.media.MediaSnifferClassifier
import app.miru.nativecore.MiruNativeBridge
import app.miru.nativecore.MiruNativeCore
import app.miru.nativecore.NativeHistoryEntry
import app.miru.nativecore.RustOmniboxIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live on-device/emulator verification that unified `libmiru_core.so` executes inside Android. */
@RunWith(AndroidJUnit4::class)
class MiruNativeCoreAndroidTest {
    @Test fun unifiedRustNativeLibraryLoadsAndRunsAllSubsystemsLive() {
        MiruNativeBridge.ensureLoaded()
        assertTrue("libmiru_core.so must load inside Android process", MiruNativeBridge.isLoaded)
        assertTrue("MiruNativeCore.isAvailable must be true", MiruNativeCore.isAvailable)

        // 1. adblock-rust: 5,000-rule compile + snapshot roundtrip + network/cosmetic/custom rules
        val rules = buildString {
            appendLine("||ads.tracker.example^")
            appendLine("||important.example^\$important")
            appendLine("@@||important.example^")
            appendLine("@@||allowed.tracker.example^\$domain=safe.example")
            appendLine("news.example###sponsored-banner")
            for (i in 0 until 5_000) {
                appendLine("||ad-$i.doubleclick.test^")
            }
        }
        val snapshot = RustAdblockEngine.fromRules(rules).use { engine ->
            assertTrue(engine.checkNetworkRequest("https://ads.tracker.example/v1.js", "https://news.example/", "script").blocked)
            assertTrue(engine.checkNetworkRequest("https://important.example/p.js", "https://news.example/", "script").blocked)
            assertFalse(engine.checkNetworkRequest("https://allowed.tracker.example/ok.js", "https://safe.example/", "script").blocked)
            assertTrue("#sponsored-banner" in engine.cosmeticResources("https://news.example/article").hideSelectors)
            engine.serializeSnapshot()
        }
        assertTrue(snapshot.size > 100_000)

        val restoreStartMs = SystemClock.elapsedRealtime()
        RustAdblockEngine.fromSnapshot(snapshot).use { restored ->
            val restoreMs = SystemClock.elapsedRealtime() - restoreStartMs
            assertTrue("5k snapshot deserialize on Android took ${restoreMs}ms", restoreMs < 250)
            assertTrue(restored.addCustomRule("news.example###custom-picker-box"))
            assertTrue("#custom-picker-box" in restored.cosmeticResources("https://news.example/").hideSelectors)
            restored.setSiteAllowed("news.example", true)
            assertFalse(restored.checkNetworkRequest("https://ads.tracker.example/v1.js", "https://news.example/", "script").blocked)
        }

        // 2. miru-media: network classifier + header/TS resolution probe using real assets
        assertEquals("HLS", MediaSnifferClassifier.classify("https://cdn.example/master.m3u8?token=abc"))
        assertNull(MediaSnifferClassifier.classify("https://cdn.example/seg001.ts", "video/mp2t", 900_000L))
        assertEquals("https://cdn.example/master.m3u8", MediaSnifferClassifier.dedupKey("https://cdn.example/master.m3u8?sign=xyz"))
        assertEquals(3840 to 2160, MiruNativeCore.probeHls("#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=3840x2160\n4k.m3u8"))

        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val tsBytes = assets.open("media/segment00.ts").use { it.readBytes() }
        assertEquals(320 to 180, MiruNativeCore.probeMediaBytes(tsBytes))
        val mp4Bytes = assets.open("media/fixture.mp4").use { stream ->
            val buf = ByteArray(393_216)
            var total = 0
            while (total < buf.size) {
                val read = stream.read(buf, total, buf.size - total)
                if (read <= 0) break
                total += read
            }
            if (total == buf.size) buf else buf.copyOf(total)
        }
        assertEquals(320 to 180, MiruNativeCore.probeMp4(mp4Bytes))

        // 3. miru-url: origin normalization, cache-key stripping, referrer policies, external schemes
        assertEquals("https://example.test", siteOrigin("HTTPS://EXAMPLE.TEST:443/path?a=1#frag"))
        assertNull(siteOrigin("https://user:pass@example.test/"))
        assertEquals(
            "https://cdn.example/seg01.ts",
            MiruNativeCore.normalizedCacheUrl("https://cdn.example/seg01.ts?Expires=1700000&Signature=abc", false),
        )
        assertEquals(
            "https://example.test/",
            contextReferrer("https://example.test/private?token=1", "https://other.test/img.png"),
        )
        assertNull(ContextImagePolicy.fromDocument("https://example.test/p", "no-referrer")?.referrerFor("https://example.test/i.png"))
        assertTrue(externalDataAllowed("weixin://dl/business", false))
        assertFalse(externalDataAllowed("intent://evil", true))

        // 4. miru-storage: omnibox inline domain completion + CJK history ranking
        assertEquals(
            InlineDomain("github.com", "https://github.com"),
            InlineDomainCompletion.find("git", listOf("https://github.com/miru/browser")),
        )
        RustOmniboxIndex().use { index ->
            index.replaceHistory(
                listOf(
                    NativeHistoryEntry("https://github.com/miru", "Miru Browser", 100L, 4),
                    NativeHistoryEntry("https://zh.wikipedia.org/wiki/浏览器", "开源浏览器", 200L, 2),
                ),
            )
            index.recordVisit("https://rust-lang.org/", "Rust Language", 500L)
            assertEquals("https://rust-lang.org", index.completeDomain("ru")?.url)
            val cjkHits = index.suggest("浏览器", 5)
            assertNotNull(cjkHits.singleOrNull())
            assertEquals("开源浏览器", cjkHits.single().title)
        }
    }
}
