// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual SimpleCache/CacheDataSource metadata and committed spans; no video/decode claim. */
@RunWith(AndroidJUnit4::class)
class CachedResourceClosureTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val coordinates = CacheKeyFactory { "${it.uri}|${it.position}|${it.length}" }

    @Test fun aNewOwnerResolvesNativeEofWithTheOriginalUnboundedKey() = disk { d ->
        read(d, coordinates, URL, unknown = true)
        val original = spec(URL)
        val key = coordinates.buildCacheKey(original)
        assertEquals(256L, ContentMetadata.getContentLength(d.cache.getContentMetadata(key)))
        val calls = ArrayList<DataSpec>()
        val resolver = CachedResourceResolver(CachedResourceBindings().snapshot(), CacheKeyFactory { calls += it; coordinates.buildCacheKey(it) },
            { ContentMetadata.getContentLength(d.cache.getContentMetadata(it)) })
        val range = requireNotNull(resolver.rangeFor(URL, 0, -1))
        assertEquals(CachedResourceRange(key, 0, 256), range)
        assertEquals(listOf(-1L), calls.map { it.length })
        assertEquals(FULL, coverage(d, range))
        val bounded = requireNotNull(resolver.rangeFor(URL, 0, 256))
        assertNotEquals(range.key, bounded.key)
        assertTrue(coverage(d, bounded).isEmpty())
    }

    @Test fun failedUnknownPrefixesAndTheirLateEofCannotBecomeWholeResources() = disk { d ->
        val bindings = CachedResourceBindings()
        try { read(d, coordinates, URL, unknown = true, failAt = 64, bindings = bindings); fail("Expected source failure") }
        catch(_: IOException) { }
        val key = coordinates.buildCacheKey(spec(URL))
        assertEquals(64L, d.cache.getCachedSpans(key).sumOf { it.length })
        assertEquals(-1L, ContentMetadata.getContentLength(d.cache.getContentMetadata(key)))
        assertNull(resolver(d, coordinates).rangeFor(URL, 0, -1))
        val late = bindings.begin("$URL&late=1", 0, -1, key)
        bindings.opened(late, -1); bindings.failed(late); bindings.ended(late, 64)
        val mutations = ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, 64) }
        d.cache.applyContentMetadataMutations(key, mutations)
        val current = CachedResourceResolver(bindings.snapshot(), coordinates, { ContentMetadata.getContentLength(d.cache.getContentMetadata(it)) })
        assertNull("Current failure remains terminal even if cache metadata later supplies a length", current.rangeFor("$URL&late=1", 0, -1))
    }

    @Test fun aKnownResponseLengthDoesNotProveAnOnlyPartiallyCommittedBody() = disk { d ->
        read(d, coordinates, URL, stopAt = 64)
        val range = requireNotNull(resolver(d, coordinates).rangeFor(URL, 0, -1))
        assertEquals(256L, range.length)
        assertEquals(64L, d.cache.getCachedSpans(range.key).sumOf { it.length })
        assertTrue(coverage(d, range).isEmpty())
    }

    @Test fun aNewNativeLengthReplacesTheOldEofWithoutTreatingItsPrefixAsComplete() = disk { d ->
        val old = CachedResourceBindings()
        read(d, coordinates, URL, bindings = old)
        val lookup = CachedResourceResolver(old.snapshot(), coordinates,
            { ContentMetadata.getContentLength(d.cache.getContentMetadata(it)) })
        val key = coordinates.buildCacheKey(spec(URL))
        d.cache.removeResource(key)
        put(d, key, 0, 256)
        assertNull("Without native length, a full-looking span would still require a network EOF probe", lookup.rangeFor(URL, 0, -1))
        d.cache.removeResource(key)
        read(d, coordinates, URL, stopAt = 256, bodySize = 512)
        val prefix = requireNotNull(lookup.rangeFor(URL, 0, -1))
        assertEquals(512L, prefix.length)
        assertTrue("An old 256-byte EOF cannot prove the new 512-byte resource", coverage(d, prefix).isEmpty())
        read(d, coordinates, URL, bodySize = 512)
        assertEquals(FULL, coverage(d, requireNotNull(lookup.rangeFor(URL, 0, -1))))
    }

    @Test fun mergedChildrenKeepTheirOpaqueParentKeyAndOneByteHoles() = disk { d ->
        val bindings = CachedResourceBindings()
        bindings.opened(bindings.begin(URL, 0, -1, "actual-parent"), -1)
        put(d, "actual-parent", 0, 9); put(d, "actual-parent", 10, 10); put(d, "actual-parent", 30, 10)
        val resolver = CachedResourceResolver(bindings.snapshot(), CacheKeyFactory { error("Must retain actual parent key") }, { -1 })
        val parent = DashCacheRequest(URL, 0, -1, "actual-parent")
        val first = requireNotNull(DashCacheResource(parent, 0, 10).resolve(resolver))
        val third = requireNotNull(DashCacheResource(parent, 30, 10).resolve(resolver))
        assertTrue(coverage(d, first).isEmpty())
        assertEquals(FULL, coverage(d, third))
        put(d, "actual-parent", 9, 1)
        assertEquals(FULL, coverage(d, first))
        val bounded = DashCacheResource(DashCacheRequest(URL, 0, 20, "actual-parent"), 19, 2)
        assertNull(bounded.resolve(resolver))
        val shortMetadata = CachedResourceResolver(bindings.snapshot(), coordinates, { 35 })
        assertNull(DashCacheResource(parent, 30, 10).resolve(shortMetadata))
    }

    @Test fun unknownResourcesWakeAnEmptyIndexOnNativeCommitAndRevokeOnRemoval() = disk { d ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val tracker = CachedTimelineTracker(d.cache, scope)
        val builds = AtomicInteger()
        try {
            tracker.replaceIndexed(10_000_000, { true }) { _, lengths ->
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                builds.incrementAndGet()
                val range = CachedResourceResolver(CachedResourceBindings().snapshot(), coordinates, lengths).rangeFor(URL, 0, -1)
                range?.let { listOf(track(it)) } ?: emptyList()
            }
            await { builds.get() >= 2 }
            assertTrue(tracker.ranges.value.isEmpty())
            read(d, coordinates, URL, unknown = true)
            await { tracker.ranges.value == FULL }
            val key = coordinates.buildCacheKey(spec(URL))
            d.cache.getCachedSpans(key).forEach(d.cache::removeSpan)
            await { tracker.ranges.value.isEmpty() }
            val count = builds.get()
            put(d, key, 0, 127); put(d, key, 128, 128)
            await { builds.get() > count }
            assertTrue(tracker.ranges.value.isEmpty())
            assertEquals("Deleting the last span also discards the SDK length", -1L,
                ContentMetadata.getContentLength(d.cache.getContentMetadata(key)))
            // A native retry fills the one-byte hole, reads the cached suffix,
            // then discovers EOF. The final metadata update has no new span.
            read(d, coordinates, URL, unknown = true)
            await { tracker.ranges.value == FULL }
        } finally { tracker.close(); runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() } }
    }

    @Test fun discoveringFourPrerequisitesDoesNotMissWritesBeforeListenerRegistration() = disk { d ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val tracker = CachedTimelineTracker(d.cache, scope)
        val uris = (0..3).map { "$URL&dependency=$it" }
        val builds = AtomicInteger()
        try {
            tracker.replaceIndexed(10_000_000, { true }) { _, lengths ->
                builds.incrementAndGet()
                val lookup = CachedResourceResolver(CachedResourceBindings().snapshot(), coordinates, lengths)
                val all = ArrayList<CachedResourceRange>()
                for(uri in uris) all += lookup.rangeFor(uri, 0, -1) ?: return@replaceIndexed emptyList()
                listOf(CachedTrackCoverage("required", listOf(CachedMediaInterval(FULL.single(), all))))
            }
            await { builds.get() >= 2 }
            // Several writes may finish before the previous dependency reveals
            // the next key. Registration must recapture their real metadata/spans.
            uris.forEach { read(d, coordinates, it, unknown = true) }
            await { tracker.ranges.value == FULL }
            val key = coordinates.buildCacheKey(spec(uris[1]))
            d.cache.getCachedSpans(key).forEach(d.cache::removeSpan)
            await { tracker.ranges.value.isEmpty() }
        } finally { tracker.close(); runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() } }
    }

    @Test fun currentHeaderPartitionsAndSourceRetirementGuardPersistentLengths() = disk { d ->
        val item = MediaItem.fromUri(URL)
        val first = PlaybackSourceSession(context, 1, item, mapOf("Cookie" to "owned-a"), d.cache)
        val second = PlaybackSourceSession(context, 2, item, mapOf("Cookie" to "owned-b"), d.cache)
        try {
            read(d, first.cacheKeyFactory, URL, unknown = true)
            val metadata: (String) -> Long = { ContentMetadata.getContentLength(d.cache.getContentMetadata(it)) }
            val own = first.cacheResourceResolver(first.cacheResourceSnapshot(), metadata)
            assertEquals(FULL, coverage(d, requireNotNull(own.rangeFor(URL, 0, -1))))
            assertNull(second.cacheResourceResolver(second.cacheResourceSnapshot(), metadata).rangeFor(URL, 0, -1))
            val privateSession = PlaybackSourceSession(context, 3, item, emptyMap())
            val offline = PlaybackSourceSession(context, 4, item, emptyMap(), d.cache,
                DataSource.Factory { ByteArrayDataSource(ByteArray(256)) })
            try {
                for(session in listOf(privateSession, offline)) assertNull(session.cacheResourceResolver(session.cacheResourceSnapshot()) {
                    error("Private/offline must not query stream-cache metadata")
                }.rangeFor(URL, 0, -1))
            } finally { privateSession.close(); offline.close() }
            first.close()
            assertNull(own.rangeFor(URL, 0, -1))
        } finally { first.close(); second.close() }
    }

    @Test fun replacingASelectionCannotPublishAnOldMetadataCalculation() = disk { d ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val tracker = CachedTimelineTracker(d.cache, scope)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        put(d, "old", 0, 10); put(d, "new", 0, 10)
        try {
            tracker.replaceIndexed(10_000_000, { true }) { _, _ ->
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                listOf(track(CachedResourceRange("old", 0, 10)))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val next = CachedTimeRange(8_000_000, 10_000_000)
            tracker.replace(listOf(CachedTrackCoverage("new", listOf(CachedMediaInterval(next, listOf(CachedResourceRange("new", 0, 10)))))), 10_000_000)
            release.countDown()
            await { tracker.ranges.value == listOf(next) }
            tracker.close()
            assertTrue(tracker.ranges.value.isEmpty())
        } finally { release.countDown(); tracker.close(); runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() } }
    }

    private fun resolver(d: Disk, keys: CacheKeyFactory) = CachedResourceResolver(CachedResourceBindings().snapshot(), keys,
        { ContentMetadata.getContentLength(d.cache.getContentMetadata(it)) })
    private fun track(range: CachedResourceRange) = CachedTrackCoverage("required", listOf(CachedMediaInterval(FULL.single(), listOf(range))))
    private fun coverage(d: Disk, range: CachedResourceRange) = CachedTimelineCoverage.compute(listOf(track(range)),
        mapOf(range.key to d.cache.getCachedSpans(range.key).map { CachedResourceRange(it.key, it.position, it.length) }), 10_000_000)
    private fun spec(uri: String) = DataSpec.Builder().setUri(uri).build()

    private fun read(d: Disk, keys: CacheKeyFactory, uri: String, unknown: Boolean = false, stopAt: Int = Int.MAX_VALUE,
        failAt: Int = Int.MAX_VALUE, bindings: CachedResourceBindings = CachedResourceBindings(), bodySize: Int = 256) {
        val cached = CacheDataSource.Factory().setCache(d.cache).setCacheKeyFactory(keys).setUpstreamDataSourceFactory {
            val actual = ByteArrayDataSource(ByteArray(bodySize) { it.toByte() })
            object : DataSource by actual {
                var count = 0
                override fun open(spec: DataSpec): Long { val length = actual.open(spec); return if(unknown) -1 else length }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if(count >= failAt) throw IOException("Owned controlled read failure")
                    return actual.read(buffer, offset, minOf(length, failAt - count)).also { if(it > 0) count += it }
                }
            }
        }.createDataSource()
        val source = CacheBindingDataSource(CacheMetadataDataSource(cached, d.cache), keys, bindings)
        try {
            source.open(spec(uri))
            val bytes = ByteArray(16); var count = 0
            while(true) {
                val n = source.read(bytes, 0, bytes.size)
                if(n < 0) break
                count += n
                if(count >= stopAt) break
            }
        } finally { source.close() }
    }
    private fun put(d: Disk, key: String, position: Long, length: Int) {
        val hole = requireNotNull(d.cache.startReadWriteNonBlocking(key, position, length.toLong()))
        check(hole.isHoleSpan)
        try {
            val file = d.cache.startFile(key, position, length.toLong())
            file.writeBytes(ByteArray(length)); d.cache.commitFile(file, length.toLong())
        } finally { d.cache.releaseHoleSpan(hole) }
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while(!predicate()) { check(SystemClock.elapsedRealtime() < deadline) { "Cache closure timed out" }; SystemClock.sleep(20) }
    }
    private fun disk(block: (Disk) -> Unit) = runBlocking { withContext(Dispatchers.IO) { Disk().use(block) } }
    private inner class Disk : Closeable {
        val root = File(context.cacheDir, "resource-closure-${UUID.randomUUID()}").apply { mkdirs() }
        @Suppress("DEPRECATION") val cache = SimpleCache(File(root, "stream"), NoOpCacheEvictor())
        override fun close() { cache.release(); check(root.deleteRecursively()) }
    }
    companion object {
        private const val URL = "https://owned.invalid/resource?token=fixture"
        private val FULL = listOf(CachedTimeRange(0, 10_000_000))
    }
}
