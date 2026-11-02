/*
 * Copyright (C) 2016 The Android Open Source Project
 * Copyright (c) 2026 Ringophilia and xiaomao
 * SPDX-License-Identifier: Apache-2.0
 *
 * Adapted scheduling policy from Media3 Mp4Extractor 1.11.1:
 * https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/mp4/Mp4Extractor.java
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package app.miru.player

/** Byte reads after a real seek; codec dependencies and seek indices belong to the caller. */
internal class Mp4ReadSchedule(tracks: List<Track>, initialSamples: IntArray, initialPosition: Long) {
    class Track(offsets: LongArray, sizes: IntArray, timestampsUs: LongArray,
        val video: Boolean = false, val itutT35: Boolean = false) {
        internal val offsets = offsets.copyOf()
        internal val sizes = sizes.copyOf()
        internal val times = timestampsUs.copyOf()
        internal val count get() = sizes.size
        init {
            require(offsets.size == sizes.size && sizes.size == timestampsUs.size)
            require(!(video && itutT35))
            for(i in sizes.indices) require(offsets[i] >= 0 && sizes[i] > 0 && offsets[i] <= Long.MAX_VALUE - sizes[i])
        }
    }
    data class Read(val track: Int, val sample: Int, val parentPosition: Long,
        val position: Long, val length: Long, val reopened: Boolean)

    private val tracks = tracks.toList()
    private val next = initialSamples.copyOf()
    private val accumulated: List<LongArray>
    private var cursor = initialPosition
    private var parent = initialPosition

    init {
        require(initialPosition >= 0 && tracks.size <= 256 && tracks.size == next.size)
        require(tracks.sumOf { it.count.toLong() } <= 2_000_000)
        tracks.indices.forEach { require(next[it] in 0..tracks[it].count) }
        val heads = IntArray(tracks.size)
        accumulated = tracks.map { LongArray(it.count) }
        var total = 0L
        while(true) {
            var chosen = -1
            for(i in tracks.indices) {
                if(heads[i] == tracks[i].count) continue
                // Native <= tie: the later track wins the timestamp merge.
                if(chosen < 0 || tracks[i].times[heads[i]] <= tracks[chosen].times[heads[chosen]]) chosen = i
            }
            if(chosen < 0) break
            val sample = heads[chosen]++
            accumulated[chosen][sample] = total
            total = Math.addExact(total, tracks[chosen].sizes[sample].toLong())
        }
    }

    fun next(): Read? {
        val chosen = choose()
        if(chosen < 0) return null
        val sample = next[chosen]++
        val offset = tracks[chosen].offsets[sample]
        val skip = Math.subtractExact(offset, cursor)
        val reopen = skip < 0 || skip >= RELOAD_BYTES
        if(reopen) { cursor = offset; parent = offset }
        val end = Math.addExact(offset, tracks[chosen].sizes[sample].toLong())
        return Read(chosen, sample, parent, cursor, Math.subtractExact(end, cursor), reopen).also { cursor = end }
    }

    private fun choose(): Int {
        var preferred = -1; var preferredSkip = Long.MAX_VALUE; var preferredReload = true
        var earliest = -1; var earliestBytes = Long.MAX_VALUE; var earliestReload = true
        var videoTime = Long.MAX_VALUE; var metadataTime = Long.MAX_VALUE; var metadata = -1
        for(i in tracks.indices) {
            val sample = next[i]; val track = tracks[i]
            if(sample == track.count) continue
            val time = track.times[sample]
            if(track.video) videoTime = minOf(videoTime, time)
            else if(track.itutT35 && time < metadataTime) { metadataTime = time; metadata = i }
            val skip = Math.subtractExact(track.offsets[sample], cursor)
            val reload = skip < 0 || skip >= RELOAD_BYTES
            if((!reload && preferredReload) || reload == preferredReload && skip < preferredSkip) {
                preferred = i; preferredReload = reload; preferredSkip = skip
            }
            if(accumulated[i][sample] < earliestBytes) {
                earliest = i; earliestBytes = accumulated[i][sample]; earliestReload = reload
            }
        }
        if(videoTime != Long.MAX_VALUE && metadata >= 0 && metadataTime <= videoTime) return metadata
        if(earliest < 0 || !earliestReload) return preferred
        return if(accumulated[preferred][next[preferred]] < earliestBytes + READ_AHEAD_BYTES) preferred else earliest
    }

    companion object {
        private const val RELOAD_BYTES = 256 * 1024L
        private const val READ_AHEAD_BYTES = 10 * 1024 * 1024L
    }
}
