// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("DEPRECATION")
package app.miru.player

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.dash.BaseUrlExclusionList
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DashSegmentIndex
import androidx.media3.exoplayer.dash.DashWrappingSegmentIndex
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.RangedUri
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.source.chunk.BundledChunkExtractor
import androidx.media3.exoplayer.source.chunk.ChunkExtractor
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.LoaderErrorThrower
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ChunkIndex
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashCachedTimelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun mergedChildrenUseTheirOwnExtentsAndTheActualParentKey() {
        val representation = parsed(listXml()).getPeriod(0).adaptationSets.single().representations.single()
        val parent = DashCacheRequest("https://dash.invalid/opaque?token=public", 0, -1, "actual-parent")
        val refs = mapOf(1L to DashCacheResource(parent, 0, 100), 2L to DashCacheResource(parent, 100, 200), 3L to DashCacheResource(parent, 300, 100))
        val indexed = view(representation, requireNotNull(representation.index), refs)
        val track = DashCachedTimelineIndex.track("v", indexed, true, 0, 12_000_000, CachedResourceBindings().snapshot())
        val spans = mapOf("actual-parent" to listOf(CachedResourceRange("actual-parent", 0, 100), CachedResourceRange("actual-parent", 300, 100)))
        assertEquals(HOLES, CachedTimelineCoverage.compute(listOf(track), spans))
        assertTrue(CachedTimelineCoverage.compute(listOf(track), mapOf("recomputed-child-key" to listOf(CachedResourceRange("recomputed-child-key", 0, 400)))).isEmpty())
        assertEquals(100L, track.intervals.first().requiredBytes.single().length)
    }

    @Test fun anUnknownMergedSuffixNeedsTheOriginalParentsTrustedLength() {
        val bindings = CachedResourceBindings()
        val parent = DashCacheRequest("https://dash.invalid/opaque?token=public", 0, -1, "whole-key")
        val suffix = DashCacheResource(parent, 300, -1)
        val original = bindings.begin(parent.uri, 0, -1, parent.key)
        bindings.opened(original, -1)
        assertNull(suffix.resolve(bindings.snapshot()))
        bindings.ended(original, 400)
        assertEquals(CachedResourceRange("whole-key", 300, 100), suffix.resolve(bindings.snapshot()))
        assertNull(bindings.snapshot().rangeFor(parent.uri, 0, 400))
        bindings.failed(original)
        assertNull(suffix.resolve(bindings.snapshot()))
    }

    @Test fun malformedChildBoundsAndOneByteHolesDoNotBecomeProportionalCoverage() {
        val parent = DashCacheRequest("https://dash.invalid/range", 10, 30, "k")
        val resources = CachedResourceBindings().snapshot()
        assertNull(DashCacheResource(parent, 9, 1).resolve(resources))
        assertNull(DashCacheResource(parent, 39, 2).resolve(resources))
        assertNull(DashCacheResource(parent, Long.MAX_VALUE, 2).resolve(resources))
        val range = requireNotNull(DashCacheResource(parent, 10, 30).resolve(resources))
        val track = CachedTrackCoverage("v", listOf(CachedMediaInterval(CachedTimeRange(0, 4_000_000), listOf(range))))
        assertTrue(CachedTimelineCoverage.compute(listOf(track), mapOf("k" to listOf(CachedResourceRange("k", 10, 29)))).isEmpty())
    }

    @Test fun sapDeclarationInheritanceAndRepresentationOverrideFollowTheOfficialObjects() {
        val catalog = DashCacheCatalog()
        val xml = listXml().replace("<Representation id=\"v\"", "<Representation startWithSAP=\"0\" id=\"v\"")
            .replace("</Representation>", "</Representation><Representation id=\"independent\" bandwidth=\"10\"><SegmentList duration=\"4\"><SegmentURL media=\"a\"/></SegmentList></Representation>")
        val manifest = DashCacheManifestParser(catalog).parse(Uri.parse(BASE), xml.byteInputStream())
        val reps = manifest.getPeriod(0).adaptationSets.single().representations
        assertFalse(catalog.snapshot().independent(reps[0]))
        assertTrue(catalog.snapshot().independent(reps[1]))
    }

    @Test fun absentOrInvalidSapIsUnknownAndDoesNotPreventNativeParsing() {
        for(value in listOf("", " startWithSAP=\"0\"", " startWithSAP=\"3\"")) {
            val catalog = DashCacheCatalog()
            val xml = listXml().replace(" startWithSAP=\"1\"", value)
            val manifest = DashCacheManifestParser(catalog).parse(Uri.parse(BASE), xml.byteInputStream())
            assertEquals(1, manifest.periodCount)
            assertFalse(catalog.snapshot().independent(manifest.getPeriod(0).adaptationSets.single().representations.single()))
        }
    }

    @Test fun proofCaptureLimitDoesNotReplaceTheOfficialParserOrRejectALargeManifest() {
        val catalog = DashCacheCatalog()
        val xml = listXml().replace("<Period", "<!--" + "x".repeat(8 * 1024 * 1024) + "--><Period")
        val manifest = DashCacheManifestParser(catalog).parse(Uri.parse(BASE), xml.byteInputStream())
        assertEquals(1, manifest.periodCount)
        assertFalse(catalog.snapshot().independent(manifest.getPeriod(0).adaptationSets.single().representations.single()))
    }

    @Test fun wrappingIndexPtoIsAppliedExactlyOnceBeforeWindowProjection() {
        val representation = parsed(listXml()).getPeriod(0).adaptationSets.single().representations.single()
        val index = DashWrappingSegmentIndex(ChunkIndex(intArrayOf(100, 200, 100), longArrayOf(0, 100, 300),
            longArrayOf(4_000_000, 4_000_000, 4_000_000), longArrayOf(0, 4_000_000, 8_000_000)), 2_000_000)
        val request = DashCacheRequest(BASE, 0, 400, "k")
        val refs = mapOf(0L to DashCacheResource(request, 0, 100), 1L to DashCacheResource(request, 100, 200), 2L to DashCacheResource(request, 300, 100))
        val track = DashCachedTimelineIndex.track("v", view(representation, index, refs), true, 12_000_000, 22_000_000, CachedResourceBindings().snapshot())
        val spans = mapOf("k" to listOf(CachedResourceRange("k", 0, 100), CachedResourceRange("k", 300, 100)))
        assertEquals(listOf(CachedTimeRange(12_000_000, 14_000_000), CachedTimeRange(18_000_000, 22_000_000)), CachedTimelineCoverage.compute(listOf(track), spans))
    }

    @Test fun overflowingSegmentBoundsCannotBypassTheEnumerationLimit() {
        val representation = parsed(listXml()).getPeriod(0).adaptationSets.single().representations.single()
        val index = object : DashCacheIndex {
            override val firstSegment = Long.MIN_VALUE
            override val segmentCount = -1L
            override fun segmentAt(timeUs: Long) = if(timeUs == 0L) Long.MIN_VALUE else Long.MAX_VALUE
            override fun startUs(segment: Long): Long = error("Must reject bounds before enumerating")
            override fun durationUs(segment: Long): Long = error("Must reject bounds before enumerating")
            override fun address(segment: Long): RangedUri = error("Must reject bounds before enumerating")
        }
        val selected = DashCacheRepresentation(0, representation, BASE, index, Any(), null, null, emptyMap())
        assertTrue(DashCachedTimelineIndex.track("v", selected, true, 0, 12_000_000, CachedResourceBindings().snapshot()).intervals.isEmpty())
    }

    @Test fun periodEndCannotPaintTheFollowingPeriodsTimeWithAnOverlongSidxReference() {
        val representation = parsed(listXml()).getPeriod(0).adaptationSets.single().representations.single()
        val index = DashWrappingSegmentIndex(ChunkIndex(intArrayOf(100, 200, 100), longArrayOf(0, 100, 300),
            longArrayOf(4_000_000, 4_000_000, 4_000_000), longArrayOf(0, 4_000_000, 8_000_000)), 0)
        val request = DashCacheRequest(BASE, 0, 400, "k")
        val refs = mapOf(0L to DashCacheResource(request, 0, 100), 1L to DashCacheResource(request, 100, 200), 2L to DashCacheResource(request, 300, 100))
        val base = view(representation, index, refs)
        val clipped = DashCacheRepresentation(0, representation, base.baseUrl, base.index, Any(), null, null, refs, 6_000_000)
        val track = DashCachedTimelineIndex.track("v", clipped, true, 12_000_000, 24_000_000, CachedResourceBindings().snapshot())
        assertEquals(listOf(CachedTimeRange(12_000_000, 18_000_000)), CachedTimelineCoverage.compute(listOf(track), mapOf("k" to listOf(CachedResourceRange("k", 0, 400)))))
    }

    @Test fun manifestAndNativeGroupIdentityPreventOldPeriodOrEqualGroupSubstitution() {
        val manifest = parsed(listXml())
        val representation = manifest.getPeriod(0).adaptationSets.single().representations.single()
        val native = TrackGroup("video", representation.format)
        val equalButOld = TrackGroup("video", representation.format)
        assertEquals(native, equalButOld); assertNotSame(native, equalButOld)
        val group = Tracks.Group(native, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))
        val catalog = DashCacheCatalog()
        val rep = view(representation, requireNotNull(representation.index), emptyMap())
        catalog.update(Any(), DashCacheCatalog.Entry(manifest, 0, equalButOld, C.TRACK_TYPE_VIDEO, 0, 0, listOf(rep)))
        val params = TrackSelectionParameters.Builder(context).build()
        assertNull(DashCachedSelection.select(catalog.snapshot(), manifest, 0, Tracks(listOf(group)), params, mapOf(C.TRACK_TYPE_VIDEO to representation.format)))
        catalog.update(Any(), DashCacheCatalog.Entry(manifest, 0, native, C.TRACK_TYPE_VIDEO, 0, 0, listOf(rep)))
        assertNotNull(DashCachedSelection.select(catalog.snapshot(), manifest, 0, Tracks(listOf(group)), params, mapOf(C.TRACK_TYPE_VIDEO to representation.format)))
        assertNull(DashCachedSelection.select(catalog.snapshot(), parsed(listXml()), 0, Tracks(listOf(group)), params, mapOf(C.TRACK_TYPE_VIDEO to representation.format)))
        assertNull(DashCachedSelection.select(catalog.snapshot(), manifest, 1, Tracks(listOf(group)), params, mapOf(C.TRACK_TYPE_VIDEO to representation.format)))
        catalog.close()
        assertTrue(catalog.snapshot().entries.isEmpty())
    }

    @Test fun actualTimelineTemplatePublishesRealDiskHoles() = running("template", 12_000_000) { fixture ->
        val rep = fixture.selected()
        assertNotNull(rep.representation.index)
        fixture.removeSegment(rep, 2)
        await { fixture.ranges.value == HOLES }
    }

    @Test fun actualSegmentListKeepsExactRangesInOneContainer() = running("list", 12_000_000) { fixture ->
        val rep = fixture.selected()
        assertNotNull(rep.representation.index)
        assertEquals(8406L, requireNotNull(rep.index).address(2).start)
        fixture.removeSegment(rep, 2)
        await { fixture.ranges.value == HOLES }
    }

    @Test fun actualSidxIndexComesFromTheCompletedNativeInitializationLoad() = running("base", 12_000_000) { fixture ->
        val rep = fixture.selected()
        assertNull("SegmentBase does not provide a manifest index", rep.representation.index)
        assertEquals(3L, requireNotNull(rep.index).segmentCount)
        assertEquals(872L, rep.index.address(0).start)
        assertEquals(796L, requireNotNull(rep.indexData).position)
        assertEquals(76L, rep.indexData.length)
        assertEquals(0L, requireNotNull(rep.initialization).request.position)
        assertEquals(872L, rep.initialization.request.length)
        fixture.removeSegment(rep, 1)
        await { fixture.ranges.value == HOLES }
    }

    @Test fun actualSidxPtoPublishesTheMedia3PresentationTimeline() = running("pto", 10_000_000) { fixture ->
        val rep = fixture.selected()
        assertEquals(-2_000_000L, requireNotNull(rep.index).startUs(0))
        fixture.removeSegment(rep, 1)
        await { fixture.ranges.value == listOf(CachedTimeRange(0, 2_000_000), CachedTimeRange(6_000_000, 10_000_000)) }
    }

    @Test fun actualPeriodTransitionDoesNotReuseAnEqualGroupFromThePreviousPeriod() = running("multi", 12_000_000) { fixture ->
        instrumentation.runOnMainSync { fixture.player.seekTo(12_000); fixture.player.play() }
        await { fixture.ranges.value == listOf(CachedTimeRange(12_000_000, 24_000_000)) }
        instrumentation.runOnMainSync { assertEquals(1, fixture.player.currentPeriodIndex); fixture.player.pause() }
        assertTrue(fixture.ranges.value.all { it.startUs >= 12_000_000 })
        instrumentation.runOnMainSync { fixture.player.seekTo(21_000) }
        await { fixture.ranges.value == listOf(CachedTimeRange(12_000_000, 24_000_000)) }
    }

    @Test fun actualMergedSidxLoadCanProveOnlyTheTwoIndividuallyCachedChildren() = running("base", 12_000_000, 3) { fixture ->
        val rep = fixture.selected()
        val first = requireNotNull(rep.media[0])
        assertEquals(872L, first.request.position)
        assertEquals(21_460L, first.request.length)
        assertTrue(rep.media.values.all { it.request.key == first.request.key && it.request.length == 21_460L })
        fixture.disk.cache.getCachedSpans(first.request.key).single { it.position == 872L }.let(fixture.disk.cache::removeSpan)
        fun seed(position: Long, length: Long) {
            val cached = CacheDataSource.Factory().setCache(fixture.disk.cache).setCacheKeyFactory(fixture.session.cacheKeyFactory)
                .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context)).createDataSource()
            try {
                cached.open(DataSpec.Builder().setUri(first.request.uri).setPosition(position).setLength(length).build())
                val buffer = ByteArray(4096)
                while(cached.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) { }
            } finally { cached.close() }
        }
        seed(872, 7534); seed(15_384, 6948)
        await { fixture.ranges.value == HOLES }
        fixture.disk.cache.getCachedSpans(first.request.key).single { it.position == 872L }.let(fixture.disk.cache::removeSpan)
        seed(872, 7533)
        await { fixture.ranges.value == listOf(CachedTimeRange(8_000_000, 12_000_000)) }
    }

    @Test fun actualIndependentAudioHoleCannotBeFilledByTheCompleteVideo() = running("av", 12_000_000,
        initial = listOf(CachedTimeRange(0, 7_999_999), CachedTimeRange(8_000_000, 12_000_000))) { fixture ->
        val audio = fixture.selected(C.TRACK_TYPE_AUDIO)
        val video = fixture.selected(C.TRACK_TYPE_VIDEO)
        val videoRange = requireNotNull(video.media[2]?.resolve(fixture.session.cacheResourceSnapshot()))
        assertTrue(fixture.disk.cache.isCached(videoRange.key, videoRange.position, videoRange.length))
        fixture.removeSegment(audio, 2)
        await { fixture.ranges.value == listOf(CachedTimeRange(0, 3_989_333), CachedTimeRange(8_000_000, 12_000_000)) }
    }

    @Test fun actualRepresentationOverrideCannotBorrowTheOldFullyCachedVideo() = running("adaptive", 12_000_000) { fixture ->
        var old: DashCacheRepresentation? = null
        instrumentation.runOnMainSync {
            old = fixture.selected()
            val group = fixture.player.currentTracks.groups.single { it.type == C.TRACK_TYPE_VIDEO }.mediaTrackGroup
            fixture.player.trackSelectionParameters = fixture.player.trackSelectionParameters.buildUpon()
                .setMaxVideoBitrate(Int.MAX_VALUE).setOverrideForType(TrackSelectionOverride(group, 1)).build()
            fixture.player.seekTo(0); fixture.player.play()
        }
        await { fixture.selectedOrNull()?.trackIndex == 1 && fixture.selectedOrNull()?.media?.size == 3 && fixture.ranges.value == listOf(CachedTimeRange(0, 12_000_000)) }
        instrumentation.runOnMainSync { fixture.player.pause() }
        val oldRange = requireNotNull(requireNotNull(old).media[2]?.resolve(fixture.session.cacheResourceSnapshot()))
        assertTrue(fixture.disk.cache.isCached(oldRange.key, oldRange.position, oldRange.length))
        fixture.removeSegment(fixture.selected(), 2)
        await { fixture.ranges.value == HOLES }
        assertTrue(fixture.disk.cache.isCached(oldRange.key, oldRange.position, oldRange.length))
    }

    @Test fun factoryPreservesExtractorKnobsTransferListenerAndOriginalTrackGroup() {
        val manifest = parsed(listXml())
        val representation = manifest.getPeriod(0).adaptationSets.single().representations.single()
        val group = TrackGroup("native", representation.format)
        val actualExtractors = BundledChunkExtractor.Factory()
        var subtitle: SubtitleParser.Factory? = null
        var parse = false
        var codecs = 0
        var captions: List<androidx.media3.common.Format>? = null
        var transfer: TransferListener? = null
        val extractors = object : ChunkExtractor.Factory by actualExtractors {
            override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ChunkExtractor.Factory {
                subtitle = factory; actualExtractors.setSubtitleParserFactory(factory); return this
            }
            override fun experimentalParseSubtitlesDuringExtraction(value: Boolean): ChunkExtractor.Factory {
                parse = value; actualExtractors.experimentalParseSubtitlesDuringExtraction(value); return this
            }
            override fun experimentalSetCodecsToParseWithinGopSampleDependencies(value: Int): ChunkExtractor.Factory {
                codecs = value; actualExtractors.experimentalSetCodecsToParseWithinGopSampleDependencies(value); return this
            }
            override fun getOutputTextFormat(format: androidx.media3.common.Format) = actualExtractors.getOutputTextFormat(format)
            override fun createProgressiveMediaExtractor(type: Int, format: androidx.media3.common.Format, enabled: Boolean,
                closedCaptions: List<androidx.media3.common.Format>, output: TrackOutput?, player: PlayerId): ChunkExtractor? {
                captions = closedCaptions
                assertTrue(enabled); assertEquals(C.TRACK_TYPE_VIDEO, type)
                return actualExtractors.createProgressiveMediaExtractor(type, format, enabled, closedCaptions, output, player)
            }
        }
        val sources = DataSource.Factory {
            val source = DefaultDataSource.Factory(context).createDataSource()
            object : DataSource by source {
                override fun addTransferListener(listener: TransferListener) { transfer = listener; source.addTransferListener(listener) }
            }
        }
        val catalog = DashCacheCatalog()
        val factory = ObservedDashChunkSourceFactory(sources, catalog, { "original" }, 3, extractors)
        val parser = DefaultSubtitleParserFactory()
        assertSame(factory, factory.setSubtitleParserFactory(parser))
        assertSame(factory, factory.experimentalParseSubtitlesDuringExtraction(true))
        assertSame(factory, factory.experimentalSetCodecsToParseWithinGopSampleDependencies(C.VIDEO_CODEC_FLAG_H264))
        assertSame(representation.format, factory.getOutputTextFormat(representation.format))
        val listener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, spec: DataSpec, network: Boolean) = Unit
            override fun onTransferStart(source: DataSource, spec: DataSpec, network: Boolean) = Unit
            override fun onTransferEnd(source: DataSource, spec: DataSpec, network: Boolean) = Unit
            override fun onBytesTransferred(source: DataSource, spec: DataSpec, network: Boolean, count: Int) = Unit
        }
        val captionFormats = listOf(androidx.media3.common.Format.Builder().setSampleMimeType("application/cea-608").build())
        val errors = object : LoaderErrorThrower { override fun maybeThrowError() = Unit; override fun maybeThrowError(minRetryCount: Int) = Unit }
        val source = factory.createDashChunkSource(errors, manifest, BaseUrlExclusionList(), 0, intArrayOf(0), FixedTrackSelection(group, 0),
            C.TRACK_TYPE_VIDEO, C.TIME_UNSET, true, captionFormats, null, listener, PlayerId.UNSET, null)
        try {
            assertSame(parser, subtitle); assertTrue(parse); assertEquals(C.VIDEO_CODEC_FLAG_H264, codecs)
            assertSame(listener, transfer); assertSame(captionFormats, captions)
            assertSame(group, catalog.snapshot().entries.single().trackGroup)
        } finally { source.release() }
        assertTrue(catalog.snapshot().entries.isEmpty())
    }

    private fun parsed(xml: String): DashManifest = androidx.media3.exoplayer.dash.manifest.DashManifestParser().parse(Uri.parse(BASE), xml.byteInputStream())
    private fun listXml() = "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT12S\" minBufferTime=\"PT1S\"><Period id=\"p\" start=\"PT0S\"><AdaptationSet contentType=\"video\" startWithSAP=\"1\"><Representation id=\"v\" bandwidth=\"1\" mimeType=\"video/mp4\" codecs=\"avc1.42c00a\"><BaseURL>https://dash.invalid/opaque?token=public</BaseURL><SegmentList timescale=\"1\" duration=\"4\"><SegmentURL mediaRange=\"0-99\"/><SegmentURL mediaRange=\"100-299\"/><SegmentURL mediaRange=\"300-399\"/></SegmentList></Representation></AdaptationSet></Period></MPD>"
    private fun view(rep: Representation, index: DashSegmentIndex, media: Map<Long, DashCacheResource>) = DashCacheRepresentation(0, rep, rep.baseUrls.first().url,
        object : DashCacheIndex {
            override val firstSegment = index.firstSegmentNum
            override val segmentCount = index.getSegmentCount(12_000_000)
            override fun segmentAt(timeUs: Long) = index.getSegmentNum(timeUs, 12_000_000)
            override fun startUs(segment: Long) = index.getTimeUs(segment)
            override fun durationUs(segment: Long) = index.getDurationUs(segment, 12_000_000)
            override fun address(segment: Long) = index.getSegmentUrl(segment)
        }, Any(), null, null, media)

    private fun running(kind: String, duration: Long, maxSegments: Int = 1,
        initial: List<CachedTimeRange> = listOf(CachedTimeRange(0, duration)), verify: (Running) -> Unit) {
        Disk().use { disk ->
            val session = PlaybackSourceSession(context, 1, disk.item(kind), emptyMap(), disk.cache)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val ranges = MutableStateFlow<List<CachedTimeRange>>(emptyList())
            val error = AtomicReference<PlaybackException?>()
            val scene = ActivityScenario.launch<CacheSelectionSurfaceActivity>(Intent(context, CacheSelectionSurfaceActivity::class.java))
            var surface: android.view.SurfaceView? = null
            await { scene.onActivity { if(it.video.width > 0 && it.video.holder.surface.isValid) surface = it.video }; surface != null }
            var player: ExoPlayer? = null
            var timeline: PlayerCachedTimeline? = null
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build().apply {
                    volume = 0f; setVideoSurface(requireNotNull(surface).holder.surface)
                    if(kind == "adaptive") trackSelectionParameters = trackSelectionParameters.buildUpon().setMaxVideoBitrate(150_000).build()
                    addListener(object : Player.Listener { override fun onPlayerError(failure: PlaybackException) { error.set(failure) } })
                    timeline = PlayerCachedTimeline(this, session, disk.cache, scope, CachedSourceKind.DASH) { ranges.value = it }
                    val source = if(maxSegments == 1) session.createMediaSource() else {
                        val upstream = DefaultDataSource.Factory(context)
                        val cached = CacheDataSource.Factory().setCache(disk.cache).setCacheKeyFactory(session.cacheKeyFactory)
                            .setUpstreamDataSourceFactory(upstream)
                        val catalog = requireNotNull(session.dashCacheCatalog)
                        DashMediaSource.Factory(ObservedDashChunkSourceFactory(session.bindMainCacheFactory(cached), catalog, session::cacheKey, maxSegments), upstream)
                            .setManifestParser(DashCacheManifestParser(catalog)).createMediaSource(session.mediaItem)
                    }
                    setMediaSource(source); prepare(); play()
                }
            }
            val fixture = Running(disk, session, ranges, requireNotNull(player))
            try {
                await { error.get()?.let { throw AssertionError("Actual DASH failed", it) }; ranges.value == initial }
                instrumentation.runOnMainSync { player?.pause() }
                verify(fixture)
            } catch(failure: Throwable) {
                val catalog = requireNotNull(session.dashCacheCatalog).snapshot()
                var native = ""
                instrumentation.runOnMainSync {
                    native = "state=${player?.playbackState},period=${player?.currentPeriodIndex},manifest=" +
                        catalog.entries.map { it.manifest === player?.currentManifest }.toString() + ",groups=" +
                        player?.currentTracks?.groups?.map { group -> "${group.mediaTrackGroup.id}:${group.type}:${catalog.entries.any { it.trackGroup === group.mediaTrackGroup }}" }
                }
                Log.e("MiruDashCacheTest", "$native,entries=${catalog.entries.size},indices=${catalog.entries.flatMap { it.representations }.map { it.index?.segmentCount }},sap=${catalog.entries.flatMap { it.representations }.map { catalog.independent(it.representation) }},ranges=${ranges.value},resources=${session.cacheResourceSnapshot().resourceCount}")
                throw failure
            } finally {
                instrumentation.runOnMainSync { timeline?.close(); player?.release() }
                scope.cancel(); runBlocking { scope.coroutineContext[Job]?.join() }
                session.close(); scene.close()
            }
        }
    }
    private class Running(val disk: Disk, val session: PlaybackSourceSession, val ranges: MutableStateFlow<List<CachedTimeRange>>, val player: ExoPlayer) {
        fun selectedOrNull(type: Int = C.TRACK_TYPE_VIDEO) = requireNotNull(session.dashCacheCatalog).snapshot().entries.singleOrNull { it.trackType == type }?.representations?.singleOrNull()
        fun selected(type: Int = C.TRACK_TYPE_VIDEO) = requireNotNull(selectedOrNull(type))
        fun removeSegment(rep: DashCacheRepresentation, number: Long) {
            val ref = requireNotNull(rep.media[number])
            val range = requireNotNull(ref.resolve(session.cacheResourceSnapshot()))
            val span = disk.cache.getCachedSpans(range.key).single { it.position == range.position }
            assertEquals(range.length, span.length)
            disk.cache.removeSpan(span)
        }
    }
    private inner class Disk : Closeable {
        val root = File(context.cacheDir, "dash-cache-${UUID.randomUUID()}").apply { mkdirs() }
        @Suppress("DEPRECATION") val cache = SimpleCache(File(root, "stream"), NoOpCacheEvictor())
        fun item(kind: String): MediaItem {
            if(kind == "av") {
                for(name in requireNotNull(instrumentation.context.assets.list("dash-av")))
                    File(root, name).writeBytes(instrumentation.context.assets.open("dash-av/$name").use { it.readBytes() })
                return MediaItem.fromUri(Uri.fromFile(File(root, "dash-manifest.mpd")))
            }
            if(kind == "adaptive") {
                for(folder in listOf("low", "high")) {
                    val directory = File(root, folder).apply { mkdirs() }
                    for(name in listOf("dash-init-0.m4s", "dash-0-00001.m4s", "dash-0-00002.m4s", "dash-0-00003.m4s"))
                        File(directory, name).writeBytes(instrumentation.context.assets.open(name).use { it.readBytes() })
                }
                val xml = buildString {
                    append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT12S\" minBufferTime=\"PT1S\"><Period id=\"p\" start=\"PT0S\"><AdaptationSet id=\"0\" contentType=\"video\" startWithSAP=\"1\" segmentAlignment=\"true\">")
                    repeat(2) { index -> val folder = if(index == 0) "low" else "high"; append("<Representation id=\"v$index\" mimeType=\"video/mp4\" codecs=\"avc1.42c00a\" bandwidth=\"${(index + 1) * 100_000}\" width=\"160\" height=\"90\"><BaseURL>$folder/</BaseURL><SegmentTemplate timescale=\"10240\" duration=\"40960\" startNumber=\"1\" initialization=\"dash-init-0.m4s\" media=\"dash-0-\$Number%05d\$.m4s\"/></Representation>") }
                    append("</AdaptationSet></Period></MPD>")
                }
                return MediaItem.fromUri(Uri.fromFile(File(root, "adaptive.mpd").apply { writeText(xml) }))
            }
            if(kind == "multi") {
                for(folder in listOf("p0", "p1")) {
                    val directory = File(root, folder).apply { mkdirs() }
                    for(name in listOf("dash-init-0.m4s", "dash-0-00001.m4s", "dash-0-00002.m4s", "dash-0-00003.m4s"))
                        File(directory, name).writeBytes(instrumentation.context.assets.open(name).use { it.readBytes() })
                }
                val xml = buildString {
                    append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT24S\" minBufferTime=\"PT1S\">")
                    repeat(2) { period -> append("<Period id=\"p$period\" start=\"PT${period * 12}S\" duration=\"PT12S\"><AdaptationSet id=\"0\" contentType=\"video\" startWithSAP=\"1\"><Representation id=\"v\" mimeType=\"video/mp4\" codecs=\"avc1.42c00a\" bandwidth=\"20000\" width=\"160\" height=\"90\"><BaseURL>p$period/</BaseURL><SegmentTemplate timescale=\"10240\" duration=\"40960\" startNumber=\"1\" initialization=\"dash-init-0.m4s\" media=\"dash-0-\$Number%05d\$.m4s\"/></Representation></AdaptationSet></Period>") }
                    append("</MPD>")
                }
                val file = File(root, "multi.mpd").apply { writeText(xml) }
                return MediaItem.fromUri(Uri.fromFile(file))
            }
            if(kind == "template") {
                for(name in listOf("dash.mpd", "dash-init-0.m4s", "dash-0-00001.m4s", "dash-0-00002.m4s", "dash-0-00003.m4s"))
                    File(root, name).writeBytes(instrumentation.context.assets.open(name).use { it.readBytes() })
                return MediaItem.fromUri(Uri.fromFile(File(root, "dash.mpd")))
            }
            for(name in listOf("base.mp4", "base.mpd", "list.mpd", "pto.mpd"))
                File(root, name).writeBytes(instrumentation.context.assets.open("dash-cache/$name").use { it.readBytes() })
            return MediaItem.fromUri(Uri.fromFile(File(root, "$kind.mpd")))
        }
        override fun close() { cache.release(); check(root.deleteRecursively()) }
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while(!predicate()) { check(SystemClock.elapsedRealtime() < deadline) { "DASH cache condition timed out" }; SystemClock.sleep(20) }
    }
    companion object {
        private const val BASE = "https://dash.invalid/manifest.mpd"
        private val HOLES = listOf(CachedTimeRange(0, 4_000_000), CachedTimeRange(8_000_000, 12_000_000))
    }
}
