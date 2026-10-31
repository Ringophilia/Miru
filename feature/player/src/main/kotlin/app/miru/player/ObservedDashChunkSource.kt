// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class)
package app.miru.player

import androidx.media3.common.Format
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.dash.BaseUrlExclusionList
import androidx.media3.exoplayer.dash.DashChunkSource
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.PlayerEmsgHandler.PlayerTrackEmsgHandler
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.RangedUri
import androidx.media3.exoplayer.source.chunk.BundledChunkExtractor
import androidx.media3.exoplayer.source.chunk.Chunk
import androidx.media3.exoplayer.source.chunk.ChunkExtractor
import androidx.media3.exoplayer.source.chunk.ChunkHolder
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoaderErrorThrower
import androidx.media3.exoplayer.upstream.CmcdData
import androidx.media3.extractor.text.SubtitleParser

/** Same defaults/knobs as the pinned official factory; no playback policy is replaced. */
internal class ObservedDashChunkSourceFactory(
    private val sources: DataSource.Factory,
    private val catalog: DashCacheCatalog,
    private val key: (DataSpec) -> String,
    private val maxSegmentsPerLoad: Int = 1,
    private val extractors: ChunkExtractor.Factory = BundledChunkExtractor.Factory(),
    private val inferUnvisitedRequests: Boolean = false,
) : DashChunkSource.Factory {
    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): DashChunkSource.Factory {
        extractors.setSubtitleParserFactory(factory); return this
    }
    override fun experimentalParseSubtitlesDuringExtraction(value: Boolean): DashChunkSource.Factory {
        extractors.experimentalParseSubtitlesDuringExtraction(value); return this
    }
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(value: Int): DashChunkSource.Factory {
        extractors.experimentalSetCodecsToParseWithinGopSampleDependencies(value); return this
    }
    override fun getOutputTextFormat(format: Format): Format = extractors.getOutputTextFormat(format)
    override fun createDashChunkSource(errors: LoaderErrorThrower, manifest: DashManifest, exclusions: BaseUrlExclusionList,
        periodIndex: Int, adaptationSetIndices: IntArray, selection: ExoTrackSelection, trackType: Int,
        elapsedRealtimeOffsetMs: Long, enableEventMessageTrack: Boolean, closedCaptionFormats: List<Format>,
        emsg: PlayerTrackEmsgHandler?, transferListener: TransferListener?, playerId: PlayerId,
        cmcd: CmcdConfiguration?): DashChunkSource {
        val data = sources.createDataSource()
        transferListener?.let(data::addTransferListener)
        return ObservedDashChunkSource(extractors, errors, manifest, exclusions, periodIndex, adaptationSetIndices,
            selection, trackType, data, elapsedRealtimeOffsetMs, maxSegmentsPerLoad, enableEventMessageTrack,
            closedCaptionFormats, emsg, playerId, cmcd, catalog, key, inferUnvisitedRequests)
    }
}

