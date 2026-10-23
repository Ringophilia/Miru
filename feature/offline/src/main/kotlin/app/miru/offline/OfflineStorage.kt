// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.offline

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/** One owner per process; offline data is never part of the streaming LRU or its clear operation. */
internal class OfflineStorage private constructor(context: Context) {
    val database = StandaloneDatabaseProvider(context)
    val cache = SimpleCache(File(context.filesDir, "offline"), NoOpCacheEvictor(), database)
    fun cached(id: String): CacheDataSource.Factory = CacheDataSource.Factory().setCache(cache)
        .setCacheKeyFactory { spec -> prefix(id) + spec.uri.toString() }
        .setCacheWriteDataSinkFactory(null)
        .setUpstreamDataSourceFactory(null)
    fun remove(id: String) { cache.keys.filter { it.startsWith(prefix(id)) }.forEach(cache::removeResource) }
    companion object {
        @Volatile private var instance: OfflineStorage? = null
        @Synchronized fun get(context: Context) = instance ?: OfflineStorage(context.applicationContext).also { instance = it }
        fun prefix(id: String) = "miru-offline:$id:"
    }
}
