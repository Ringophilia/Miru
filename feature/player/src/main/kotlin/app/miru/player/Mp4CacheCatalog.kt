// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.common.Format
import androidx.media3.extractor.SeekMap
import java.io.Closeable
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class Mp4CacheRequest(val uri: String, val position: Long, val length: Long, val key: String) {
    fun sameAs(other: Mp4CacheRequest?) = other != null && uri == other.uri && position == other.position && length == other.length && key == other.key
}

internal data class Mp4ParserProfile(val trustedDefaults: Boolean, val parseHagcMetadata: Boolean,
    val textTranscoding: Boolean, val codecsWithinGop: Int)

/** Actual progressive extractor output only. It neither fetches media nor asserts cached time. */
internal class Mp4CacheCatalog : Closeable {
    class Track(val outputId: Int, val type: Int, val ordinal: Int, val format: Format?, val durationUs: Long)
    class Entry(val profile: Mp4ParserProfile, tracks: Collection<Track>, val tracksEnded: Boolean,
        val seekMap: SeekMap?, val fileLength: Long, val request: Mp4CacheRequest?) {
        val tracks = Collections.unmodifiableList(ArrayList(tracks))
        fun sameAs(other: Entry) = profile == other.profile && tracksEnded == other.tracksEnded && seekMap === other.seekMap &&
            fileLength == other.fileLength && (request === other.request || request?.sameAs(other.request) == true) &&
            tracks.size == other.tracks.size && tracks.indices.all { i ->
                val a = tracks[i]; val b = other.tracks[i]
                a.outputId == b.outputId && a.type == b.type && a.ordinal == b.ordinal && a.format === b.format && a.durationUs == b.durationUs
            }
    }
    class Snapshot(val revision: Long, entries: Collection<Entry>) {
        val entries = Collections.unmodifiableList(ArrayList(entries))
    }
    private val gate = Any()
    private var closed = false
    private val entries = LinkedHashMap<Any, Entry>()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    fun snapshot() = synchronized(gate) { Snapshot(mutableRevision.value, entries.values) }
    fun update(owner: Any, entry: Entry) = synchronized(gate) {
        if(!closed && entries[owner]?.sameAs(entry) != true) {
            entries[owner] = entry
            if(entries.size > 128) entries.remove(entries.keys.first())
            mutableRevision.value++
        }
    }
    fun remove(owner: Any) = synchronized(gate) { if(entries.remove(owner) != null) mutableRevision.value++ }
    override fun close() = synchronized(gate) {
        if(!closed) { closed = true; entries.clear(); mutableRevision.value++ }
    }
}