/** Observe the holder AFTER actual init/sidx parsing and the requests actually emitted by super. */
internal class ObservedDashChunkSource(
    extractors: ChunkExtractor.Factory, errors: LoaderErrorThrower, manifest: DashManifest, exclusions: BaseUrlExclusionList,
    periodIndex: Int, adaptationSetIndices: IntArray, selection: ExoTrackSelection, private val kind: Int,
    data: DataSource, elapsedRealtimeOffsetMs: Long, private val maxSegmentsPerLoad: Int, enableEventMessageTrack: Boolean,
    closedCaptionFormats: List<Format>, emsg: PlayerTrackEmsgHandler?, playerId: PlayerId, cmcd: CmcdConfiguration?,
    private val catalog: DashCacheCatalog, private val key: (DataSpec) -> String, inferUnvisitedRequests: Boolean,
) : DefaultDashChunkSource(extractors, errors, manifest, exclusions, periodIndex, adaptationSetIndices, selection,
    kind, data, elapsedRealtimeOffsetMs, maxSegmentsPerLoad, enableEventMessageTrack, closedCaptionFormats, emsg, playerId, cmcd) {
    private val owner = Any()
    private var currentManifest = manifest
    private var currentPeriod = periodIndex
    private var currentSelection = selection
    private var closed = false
    private var loadRevision = 0L
    private val initialization = LinkedHashMap<Int, DashCacheResource>()
    private val indexes = LinkedHashMap<Int, DashCacheResource>()
    private val media = LinkedHashMap<Int, LinkedHashMap<Long, DashCacheResource>>()
    // Main session's key contract ignores request flags. With one segment/load
    // and no dynamic CMCD, DashUtil constructs the same future native request.
    // Other factory configurations retain actual emitted parent requests only.
    private val canInfer = inferUnvisitedRequests && maxSegmentsPerLoad == 1 && cmcd == null

    init { capture() }

    override fun updateManifest(manifest: DashManifest, periodIndex: Int) {
        super.updateManifest(manifest, periodIndex)
        currentManifest = manifest; currentPeriod = periodIndex
        // Keep the init actually consumed by the retained extractor. The mapper
        // rechecks every media URI/range against the replacement holder index.
        loadRevision++
        capture()
    }
    override fun updateTrackSelection(selection: ExoTrackSelection) {
        super.updateTrackSelection(selection); currentSelection = selection; capture()
    }
    override fun getNextChunk(info: LoadingInfo, loadPositionUs: Long, queue: List<MediaChunk>, out: ChunkHolder) {
        super.getNextChunk(info, loadPositionUs, queue, out); capture()
    }
    override fun onChunkLoadCompleted(chunk: Chunk) { super.onChunkLoadCompleted(chunk); capture() }
    override fun release() {
        closed = true
        try { super.release() } finally { catalog.remove(owner); initialization.clear(); indexes.clear(); media.clear() }
    }

    override fun newInitializationChunk(holder: RepresentationHolder, source: DataSource, format: Format, reason: Int,
        selectionData: Any?, initializationUri: RangedUri?, indexUri: RangedUri?, cmcd: CmcdData.Factory?): Chunk {
        val chunk = super.newInitializationChunk(holder, source, format, reason, selectionData, initializationUri, indexUri, cmcd)
        val track = trackIndex(holder)
        val original = request(chunk.dataSpec)
        val base = holder.selectedBaseUrl.url
        val actualRange = initializationUri?.let { it.attemptMerge(indexUri, base) ?: it } ?: requireNotNull(indexUri)
        val resourceUri = actualRange.resolveUriString(base)
        fun remember(range: RangedUri?, target: MutableMap<Int, DashCacheResource>) {
            if(range == null || range.resolveUriString(base) != resourceUri) return
            val ref = child(original, range, resourceUri) ?: return
            if(target[track]?.sameAs(ref) != true) { target[track] = ref; loadRevision++ }
        }
        remember(initializationUri, initialization)
        remember(indexUri, indexes)
        return chunk
    }

    override fun newMediaChunk(holder: RepresentationHolder, source: DataSource, trackType: Int, format: Format, reason: Int,
        selectionData: Any?, firstSegmentNum: Long, maxSegmentCount: Int, seekTimeUs: Long, nowPeriodTimeUs: Long,
        cmcd: CmcdData.Factory?): Chunk {
        val chunk = super.newMediaChunk(holder, source, trackType, format, reason, selectionData, firstSegmentNum,
            maxSegmentCount, seekTimeUs, nowPeriodTimeUs, cmcd)
        val count = (chunk as? MediaChunk)?.nextChunkIndex?.minus(firstSegmentNum) ?: 0
        if(count in 1..maxSegmentCount.toLong()) {
            val original = request(chunk.dataSpec)
            val track = trackIndex(holder)
            val refs = media.getOrPut(track) { linkedMapOf() }
            for(offset in 0 until count) {
                val number = Math.addExact(firstSegmentNum, offset)
                val range = holder.getSegmentUrl(number)
                val ref = child(original, range, range.resolveUriString(holder.selectedBaseUrl.url)) ?: continue
                if(refs[number]?.sameAs(ref) != true) { refs[number] = ref; loadRevision++ }
            }
            while(refs.size > 4096) refs.remove(refs.keys.first())
        }
        return chunk
    }

    private fun request(spec: DataSpec) = DashCacheRequest(spec.uri.toString(), spec.position, spec.length, key(spec))
    private fun child(parent: DashCacheRequest, range: RangedUri, uri: String): DashCacheResource? {
        if(range.start < parent.position || range.length != -1L && range.length <= 0) return null
        if(range.length > 0 && range.length > Long.MAX_VALUE - range.start) return null
        if(parent.length > 0 && (parent.length > Long.MAX_VALUE - parent.position ||
                range.length > 0 && range.start + range.length > parent.position + parent.length)) return null
        return DashCacheResource(parent, range.start, range.length, uri)
    }
    private fun trackIndex(holder: RepresentationHolder): Int {
        val index = representationHolders.indexOfFirst { it === holder }
        return if(index < 0) -1 else currentSelection.getIndexInTrackGroup(index)
    }
    private fun capture() {
        if(closed) return
        val snapshots = representationHolders.mapIndexed { holderIndex, holder ->
            val track = currentSelection.getIndexInTrackGroup(holderIndex)
            val index = if(holder.segmentIndex == null) null else object : DashCacheIndex {
                override val firstSegment = holder.firstSegmentNum
                override val segmentCount = holder.segmentCount
                override fun segmentAt(timeUs: Long) = holder.getSegmentNum(timeUs)
                override fun startUs(segment: Long) = holder.getSegmentStartTimeUs(segment)
                override fun durationUs(segment: Long) = Math.subtractExact(holder.getSegmentEndTimeUs(segment), holder.getSegmentStartTimeUs(segment))
                override fun address(segment: Long) = holder.getSegmentUrl(segment)
            }
            val unvisited: ((RangedUri) -> DashCacheResource?)? = if(canInfer) { range ->
                val spec = DashUtil.buildDataSpec(holder.representation, holder.selectedBaseUrl.url, range, 0)
                child(request(spec), range, range.resolveUriString(holder.selectedBaseUrl.url))
            } else null
            DashCacheRepresentation(track, holder.representation, holder.selectedBaseUrl.url, index, holder,
                initialization[track], indexes[track].takeIf { holder.representation.indexUri != null }, media[track].orEmpty(),
                currentManifest.getPeriodDurationUs(currentPeriod), unvisited)
        }
        catalog.update(owner, DashCacheCatalog.Entry(currentManifest, currentPeriod, currentSelection.trackGroup, kind,
            currentSelection.selectedIndexInTrackGroup, loadRevision, snapshots))
    }
}
