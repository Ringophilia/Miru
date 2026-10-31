// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Watches only the keys in an exact, source-owned media index. The format adapters
 * supply decode/init/key dependencies and selected track identity; neither load
 * completion nor bufferedPosition supplies proof of disk availability here.
 *
 * Callbacks never query disk: they enqueue invalidation and withdraw removed
 * coverage while Cache invokes listeners under its monitor.
 * Registration, coherent committed-span snapshots and cleanup run on IO. Removal
 * clears publication synchronously under the small selection gate. No path holds
 * that gate while acquiring the cache monitor, so lock order stays acyclic.
 */
internal class CachedTimelineTracker(
    private val cache: Cache,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val calculate: (Collection<CachedTrackCoverage>, Map<String, Collection<CachedResourceRange>>, Long?) -> List<CachedTimeRange> =
        CachedTimelineCoverage::compute,
) : Closeable {
    private class Selection(tracks: Collection<CachedTrackCoverage>, val durationUs: Long?, val isCurrent: () -> Boolean) {
        val tracks: List<CachedTrackCoverage> = Collections.unmodifiableList(ArrayList(tracks))
        val keys: Set<String> = this.tracks.asSequence().flatMap { it.intervals.asSequence() }
            .flatMap { it.requiredBytes.asSequence() }.map { it.key }.toSet()

        init { require(durationUs == null || durationUs >= 0) }
    }

    private val gate = Any()
    private var selection: Selection? = null
    private var closed = false
    private val cacheRevision = AtomicLong()
    private val invalidations = Channel<Unit>(Channel.CONFLATED)
    private val mutable = MutableStateFlow<List<CachedTimeRange>>(emptyList())
    val ranges = mutable.asStateFlow()
    private val listener = object : Cache.Listener {
        override fun onSpanAdded(cache: Cache, span: CacheSpan) { invalidate() }
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) { invalidate(clear = true) }
        // Touch replaces metadata for the same committed byte extent.
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = Unit
    }

    private val worker = scope.launch(ioDispatcher) {
        val registered = LinkedHashSet<String>()
        try {
            for(ignored in invalidations) {
                val current = synchronized(gate) { if(closed) null else selection } ?: continue
                var capturedRevision = cacheRevision.get()
                val result = try {
                    val spans = synchronized(cache) {
                        (registered - current.keys).forEach { key ->
                            cache.removeListener(key, listener)
                            registered.remove(key)
                        }
                        (current.keys - registered).forEach { key ->
                            // Retain cleanup ownership even if an implementation
                            // throws after partially registering the listener.
                            registered.add(key)
                            cache.addListener(key, listener)
                        }
                        current.keys.associateWith { key ->
                            cache.getCachedSpans(key).mapNotNull { span ->
                                if(span.key != key || !span.isCached || span.position < 0 ||
                                    span.length <= 0 || span.position > Long.MAX_VALUE - span.length) null
                                else CachedResourceRange(key, span.position, span.length)
                            }
                        }.also { capturedRevision = cacheRevision.get() }
                    }
                    calculate(current.tracks, spans, current.durationUs).also { coroutineContext.ensureActive() }
                } catch(cancelled: CancellationException) {
                    throw cancelled
                } catch(_: Exception) {
                    // Fail closed, and permit a later index replacement to retry
                    // registration after a transient cache failure.
                    detach(registered)
                    emptyList()
                }
                synchronized(gate) {
                    // A replaced selection/source can never receive old IO results.
                    if(!closed && selection === current && cacheRevision.get() == capturedRevision && current.isCurrent()) mutable.value = result
                }
            }
        } finally {
            synchronized(gate) { closed = true; selection = null; mutable.value = emptyList() }
            invalidations.close()
            withContext(NonCancellable + ioDispatcher) { detach(registered) }
        }
    }

    private fun invalidate(clear: Boolean = false) = synchronized(gate) {
        cacheRevision.incrementAndGet()
        if(clear) mutable.value = emptyList()
        invalidations.trySend(Unit)
    }

    /** Replace the complete current window/period/representation selection. */
    fun replace(tracks: Collection<CachedTrackCoverage>, durationUs: Long? = null, isCurrent: () -> Boolean = { true }) {
        val next = Selection(tracks, durationUs, isCurrent)
        synchronized(gate) {
            if(closed) return
            selection = next
            // Clear synchronously before IO so old auth/track/window coverage is
            // never displayed while the replacement index is being computed.
            mutable.value = emptyList()
            invalidations.trySend(Unit)
        }
    }

    override fun close() {
        val cancel = synchronized(gate) {
            if(closed) false else {
                closed = true
                selection = null
                mutable.value = emptyList()
                true
            }
        }
        if(cancel) { invalidations.close(); worker.cancel() }
    }

    private fun detach(registered: MutableSet<String>) = synchronized(cache) {
        registered.toList().forEach { key ->
            try { cache.removeListener(key, listener) } catch(_: Exception) { /* Cache is unavailable. */ }
            registered.remove(key)
        }
    }
}
