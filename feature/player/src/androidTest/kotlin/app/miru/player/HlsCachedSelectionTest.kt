// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.net.Uri
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.hls.playlist.HlsRedundantGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HlsCachedSelectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val parameters get() = TrackSelectionParameters.Builder(context).build()
    private val low = video("low")
    private val high = video("high")

    @Test fun actualDownstreamVariantWinsOverAllOtherAllowedAdaptiveAlternatives() {
        val tracks = Tracks(listOf(group("main", listOf(low, high), true, true)))
        val catalog = catalog(listOf(entry("low", low), entry("high", high)))
        val selected = requireNotNull(HlsCachedSelection.select(catalog, tracks, parameters, mapOf(VARIANT to low)))
        assertEquals(listOf(uri("low")), selected.map { it.uri })
        assertNull(HlsCachedSelection.select(catalog, tracks, parameters, emptyMap()))
    }

    @Test fun explicitOverrideWithdrawsAnOldDownstreamBeforeTheReplacementIsRead() {
        val group = group("main", listOf(low, high), true, true)
        val tracks = Tracks(listOf(group))
        val catalog = catalog(listOf(entry("low", low), entry("high", high)))
        val forced = parameters.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 1)).build()
        assertNull(HlsCachedSelection.select(catalog, tracks, forced, mapOf(VARIANT to low)))
        assertEquals(listOf(uri("high")), requireNotNull(HlsCachedSelection.select(catalog, tracks, forced, mapOf(VARIANT to high))).map { it.uri })
    }

    @Test fun independentAudioCannotBeOmittedOrReplacedByThePreviousLanguage() {
        val en = audio("en")
        val ja = audio("ja")
        val tracks = Tracks(listOf(group("main", listOf(low), true), group("audio", listOf(en, ja), false, true)))
        val catalog = catalog(listOf(entry("low", low), entry("en", en, AUDIO), entry("ja", ja, AUDIO)))
        assertNull(HlsCachedSelection.select(catalog, tracks, parameters, mapOf(VARIANT to low)))
        assertNull(HlsCachedSelection.select(catalog, tracks, parameters, mapOf(VARIANT to low, AUDIO to en)))
        val chosen = requireNotNull(HlsCachedSelection.select(catalog, tracks, parameters, mapOf(VARIANT to low, AUDIO to ja)))
        assertEquals(listOf(uri("low"), uri("ja")), chosen.map { it.uri })
    }

    @Test fun muxedAudioUsesItsActualPrimaryVariantRatherThanItsStaticFirstVariantId() {
        val muxed = audio("low").buildUpon().setPrimaryTrackGroupId("main").build()
        val tracks = Tracks(listOf(group("main", listOf(low, high), true, true), group("main:audio", listOf(muxed), true)))
        val catalog = catalog(listOf(entry("low", low), entry("high", high)))
        val chosen = requireNotNull(HlsCachedSelection.select(catalog, tracks, parameters, mapOf(VARIANT to high)))
        assertEquals(listOf(uri("high"), uri("high")), chosen.map { it.uri })
    }

    @Test fun redundantTransportPathsNeverUnionAndMissingCurrentPathFailsClosed() {
        val shared = Any()
        val old = entry("old-path", low, owner = shared, current = uri("new-path"))
        val next = entry("new-path", low, owner = shared, current = uri("new-path"))
        val tracks = Tracks(listOf(group("main", listOf(low), true)))
        assertNull(HlsCachedSelection.select(catalog(listOf(old)), tracks, parameters, mapOf(VARIANT to low)))
        val selected = requireNotNull(HlsCachedSelection.select(catalog(listOf(old, next)), tracks, parameters, mapOf(VARIANT to low)))
        assertEquals(listOf(uri("new-path")), selected.map { it.uri })
    }

    @Test fun disabledOrUnresolvedSelectedGroupsCannotTurnAvCoverageIntoVideoOnlyCoverage() {
        val en = audio("en")
        val tracks = Tracks(listOf(group("main", listOf(low), true), group("audio", listOf(en), true)))
        val catalog = catalog(listOf(entry("low", low)))
        assertNull(HlsCachedSelection.select(catalog, tracks, parameters, mapOf(VARIANT to low)))
        val disabling = parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        assertNull(HlsCachedSelection.select(catalog, tracks, disabling, mapOf(VARIANT to low)))
        val unknownParent = en.buildUpon().setPrimaryTrackGroupId("missing").build()
        assertNull(HlsCachedSelection.select(catalog, Tracks(listOf(group("audio", listOf(unknownParent), true))), parameters, mapOf(VARIANT to low)))
    }

    @Test fun nullIdsAndIdenticalFormatsUseTheObservedWrapperGroupWithoutMergingAlternatives() {
        val same = video("ignored").buildUpon().setId(null).build()
        val one = Any(); val two = Any()
        val entries = listOf(entry("one", same, owner = one, index = 0), entry("two", same, owner = two, index = 1))
        val snapshot = HlsCacheCatalog.Snapshot(1, 0, entries.associate { it.uri to it.playlist }, entries.associate { it.uri to it.identity }, mapOf("main" to two))
        val group = group("main", listOf(same, same), true, true)
        assertEquals(listOf(uri("two")), requireNotNull(HlsCachedSelection.select(snapshot, Tracks(listOf(group)), parameters, mapOf(VARIANT to same))).map { it.uri })
        val forced = parameters.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
        assertEquals(listOf(uri("one")), requireNotNull(HlsCachedSelection.select(snapshot, Tracks(listOf(group)), forced, mapOf(VARIANT to same))).map { it.uri })
    }

    @Test fun actualSourcePublishesOnlyCommittedSpansAndWithdrawsOnRemovalAndOwnerClose() = running { fixture ->
        await { fixture.ranges.value == FULL }
        val snapshot = requireNotNull(fixture.session.hlsCacheCatalog).snapshot()
        val playlist = snapshot.playlists.values.single()
        assertEquals(VARIANT, snapshot.identities.values.single().kind)
        val middle = requireNotNull(fixture.session.cacheResourceSnapshot().rangeFor(UriUtil.resolve(playlist.baseUri, playlist.segments[1].url)))
        fixture.disk.cache.removeResource(middle.key)
        await { fixture.ranges.value == HOLES }
        instrumentation.runOnMainSync { fixture.publisher.close() }
        assertTrue(fixture.ranges.value.isEmpty())
        fixture.disk.cache.removeResource(fixture.disk.cache.keys.first())
        instrumentation.runOnMainSync { fixture.player.seekTo(9_000) }
        SystemClock.sleep(150)
        assertTrue(fixture.ranges.value.isEmpty())
    }

    @Test fun parentCancellationClearsPublicationWithoutReleasingTheOwnersPlayer() = running { fixture ->
        await { fixture.ranges.value == FULL }
        val locked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val holder = Thread {
            synchronized(fixture.disk.cache) { locked.countDown(); check(resume.await(20, TimeUnit.SECONDS)) }
        }.apply { start() }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            fixture.scope.cancel()
            await { fixture.ranges.value.isEmpty() }
            assertEquals("UI withdrawal must not wait for cache-monitor cleanup", 1L, resume.count)
            instrumentation.runOnMainSync { assertNotEquals(Player.STATE_IDLE, fixture.player.playbackState) }
        } finally { resume.countDown(); holder.join(5_000) }
        instrumentation.runOnMainSync { fixture.player.seekTo(9_000) }
        SystemClock.sleep(150)
        assertTrue(fixture.ranges.value.isEmpty())
    }

    @Test fun actualForcedVariantDoesNotUnionTheFullyCachedPreviousRepresentation() = running("adaptive") { fixture ->
        var target: TrackGroup? = null
        instrumentation.runOnMainSync {
            target = fixture.player.currentTracks.groups.single { it.type == C.TRACK_TYPE_VIDEO }.mediaTrackGroup
            fixture.player.trackSelectionParameters = fixture.player.trackSelectionParameters.buildUpon()
                .setMaxVideoBitrate(Int.MAX_VALUE).setOverrideForType(TrackSelectionOverride(requireNotNull(target), 1)).build()
            fixture.player.play()
        }
        val highUrl = Uri.fromFile(File(fixture.disk.root, "high/ts-01.ts")).toString()
        await { fixture.session.cacheResourceSnapshot().rangeFor(highUrl) != null && fixture.ranges.value == FULL }
        instrumentation.runOnMainSync { fixture.player.pause() }
        val high = requireNotNull(fixture.session.cacheResourceSnapshot().rangeFor(highUrl))
        val lowUrl = Uri.fromFile(File(fixture.disk.root, "low/aes-01.ts")).toString()
        val low = requireNotNull(fixture.session.cacheResourceSnapshot().rangeFor(lowUrl))
        assertTrue(fixture.disk.cache.isCached(low.key, low.position, low.length))
        fixture.disk.cache.removeResource(high.key)
        await { fixture.ranges.value == HOLES }
        assertTrue("A cached adaptive alternative must not fill the selected variant's hole", fixture.disk.cache.isCached(low.key, low.position, low.length))
    }

    @Test fun actualAudioSwitchRequiresTheSelectedLanguageRatherThanThePreparedOldAudio() = running("tracks") { fixture ->
        instrumentation.runOnMainSync {
            val group = fixture.player.currentTracks.groups.first { candidate ->
                candidate.type == C.TRACK_TYPE_AUDIO && (0 until candidate.length).any { candidate.getTrackFormat(it).language == "ja" }
            }
            val index = (0 until group.length).first { group.getTrackFormat(it).language == "ja" }
            fixture.player.trackSelectionParameters = fixture.player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
            fixture.player.play()
        }
        val jaUrl = Uri.fromFile(File(fixture.disk.root, "japanese-01.ts")).toString()
        await { fixture.session.cacheResourceSnapshot().rangeFor(jaUrl) != null && fixture.ranges.value == FULL }
        instrumentation.runOnMainSync { fixture.player.pause() }
        val ja = requireNotNull(fixture.session.cacheResourceSnapshot().rangeFor(jaUrl))
        val en = requireNotNull(fixture.session.cacheResourceSnapshot().rangeFor(Uri.fromFile(File(fixture.disk.root, "english-01.ts")).toString()))
        assertTrue(fixture.disk.cache.isCached(en.key, en.position, en.length))
        fixture.disk.cache.removeResource(ja.key)
        val expected = listOf(CachedTimeRange(0, 4_017_056), CachedTimeRange(8_010_889, 12_000_000))
        await { fixture.ranges.value == expected }
        assertTrue("The previous language remains on disk but cannot satisfy Japanese audio", fixture.disk.cache.isCached(en.key, en.position, en.length))
    }

    private fun running(kind: String = "ts", verify: (Running) -> Unit) {
        Disk().use { disk ->
            val session = PlaybackSourceSession(context, 1, disk.fixture(kind), emptyMap(), disk.cache)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val ranges = MutableStateFlow<List<CachedTimeRange>>(emptyList())
            val error = AtomicReference<PlaybackException?>()
            val formats = CopyOnWriteArrayList<String>()
            val scenario = ActivityScenario.launch<CacheSelectionSurfaceActivity>(Intent(context, CacheSelectionSurfaceActivity::class.java))
            var video: android.view.SurfaceView? = null
            await { scenario.onActivity { if(it.video.holder.surface.isValid && it.video.width > 0) video = it.video }; video != null }
            var player: ExoPlayer? = null
            var publisher: PlayerCachedTimeline? = null
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build().apply {
                    volume = 0f
                    setVideoSurface(requireNotNull(video).holder.surface)
                    trackSelectionParameters = trackSelectionParameters.buildUpon().setMaxVideoBitrate(150_000).build()
                    addListener(object : Player.Listener { override fun onPlayerError(failure: PlaybackException) { error.set(failure) } })
                    addAnalyticsListener(object : AnalyticsListener {
                        override fun onDownstreamFormatChanged(time: AnalyticsListener.EventTime, data: MediaLoadData) {
                            val currentUid = if(currentTimeline.isEmpty) null else currentTimeline.getPeriod(currentPeriodIndex, androidx.media3.common.Timeline.Period(), true).uid
                            formats += "id=${data.trackFormat?.id},type=${data.trackType},currentPeriod=${time.mediaPeriodId?.periodUid == currentUid}"
                        }
                    })
                    publisher = PlayerCachedTimeline(this, session, disk.cache, scope) { ranges.value = it }
                    setMediaSource(session.createMediaSource()); prepare(); play()
                }
            }
            val fixture = Running(disk, session, scope, ranges, requireNotNull(player), requireNotNull(publisher))
            try {
                await {
                    error.get()?.let { throw AssertionError("Actual selected HLS source failed", it) }
                    ranges.value == FULL
                }
                instrumentation.runOnMainSync { fixture.player.pause() }
                verify(fixture)
            } catch(failure: Throwable) {
                var native = ""
                instrumentation.runOnMainSync {
                    native = "state=${fixture.player.playbackState},groups=" + fixture.player.currentTracks.groups.joinToString { group ->
                        "${group.mediaTrackGroup.id}:" + (0 until group.length).joinToString { "${group.getTrackFormat(it).id}/${group.isTrackSelected(it)}" }
                    }
                }
                val catalog = requireNotNull(session.hlsCacheCatalog).snapshot()
                Log.e("MiruHlsSelectionTest", "$native,raw=$formats,catalog=${catalog.identities.values.map { "${it.kind}:${it.format.id}" }},ranges=${ranges.value},resources=${session.cacheResourceSnapshot().resourceCount}")
                throw failure
            } finally {
                instrumentation.runOnMainSync { fixture.publisher.close(); fixture.player.release() }
                scope.cancel()
                runBlocking { scope.coroutineContext[Job]?.join() }
                session.close()
                scenario.close()
            }
        }
    }

    private class Running(val disk: Disk, val session: PlaybackSourceSession, val scope: CoroutineScope,
        val ranges: MutableStateFlow<List<CachedTimeRange>>, val player: ExoPlayer, val publisher: PlayerCachedTimeline)

    private fun group(id: String, formats: List<Format>, vararg selected: Boolean) = Tracks.Group(
        TrackGroup(id, *formats.toTypedArray()), formats.size > 1, IntArray(formats.size) { C.FORMAT_HANDLED }, selected)
    private fun video(id: String) = Format.Builder().setId(id).setSampleMimeType("video/avc").build()
    private fun audio(id: String) = Format.Builder().setId(id).setLanguage(id).setSampleMimeType("audio/mp4a-latm").build()
    private fun uri(name: String) = Uri.parse("https://selected.invalid/$name.m3u8")
    private class Entry(val uri: Uri, val playlist: HlsMediaPlaylist, val identity: HlsCacheCatalog.Identity)
    private fun entry(name: String, format: Format, kind: Int = VARIANT, owner: Any = Any(), current: Uri = uri(name),
        index: Int = if(format === high || format.language == "ja") 1 else 0): Entry {
        val uri = uri(name)
        val playlist = HlsPlaylistParser().parse(uri, "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXTINF:4,\nsegment.ts\n#EXT-X-ENDLIST\n".byteInputStream()) as HlsMediaPlaylist
        return Entry(uri, playlist, HlsCacheCatalog.Identity(owner, kind, format, current, if(kind == AUDIO) "audio" else "main", index))
    }
    private fun catalog(entries: List<Entry>) = HlsCacheCatalog.Snapshot(1, 0, entries.associate { it.uri to it.playlist }, entries.associate { it.uri to it.identity })
    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while(!predicate()) { check(SystemClock.elapsedRealtime() < deadline) { "Selected HLS coverage timed out" }; SystemClock.sleep(20) }
    }

    private inner class Disk : Closeable {
        val root = File(context.cacheDir, "hls-selected-${UUID.randomUUID()}").apply { mkdirs() }
        @Suppress("DEPRECATION") val cache = SimpleCache(File(root, "stream"), NoOpCacheEvictor())
        fun fixture(kind: String): MediaItem {
            if(kind == "tracks") {
                for(name in requireNotNull(instrumentation.context.assets.list("tracks"))) {
                    val bytes = instrumentation.context.assets.open("tracks/$name").use { it.readBytes() }
                    File(root, name).writeBytes(if(name.endsWith(".m3u8")) bytes.toString(Charsets.UTF_8)
                        .replace("#EXTM3U", "#EXTM3U\n#EXT-X-INDEPENDENT-SEGMENTS").toByteArray() else bytes)
                }
                return MediaItem.fromUri(Uri.fromFile(File(root, "master.m3u8")))
            }
            if(kind == "adaptive") {
                for((folder, prefix, extra) in listOf(Triple("low", "aes", listOf("aes.key")), Triple("high", "ts", emptyList()))) {
                    val directory = File(root, folder).apply { mkdirs() }
                    for(name in extra + (0..2).map { "$prefix-0$it.ts" })
                        File(directory, name).writeBytes(instrumentation.context.assets.open(name).use { it.readBytes() })
                    val original = instrumentation.context.assets.open("$prefix.m3u8").bufferedReader().use { it.readText() }
                    File(directory, "video.m3u8").writeText(original.replace("#EXTM3U", "#EXTM3U\n#EXT-X-INDEPENDENT-SEGMENTS"))
                }
                val master = File(root, "master.m3u8").apply { writeText("#EXTM3U\n#EXT-X-INDEPENDENT-SEGMENTS\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=160x90\nlow/video.m3u8\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=200000,RESOLUTION=160x90\nhigh/video.m3u8\n") }
                return MediaItem.fromUri(Uri.fromFile(master))
            }
            for(name in listOf("ts-00.ts", "ts-01.ts", "ts-02.ts"))
                File(root, name).outputStream().use { output -> instrumentation.context.assets.open(name).use { it.copyTo(output) } }
            val original = instrumentation.context.assets.open("ts.m3u8").bufferedReader().use { it.readText() }
            val manifest = File(root, "video.m3u8").apply { writeText(original.replace("#EXTM3U", "#EXTM3U\n#EXT-X-INDEPENDENT-SEGMENTS")) }
            return MediaItem.fromUri(Uri.fromFile(manifest))
        }
        override fun close() { cache.release(); check(root.deleteRecursively()) }
    }
    companion object {
        private const val VARIANT = HlsRedundantGroup.VARIANT
        private const val AUDIO = HlsRedundantGroup.AUDIO_RENDITION
        private val FULL = listOf(CachedTimeRange(0, 12_000_000))
        private val HOLES = listOf(CachedTimeRange(0, 4_000_000), CachedTimeRange(8_000_000, 12_000_000))
    }
}
