// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import app.miru.adblock.rust.RustAdblockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class ChromiumPhaseC2UnitTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun adblockManagerLoadsBundledListsCachesSnapshotAndBlocksTrackers() {
        val assetDir = File("src/main/assets")
        val sink = RecordingAdblockSink()
        val filesDir = tempFolder.newFolder("app-files")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val manager = ChromiumAdblockManager(
                filesDir = filesDir,
                defaultLocaleLanguage = "zh",
                assetReader = { rel -> File(assetDir, rel).readText(Charsets.UTF_8) },
                scope = scope,
                runtimeSink = sink,
            )
            manager.initializeSync()

            assertTrue("Snapshot should be serialized and cached on disk", File(filesDir, "adblock/engine.dat").length() > 0L)
            assertEquals(1, sink.snapshots.size)
            val state = manager.state.value
            assertEquals(4, state.filterLists.size)
            assertTrue(state.filterLists.all { it.enabled && it.ruleCount > 0 })

            // Verify bundled default lists cover major ad/tracker/Chinese-ad categories.
            RustAdblockEngine.fromSnapshot(sink.snapshots.last()).use { engine ->
                assertTrue(engine.checkNetworkRequest("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "https://example.com/", "script").blocked)
                assertTrue(engine.checkNetworkRequest("https://www.google-analytics.com/analytics.js", "https://example.com/", "script").blocked)
                assertTrue(engine.checkNetworkRequest("https://cpro.baidu.com/cpro/ui/c.js", "https://news.example.cn/", "script").blocked)
                assertTrue(engine.checkNetworkRequest("https://gdt.qq.com/qschema/ad.js", "https://news.example.cn/", "script").blocked)
                assertTrue(engine.checkNetworkRequest("https://browser.sentry-cdn.com/7.0.0/bundle.min.js", "https://example.com/", "script").blocked)
                assertTrue(engine.checkNetworkRequest("http://127.0.0.1:8080/pagead.js", "http://127.0.0.1:8080/page", "script").blocked)
                assertFalse(engine.checkNetworkRequest("https://example.com/main.js", "https://example.com/", "script").blocked)
                val cosmetic = engine.cosmeticResources("https://example.com/")
                assertTrue(cosmetic.hideSelectors.contains("div.ad-banner"))
                assertTrue(cosmetic.hideSelectors.contains("[data-ad-slot]"))
            }

            // Add custom element-picker style rule + site exception and verify persistence across restart.
            assertTrue(manager.addCustomRule("example.com###picked-banner"))
            assertTrue(manager.setSiteAllowed("trusted.example", true))

            RustAdblockEngine.fromSnapshot(sink.snapshots.last()).use { engine ->
                assertTrue(engine.cosmeticResources("https://example.com/").hideSelectors.contains("#picked-banner"))
            }

            val sink2 = RecordingAdblockSink()
            val restarted = ChromiumAdblockManager(
                filesDir = filesDir,
                defaultLocaleLanguage = "zh",
                assetReader = { error("Should reuse cached snapshot on unchanged config!") },
                scope = scope,
                runtimeSink = sink2,
            )
            restarted.initializeSync()
            assertEquals(1, sink2.snapshots.size)
            assertTrue(restarted.isSiteAllowed("sub.trusted.example"))
            assertEquals(listOf("example.com###picked-banner"), restarted.state.value.customRules)
            assertEquals(listOf("trusted.example"), sink2.allowedHosts.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun mediaManagerMergesDomStateSanitizesHeadersAndEnforcesDrmAndPrivacy() = runBlocking {
        val cookieCalls = CopyOnWriteArrayList<Pair<Boolean, String>>()
        val manager = ChromiumMediaManager(
            cookieFetcher = { isPrivate, url, cb ->
                cookieCalls += isPrivate to url
                cb(if (isPrivate) "priv=1" else "sess=fresh_cookie")
            },
            userAgentProvider = { "MiruTest/Chrome159" },
            uiDispatcher = Dispatchers.Unconfined,
        )

        manager.onNetworkMedia(
            tabId = "tab-1",
            pageUrl = "https://video.example/watch",
            title = "Episode 1",
            isPrivate = false,
            url = "https://cdn.example/live/master.m3u8?token=abc",
            kind = "HLS",
            mimeType = "application/vnd.apple.mpegurl",
            contentLength = -1L,
            headersJson = """{"Referer":"https://video.example/watch","X-Evil":"drop","Authorization":"Bearer tok"}""",
        )

        val event = JSONObject(
            """{
                "kind":"dom_media",
                "event":"play",
                "pageUrl":"https://video.example/watch",
                "title":"Episode 1 HD",
                "items":[{"url":"blob:https://video.example/1234","kind":"VIDEO","duration":125.5,"position":42.25,"width":1920,"height":1080,"playing":true,"drm":false}]
            }""",
        )
        manager.onDomMediaEvent("tab-1", "https://video.example/watch", "", false, event)

        val detected = manager.state.value["tab-1"].orEmpty().single()
        assertEquals(42.25, detected.position, 0.01)
        assertEquals(1920, detected.width)
        assertEquals(1080, detected.height)
        assertTrue(detected.playing)
        assertEquals("https://video.example/watch", detected.headers["Referer"])
        assertEquals("Bearer tok", detected.headers["Authorization"])
        assertFalse(detected.headers.containsKey("X-Evil"))

        val prepared = manager.prepareMedia("tab-1", null, "https://video.example/watch", detected.id)
        assertNotNull(prepared)
        assertEquals(42_250L, prepared!!.positionMs)
        assertEquals("sess=fresh_cookie", prepared.headers["Cookie"])
        assertEquals("MiruTest/Chrome159", prepared.headers["User-Agent"])

        val dlHeaders = manager.downloadHeaders(detected.url, detected.pageUrl)
        assertEquals("sess=fresh_cookie", dlHeaders?.get("Cookie"))

        // DRM marking blocks handoff cleanly.
        val drmEvent = JSONObject(
            """{
                "kind":"dom_media",
                "event":"encrypted",
                "pageUrl":"https://video.example/watch",
                "title":"Episode 1 HD",
                "items":[{"url":"blob:https://video.example/1234","kind":"VIDEO","duration":125.5,"position":42.25,"width":1920,"height":1080,"playing":true,"drm":true}]
            }""",
        )
        manager.onDomMediaEvent("tab-1", "https://video.example/watch", "", false, drmEvent)
        assertNull(manager.prepareMedia("tab-1", null, "https://video.example/watch", detected.id))

        manager.onNavigation("tab-1")
        assertTrue(manager.state.value["tab-1"].isNullOrEmpty())
        assertTrue(cookieCalls.none { it.first })

        // Verify sub-iframe HTTP <video> DOM event preserves C++ network-captured Referer header.
        manager.onNetworkMedia(
            tabId = "tab-2",
            pageUrl = "https://host.example/watch",
            title = "Subframe Page",
            isPrivate = false,
            url = "https://cdn.example/clip.mp4",
            kind = "VIDEO",
            mimeType = "video/mp4",
            contentLength = 1_500_000L,
            headersJson = """{"Referer":"https://host.example/embed"}""",
        )
        val subEvent = JSONObject(
            """{
                "kind":"dom_media",
                "event":"seeked",
                "isTop":false,
                "pageUrl":"https://host.example/embed",
                "title":"Subframe Player",
                "items":[{"url":"https://cdn.example/clip.mp4","kind":"VIDEO","duration":60.0,"position":12.0,"width":1280,"height":720,"playing":false,"drm":false}]
            }""",
        )
        manager.onDomMediaEvent("tab-2", "https://host.example/watch", "Subframe Page", false, subEvent)
        val merged = manager.state.value["tab-2"].orEmpty().single()
        assertEquals("https://host.example/embed", merged.headers["Referer"])
        assertEquals(12.0, merged.position, 0.01)
    }

    private class RecordingAdblockSink : RuntimeAdblockSink {
        val snapshots = mutableListOf<ByteArray>()
        val allowedHosts = linkedSetOf<String>()
        var adblockEnabled = true

        override fun setSnapshot(snapshot: ByteArray): Boolean {
            snapshots += snapshot
            return true
        }

        override fun setRules(rulesText: String) = Unit
        override fun setEnabled(enabled: Boolean) { adblockEnabled = enabled }
        override fun setSiteAllowed(host: String, allowed: Boolean) {
            if (allowed) allowedHosts.add(host) else allowedHosts.remove(host)
        }
    }
}
