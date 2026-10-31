// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.common.C
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/** Actual native index times are already PTO-adjusted; project them once into the window. */
internal object DashCachedTimelineIndex {
    fun track(id: String, representation: DashCacheRepresentation, independent: Boolean,
        periodPositionInWindowUs: Long, windowDurationUs: Long?, resources: CachedResourceSnapshot,
        context: CoroutineContext? = null, lookup: CachedResourceLookup = resources.lookup): CachedTrackCoverage {
        val intervals = ArrayList<CachedMediaInterval>()
        val index = representation.index ?: return CachedTrackCoverage(id, intervals)
        if(!independent || representation.representation.format.drmInitData != null ||
            periodPositionInWindowUs == C.TIME_UNSET || windowDurationUs == null || windowDurationUs <= 0 || index.segmentCount == 0L)
            return CachedTrackCoverage(id, intervals)
        if(representation.representation.initializationUri != null && representation.initialization == null ||
            representation.representation.indexUri != null && representation.indexData == null) return CachedTrackCoverage(id, intervals)
        val init = listOfNotNull(representation.initialization, representation.indexData)
            .map { it.resolve(lookup) ?: return CachedTrackCoverage(id, intervals) }
        val visibleStart = exact { Math.negateExact(periodPositionInWindowUs) } ?: return CachedTrackCoverage(id, intervals)
        val visibleEnd = exact { Math.subtractExact(windowDurationUs, periodPositionInWindowUs) } ?: return CachedTrackCoverage(id, intervals)
        val periodEnd = if(representation.periodDurationUs >= 0)
            exact { Math.addExact(periodPositionInWindowUs, representation.periodDurationUs) } ?: return CachedTrackCoverage(id, intervals)
            else Long.MAX_VALUE
        if(visibleEnd <= visibleStart) return CachedTrackCoverage(id, intervals)
        val first = maxOf(index.firstSegment, index.segmentAt(maxOf(0, visibleStart)))
        var last = index.segmentAt(visibleEnd - 1)
        if(index.segmentCount > 0) {
            val end = exact { Math.addExact(index.firstSegment, index.segmentCount - 1) } ?: return CachedTrackCoverage(id, intervals)
            last = minOf(last, end)
        }
        if(last < first || (exact { Math.subtractExact(last, first) } ?: return CachedTrackCoverage(id, intervals)) > 100_000)
            return CachedTrackCoverage(id, intervals)
        var segment = first
        while(segment <= last) {
            context?.ensureActive()
            val address = index.address(segment)
            val ref = representation.media[segment] ?: representation.unvisitedMedia?.invoke(address)
            val sameResource = ref != null && (ref.resourceUri == null || ref.resourceUri == address.resolveUriString(representation.baseUrl)) &&
                ref.position == address.start && ref.length == address.length
            val media = if(sameResource) ref?.resolve(lookup) else null
            if(media != null) {
                val duration = index.durationUs(segment)
                val start = exact { Math.addExact(index.startUs(segment), periodPositionInWindowUs) }
                val end = if(start == null || duration <= 0) null else exact { Math.addExact(start, duration) }
                if(start != null && end != null) {
                    val from = maxOf(0, periodPositionInWindowUs, start)
                    val to = minOf(windowDurationUs, periodEnd, end)
                    if(from < to) intervals += CachedMediaInterval(CachedTimeRange(from, to), init + media)
                }
            }
            if(segment == Long.MAX_VALUE) break
            segment++
        }
        return CachedTrackCoverage(id, intervals)
    }
    private inline fun exact(block: () -> Long): Long? = try { block() } catch(_: ArithmeticException) { null }
}
