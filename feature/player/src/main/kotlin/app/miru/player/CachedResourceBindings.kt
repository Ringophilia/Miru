// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An immutable lookup of actual source opens, not a cache-availability assertion.
 * The caller must still prove that every required byte is committed to disk.
 * URLs stay in memory; neither snapshots nor request identities stringify them.
 */
class CachedResourceSnapshot internal constructor(
    val revision: Long,
    private val resources: Map<ResourceRequest, CachedResourceRange?>,
) {
    val resourceCount: Int get() = resources.size

    /** Match the original request, BEFORE replacing LENGTH_UNSET with an EOF length. */
    fun rangeFor(uri: String, position: Long = 0, requestedLength: Long = -1): CachedResourceRange? =
        resources[ResourceRequest(uri, position, requestedLength)]
}

internal data class ResourceRequest(val uri: String, val position: Long, val length: Long) {
    override fun toString(): String = "ResourceRequest(position=$position, length=$length)"
}

/**
 * Bounded, source-owned metadata. A newer open invalidates the old observation
 * synchronously; delayed opens/EOF/failures cannot mutate its successor. No read
 * count, HTTP length or EOF is ever treated as a committed cache span.
 */
internal class CachedResourceBindings(private val capacity: Int = 4096) : Closeable {
    internal class Observation internal constructor(
        internal val request: ResourceRequest,
        internal val key: String,
    )
    private class Entry(val observation: Observation, var range: CachedResourceRange? = null)
    private val gate = Any()
    private val entries = LinkedHashMap<ResourceRequest, Entry>()
    private var closed = false
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    init { require(capacity > 0) }

    fun snapshot(): CachedResourceSnapshot = synchronized(gate) {
        CachedResourceSnapshot(mutableRevision.value, entries.mapValues { it.value.range })
    }

    fun begin(uri: String, position: Long, length: Long, key: String): Observation? = synchronized(gate) {
        if(closed || uri.isEmpty() || key.isEmpty() || position < 0 ||
            (length != -1L && (length <= 0 || length > Long.MAX_VALUE - position))) return null
        val request = ResourceRequest(uri, position, length)
        val observation = Observation(request, key)
        // Reopening makes this the newest entry for both ownership and eviction.
        entries.remove(request)
        entries[request] = Entry(observation)
        if(entries.size > capacity) entries.remove(entries.keys.first())
        changed()
        observation
    }

    fun opened(observation: Observation?, resolvedLength: Long) = update(observation) { current ->
        val requested = current.observation.request.length
        // A bounded request needs ALL requested bytes, even if a response claims
        // a shorter body. Do not shrink an init/segment requirement to fit it.
        val length = when {
            requested > 0 && resolvedLength >= 0 && resolvedLength < requested -> -1L
            requested > 0 -> requested
            else -> resolvedLength
        }
        current.range = range(current.observation, length)
    }

    fun ended(observation: Observation?, bytesRead: Long) = update(observation) { current ->
        val requested = current.observation.request.length
        val expected = current.range?.length
        current.range = when {
            bytesRead <= 0 -> null
            requested > 0 -> if(bytesRead == requested) range(current.observation, requested) else null
            expected != null -> if(bytesRead == expected) current.range else null
            else -> range(current.observation, bytesRead)
        }
    }

    fun failed(observation: Observation?) {
        if(observation == null) return
        synchronized(gate) {
            if(closed || entries[observation.request]?.observation !== observation) return
            // Failure retires this open, not just its current length. A late EOF
            // from that same reader cannot promote its prefix to a full segment.
            entries.remove(observation.request)
            changed()
        }
    }

    private fun range(observation: Observation, length: Long): CachedResourceRange? {
        val position = observation.request.position
        return if(length > 0 && length <= Long.MAX_VALUE - position)
            CachedResourceRange(observation.key, position, length) else null
    }

    private inline fun update(observation: Observation?, block: (Entry) -> Unit) {
        if(observation == null) return
        synchronized(gate) {
            if(closed) return
            val entry = entries[observation.request] ?: return
            if(entry.observation !== observation) return
            val previous = entry.range
            block(entry)
            if(previous != entry.range) changed()
        }
    }

    private fun changed() { mutableRevision.value++ }

    override fun close() = synchronized(gate) {
        if(!closed) { closed = true; entries.clear(); changed() }
    }
}
