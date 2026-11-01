// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual returned bytes, original parents and cache verification, not sample/time coverage. */
@RunWith(AndroidJUnit4::class)
class Mp4MetadataProofTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun sniffPrefixIsRetainedAndFreezeDoesNotHashLaterMedia() {
        val body = ByteArray(100) { it.toByte() }
        val requests = Mp4CacheRequestContext { "${it.position}/${it.length}" }
        val source = requests.factory { ByteArrayDataSource(body) }.createDataSource()
        try {
            source.open(spec("https://owned.invalid/a"))
            source.read(ByteArray(8), 0, 8) // before native init, e.g. sniff/peek
            source.read(ByteArray(24), 0, 24)
            val proof = requireNotNull(requests.freezeMetadata())
            source.read(ByteArray(68), 0, 68)
            assertEquals(32, proof.reads.single().length)
            assertEquals("0/-1", proof.reads.single().request.key)
            assertEquals(Mp4MetadataProof.digest(body.copyOfRange(0, 32)), proof.reads.single().sha256)
            assertNull(requests.freezeMetadata())
        } finally { source.close() }
    }

    @Test fun reopenedParentsKeepTheirOwnKeysAndVerifySparseMetadata() = disk { d ->
        val body = ByteArray(2048) { (it % 251).toByte() }
        val requests = Mp4CacheRequestContext { "${it.position}/${it.length}" }
        val source = requests.factory { ByteArrayDataSource(body) }.createDataSource()
        val proof: Mp4MetadataProof
        try {
            source.open(spec("https://owned.invalid/opaque?token=one"))
            source.read(ByteArray(16), 0, 16); source.close()
            source.open(spec("https://owned.invalid/opaque?token=one", 1600))
            source.read(ByteArray(64), 0, 64)
            proof = requireNotNull(requests.freezeMetadata())
        } finally { source.close() }
        assertEquals(listOf("0/-1", "1600/-1"), proof.reads.map { it.request.key })
        for(read in proof.reads) put(d, read.request.key, read.request.position,
            body.copyOfRange(read.request.position.toInt(), read.request.position.toInt() + read.length))
        val before = d.cache.cacheSpace; val keys = d.cache.keys.toSet()
        val verified = requireNotNull(proof.verifiedBytes(d.cache))
        val block = requireNotNull(verified.read(1604, 8))
        assertEquals("1600/-1", block.dependency.key)
        assertArrayEquals(body.copyOfRange(1604, 1612), block.bytes)
        assertNull(verified.read(100, 8))
        assertEquals(before, d.cache.cacheSpace); assertEquals(keys, d.cache.keys)
    }

    @Test fun sameLengthCacheMutationCannotBePairedWithOldNativeMetadata() = disk { d ->
        val body = ByteArray(64) { it.toByte() }
        val proof = Mp4MetadataProof(listOf(Mp4MetadataProof.Read(Mp4CacheRequest("https://owned.invalid/a", 0, -1, "key"), body.size, Mp4MetadataProof.digest(body))))
        put(d, "key", 0, body)
        assertNotNull(proof.verifiedBytes(d.cache))
        val file = requireNotNull(d.cache.getCachedSpans("key").single().file)
        RandomAccessFile(file, "rw").use { it.seek(12); it.write(127) }
        assertNull(proof.verifiedBytes(d.cache))
        file.writeBytes(body)
        assertNotNull(proof.verifiedBytes(d.cache))
    }

    @Test fun overlappingParentsWithDifferentBytesCannotCreateOneMetadataImage() = disk { d ->
        val a = ByteArray(32) { 1 }; val b = ByteArray(32) { 2 }
        put(d, "a", 0, a); put(d, "b", 16, b)
        val proof = Mp4MetadataProof(listOf(
            Mp4MetadataProof.Read(Mp4CacheRequest("https://owned.invalid/a", 0, -1, "a"), 32, Mp4MetadataProof.digest(a)),
            Mp4MetadataProof.Read(Mp4CacheRequest("https://owned.invalid/a", 16, -1, "b"), 32, Mp4MetadataProof.digest(b))))
        assertNull(proof.verifiedBytes(d.cache))
    }

    @Test fun boundedOrDiscardedCollectionNeverAltersTransportBytes() {
        val request = Mp4CacheRequest("https://owned.invalid/a", 0, -1, "key")
        val bounded = Mp4MetadataTrace(16)
        bounded.opened(request); bounded.record(ByteArray(32), 0, 32)
        assertNull(bounded.freeze())
        val body = ByteArray(80) { it.toByte() }
        val requests = Mp4CacheRequestContext { "key" }
        val source = requests.factory { ByteArrayDataSource(body) }.createDataSource()
        try {
            source.open(spec(request.uri)); requests.discardMetadata()
            val actual = ByteArray(80); assertEquals(80, source.read(actual, 0, actual.size))
            assertArrayEquals(body, actual); assertNull(requests.freezeMetadata())
        } finally { source.close() }
    }

    @Test fun twoLoaderThreadsAndAReparseDoNotShareOrResurrectProofs() {
        val requests = Mp4CacheRequestContext { it.uri.toString() }
        val pool = Executors.newFixedThreadPool(2)
        val body = ByteArray(64) { it.toByte() }
        try {
            val jobs = (1..2).map { index -> pool.submit<Mp4MetadataProof> {
                val source = requests.factory { ByteArrayDataSource(body) }.createDataSource()
                try {
                    source.open(spec("https://owned.invalid/$index")); source.read(ByteArray(16), 0, 16)
                    requireNotNull(requests.freezeMetadata())
                } finally { source.close() }
            } }
            assertEquals(listOf("https://owned.invalid/1", "https://owned.invalid/2"), jobs.map { it.get().reads.single().request.key })
            assertNull(requests.request())
            val source = requests.factory { ByteArrayDataSource(body) }.createDataSource()
            try {
                source.open(spec("https://owned.invalid/reparse")); source.read(ByteArray(16), 0, 16)
                val first = requireNotNull(requests.freezeMetadata()); source.close()
                body[0] = 100
                source.open(spec("https://owned.invalid/reparse")); requests.restartMetadata()
                source.read(ByteArray(32), 0, 32)
                val second = requireNotNull(requests.freezeMetadata())
                assertEquals(16, first.reads.single().length); assertEquals(32, second.reads.single().length)
                assertNotEquals(first.reads.single().sha256, second.reads.single().sha256)
            } finally { source.close() }
        } finally { pool.shutdownNow() }
    }

    @Test fun nativeMovieMetadataProofCanBeVerifiedAndParsedFromItsCommittedCache() = disk { d ->
        val file = File(d.root, "owned.mp4").apply {
            instrumentation.context.assets.open("colors.mp4").use { input -> outputStream().use { input.copyTo(it) } }
        }
        val session = PlaybackSourceSession(context, 1, MediaItem.fromUri(file.toURI().toString()), emptyMap(), d.cache)
        val captured = AtomicReference<Mp4CacheCatalog.Entry?>(); val error = AtomicReference<PlaybackException?>()
        var player: ExoPlayer? = null
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener { override fun onPlayerError(failure: PlaybackException) { error.set(failure) } })
                setMediaSource(session.createMediaSource()); prepare()
            }
        }
        try {
            await {
                error.get()?.let { throw AssertionError("Native metadata source failed", it) }
                captured.set(session.mp4CacheCatalog!!.snapshot().entries.singleOrNull { it.metadataProof != null })
                captured.get() != null
            }
        } finally { instrumentation.runOnMainSync { player?.release() }; session.close() }
        val entry = requireNotNull(captured.get()); val proof = requireNotNull(entry.metadataProof)
        var verified: Mp4MetadataBytes? = null
        await { verified = proof.verifiedBytes(d.cache); verified != null }
        val movie = requireNotNull(CachedMp4MetadataReader.read(requireNotNull(verified), entry.fileLength, entry.profile))
        assertEquals(120, movie.tables.single().sampleCount)
        assertTrue(proof.reads.sumOf { it.length } <= file.length())
        assertTrue(proof.reads.all { it.request.uri == file.toURI().toString() && it.request.length == -1L })
    }

    private fun spec(uri: String, position: Long = 0) = DataSpec.Builder().setUri(uri).setPosition(position).build()
    private fun put(d: Disk, key: String, position: Long, bytes: ByteArray) {
        val hole = requireNotNull(d.cache.startReadWriteNonBlocking(key, position, bytes.size.toLong())); check(hole.isHoleSpan)
        try { val file = d.cache.startFile(key, position, bytes.size.toLong()); file.writeBytes(bytes); d.cache.commitFile(file, bytes.size.toLong()) }
        finally { d.cache.releaseHoleSpan(hole) }
    }
    private fun await(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while(!predicate()) { check(SystemClock.elapsedRealtime() < end) { "Metadata proof timed out" }; SystemClock.sleep(20) }
    }
    private fun disk(block: (Disk) -> Unit) = runBlocking { withContext(Dispatchers.IO) { Disk().use(block) } }
    private inner class Disk : Closeable {
        val root = File(context.cacheDir, "mp4-proof-${UUID.randomUUID()}").apply { mkdirs() }
        @Suppress("DEPRECATION") val cache = SimpleCache(File(root, "stream"), NoOpCacheEvictor())
        override fun close() { cache.release(); check(root.deleteRecursively()) }
    }
}
