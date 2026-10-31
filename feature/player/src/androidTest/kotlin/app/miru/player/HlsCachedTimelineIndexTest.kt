// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.hls.playlist.HlsRedundantGroup
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceEventListener.EventDispatcher
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import java.io.Closeable
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

/** Official parser/tracker paths. Synthetic byte dependencies are separate from the real TS disk gate. */
@RunWith(AndroidJUnit4::class)
class HlsCachedTimelineIndexTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun independentVbrSegmentsKeepTheDeclaredTimeHoleInsteadOfByteProportions() {
        val playlist = parse("#EXTINF:2.5,\na.ts\n#EXTINF:1.5,\nb.ts\n#EXTINF:4,\nc.ts")
        val fixture = Resources().apply { put("a.ts", 13); put("b.ts", 50_000, cached = false); put("c.ts", 97) }
        assertEquals(listOf(time(0, 2_500_000), time(4_000_000, 8_000_000)), coverage(index(playlist, fixture), fixture))
    }

    @Test fun byterangesShareAResourceButAOneByteHoleNeverBecomesTimeCoverage() {
        val playlist = parse("#EXTINF:4,\n#EXT-X-BYTERANGE:30@10\nall.ts\n#EXTINF:4,\n#EXT-X-BYTERANGE:50@40\nall.ts\n#EXTINF:4,\n#EXT-X-BYTERANGE:10@90\nall.ts")
        val fixture = Resources().apply {
            put("all.ts", 30, 10, 30); put("all.ts", 50, 40, 50, cached = false); put("all.ts", 10, 90, 10)
            spans.getOrPut(key("all.ts")) { arrayListOf() }.add(CachedResourceRange(key("all.ts"), 40, 49))
        }
        assertEquals(listOf(time(0, 4_000_000), time(8_000_000, 12_000_000)), coverage(index(playlist, fixture), fixture))
        fixture.spans.getValue(fixture.key("all.ts")).add(CachedResourceRange(fixture.key("all.ts"), 89, 1))
        assertEquals(listOf(time(0, 12_000_000)), coverage(index(playlist, fixture), fixture))
    }

    @Test fun encryptedInitAndMediaNeedBothKeysAndEveryInitByte() {
        val playlist = parse("#EXT-X-KEY:METHOD=AES-128,URI=\"init.key\",IV=0x01\n#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"32@7\"\n#EXT-X-KEY:METHOD=AES-128,URI=\"media.key\",IV=0x02\n#EXTINF:4,\nmedia.m4s")
        val fixture = Resources().apply {
            put("init.key", 16); put("media.key", 16); put("init.mp4", 32, 7, 32); put("media.m4s", 57)
        }
        val track = index(playlist, fixture)
        assertEquals(4, track.intervals.single().requiredBytes.size)
        assertEquals(listOf(time(0, 4_000_000)), coverage(track, fixture))
        for(name in listOf("init.key", "media.key", "init.mp4", "media.m4s")) {
            val removed = fixture.spans.remove(fixture.key(name))
            assertTrue("Missing $name must withhold the entire encrypted segment", coverage(track, fixture).isEmpty())
            fixture.spans[fixture.key(name)] = requireNotNull(removed)
        }
    }

    @Test fun resolvedCoordinatesCannotShortenOrShiftADeclaredSegmentDependency() {
        val playlist = parse("#EXTINF:4,\n#EXT-X-BYTERANGE:30@10\nall.ts")
        for(wrong in listOf(CachedResourceRange("k", 10, 29), CachedResourceRange("k", 11, 30))) {
            val track = HlsCachedTimelineIndex.track("v", playlist, 0, 0, null, { _, _, _ -> wrong })
            assertTrue("A shorter or shifted range cannot stand in for the requested bytes", track.intervals.isEmpty())
        }
    }

    @Test fun unknownLengthsNeedAnActualOriginalBindingAndInvalidAesLengthsAreRejected() {
        val playlist = parse("#EXT-X-KEY:METHOD=AES-128,URI=\"media.key\",IV=0x02\n#EXTINF:4,\nmedia.ts")
        val fixture = Resources().apply { put("media.key", 15); put("media.ts", 73) }
        assertTrue(coverage(index(playlist, fixture), fixture).isEmpty())
        fixture.put("media.key", 16)
        assertEquals(listOf(time(0, 4_000_000)), coverage(index(playlist, fixture), fixture))
        val noBindings = HlsCachedTimelineIndex.track("v", playlist, 0, 0, null, { _, _, _ -> null })
        assertTrue(CachedTimelineCoverage.compute(listOf(noBindings), fixture.spans).isEmpty())
    }

    @Test fun nonIndependentSegmentsRequireExplicitDecodeProofAndAllPrerollBytes() {
        val playlist = parse("#EXTINF:4,\na.ts\n#EXTINF:4,\nb.ts", independent = false)
        val fixture = Resources().apply { put("a.ts", 31, cached = false); put("b.ts", 61) }
        assertTrue(coverage(index(playlist, fixture), fixture).isEmpty())
        val proven = HlsCachedTimelineIndex.track("v", playlist, 0, 0, null, fixture::resolve, mapOf(1 to 0))
        assertEquals(2, proven.intervals.single().requiredBytes.size)
        assertTrue(coverage(proven, fixture).isEmpty())
        fixture.put("a.ts", 31)
        assertEquals(listOf(time(4_000_000, 8_000_000)), coverage(proven, fixture))
    }

    @Test fun discontinuityRejectsPrerollFromAnotherDecoderContext() {
        val playlist = parse("#EXTINF:4,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:4,\nb.ts", independent = false)
        val fixture = Resources().apply { put("a.ts", 31); put("b.ts", 61) }
        val wrong = HlsCachedTimelineIndex.track("v", playlist, 0, 0, null, fixture::resolve, mapOf(1 to 0))
        assertTrue(coverage(wrong, fixture).isEmpty())
        val local = HlsCachedTimelineIndex.track("v", playlist, 0, 0, null, fixture::resolve, mapOf(1 to 1))
        assertEquals(listOf(time(4_000_000, 8_000_000)), coverage(local, fixture))
    }

    @Test fun independentlySelectedAudioAndVideoAreIntersectedNotUnioned() {
        val video = parse("#EXTINF:4,\nvideo.ts")
        val audio = parse("#EXTINF:4,\naudio.aac")
        val fixture = Resources().apply { put("video.ts", 70); put("audio.aac", 3) }
        val tracks = listOf(index(video, fixture), HlsCachedTimelineIndex.track("audio", audio, 0, 2_000_000, null, fixture::resolve))
        assertEquals(listOf(time(2_000_000, 4_000_000)), CachedTimelineCoverage.compute(tracks, fixture.spans))
        fixture.spans.remove(fixture.key("audio.aac"))
        assertTrue(CachedTimelineCoverage.compute(tracks, fixture.spans).isEmpty())
    }

    @Test fun liveWindowUsesTheInitialTrackerTimeAndClipsBothEdges() {
        val playlist = parse("#EXTINF:4,\na.ts\n#EXTINF:4,\nb.ts").copyWith(100_000_000, 3)
        val fixture = Resources().apply { put("a.ts", 31); put("b.ts", 61) }
        val track = HlsCachedTimelineIndex.track("v", playlist, 90_000_000, -12_000_000, 5_000_000, fixture::resolve)
        assertEquals(listOf(time(0, 5_000_000)), coverage(track, fixture))
        val unknown = HlsCachedTimelineIndex.track("v", playlist, C.TIME_UNSET, 0, null, fixture::resolve)
        assertTrue(coverage(unknown, fixture).isEmpty())
        val overflow = HlsCachedTimelineIndex.track("v", playlist, 0, Long.MAX_VALUE, null, fixture::resolve)
        assertTrue(coverage(overflow, fixture).isEmpty())
    }

    @Test fun gapAndTrailingLowLatencyPartsNeverInventFullSegmentCoverage() {
        val playlist = parse("#EXT-X-GAP\n#EXTINF:4,\na.ts\n#EXTINF:4,\nb.ts\n#EXT-X-PART:DURATION=1,URI=\"part.ts\",INDEPENDENT=YES")
        val fixture = Resources().apply { put("a.ts", 31); put("b.ts", 61); put("part.ts", 9) }
        assertEquals(listOf(time(4_000_000, 8_000_000)), coverage(index(playlist, fixture), fixture))
        assertEquals(1, playlist.trailingParts.size)
    }

    @Test fun mapperUsesTheOriginalUnknownRequestRatherThanRebuildingItAtEof() {
        val playlist = parse("#EXTINF:4,\nopaque?token=synthetic")
        val bindings = CachedResourceBindings()
        val uri = UriUtil.resolve(BASE, "opaque?token=synthetic")
        val whole = bindings.begin(uri, 0, -1, "original-whole")
        bindings.opened(whole, -1); bindings.ended(whole, 73)
        bindings.opened(bindings.begin(uri, 0, 73, "different-bounded"), 73)
        val snapshot = bindings.snapshot()
        val track = HlsCachedTimelineIndex.track("v", playlist, 0, 0, null, snapshot::rangeFor)
        assertEquals("original-whole", track.intervals.single().requiredBytes.single().key)
        assertTrue(CachedTimelineCoverage.compute(listOf(track), mapOf("different-bounded" to listOf(CachedResourceRange("different-bounded", 0, 73)))).isEmpty())
    }

    @Test fun trackerForwardsEveryPublicMethodIncludingItsDefaultDeactivateMethod() {
        val fake = Tracker()
        val catalog = HlsCacheCatalog()
        val tracker = ObservedHlsPlaylistTracker(fake.proxy, catalog)
        val uri = Uri.parse(BASE)
        fake.playlists[uri] = parse("#EXTINF:4,\na.ts")
        tracker.start(uri, EventDispatcher(), { }, DefaultBandwidthMeter.getSingletonInstance(context))
        assertSame(fake.playlists[uri], tracker.getPlaylistSnapshot(uri, true))
        assertTrue(tracker.isSnapshotValid(uri))
        assertTrue(tracker.isLive)
        assertEquals(123L, tracker.initialStartTimeUs)
        assertNull(tracker.multivariantPlaylist)
        assertNull(tracker.contentSteeringTracker)
        assertNull(tracker.getRedundantGroups(HlsRedundantGroup.VARIANT))
        assertNull(tracker.getRedundantGroup(uri))
        tracker.maybeThrowPrimaryPlaylistRefreshError(); tracker.maybeThrowPlaylistRefreshError(uri)
        assertTrue(tracker.excludeMediaPlaylist(uri, 55))
        tracker.refreshPlaylist(uri); tracker.deactivatePlaylistForPlayback(uri)
        assertTrue(tracker.isExcluded(uri, 55))
        val group = HlsRedundantGroup.Factory(HlsMultivariantPlaylist.createSingleVariantMultivariantPlaylist(BASE))
            .createVariantRedundantGroupList().single()
        assertTrue(tracker.isExcluded(group, 55))
        val listener = object : HlsPlaylistTracker.PlaylistEventListener {
            override fun onPlaylistChanged() = Unit
            override fun onPlaylistError(url: Uri, info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo, forceRetry: Boolean) = false
        }
        tracker.addListener(listener); tracker.removeListener(listener)
        tracker.stop()
        val expected = HlsPlaylistTracker::class.java.methods.map(::signature).toSet()
        assertEquals("A fixed-version public tracker method must never silently use a wrapper default", expected, fake.calls.toSet())
        assertTrue(catalog.snapshot().playlists.isEmpty())
    }

    @Test fun redirectedPrimaryUrlsAreNotQueriedAsUnknownTrackerKeysAndLateCallbacksAreRejected() {
        val fake = Tracker()
        val catalog = HlsCacheCatalog()
        val tracker = ObservedHlsPlaylistTracker(fake.proxy, catalog)
        val uri = Uri.parse(BASE)
        val redirected = parse("#EXTINF:4,\na.ts", base = "https://cdn.invalid/redirected.m3u8")
        fake.playlists[uri] = redirected
        tracker.start(uri, EventDispatcher(), { }, DefaultBandwidthMeter.getSingletonInstance(context))
        val first = fake.primary.last()
        first.onPrimaryPlaylistRefreshed(redirected)
        assertTrue("A final baseUri is not necessarily a registered tracker URL", catalog.snapshot().playlists.isEmpty())
        assertSame(redirected, tracker.getPlaylistSnapshot(uri, true))
        fake.listeners.toList().forEach { it.onPlaylistChanged() }
        assertEquals(setOf(uri), catalog.snapshot().playlists.keys)
        tracker.stop()
        tracker.start(uri, EventDispatcher(), { }, DefaultBandwidthMeter.getSingletonInstance(context))
        val revision = catalog.revision.value
        first.onPrimaryPlaylistRefreshed(redirected)
        assertEquals(revision, catalog.revision.value)
        assertTrue(catalog.snapshot().playlists.isEmpty())
        tracker.getPlaylistSnapshot(uri, false)
        catalog.close()
        tracker.getPlaylistSnapshot(uri, true)
        fake.primary.last().onPrimaryPlaylistRefreshed(redirected)
        assertTrue(catalog.snapshot().playlists.isEmpty())
        tracker.stop()
    }

    @Test fun actualMainHlsTrackerAndBindingsMapRealTsCacheHolesAndRemoval() = Disk().use { disk ->
        val item = disk.fixture()
        val session = PlaybackSourceSession(context, 1, item, emptyMap(), disk.cache)
        try {
            prepare(session.createMediaSource()) {
                val catalog = requireNotNull(session.hlsCacheCatalog)
                await { catalog.snapshot().playlists.isNotEmpty() && disk.cache.cacheSpace >= 39_480 }
                val snapshot = catalog.snapshot()
                val playlist = snapshot.playlists.values.single()
                assertEquals(3, playlist.segments.size)
                assertTrue(playlist.hasIndependentSegments)
                val resources = session.cacheResourceSnapshot()
                val track = HlsCachedTimelineIndex.track("selected-v", playlist, snapshot.initialStartTimeUs, 0, 12_000_000, resources::rangeFor)
                assertEquals(listOf(time(0, 12_000_000)), CachedTimelineCoverage.compute(listOf(track), disk.spans()))
                val middleUri = UriUtil.resolve(playlist.baseUri, playlist.segments[1].url)
                val middle = requireNotNull(resources.rangeFor(middleUri))
                assertEquals(12_972L, middle.length)
                disk.cache.removeResource(middle.key)
                assertEquals(listOf(time(0, 4_000_000), time(8_000_000, 12_000_000)), CachedTimelineCoverage.compute(listOf(track), disk.spans()))
                track.intervals.flatMap { it.requiredBytes }.forEach { disk.cache.removeResource(it.key) }
                assertTrue(CachedTimelineCoverage.compute(listOf(track), disk.spans()).isEmpty())
            }
        } finally { session.close() }
        assertTrue(requireNotNull(session.hlsCacheCatalog).snapshot().playlists.isEmpty())
    }

    @Test fun absentFinalSnapshotWithdrawsOldCatalogDataAndPreservesTheNullReturn() {
        val fake = Tracker()
        val catalog = HlsCacheCatalog()
        val tracker = ObservedHlsPlaylistTracker(fake.proxy, catalog)
        val uri = Uri.parse(BASE)
        fake.playlists[uri] = parse("#EXTINF:4,\na.ts")
        tracker.start(uri, EventDispatcher(), { }, DefaultBandwidthMeter.getSingletonInstance(context))
        assertNotNull(tracker.getPlaylistSnapshot(uri, true))
        assertEquals(1, catalog.snapshot().playlists.size)
        fake.allowMissing = true
        fake.playlists.clear()
        assertNull(tracker.getPlaylistSnapshot(uri, true))
        assertTrue(catalog.snapshot().playlists.isEmpty())
        tracker.stop()
    }

    @Test fun actualFmp4InitRemovalWithholdsEveryOtherwiseCachedSegment() = withRealHls("fmp4") { disk, playlist, track, resources ->
        val init = requireNotNull(playlist.segments.first().initializationSegment)
        val range = requireNotNull(resources.rangeFor(UriUtil.resolve(playlist.baseUri, init.url), init.byteRangeOffset, init.byteRangeLength))
        assertEquals(828L, range.length)
        assertTrue(track.intervals.all { range in it.requiredBytes })
        disk.cache.removeResource(range.key)
        assertTrue(disk.cache.cacheSpace > 0)
        assertTrue(CachedTimelineCoverage.compute(listOf(track), disk.spans()).isEmpty())
    }

    @Test fun actualAes128CachedBytesRequireTheRawKeyAndCiphertextExtent() = withRealHls("aes") { disk, playlist, track, resources ->
        val segment = playlist.segments.first()
        val key = requireNotNull(resources.rangeFor(UriUtil.resolve(playlist.baseUri, requireNotNull(segment.fullSegmentEncryptionKeyUri))))
        val encrypted = requireNotNull(resources.rangeFor(UriUtil.resolve(playlist.baseUri, segment.url)))
        assertEquals(16L, key.length)
        assertEquals(13_552L, encrypted.length)
        assertTrue(track.intervals.all { key in it.requiredBytes })
        disk.cache.removeResource(key.key)
        assertTrue(disk.cache.cacheSpace > 0)
        assertTrue(CachedTimelineCoverage.compute(listOf(track), disk.spans()).isEmpty())
    }

    @Test fun actualByterangeRequestsMapTheMiddleSpanWithoutConfusingResourceOffsets() = withRealHls("range") { disk, playlist, track, resources ->
        val segment = playlist.segments[1]
        val middle = requireNotNull(resources.rangeFor(UriUtil.resolve(playlist.baseUri, segment.url), segment.byteRangeOffset, segment.byteRangeLength))
        assertEquals(13_536L, middle.position)
        assertEquals(12_972L, middle.length)
        assertEquals(1, disk.cache.keys.size)
        val span = disk.cache.getCachedSpans(middle.key).single { it.position == middle.position }
        assertEquals(middle.length, span.length)
        disk.cache.removeSpan(span)
        assertEquals(listOf(time(0, 4_000_000), time(8_000_000, 12_000_000)), CachedTimelineCoverage.compute(listOf(track), disk.spans()))
    }

    @Test fun actualPreviewPrivateAndOfflineHlsFactoriesDoNotPopulateTheMainCatalog() = Disk().use { disk ->
        val item = disk.fixture()
        val privateSession = PlaybackSourceSession(context, 1, item, emptyMap())
        val offlineSession = PlaybackSourceSession(context, 2, item, emptyMap(), disk.cache, DefaultDataSource.Factory(context))
        val previewSession = PlaybackSourceSession(context, 3, item, emptyMap(), disk.cache)
        try {
            prepare(privateSession.createMediaSource()) { }
            prepare(offlineSession.createMediaSource()) { }
            assertNull(privateSession.hlsCacheCatalog); assertNull(offlineSession.hlsCacheCatalog)
            assertTrue(disk.cache.keys.isEmpty())
            val lease = previewSession.previewLease()
            try { prepare(lease.mediaSourceFactory.createMediaSource(item)) { await { disk.cache.cacheSpace > 0 } } }
            finally { lease.close() }
            assertEquals(0L, requireNotNull(previewSession.hlsCacheCatalog).revision.value)
            assertTrue(requireNotNull(previewSession.hlsCacheCatalog).snapshot().playlists.isEmpty())
        } finally { privateSession.close(); offlineSession.close(); previewSession.close() }
    }

    private fun parse(body: String, independent: Boolean = true, base: String = BASE): HlsMediaPlaylist =
        HlsPlaylistParser().parse(Uri.parse(base), ("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n" +
            (if(independent) "#EXT-X-INDEPENDENT-SEGMENTS\n" else "") + body + "\n#EXT-X-ENDLIST\n").byteInputStream()) as HlsMediaPlaylist

    private fun index(playlist: HlsMediaPlaylist, resources: Resources) = HlsCachedTimelineIndex.track("video", playlist, 0, 0, null, resources::resolve)
    private fun coverage(track: CachedTrackCoverage, resources: Resources) = CachedTimelineCoverage.compute(listOf(track), resources.spans)
    private fun time(start: Long, end: Long) = CachedTimeRange(start, end)

    private fun withRealHls(kind: String, verify: (Disk, HlsMediaPlaylist, CachedTrackCoverage, CachedResourceSnapshot) -> Unit) {
        Disk().use { disk ->
            val session = PlaybackSourceSession(context, 1, disk.fixture(kind), emptyMap(), disk.cache)
            try {
                prepare(session.createMediaSource()) {
                    val catalog = requireNotNull(session.hlsCacheCatalog)
                    await {
                        val snapshot = catalog.snapshot()
                        val playlist = snapshot.playlists.values.singleOrNull()
                        if(playlist == null) false else {
                            val resources = session.cacheResourceSnapshot()
                            val track = HlsCachedTimelineIndex.track("selected-v", playlist, snapshot.initialStartTimeUs, 0, 12_000_000, resources::rangeFor)
                            CachedTimelineCoverage.compute(listOf(track), disk.spans()) == listOf(time(0, 12_000_000))
                        }
                    }
                    val snapshot = catalog.snapshot()
                    val playlist = snapshot.playlists.values.single()
                    val resources = session.cacheResourceSnapshot()
                    val track = HlsCachedTimelineIndex.track("selected-v", playlist, snapshot.initialStartTimeUs, 0, 12_000_000, resources::rangeFor)
                    assertEquals(3, track.intervals.size)
                    verify(disk, playlist, track, resources)
                }
            } finally { session.close() }
        }
    }

    private class Resources {
        val bindings = CachedResourceBindings()
        val spans = linkedMapOf<String, ArrayList<CachedResourceRange>>()
        fun key(path: String) = "key:$path"
        fun put(path: String, length: Long, position: Long = 0, requestedLength: Long = -1, cached: Boolean = true) {
            bindings.opened(bindings.begin(UriUtil.resolve(BASE, path), position, requestedLength, key(path)), length)
            if(cached) spans.getOrPut(key(path)) { arrayListOf() }.add(CachedResourceRange(key(path), position, length))
        }
        fun resolve(uri: String, position: Long, requestedLength: Long) = bindings.snapshot().rangeFor(uri, position, requestedLength)
    }

    private class Tracker {
        val calls = arrayListOf<String>()
        val playlists = linkedMapOf<Uri, HlsMediaPlaylist>()
        val primary = arrayListOf<HlsPlaylistTracker.PrimaryPlaylistListener>()
        val listeners = arrayListOf<HlsPlaylistTracker.PlaylistEventListener>()
        var allowMissing = false
        val proxy = Proxy.newProxyInstance(HlsPlaylistTracker::class.java.classLoader, arrayOf(HlsPlaylistTracker::class.java)) { _, method, args ->
            calls += signature(method)
            when(method.name) {
                "start" -> { primary += args[2] as HlsPlaylistTracker.PrimaryPlaylistListener; null }
                "addListener" -> { listeners += args[0] as HlsPlaylistTracker.PlaylistEventListener; null }
                "removeListener" -> { listeners.remove(args[0] as HlsPlaylistTracker.PlaylistEventListener); null }
                "getPlaylistSnapshot" -> playlists[args[0] as Uri] ?: if(allowMissing) null else throw IllegalArgumentException("Unregistered tracker URI")
                "getInitialStartTimeUs" -> 123L
                "isLive", "isExcluded", "isSnapshotValid", "excludeMediaPlaylist" -> true
                else -> null
            }
        } as HlsPlaylistTracker
    }

    private fun prepare(source: MediaSource, verify: () -> Unit) {
        val ready = AtomicBoolean()
        val error = AtomicReference<PlaybackException?>()
        var player: ExoPlayer? = null
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { if(state == Player.STATE_READY) ready.set(true) }
                    override fun onPlayerError(failure: PlaybackException) { error.set(failure) }
                })
                setMediaSource(source); prepare()
            }
        }
        try { await { error.get()?.let { throw AssertionError("Actual HLS source failed", it) }; ready.get() }; verify() }
        finally { instrumentation.runOnMainSync { player?.release() } }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while(!predicate()) { check(SystemClock.elapsedRealtime() < deadline) { "HLS condition timed out" }; SystemClock.sleep(20) }
    }

    private inner class Disk : Closeable {
        val root = File(context.cacheDir, "hls-cache-${UUID.randomUUID()}").apply { mkdirs() }
        @Suppress("DEPRECATION") val cache = SimpleCache(File(root, "stream"), NoOpCacheEvictor())
        fun fixture(kind: String = "ts"): MediaItem {
            val names = when(kind) {
                "ts", "range" -> listOf("ts-00.ts", "ts-01.ts", "ts-02.ts")
                "aes" -> listOf("aes.key", "aes-00.ts", "aes-01.ts", "aes-02.ts")
                "fmp4" -> listOf("fmp4-init.mp4", "fmp4-00.m4s", "fmp4-01.m4s", "fmp4-02.m4s")
                else -> error("Unknown controlled fixture")
            }
            for(name in names)
                File(root, name).outputStream().use { output -> instrumentation.context.assets.open(name).use { it.copyTo(output) } }
            val original = if(kind == "range") {
                File(root, "combined.ts").outputStream().use { output -> names.forEach { File(root, it).inputStream().use { it.copyTo(output) } } }
                "#EXTM3U\n#EXT-X-VERSION:4\n#EXT-X-TARGETDURATION:4\n#EXT-X-PLAYLIST-TYPE:VOD\n" +
                    "#EXTINF:4,\n#EXT-X-BYTERANGE:13536@0\ncombined.ts\n#EXTINF:4,\n#EXT-X-BYTERANGE:12972@13536\ncombined.ts\n" +
                    "#EXTINF:4,\n#EXT-X-BYTERANGE:12972@26508\ncombined.ts\n#EXT-X-ENDLIST\n"
            } else instrumentation.context.assets.open("$kind.m3u8").bufferedReader().use { it.readText() }
            val manifest = File(root, "video.m3u8").apply { writeText(original.replace("#EXTM3U", "#EXTM3U\n#EXT-X-INDEPENDENT-SEGMENTS")) }
            return MediaItem.fromUri(Uri.fromFile(manifest))
        }
        fun spans() = cache.keys.associateWith { key -> cache.getCachedSpans(key).map { CachedResourceRange(key, it.position, it.length) } }
        override fun close() { cache.release(); check(root.deleteRecursively()) }
    }

    companion object {
        private const val BASE = "https://media.invalid/video.m3u8"
        private fun signature(method: Method) = method.name + method.parameterTypes.joinToString { it.name }
    }
}
