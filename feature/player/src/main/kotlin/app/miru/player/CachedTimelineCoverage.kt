// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import java.util.Collections

/** A known, nonempty half-open byte range in one exact cache-key partition. */
data class CachedResourceRange(val key: String, val position: Long, val length: Long) {
    init {
        require(key.isNotEmpty()) { "A resource must have an exact cache key" }
        require(position >= 0) { "A byte position cannot be negative" }
        require(length > 0) { "An unknown or empty byte range is not cache evidence" }
        require(length <= Long.MAX_VALUE - position) { "Byte range overflows" }
    }

    val endExclusive: Long get() = position + length
}

/** Half-open time range, already projected into the current playback window. */
data class CachedTimeRange(val startUs: Long, val endUs: Long) {
    init {
        require(startUs >= 0 && endUs > startUs) { "A time range must be nonempty and nonnegative" }
    }
}

/**
 * A format mapper supplies the complete byte dependency closure for this interval:
 * media samples/segments, random-access preroll, init data and encryption keys.
 * An empty dependency list means unknown availability and never proves coverage.
 */
class CachedMediaInterval(val time: CachedTimeRange, requiredBytes: Collection<CachedResourceRange>) {
    val requiredBytes: List<CachedResourceRange> = immutableSnapshot(requiredBytes)
}

/**
 * One required selected track in a specific period/representation. The opaque id
 * must distinguish these identities; alternative ABR representations are not
 * interchangeable evidence. Empty intervals cannot establish track coverage.
 */
class CachedTrackCoverage(val id: String, intervals: Collection<CachedMediaInterval>) {
    init { require(id.isNotBlank()) { "A selected track must have a stable identity" } }
    val intervals: List<CachedMediaInterval> = immutableSnapshot(intervals)
}

/**
 * Pure dependency coverage, not a media parser or a byte/duration approximation.
 * The caller supplies a coherent snapshot of committed cache spans belonging to
 * the current source/timeline/selection generation. Unknown format/index data
 * must remain absent rather than constructing a guessed interval.
 */
object CachedTimelineCoverage {
    fun compute(
        requiredTracks: Collection<CachedTrackCoverage>,
        committedSpansByKey: Map<String, Collection<CachedResourceRange>>,
        durationUs: Long? = null,
    ): List<CachedTimeRange> {
        require(durationUs == null || durationUs >= 0) { "A known duration cannot be negative" }
        val tracks = requiredTracks.toList()
        require(tracks.map { it.id }.toSet().size == tracks.size) { "Required track identities must be unique" }
        if(tracks.isEmpty() || durationUs == 0L) return immutableSnapshot(emptyList())

        // Merge once per resource. Sample/GOP lookups then use binary containment
        // rather than rescanning every disk fragment for every sample.
        val resources = committedSpansByKey.entries.associate { (key, spans) ->
            val snapshot = spans.toList()
            require(snapshot.all { it.key == key }) { "A cache span cannot change key partitions" }
            key to ResourceCoverage(snapshot)
        }

        var common: List<CachedTimeRange>? = null
        for(track in tracks) {
            val covered = track.intervals.mapNotNull { interval ->
                if(interval.requiredBytes.isEmpty() || !interval.requiredBytes.all {
                    resources[it.key]?.contains(it) == true
                }) return@mapNotNull null
                val endUs = durationUs?.let { minOf(interval.time.endUs, it) } ?: interval.time.endUs
                if(interval.time.startUs >= endUs) null else CachedTimeRange(interval.time.startUs, endUs)
            }
            val union = union(covered)
            if(union.isEmpty()) return immutableSnapshot(emptyList())
            common = common?.let { intersect(it, union) } ?: union
            if(common.isEmpty()) return immutableSnapshot(emptyList())
        }
        return immutableSnapshot(common.orEmpty())
    }

    private class ResourceCoverage(spans: List<CachedResourceRange>) {
        private val ranges: List<ByteInterval>

        init {
            val sorted = spans.sortedWith(compareBy({ it.position }, { it.endExclusive }))
            val merged = ArrayList<ByteInterval>()
            for(span in sorted) {
                val previous = merged.lastOrNull()
                if(previous != null && span.position <= previous.endExclusive) {
                    merged[merged.lastIndex] = ByteInterval(previous.start, maxOf(previous.endExclusive, span.endExclusive))
                } else merged += ByteInterval(span.position, span.endExclusive)
            }
            ranges = merged
        }

        fun contains(required: CachedResourceRange): Boolean {
            var low = 0
            var high = ranges.lastIndex
            while(low <= high) {
                val middle = low + (high - low) / 2
                if(ranges[middle].start <= required.position) low = middle + 1 else high = middle - 1
            }
            return high >= 0 && ranges[high].endExclusive >= required.endExclusive
        }
    }

    private data class ByteInterval(val start: Long, val endExclusive: Long)

    private fun union(intervals: List<CachedTimeRange>): List<CachedTimeRange> {
        val merged = ArrayList<CachedTimeRange>()
        for(interval in intervals.sortedWith(compareBy({ it.startUs }, { it.endUs }))) {
            val previous = merged.lastOrNull()
            if(previous != null && interval.startUs <= previous.endUs) {
                merged[merged.lastIndex] = CachedTimeRange(previous.startUs, maxOf(previous.endUs, interval.endUs))
            } else merged += interval
        }
        return merged
    }

    private fun intersect(first: List<CachedTimeRange>, second: List<CachedTimeRange>): List<CachedTimeRange> {
        val result = ArrayList<CachedTimeRange>()
        var i = 0
        var j = 0
        while(i < first.size && j < second.size) {
            val a = first[i]
            val b = second[j]
            val start = maxOf(a.startUs, b.startUs)
            val end = minOf(a.endUs, b.endUs)
            if(start < end) result += CachedTimeRange(start, end)
            when {
                a.endUs < b.endUs -> i++
                a.endUs > b.endUs -> j++
                else -> { i++; j++ }
            }
        }
        return result
    }
}

private fun <T> immutableSnapshot(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
