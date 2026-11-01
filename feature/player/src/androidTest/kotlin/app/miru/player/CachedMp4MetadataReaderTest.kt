// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.extractor.mp4.TrackSampleTable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exact metadata from real committed files, with independent per-sample truth. No time/UI claim. */
@RunWith(AndroidJUnit4::class)
class CachedMp4MetadataReaderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val profile = Mp4ParserProfile(true, true, true, C.VIDEO_CODEC_FLAG_H264 or C.VIDEO_CODEC_FLAG_H265)
    private val original = Mp4CacheRequest("https://owned.invalid/opaque?token=original", 0, -1, "original-open-key")

    @Test fun cachedHeadMoovMatchesEveryIndependentBaselineSample() = disk { d ->
        val reference = reference("colors.samples.json"); val bytes = media(reference)
        seed(d, bytes)
        val before = d.cache.keys.toSet()
        val metadata = requireNotNull(read(d, bytes.size.toLong()))
        assertTables(bytes, metadata.tables, reference)
        assertTrue(metadata.structuralBytes.all { it.key == original.key && it.endExclusive <= 1376 })
        assertEquals(before, d.cache.keys)
    }

    @Test fun strongVbrBFramesHeadAndTailKeepTheirExactSourceArrays() = disk { d ->
        for(name in listOf("head-vbr-bframes.samples.json", "tail-vbr-bframes.samples.json")) {
            d.cache.removeResource(original.key)
            val ref = reference(name); val bytes = media(ref); seed(d, bytes)
            val parsed = requireNotNull(read(d, bytes.size.toLong()))
            assertTables(bytes, parsed.tables, ref)
            assertTrue(parsed.tables.single().timestampsUs.asList().zipWithNext().any { it.first > it.second })
        }
    }

    @Test fun sparseTailMetadataCanBeParsedWithoutReadingMediaOrCreatingCacheEntries() = disk { d ->
        val ref = reference("tail-vbr-bframes.samples.json"); val bytes = media(ref); seed(d, bytes)
        val first = requireNotNull(read(d, bytes.size.toLong()))
        val ranges = first.structuralBytes.sortedBy { it.position }.fold(mutableListOf<Pair<Long, Long>>()) { all, next ->
            val last = all.lastOrNull()
            if(last != null && next.position <= last.second) all[all.lastIndex] = last.first to maxOf(last.second, next.endExclusive)
            else all += next.position to next.endExclusive
            all
        }
        d.cache.removeResource(original.key)
        ranges.forEach { (start, end) -> put(d, original.key, start, bytes.copyOfRange(start.toInt(), end.toInt())) }
        val keys = d.cache.keys.toSet(); val space = d.cache.cacheSpace
        val parsed = requireNotNull(read(d, bytes.size.toLong()))
        assertTables(bytes, parsed.tables, ref)
        assertTrue("Structural parsing must not require cached mdat payload", space < bytes.size / 4)
        assertEquals(space, d.cache.cacheSpace); assertEquals(keys, d.cache.keys)
        // A structural result is not a claim that native cold startup can skip
        // every missing byte: I/O/decode closure is a separate required layer.
    }

    @Test fun oneMetadataByteHoleCannotBeFilledFromAnotherKeyAndCancellationStopsReads() = disk { d ->
        val bytes = media(reference("colors.samples.json"))
        val missing = find(bytes, "stsz") + 12
        put(d, original.key, 0, bytes.copyOfRange(0, missing))
        put(d, original.key, missing + 1L, bytes.copyOfRange(missing + 1, bytes.size))
        put(d, "different-request-key", 0, bytes)
        val space = d.cache.cacheSpace; val keys = d.cache.keys.toSet()
        assertNull(read(d, bytes.size.toLong()))
        assertEquals(space, d.cache.cacheSpace); assertEquals(keys, d.cache.keys)
        put(d, original.key, missing.toLong(), byteArrayOf(bytes[missing]))
        assertEquals(120, requireNotNull(read(d, bytes.size.toLong())).tables.single().sampleCount)
        val job = Job(); var calls = 0
        try {
            CachedMp4MetadataReader.read(Mp4MetadataBytes { position, length ->
                if(++calls == 3) job.cancel()
                CachedMp4Block(bytes.copyOfRange(position.toInt(), position.toInt() + length), CachedResourceRange(original.key, position, length.toLong()))
            }, bytes.size.toLong(), profile, job)
            fail("Cancelled metadata parsing must not complete")
        } catch(_: CancellationException) { assertEquals(3, calls) }
        finally { job.cancel() }
    }

    @Test fun extendedMoovAndCo64PreserveAllTailMoviePayloads() = disk { d ->
        val ref = reference("tail-vbr-bframes.samples.json"); val originalBytes = media(ref)
        val top = boxes(originalBytes, 0, originalBytes.size)
        val moov = top.single { type(originalBytes, it.first + 4) == "moov" }
        assertEquals(originalBytes.size, moov.second)
        fun rebuild(start: Int, end: Int): ByteArray {
            val name = type(originalBytes, start + 4)
            val payload = if(name in setOf("moov", "trak", "mdia", "minf", "stbl", "edts"))
                boxes(originalBytes, start + 8, end).map { rebuild(it.first, it.second) }.fold(ByteArray(0)) { a, b -> a + b }
            else if(name == "stco") {
                val count = int(originalBytes, start + 12)
                ByteBuffer.allocate(8 + count * 8).order(ByteOrder.BIG_ENDIAN).putInt(int(originalBytes, start + 8)).putInt(count).apply {
                    repeat(count) { putLong(int(originalBytes, start + 16 + it * 4).toLong() and 0xffffffffL) }
                }.array()
            } else originalBytes.copyOfRange(start + 8, end)
            val outType = if(name == "stco") "co64" else name
            val header = if(name == "moov") ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).putInt(1).put(outType.toByteArray()).putLong(16L + payload.size).array()
                else ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(8 + payload.size).put(outType.toByteArray()).array()
            return header + payload
        }
        val changed = originalBytes.copyOfRange(0, moov.first) + rebuild(moov.first, moov.second)
        assertTrue(changed.size > originalBytes.size)
        seed(d, changed)
        assertTables(changed, requireNotNull(read(d, changed.size.toLong())).tables, ref)
    }

    @Test fun malformedLengthsAndExplosiveConstantSampleCountsCannotReachNativeAllocation() = disk { d ->
        val bytes = media(reference("colors.samples.json")).copyOf()
        val sizeBox = find(bytes, "stsz") - 4
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(sizeBox + 12, 4).putInt(sizeBox + 16, Int.MAX_VALUE)
        seed(d, bytes)
        assertNull(read(d, bytes.size.toLong()))
        val shifted = Mp4MetadataBytes { position, length -> CachedMp4Block(ByteArray(length), CachedResourceRange(original.key, position + 1, length.toLong())) }
        assertNull(CachedMp4MetadataReader.read(shifted, 100, profile))
        assertNull(CachedMp4MetadataReader.read(shifted, 100, profile.copy(trustedDefaults = false)))
        val huge = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).putInt(1).put("moov".toByteArray()).putLong(Long.MAX_VALUE).array()
        val source = Mp4MetadataBytes { position, length -> CachedMp4Block(huge.copyOfRange(position.toInt(), position.toInt() + length), CachedResourceRange(original.key, position, length.toLong())) }
        assertNull(CachedMp4MetadataReader.read(source, 16, profile))
    }

    private fun reference(name: String) = JSONObject(instrumentation.context.assets.open("mp4-cache/$name").bufferedReader().use { it.readText() })
    private fun media(ref: JSONObject): ByteArray {
        val name = ref.getString("asset").substringAfterLast('/')
        val path = if(name == "colors.mp4") name else "mp4-cache/$name"
        return instrumentation.context.assets.open(path).use { it.readBytes() }.also {
            assertEquals(ref.getLong("bytes"), it.size.toLong()); assertEquals(ref.getString("sha256"), digest(it))
        }
    }
    private fun assertTables(body: ByteArray, tables: List<TrackSampleTable>, ref: JSONObject) {
        val expected = ref.getJSONArray("tracks"); assertEquals(expected.length(), tables.size)
        for(trackIndex in 0 until expected.length()) {
            val track = expected.getJSONObject(trackIndex); val table = tables.single { it.track.id == track.getInt("id") }
            val samples = track.getJSONArray("samples"); assertEquals(samples.length(), table.sampleCount)
            repeat(samples.length()) { index ->
                val sample = samples.getJSONObject(index)
                assertEquals(sample.getLong("offset"), table.offsets[index]); assertEquals(sample.getInt("size"), table.sizes[index])
                assertEquals(nativePresentationUs(sample, ref, track), table.timestampsUs[index])
                val flags = (if(sample.getBoolean("isSync")) C.BUFFER_FLAG_KEY_FRAME else 0) or (if(index == table.sampleCount - 1) C.BUFFER_FLAG_LAST_SAMPLE else 0)
                assertEquals(flags, table.flags[index])
                assertEquals(sample.getString("sha256"), digest(body.copyOfRange(table.offsets[index].toInt(), table.offsets[index].toInt() + table.sizes[index])))
            }
        }
    }
    /** Independent export uses ignore_editlist=1 for VBR. Apply its recorded elst
     * in integer ticks, without changing the raw reference or adding tolerance. */
    private fun nativePresentationUs(sample: JSONObject, ref: JSONObject, track: JSONObject): Long {
        val rawUs = sample.getLong("ptsUs")
        if(!ref.getBoolean("ignoreEditLists")) return rawUs
        val facts = ref.getJSONObject("observed").getJSONObject("bmff").getJSONArray("tracks")
        val exported = (0 until facts.length()).map(facts::getJSONObject).single { it.getInt("id") == track.getInt("id") }
        val edits = exported.getJSONArray("edits")
        assertEquals("These fixed VBR fixtures have exactly one independently exported edit", 1, edits.length())
        val edit = edits.getJSONObject(0)
        assertEquals(65536, edit.getInt("mediaRateFixed16"))
        val scale = track.getLong("timescale")
        assertTrue("Recovering ticks from truncated microseconds must be unambiguous", scale in 1..499_999)
        val numerator = Math.multiplyExact(rawUs, scale)
        val ticks = (numerator + if(numerator >= 0) 500_000 else -500_000) / 1_000_000
        assertEquals("Recovered tick must reproduce the exact original ffprobe timestamp", rawUs, ticks * 1_000_000 / scale)
        return Math.multiplyExact(ticks - edit.getLong("mediaTimeTicks"), 1_000_000L) / scale
    }
    private fun read(d: Disk, length: Long) = CachedMp4MetadataReader.read(CacheOnlyMp4Bytes(d.cache, original), length, profile)
    private fun seed(d: Disk, body: ByteArray) {
        val source = CacheDataSource.Factory().setCache(d.cache).setCacheKeyFactory { original.key }
            .setUpstreamDataSourceFactory { ByteArrayDataSource(body) }.createDataSource()
        try { source.open(DataSpec.Builder().setUri(original.uri).build()); val bytes = ByteArray(4096); while(source.read(bytes, 0, bytes.size) >= 0) { } }
        finally { source.close() }
    }
    private fun put(d: Disk, key: String, position: Long, body: ByteArray) {
        val hole = requireNotNull(d.cache.startReadWriteNonBlocking(key, position, body.size.toLong()))
        check(hole.isHoleSpan)
        try { val file = d.cache.startFile(key, position, body.size.toLong()); file.writeBytes(body); d.cache.commitFile(file, body.size.toLong()) }
        finally { d.cache.releaseHoleSpan(hole) }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun int(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.BIG_ENDIAN).int
    private fun type(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
    private fun find(bytes: ByteArray, name: String) = (0..bytes.size - 4).first { type(bytes, it) == name }
    private fun boxes(bytes: ByteArray, start: Int, end: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(); var at = start
        while(at < end) { val size = int(bytes, at); require(size >= 8 && size <= end - at); out += at to at + size; at += size }
        require(at == end); return out
    }
    private fun disk(block: (Disk) -> Unit) = runBlocking { withContext(Dispatchers.IO) { Disk().use(block) } }
    private inner class Disk : Closeable {
        val root = File(context.cacheDir, "mp4-metadata-${UUID.randomUUID()}").apply { mkdirs() }
        @Suppress("DEPRECATION") val cache = SimpleCache(File(root, "stream"), NoOpCacheEvictor())
        override fun close() { cache.release(); check(root.deleteRecursively()) }
    }
}

