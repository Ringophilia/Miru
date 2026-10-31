// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.common.C
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import java.math.BigInteger

/**
 * Maps one explicitly selected HLS playlist into the current window. Selection
 * belongs to the caller: alternative variants/renditions must not be unioned.
 * Resources are resolved by their ORIGINAL request coordinates, before EOF can
 * change their length. Resolution does not itself prove committed disk coverage.
 */
internal object HlsCachedTimelineIndex {
    fun track(
        id: String,
        playlist: HlsMediaPlaylist,
        initialStartTimeUs: Long,
        periodPositionInWindowUs: Long,
        windowDurationUs: Long?,
        resolve: (uri: String, position: Long, requestedLength: Long) -> CachedResourceRange?,
        // Only a container/sample parser may supply missing random-access proof.
        // No "previous segment is probably enough" heuristic is permitted.
        provenDecodeStarts: Map<Int, Int> = emptyMap(),
    ): CachedTrackCoverage {
        val intervals = ArrayList<CachedMediaInterval>()
        if(initialStartTimeUs == C.TIME_UNSET || playlist.startTimeUs == C.TIME_UNSET || periodPositionInWindowUs == C.TIME_UNSET ||
            windowDurationUs != null && windowDurationUs <= 0 || playlist.protectionSchemes != null ||
            playlist.interstitials.isNotEmpty()) return CachedTrackCoverage(id, intervals)
        val base = exact { Math.addExact(Math.subtractExact(playlist.startTimeUs, initialStartTimeUs), periodPositionInWindowUs) }
            ?: return CachedTrackCoverage(id, intervals)

        // Resolve init/key/media once even when a verified preroll chain shares
        // dependencies across several target segments.
        val dependencies = playlist.segments.map { segment -> dependencies(playlist, segment, resolve) }
        playlist.segments.forEachIndexed { index, segment ->
            if(segment.durationUs <= 0 || segment.relativeStartTimeUs < 0) return@forEachIndexed
            val decodeStart = if(playlist.hasIndependentSegments) index else provenDecodeStarts[index]
                ?: return@forEachIndexed
            if(decodeStart !in 0..index) return@forEachIndexed
            val required = LinkedHashSet<CachedResourceRange>()
            for(previous in decodeStart..index) {
                val predecessor = playlist.segments[previous]
                // A discontinuity starts a different decoding context. A proof
                // from the preceding context cannot establish random access here.
                if(predecessor.relativeDiscontinuitySequence != segment.relativeDiscontinuitySequence)
                    return@forEachIndexed
                required.addAll(dependencies[previous] ?: return@forEachIndexed)
            }
            val start = exact { Math.addExact(base, segment.relativeStartTimeUs) } ?: return@forEachIndexed
            val end = exact { Math.addExact(start, segment.durationUs) } ?: return@forEachIndexed
            val clippedStart = maxOf(0, start)
            val clippedEnd = windowDurationUs?.let { minOf(it, end) } ?: end
            if(clippedStart < clippedEnd)
                intervals += CachedMediaInterval(CachedTimeRange(clippedStart, clippedEnd), required)
        }
        return CachedTrackCoverage(id, intervals)
    }

    private fun dependencies(
        playlist: HlsMediaPlaylist,
        segment: HlsMediaPlaylist.Segment,
        resolve: (String, Long, Long) -> CachedResourceRange?,
    ): List<CachedResourceRange>? {
        if(segment.hasGapTag || segment.drmInitData != null) return null
        val result = ArrayList<CachedResourceRange>()
        fun resource(url: String, offset: Long, length: Long): CachedResourceRange? {
            if(offset < 0 || length != C.LENGTH_UNSET.toLong() && length <= 0) return null
            if(length > 0 && length > Long.MAX_VALUE - offset) return null
            val uri = UriUtil.resolve(playlist.baseUri, url)
            val range = resolve(uri, offset, length) ?: return null
            if(range.position != offset || length != C.LENGTH_UNSET.toLong() && range.length != length) return null
            return range
        }
        fun encryptedResource(item: HlsMediaPlaylist.Segment): Boolean {
            val keyUri = item.fullSegmentEncryptionKeyUri
            if(keyUri != null) {
                val iv = item.encryptionIV ?: return false
                val number = runCatching { BigInteger(iv.removePrefix("0x").removePrefix("0X"), 16) }.getOrNull()
                    ?: return false
                if(number.signum() < 0 || number.bitLength() > 128) return false
                val key = resource(keyUri, 0, C.LENGTH_UNSET.toLong()) ?: return false
                if(key.length != 16L) return false
                result += key
            }
            result += resource(item.url, item.byteRangeOffset, item.byteRangeLength) ?: return false
            return true
        }
        segment.initializationSegment?.let { init ->
            if(init.hasGapTag || init.drmInitData != null || init.initializationSegment != null || !encryptedResource(init)) return null
        }
        if(!encryptedResource(segment)) return null
        return result
    }

    private inline fun exact(operation: () -> Long): Long? = try { operation() } catch(_: ArithmeticException) { null }
}
