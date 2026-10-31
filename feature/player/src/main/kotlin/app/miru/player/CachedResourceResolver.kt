// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory

/** A byte dependency lookup, never a claim that these bytes are committed. */
internal interface CachedResourceLookup {
    fun rangeFor(uri: String, position: Long, requestedLength: Long): CachedResourceRange?
    fun accepts(key: String, uri: String, position: Long, requestedLength: Long, endExclusive: Long): Boolean
}

/**
 * Current main-session keys in ORIGINAL request coordinates, including unvisited
 * resources. Lengths come from a coherent cache-metadata snapshot on the tracker
 * worker; neither a span's maximum end nor an EOF-rewritten DataSpec supplies one.
 */
internal class CachedResourceResolver(
    private val observed: CachedResourceSnapshot,
    private val keys: CacheKeyFactory,
    private val contentLength: (String) -> Long,
    private val active: () -> Boolean = { true },
) : CachedResourceLookup {
    override fun rangeFor(uri: String, position: Long, requestedLength: Long): CachedResourceRange? {
        if(!active() || uri.isEmpty() || position < 0 || requestedLength != -1L &&
            (requestedLength <= 0 || requestedLength > Long.MAX_VALUE - position)) return null
        val original = observed.observation(uri, position, requestedLength)
        if(original?.retired == true) return null
        val key = original?.key ?: keys.buildCacheKey(DataSpec.Builder().setUri(uri).setPosition(position).setLength(requestedLength).build())
        val total = contentLength(key)
        if(original != null && !original.opened) return null
        // Future CacheDataSource opens obey the CURRENT persisted resource end.
        // An earlier source's shorter EOF cannot close a newly refilled prefix;
        // without SDK metadata even a full-looking span still needs an EOF probe.
        val length = if(requestedLength > 0) requestedLength else {
            if(total <= position) return null
            total - position
        }
        if(length <= 0 || length > Long.MAX_VALUE - position || total >= 0 && position + length > total) return null
        return CachedResourceRange(key, position, length)
    }

    override fun accepts(key: String, uri: String, position: Long, requestedLength: Long, endExclusive: Long): Boolean {
        if(!active() || !observed.lookup.accepts(key, uri, position, requestedLength, endExclusive)) return false
        val total = contentLength(key)
        return total < 0 || endExclusive <= total
    }
}
