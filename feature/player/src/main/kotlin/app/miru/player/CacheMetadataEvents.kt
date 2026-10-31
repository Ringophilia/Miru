// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import java.util.WeakHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Cache.Listener has no metadata callback (EOF can occur after the final span). */
internal object CacheMetadataEvents {
    private val revisions = WeakHashMap<Cache, MutableStateFlow<Long>>()
    @Synchronized private fun state(cache: Cache) = revisions.getOrPut(cache) { MutableStateFlow(0L) }
    fun revision(cache: Cache): StateFlow<Long> = state(cache)
    fun changed(cache: Cache) { state(cache).update { it + 1 } }
}

/** Signal only actual SDK source activity, without reading metadata or doing IO here. */
internal class CacheMetadataDataSource(private val actual: DataSource, private val cache: Cache) : DataSource by actual {
    override fun open(dataSpec: DataSpec): Long = try { actual.open(dataSpec) }
        finally { CacheMetadataEvents.changed(cache) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = actual.read(buffer, offset, length).also {
        if(it == C.RESULT_END_OF_INPUT) CacheMetadataEvents.changed(cache)
    }
    override fun close() { try { actual.close() } finally { CacheMetadataEvents.changed(cache) } }
}
