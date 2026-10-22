// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.CacheEvictor
import java.io.File

/** Single process-wide owner. Construct on IO; private playback never asks for this instance. */
object PlaybackCache {
    @Volatile private var instance: SimpleCache? = null
    private var desiredLimit = 1024L * 1024 * 1024
    private val policy = AdjustableEvictor(1024L * 1024 * 1024)
    @Synchronized fun get(context: Context): SimpleCache {
        val cache = instance ?: SimpleCache(File(context.cacheDir, "media"), policy, StandaloneDatabaseProvider(context.applicationContext)).also { instance = it }
        policy.resize(cache, desiredLimit)
        return cache
    }
    @Synchronized fun setLimit(limitBytes: Long) { desiredLimit = limitBytes.coerceAtLeast(1); instance?.let { policy.resize(it, desiredLimit) } }
    @Synchronized fun limitBytes(): Long = desiredLimit
    fun usedBytes(context: Context): Long = get(context).cacheSpace
    fun clear(context: Context) { val cache = get(context); synchronized(cache) { cache.keys.toList().forEach(cache::removeResource) } }
}

/** Adapter reuses Media3's LRU implementation and changes its budget under SimpleCache's monitor. */
private class AdjustableEvictor(private var limit: Long) : CacheEvictor {
    private var delegate = LeastRecentlyUsedCacheEvictor(limit)
    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() = Unit
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) = delegate.onStartFile(cache, key, position, length)
    override fun onSpanAdded(cache: Cache, span: CacheSpan) = delegate.onSpanAdded(cache, span)
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) = delegate.onSpanRemoved(cache, span)
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = delegate.onSpanTouched(cache, oldSpan, newSpan)
    fun resize(cache: Cache, bytes: Long) = synchronized(cache) {
        val next = bytes.coerceAtLeast(1)
        if(limit == next) return@synchronized
        val spans = cache.keys.flatMap { cache.getCachedSpans(it) }.sortedBy { it.lastTouchTimestamp }
        limit = next; delegate = LeastRecentlyUsedCacheEvictor(next)
        // Ascending touch time ensures an eviction only removes spans already registered in the new LRU.
        spans.forEach { delegate.onSpanAdded(cache, it) }
    }
}
