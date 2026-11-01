// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.extractor.mp4.Track
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** First vertical adapter. Unsupported mux/edits/codecs remain explicit work, never guessed. */
internal object Mp4AvcCachedTimelineIndex {
    fun tracks(selected: Mp4CachedSelection.Selected, session: PlaybackSourceSession, cache: Cache,
        positionInWindowUs: Long, durationUs: Long, lengths: (String) -> Long, context: CoroutineContext): List<CachedTrackCoverage> {
        val entry = selected.entry
        val proof = entry.metadataProof ?: return emptyList()
        // Register even unavailable metadata so later commits can wake this index.
        proof.reads.forEach { lengths(it.request.key) }
        val verified = proof.verifiedBytes(cache, context) ?: return emptyList()
        val metadata = CachedMp4MetadataReader.read(verified, entry.fileLength.takeIf { it >= 0 }, entry.profile, context) ?: return emptyList()
        // Multi-track scheduling and audio codec preroll require their own native
        // I/O closure, not a union or a fabricated AV intersection.
        val exposed = metadata.tables.filter { it.sampleCount > 0 && it.track.shouldBeExposed }
        if(exposed.size != 1 || entry.tracks.size != 1 || selected.outputs.size != 1) return emptyList()
        val output = selected.outputs.single()
        val table = exposed.single()
        val format = output.format ?: return emptyList()
        if(output.type != C.TRACK_TYPE_VIDEO || format.sampleMimeType != MimeTypes.VIDEO_H264 ||
            table.track.format.sampleMimeType != MimeTypes.VIDEO_H264 || format.id != table.track.id.toString() ||
            table.track.sampleTransformation != Track.TRANSFORMATION_NONE || table.track.nalUnitLengthFieldLength !in 1..4 ||
            table.track.format.initializationData.isEmpty()) return emptyList()
        try {
            for(description in 0 until 256) if(table.track.getSampleDescriptionEncryptionBox(description) != null) return emptyList()
        } catch(_: IndexOutOfBoundsException) { /* All declared descriptions checked. */ }
        val timing = Mp4RawSampleTiming.from(metadata, table.track.id) ?: return emptyList()
        if(table.sampleCount != timing.sampleCount) return emptyList()
        val edits = table.track.editListMediaTimes
        if(edits != null && (edits.length() != 1 || edits.get(0) < 0)) return emptyList()
        val originTicks = edits?.get(0) ?: 0L
        // For a single retained edit, an unchanged count means the native parser
        // kept the full original decode order. Check every native start exactly.
        for(i in 0 until table.sampleCount) {
            context.ensureActive()
            if(table.timestampsUs[i] != timing.micros(timing.presentationTicks(i) - originTicks) || table.sizes[i] <= 0) return emptyList()
        }
        val uri = proof.reads.map { it.request.uri }.distinct().singleOrNull() ?: return emptyList()
        val intervals = ArrayList<CachedMediaInterval>()
        val rap = HashMap<Pair<Int, String>, Boolean>()
        val map = entry.seekMap ?: return emptyList()
        val metadataBytes = proof.reads.map { it.range }
        for(i in 0 until table.sampleCount) {
            context.ensureActive()
            val start = Math.addExact(table.timestampsUs[i], positionInWindowUs)
            val end = Math.addExact(timing.micros(Math.addExact(timing.presentationTicks(i) - originTicks, timing.durationTicks(i))), positionInWindowUs)
            val from = maxOf(0, start); val to = minOf(durationUs, end)
            if(from >= to) continue
            var first = table.getIndexOfEarlierOrEqualSynchronizationSample(maxOf(0, table.timestampsUs[i]))
            if(first == C.INDEX_UNSET) first = table.getIndexOfLaterOrEqualSynchronizationSample(maxOf(0, table.timestampsUs[i]))
            if(first !in 0..i) continue
            val initial = map.getSeekPoints(maxOf(0, table.timestampsUs[i])).first.position
            if(initial < 0 || initial > table.offsets[first]) continue
            var parentStart = initial
            var key = session.cacheKey(DataSpec.Builder().setUri(uri).setPosition(parentStart).build())
            lengths(key)
            val isIdr = rap.getOrPut(first to key) {
                idr(cache, Mp4CacheRequest(uri, parentStart, -1, key), table.offsets[first], table.sizes[first], table.track.nalUnitLengthFieldLength, context)
            }
            if(!isIdr) continue
            var cursor = initial
            val required = ArrayList<CachedResourceRange>(metadataBytes)
            for(index in first..i) {
                context.ensureActive()
                val offset = table.offsets[index]; val size = table.sizes[index].toLong()
                val skip = Math.subtractExact(offset, cursor)
                // Same fixed native readSample boundary. A large/backward gap
                // reopens at the sample; smaller gaps are consumed by skipFully.
                if(skip < 0 || skip >= 256 * 1024) {
                    parentStart = offset
                    key = session.cacheKey(DataSpec.Builder().setUri(uri).setPosition(parentStart).build())
                    lengths(key); cursor = offset
                }
                val count = Math.subtractExact(Math.addExact(offset, size), cursor)
                if(count <= 0) continue
                required += CachedResourceRange(key, cursor, count)
                cursor = offset + size
            }
            intervals += CachedMediaInterval(CachedTimeRange(from, to), required)
        }
        return listOf(CachedTrackCoverage("mp4-selected-video", intervals))
    }

    private fun idr(cache: Cache, request: Mp4CacheRequest, offset: Long, size: Int, lengthBytes: Int, context: CoroutineContext): Boolean {
        val input = CacheOnlyMp4Bytes(cache, request, context)
        val end = Math.addExact(offset, size.toLong()); var cursor = offset; var found = false; var count = 0
        while(cursor < end) {
            context.ensureActive()
            if(++count > 4096 || end - cursor < lengthBytes) return false
            val prefix = input.read(cursor, lengthBytes)?.bytes ?: return false
            val length = prefix.fold(0L) { a, b -> (a shl 8) or (b.toLong() and 255) }
            cursor += lengthBytes
            if(length <= 0 || length > end - cursor) return false
            val header = input.read(cursor, 1)?.bytes?.get(0)?.toInt()?.and(255) ?: return false
            if(header and 128 != 0) return false
            val type = header and 31
            if(type in 1..5) { if(type != 5) return false; found = true }
            cursor += length
        }
        return cursor == end && found
    }
}
