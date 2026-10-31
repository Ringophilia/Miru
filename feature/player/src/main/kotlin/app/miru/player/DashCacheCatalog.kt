// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.dash.manifest.RangedUri
import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Original outer request: key is computed before EOF/merged-load length resolution. */
internal class DashCacheRequest(val uri: String, val position: Long, val length: Long, val key: String) {
    fun sameAs(other: DashCacheRequest) = uri == other.uri && position == other.position && length == other.length && key == other.key
}

/** One exact child range inside an actually emitted (possibly merged) request. */
internal class DashCacheResource(val request: DashCacheRequest, val position: Long, val length: Long, val resourceUri: String? = null) {
    fun resolve(resources: CachedResourceSnapshot): CachedResourceRange? {
        if(position < request.position || position < 0 || length != -1L && length <= 0) return null
        if(length > 0) {
            if(length > Long.MAX_VALUE - position) return null
            if(request.length > 0 && (request.length > Long.MAX_VALUE - request.position || position + length > request.position + request.length)) return null
            return CachedResourceRange(request.key, position, length)
        }
        val actual = resources.rangeFor(request.uri, request.position, request.length) ?: return null
        if(actual.key != request.key || actual.position != request.position || actual.endExclusive <= position) return null
        return CachedResourceRange(request.key, position, actual.endExclusive - position)
    }
    fun sameAs(other: DashCacheResource) = position == other.position && length == other.length && resourceUri == other.resourceUri && request.sameAs(other.request)
}

/** Immutable holder view. Its segment numbers already include Media3's live shift. */
internal interface DashCacheIndex {
    val firstSegment: Long
    val segmentCount: Long
    fun segmentAt(timeUs: Long): Long
    fun startUs(segment: Long): Long
    fun durationUs(segment: Long): Long
    fun address(segment: Long): RangedUri
}

internal class DashCacheRepresentation(
    val trackIndex: Int,
    val representation: Representation,
    val baseUrl: String,
    val index: DashCacheIndex?,
    val holderIdentity: Any,
    val initialization: DashCacheResource?,
    val indexData: DashCacheResource?,
    media: Map<Long, DashCacheResource>,
    val periodDurationUs: Long = androidx.media3.common.C.TIME_UNSET,
) {
    val media: Map<Long, DashCacheResource> = Collections.unmodifiableMap(LinkedHashMap(media))
}

/** Metadata only. No source credentials, IO, secondary manifest fetch or disk writes. */
internal class DashCacheCatalog : Closeable {
    class Entry(val manifest: DashManifest, val periodIndex: Int, val trackGroup: TrackGroup,
        val trackType: Int, val preferredTrackIndex: Int, val revision: Long,
        representations: Collection<DashCacheRepresentation>) {
        val representations = Collections.unmodifiableList(ArrayList(representations))
        fun sameAs(other: Entry) = manifest === other.manifest && periodIndex == other.periodIndex &&
            trackGroup === other.trackGroup && preferredTrackIndex == other.preferredTrackIndex && revision == other.revision &&
            representations.size == other.representations.size && representations.indices.all { index ->
                val one = representations[index]; val two = other.representations[index]
                one.trackIndex == two.trackIndex && one.holderIdentity === two.holderIdentity && one.baseUrl == two.baseUrl
            }
    }
    class Snapshot internal constructor(val revision: Long, entries: Collection<Entry>, proofs: Map<Representation, Int>) {
        val entries = Collections.unmodifiableList(ArrayList(entries))
        private val proofs = Collections.unmodifiableMap(IdentityHashMap(proofs))
        fun independent(representation: Representation) = proofs[representation]?.let { it in 1..2 } == true
    }
    private val gate = Any()
    private var closed = false
    private val entries = LinkedHashMap<Any, Entry>()
    private val proofs = IdentityHashMap<Representation, Int>()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    fun snapshot(): Snapshot = synchronized(gate) { Snapshot(mutableRevision.value, entries.values, proofs) }
    fun update(owner: Any, entry: Entry) = synchronized(gate) {
        if(!closed && entries[owner]?.sameAs(entry) != true) {
            entries[owner] = entry
            if(entries.size > 128) entries.remove(entries.keys.first())
            changed()
        }
    }
    fun remove(owner: Any) = synchronized(gate) { if(entries.remove(owner) != null) changed() }
    fun manifestProofs(values: Map<Representation, Int>) = synchronized(gate) {
        if(!closed) {
            // Keep current/previous immutable representations for active periods
            // during a manifest refresh, with a strict metadata bound.
            if(proofs.size + values.size > 8192) proofs.clear()
            values.entries.take(8192).forEach { proofs[it.key] = it.value }
            changed()
        }
    }
    private fun changed() { mutableRevision.value++ }
    override fun close() = synchronized(gate) {
        if(!closed) { closed = true; entries.clear(); proofs.clear(); changed() }
    }
}
