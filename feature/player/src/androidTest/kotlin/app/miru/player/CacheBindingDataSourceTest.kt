// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SimpleCache files and production DataSource/session paths; no time mapper or UI claim. */
@RunWith(AndroidJUnit4::class)
class CacheBindingDataSourceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val body = ByteArray(96_000) { ((it * 31 + it / 17) % 256).toByte() }

    @Test fun originalRequestKeyAndPositionSurviveEofAndUriPositionOffset() = Disk().use { disk ->
        val url = "https://media.invalid/opaque?token=synthetic"
        val session = session(url, disk.cache)
        val spec = DataSpec.Builder().setUri(url).setPosition(33).setLength(173).setUriPositionOffset(700).build()
        val bindings = CachedResourceBindings()
        val reader = reader(session, disk, bindings)
        try {
            reader.open(spec)
            assertArrayEquals(body.copyOfRange(33, 206), consume(reader))
        } finally { reader.close() }
        val actual = requireNotNull(bindings.snapshot().rangeFor(url, 33, 173))
        assertEquals(session.cacheKey(spec), actual.key)
        assertEquals(33L, actual.position)
        assertEquals(173L, actual.length)
        assertTrue(disk.cache.isCached(actual.key, 33, 173))
        assertFalse(disk.cache.isCached(actual.key, 733, 173))
        assertEquals(setOf(actual.key), disk.cache.keys)
        session.close()
    }

    @Test fun unknownLengthEofKeepsTheKeyFromBeforeLengthDiscovery() = Disk().use { disk ->
        val url = "https://media.invalid/opaque?token=synthetic"
        val session = session(url, disk.cache)
        val spec = DataSpec.Builder().setUri(url).build()
        val bindings = CachedResourceBindings()
        val reader = reader(session, disk, bindings, unknownLength = true)
        try {
            assertEquals(C.LENGTH_UNSET.toLong(), reader.open(spec))
            assertNull(bindings.snapshot().rangeFor(url))
            assertArrayEquals(body, consume(reader))
        } finally { reader.close() }
        val range = requireNotNull(bindings.snapshot().rangeFor(url))
        assertEquals(body.size.toLong(), range.length)
        assertEquals(session.cacheKey(spec), range.key)
        assertNotEquals("Reconstructing DataSpec after EOF would select a different key",
            session.cacheKey(spec.buildUpon().setLength(body.size.toLong()).build()), range.key)
        assertTrue(disk.cache.isCached(range.key, 0, range.length))
        assertNull(bindings.snapshot().rangeFor(url, requestedLength = range.length))
        session.close()
    }

    @Test fun partialUnknownCloseCommitsBytesWithoutInventingResourceLength() = Disk().use { disk ->
        val url = "https://media.invalid/opaque?token=synthetic"
        val session = session(url, disk.cache)
        val spec = DataSpec.Builder().setUri(url).build()
        val bindings = CachedResourceBindings()
        val reader = reader(session, disk, bindings, unknownLength = true)
        try {
            assertEquals(-1L, reader.open(spec))
            assertEquals(128, reader.read(ByteArray(128), 0, 128))
        } finally { reader.close() }
        assertTrue(disk.cache.isCached(session.cacheKey(spec), 0, 128))
        assertFalse(disk.cache.isCached(session.cacheKey(spec), 0, body.size.toLong()))
        assertNull(bindings.snapshot().rangeFor(url))
        session.close()
    }

    @Test fun knownLengthAndUncommittedWritesDoNotProveDiskCoverage() = Disk().use { disk ->
        val session = session(URL, disk.cache)
        val bindings = CachedResourceBindings()
        val reader = reader(session, disk, bindings)
        try {
            reader.open(DataSpec.Builder().setUri(URL).build())
            val range = requireNotNull(bindings.snapshot().rangeFor(URL))
            val tracks = listOf(CachedTrackCoverage("v", listOf(CachedMediaInterval(CachedTimeRange(0, 12_000_000), listOf(range)))))
            assertEquals(128, reader.read(ByteArray(128), 0, 128))
            assertTrue("Writing bytes must not appear in committed spans", disk.cache.getCachedSpans(range.key).isEmpty())
            assertTrue(CachedTimelineCoverage.compute(tracks, committed(disk)).isEmpty())
            reader.close()
            assertTrue(disk.cache.isCached(range.key, 0, 128))
            assertTrue("Partial committed resource still cannot cover its time", CachedTimelineCoverage.compute(tracks, committed(disk)).isEmpty())
        } finally { reader.close(); session.close() }
    }

    @Test fun actualDiskHolesCommitRemovalAndClearDriveTheTracker() = Disk().use { disk -> runBlocking {
        val session = session(URL, disk.cache)
        val bindings = CachedResourceBindings()
        fun fill(position: Long) {
            val source = reader(session, disk, bindings)
            try { source.open(DataSpec.Builder().setUri(URL).setPosition(position).setLength(32).build()); consume(source) }
            finally { source.close() }
        }
        fill(0); fill(64)
        val key = session.cacheKey(DataSpec.Builder().setUri(URL).build())
        val tracks = listOf(CachedTrackCoverage("selected-video", (0..2).map { segment ->
            CachedMediaInterval(CachedTimeRange(segment * 4_000_000L, (segment + 1) * 4_000_000L),
                listOf(CachedResourceRange(key, segment * 32L, 32)))
        }))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tracker = CachedTimelineTracker(disk.cache, scope)
        try {
            tracker.replace(tracks)
            val holes = listOf(CachedTimeRange(0, 4_000_000), CachedTimeRange(8_000_000, 12_000_000))
            withTimeout(5_000) { tracker.ranges.first { it == holes } }
            val middle = reader(session, disk, bindings)
            try {
                middle.open(DataSpec.Builder().setUri(URL).setPosition(32).setLength(32).build())
                assertEquals(32, middle.read(ByteArray(32), 0, 32))
                assertEquals(holes, tracker.ranges.value)
                assertFalse(disk.cache.isCached(key, 32, 32))
            } finally { middle.close() }
            withTimeout(5_000) { tracker.ranges.first { it == listOf(CachedTimeRange(0, 12_000_000)) } }
            disk.cache.getCachedSpans(key).single { it.position == 32L }.let(disk.cache::removeSpan)
            withTimeout(5_000) { tracker.ranges.first { it == holes } }
            disk.cache.removeResource(key)
            withTimeout(5_000) { tracker.ranges.first { it.isEmpty() } }
        } finally { tracker.close(); scope.coroutineContext[Job]?.cancelAndJoin(); session.close() }
        Unit // JUnit requires a void method, not runBlocking's last Flow result.
    } }

    @Test fun readFailureCannotPromoteItsCommittedPrefixToAWholeResource() = Disk().use { disk ->
        val session = session(URL, disk.cache)
        val bindings = CachedResourceBindings()
        val source = ByteArrayDataSource(body)
        val failing = object : DataSource by source {
            private var read = false
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if(read) throw IOException("Synthetic read failure")
                read = true
                return source.read(buffer, offset, minOf(128, length))
            }
        }
        val cached = CacheDataSource.Factory().setCache(disk.cache).setCacheKeyFactory(session.cacheKeyFactory)
            .setUpstreamDataSourceFactory { failing }.createDataSource()
        val reader = CacheBindingDataSource(cached, session.cacheKeyFactory, bindings)
        try {
            reader.open(DataSpec.Builder().setUri(URL).build())
            assertNotNull(bindings.snapshot().rangeFor(URL))
            assertEquals(128, reader.read(ByteArray(128), 0, 128))
            assertTrue(runCatching { reader.read(ByteArray(128), 0, 128) }.exceptionOrNull() is IOException)
            assertNull(bindings.snapshot().rangeFor(URL))
        } finally { reader.close() }
        assertTrue(disk.cache.isCached(session.cacheKey(DataSpec.Builder().setUri(URL).build()), 0, 128))
        session.close()
    }

    @Test fun originalMainFactoryBindsAndOwnerCloseRejectsLaterSourceOpens() = Disk().use { disk ->
        val file = wav(disk)
        val url = Uri.fromFile(file).toString()
        val session = session(url, disk.cache)
        prepare(session.createMediaSource()) {
            await { session.cacheResourceSnapshot().rangeFor(url) != null }
            assertEquals(file.length(), session.cacheResourceSnapshot().rangeFor(url)?.length)
        }
        val lateSource = session.createMediaSource()
        session.close()
        assertEquals(0, session.cacheResourceSnapshot().resourceCount)
        assertTrue(runCatching { session.createMediaSource() }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { session.previewLease() }.exceptionOrNull() is IllegalStateException)
        prepare(lateSource) { await { disk.cache.cacheSpace > 0 } }
        assertEquals(0, session.cacheResourceSnapshot().resourceCount)
    }

    @Test fun failedUnknownReadThenLateEofCannotInventACompletePrefixResource() = Disk().use { disk ->
        val session = session(URL, disk.cache)
        val bindings = CachedResourceBindings()
        val source = ByteArrayDataSource(body)
        val failing = object : DataSource by source {
            private var reads = 0
            override fun open(dataSpec: DataSpec): Long { source.open(dataSpec); return -1 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = when(reads++) {
                0 -> source.read(buffer, offset, minOf(128, length))
                1 -> throw IOException("Synthetic read failure before late EOF")
                else -> C.RESULT_END_OF_INPUT
            }
        }
        val cached = CacheDataSource.Factory().setCache(disk.cache).setCacheKeyFactory(session.cacheKeyFactory)
            .setUpstreamDataSourceFactory { failing }.createDataSource()
        val reader = CacheBindingDataSource(cached, session.cacheKeyFactory, bindings)
        try {
            assertEquals(-1L, reader.open(DataSpec.Builder().setUri(URL).build()))
            assertEquals(128, reader.read(ByteArray(128), 0, 128))
            assertTrue(runCatching { reader.read(ByteArray(128), 0, 128) }.exceptionOrNull() is IOException)
            assertEquals(C.RESULT_END_OF_INPUT, reader.read(ByteArray(128), 0, 128))
            assertNull("EOF after a failed open must not prove a complete 128-byte resource", bindings.snapshot().rangeFor(URL))
        } finally { reader.close(); session.close() }
    }

    @Test fun previewFactoryCannotWriteMainBindingsEvenWhenItPopulatesDisk() = Disk().use { disk ->
        val file = wav(disk)
        val session = session(Uri.fromFile(file).toString(), disk.cache)
        val lease = session.previewLease()
        try {
            prepare(lease.mediaSourceFactory.createMediaSource(lease.mediaItem)) { await { disk.cache.cacheSpace > 0 } }
            assertEquals(0, session.cacheResourceSnapshot().resourceCount)
            assertEquals(0L, session.cacheBindingRevision.value)
        } finally { lease.close(); session.close() }
    }

    @Test fun privateAndOfflineFactoriesHaveNoStreamBindingsOrStreamWrites() = Disk().use { disk ->
        val url = Uri.fromFile(wav(disk)).toString()
        val privateSession = session(url, null)
        val offline = PlaybackSourceSession(context, 2, MediaItem.fromUri(url), emptyMap(), disk.cache,
            offlineDataSourceFactory = DefaultDataSource.Factory(context))
        try {
            prepare(privateSession.createMediaSource()) { }
            prepare(offline.createMediaSource()) { }
            assertTrue(disk.cache.keys.isEmpty())
            assertEquals(0, privateSession.cacheResourceSnapshot().resourceCount)
            assertEquals(0, offline.cacheResourceSnapshot().resourceCount)
            assertEquals(0L, privateSession.cacheBindingRevision.value)
            assertEquals(0L, offline.cacheBindingRevision.value)
        } finally { privateSession.close(); offline.close() }
    }

    @Test fun sameUrlWithDifferentAuthenticationNeverSharesTheBoundCacheKey() = Disk().use { disk ->
        val first = session(URL, disk.cache, mapOf("Authorization" to "Bearer synthetic-A"))
        val second = session(URL, disk.cache, mapOf("Authorization" to "Bearer synthetic-B"))
        val spec = DataSpec.Builder().setUri(URL).build()
        val one = CachedResourceBindings()
        val two = CachedResourceBindings()
        listOf(first to one, second to two).forEach { (session, bindings) ->
            val source = reader(session, disk, bindings)
            try { source.open(spec); assertArrayEquals(body, consume(source)) } finally { source.close() }
        }
        val a = requireNotNull(one.snapshot().rangeFor(URL))
        val b = requireNotNull(two.snapshot().rangeFor(URL))
        assertNotEquals(a.key, b.key)
        assertEquals(setOf(a.key, b.key), disk.cache.keys)
        assertFalse(a.key.contains("synthetic-A"))
        assertFalse(b.key.contains("synthetic-B"))
        first.close(); second.close()
    }

    private fun reader(session: PlaybackSourceSession, disk: Disk, bindings: CachedResourceBindings,
        unknownLength: Boolean = false): DataSource {
        val upstream = DataSource.Factory {
            val actual = ByteArrayDataSource(body)
            if(!unknownLength) actual else object : DataSource by actual {
                override fun open(dataSpec: DataSpec): Long { actual.open(dataSpec); return -1 }
            }
        }
        val cached = CacheDataSource.Factory().setCache(disk.cache).setCacheKeyFactory(session.cacheKeyFactory)
            .setUpstreamDataSourceFactory(upstream).createDataSource()
        return CacheBindingDataSource(cached, session.cacheKeyFactory, bindings)
    }

    private fun session(url: String, cache: SimpleCache?, headers: Map<String, String> = emptyMap()) =
        PlaybackSourceSession(context, 1, MediaItem.fromUri(url), headers, cache)

    private fun committed(disk: Disk) = disk.cache.keys.associateWith { key ->
        disk.cache.getCachedSpans(key).map { CachedResourceRange(key, it.position, it.length) }
    }

    private fun consume(source: DataSource): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val bytes = ByteArray(4096)
        while(true) {
            val count = source.read(bytes, 0, bytes.size)
            if(count == C.RESULT_END_OF_INPUT) return output.toByteArray()
            if(count > 0) output.write(bytes, 0, count)
        }
    }

    private fun wav(disk: Disk): File {
        val pcm = ByteArray(32_000) // One second, mono, 16 kHz, signed 16-bit silence.
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).array()
        return File(disk.root, "source.wav").apply { outputStream().use { it.write(header); it.write(pcm) } }
    }

    private fun prepare(source: androidx.media3.exoplayer.source.MediaSource, verify: () -> Unit) {
        val ready = AtomicBoolean()
        val error = AtomicReference<PlaybackException?>()
        var player: ExoPlayer? = null
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) { if(playbackState == Player.STATE_READY) ready.set(true) }
                    override fun onPlayerError(failure: PlaybackException) { error.set(failure) }
                })
                setMediaSource(source); prepare()
            }
        }
        try {
            await { error.get()?.let { throw AssertionError("Actual source failed", it) }; ready.get() }
            verify()
        } finally { instrumentation.runOnMainSync { player?.release() } }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while(!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Source/cache condition timed out" }
            SystemClock.sleep(20)
        }
    }

    private inner class Disk : Closeable {
        val root = File(context.cacheDir, "cache-bindings-${UUID.randomUUID()}").apply { mkdirs() }
        @Suppress("DEPRECATION")
        val cache = SimpleCache(File(root, "stream"), NoOpCacheEvictor())
        override fun close() { cache.release(); check(root.deleteRecursively()) }
    }

    companion object { private const val URL = "https://media.invalid/segment.ts?token=synthetic" }
}
