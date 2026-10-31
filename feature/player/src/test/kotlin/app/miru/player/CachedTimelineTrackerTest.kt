// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.TreeSet
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CachedTimelineTrackerTest {
    @Test fun initialCommittedSnapshotAndRemovalPreserveTheActualTimeHole() = runTest {
        val store = FakeCache()
        store.put("main", 0, 40)
        store.put("main", 80, 40)
        val tracker = CachedTimelineTracker(store.cache, this, StandardTestDispatcher(testScheduler))
        tracker.replace(listOf(track("main")))
        assertTrue("Replacement must not query disk on the caller", store.queriedKeys.isEmpty())
        runCurrent()
        assertEquals(listOf(time(0, 4), time(8, 12)), tracker.ranges.value)
        assertEquals(setOf("main"), store.listeners.keys)
        store.put("main", 40, 40)
        runCurrent()
        assertEquals(listOf(time(0, 12)), tracker.ranges.value)
        store.remove("main", 40)
        runCurrent()
        assertEquals(listOf(time(0, 4), time(8, 12)), tracker.ranges.value)
        store.clear("main")
        runCurrent()
        assertTrue(tracker.ranges.value.isEmpty())
        tracker.close(); runCurrent()
        assertTrue(store.listeners.isEmpty())
    }

    @Test fun holesUnknownLengthsAndOverflowingSpansAreNotCommittedBytes() = runTest {
        val store = FakeCache()
        store.spans["main"] = mutableListOf(CacheSpan("main", 0, 120),
            CacheSpan("main", 0, -1, 0, File("not-open-ended-data")),
            CacheSpan("main", 1, Long.MAX_VALUE, 0, File("overflow")))
        val tracker = CachedTimelineTracker(store.cache, this, StandardTestDispatcher(testScheduler))
        tracker.replace(listOf(track("main")))
        runCurrent()
        assertTrue(tracker.ranges.value.isEmpty())
        tracker.close(); runCurrent()
    }

    @Test fun sourceAndHeaderReplacementClearImmediatelyAndWatchOnlyCurrentKeys() = runTest {
        val store = FakeCache()
        store.put("main-auth-A", 0, 120)
        store.put("preview-auth-B", 0, 120)
        val tracker = CachedTimelineTracker(store.cache, this, StandardTestDispatcher(testScheduler))
        tracker.replace(listOf(track("main-auth-A")))
        runCurrent()
        assertEquals(listOf(time(0, 12)), tracker.ranges.value)
        assertEquals(setOf("main-auth-A"), store.queriedKeys.toSet())
        tracker.replace(listOf(track("new-main-auth-C")))
        assertTrue(tracker.ranges.value.isEmpty())
        runCurrent()
        assertEquals(setOf("new-main-auth-C"), store.listeners.keys)
        assertTrue(tracker.ranges.value.isEmpty())
        store.put("new-main-auth-C", 80, 40)
        runCurrent()
        assertEquals(listOf(time(8, 12)), tracker.ranges.value)
        tracker.close(); runCurrent()
    }

    @Test fun replacingSelectionDuringOldSnapshotNeverPublishesOldCoverage() = runTest {
        val store = FakeCache()
        store.put("old", 0, 120)
        store.put("new", 80, 40)
        val tracker = CachedTimelineTracker(store.cache, this, StandardTestDispatcher(testScheduler))
        val observed = mutableListOf<List<CachedTimeRange>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            tracker.ranges.collect { observed.add(it) }
        }
        store.beforeSnapshot = { key ->
            if(key == "old") {
                store.beforeSnapshot = null
                tracker.replace(listOf(track("new")))
            }
        }
        tracker.replace(listOf(track("old")))
        runCurrent()
        assertEquals(listOf(time(8, 12)), tracker.ranges.value)
        assertFalse(observed.contains(listOf(time(0, 12))))
        assertEquals(setOf("new"), store.listeners.keys)
        tracker.close(); runCurrent()
    }

    @Test fun closeRejectsQueuedAndLateCallbacksAndCannotBeRebound() = runTest {
        val store = FakeCache()
        store.put("main", 0, 120)
        val tracker = CachedTimelineTracker(store.cache, this, StandardTestDispatcher(testScheduler))
        tracker.replace(listOf(track("main")))
        runCurrent()
        val oldListener = store.listeners.getValue("main")
        store.put("main", 120, 1)
        tracker.close()
        assertTrue(tracker.ranges.value.isEmpty())
        tracker.replace(listOf(track("later")))
        oldListener.onSpanAdded(store.cache, cached("main", 0, 120))
        runCurrent()
        tracker.close()
        assertTrue(tracker.ranges.value.isEmpty())
        assertTrue(store.listeners.isEmpty())
        assertFalse(store.queriedKeys.contains("later"))
    }

    @Test fun parentCancellationClearsCoverageAndRemovesOwnedSubscriptions() = runTest {
        val store = FakeCache()
        store.put("main", 0, 120)
        val parent = SupervisorJob()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tracker = CachedTimelineTracker(store.cache, CoroutineScope(parent + dispatcher), dispatcher)
        tracker.replace(listOf(track("main")))
        runCurrent()
        assertFalse(tracker.ranges.value.isEmpty())
        parent.cancel()
        runCurrent()
        assertTrue(tracker.ranges.value.isEmpty())
        assertTrue(store.listeners.isEmpty())
        tracker.close()
    }

    @Test fun evictionDuringCoverageCalculationRejectsTheObsoleteByteSnapshot() = runTest {
        val store = FakeCache()
        store.put("main", 0, 120)
        var first = true
        val tracker = CachedTimelineTracker(store.cache, this, StandardTestDispatcher(testScheduler)) { tracks, spans, duration ->
            if(first) { first = false; store.clear("main") }
            CachedTimelineCoverage.compute(tracks, spans, duration)
        }
        val observed = mutableListOf<List<CachedTimeRange>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { tracker.ranges.collect { observed.add(it) } }
        tracker.replace(listOf(track("main")))
        runCurrent()
        assertTrue(tracker.ranges.value.isEmpty())
        assertFalse("An evicted snapshot cannot flash old full coverage", observed.contains(listOf(time(0, 12))))
        tracker.close(); runCurrent()
    }

    @Test fun cancellationDuringSynchronousCalculationCannotPublishItsResult() = runTest {
        val store = FakeCache()
        store.put("main", 0, 120)
        val parent = SupervisorJob()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tracker = CachedTimelineTracker(store.cache, CoroutineScope(parent + dispatcher), dispatcher) { tracks, spans, duration ->
            parent.cancel()
            CachedTimelineCoverage.compute(tracks, spans, duration)
        }
        val observed = mutableListOf<List<CachedTimeRange>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { tracker.ranges.collect { observed.add(it) } }
        tracker.replace(listOf(track("main")))
        runCurrent()
        assertTrue(tracker.ranges.value.isEmpty())
        assertTrue(observed.all { it.isEmpty() })
        assertTrue(store.listeners.isEmpty())
        tracker.close()
    }

    @Test fun realWorkerRejectsOldSnapshotWhenAnotherThreadEvictsAndReplaces() = runBlocking {
        val store = FakeCache()
        store.put("old", 0, 120)
        store.put("new", 80, 40)
        val captured = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val waitExpired = AtomicBoolean(false)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tracker = CachedTimelineTracker(store.cache, owner) { tracks, spans, duration ->
            if(first.compareAndSet(true, false)) {
                captured.countDown()
                val released = resume.await(5, TimeUnit.SECONDS)
                if(!released) waitExpired.set(true)
                check(released) { "Test did not release the captured snapshot" }
            }
            CachedTimelineCoverage.compute(tracks, spans, duration)
        }
        val observed = Collections.synchronizedList(mutableListOf<List<CachedTimeRange>>())
        val observer = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        observer.launch { tracker.ranges.collect { observed.add(it) } }
        try {
            tracker.replace(listOf(track("old")))
            assertTrue("A real IO worker must reach the captured snapshot", captured.await(5, TimeUnit.SECONDS))
            // The IO worker is still calculating. Neither operation can depend
            // on that worker releasing the Cache or selection monitor.
            store.clear("old")
            tracker.replace(listOf(track("new")))
            assertFalse("Eviction/replacement cannot wait for the blocked calculator", waitExpired.get())
            resume.countDown()
            withTimeout(5_000) { tracker.ranges.first { it == listOf(time(8, 12)) } }
            assertFalse(observed.contains(listOf(time(0, 12))))
            synchronized(store.cache) { assertEquals(setOf("new"), store.listeners.keys) }
        } finally {
            resume.countDown()
            tracker.close()
            owner.cancel()
            observer.cancel()
            withTimeout(5_000) { owner.coroutineContext[Job]!!.cancelAndJoin() }
            observer.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test fun cacheFailureClearsResultsAndLaterIndexReplacementCanRetry() = runTest {
        val store = FakeCache()
        store.put("main", 0, 120)
        val tracker = CachedTimelineTracker(store.cache, this, StandardTestDispatcher(testScheduler))
        tracker.replace(listOf(track("main")))
        runCurrent()
        store.failNextSnapshot = true
        store.put("main", 120, 1)
        runCurrent()
        assertTrue(tracker.ranges.value.isEmpty())
        assertTrue(store.listeners.isEmpty())
        tracker.replace(listOf(track("main")))
        runCurrent()
        assertEquals(listOf(time(0, 12)), tracker.ranges.value)
        tracker.close(); runCurrent()
    }

    @Test fun replacingWithNoSelectedTracksDetachesAndTouchesDoNotReindex() = runTest {
        val store = FakeCache()
        store.put("main", 0, 120)
        val tracker = CachedTimelineTracker(store.cache, this, StandardTestDispatcher(testScheduler))
        tracker.replace(listOf(track("main")), 10_000_000)
        runCurrent()
        assertEquals(listOf(time(0, 10)), tracker.ranges.value)
        val queries = store.queriedKeys.size
        store.listeners.getValue("main").onSpanTouched(store.cache,
            cached("main", 0, 120), cached("main", 0, 120))
        runCurrent()
        assertEquals(queries, store.queriedKeys.size)
        tracker.replace(emptyList())
        assertTrue(tracker.ranges.value.isEmpty())
        runCurrent()
        assertTrue(store.listeners.isEmpty())
        tracker.close(); runCurrent()
    }

    private fun time(start: Long, end: Long) = CachedTimeRange(start * 1_000_000, end * 1_000_000)
    private fun track(key: String) = CachedTrackCoverage("video:$key", (0L..2L).map {
        CachedMediaInterval(time(it * 4, (it + 1) * 4), listOf(CachedResourceRange(key, it * 40, 40)))
    })

    /** Implements only the Cache contract used here, without Android storage or IO. */
    private class FakeCache {
        val spans = mutableMapOf<String, MutableList<CacheSpan>>()
        val listeners = mutableMapOf<String, Cache.Listener>()
        val queriedKeys = mutableListOf<String>()
        var beforeSnapshot: ((String) -> Unit)? = null
        var failNextSnapshot = false
        val cache: Cache = Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { proxy, method, args ->
            when(method.name) {
                "addListener" -> {
                    val key = args!![0] as String
                    check(listeners.put(key, args[1] as Cache.Listener) == null)
                    TreeSet(spans[key].orEmpty())
                }
                "removeListener" -> {
                    val key = args!![0] as String
                    check(listeners[key] == null || listeners[key] === args[1])
                    listeners.remove(key)
                    null
                }
                "getCachedSpans" -> {
                    val key = args!![0] as String
                    queriedKeys.add(key)
                    beforeSnapshot?.invoke(key)
                    if(failNextSnapshot) { failNextSnapshot = false; throw IOException("snapshot unavailable") }
                    TreeSet(spans[key].orEmpty())
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "toString" -> "FakeCache"
                else -> error("Unexpected Cache call: ${method.name}")
            }
        } as Cache

        fun put(key: String, position: Long, length: Long) = synchronized(cache) {
            val span = cached(key, position, length)
            spans.getOrPut(key) { mutableListOf() }.add(span)
            listeners[key]?.onSpanAdded(cache, span)
        }
        fun remove(key: String, position: Long) = synchronized(cache) {
            val span = spans.getValue(key).first { it.position == position }
            spans.getValue(key).remove(span)
            listeners[key]?.onSpanRemoved(cache, span)
        }
        fun clear(key: String) = synchronized(cache) { spans[key].orEmpty().map { it.position }.forEach { remove(key, it) } }
    }

    companion object {
        private fun cached(key: String, position: Long, length: Long) =
            CacheSpan(key, position, length, 0, File("committed-$position"))
    }
}
