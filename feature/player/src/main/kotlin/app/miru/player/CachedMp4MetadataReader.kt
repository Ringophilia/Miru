// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.Mp4Box
import androidx.media3.datasource.cache.Cache
import androidx.media3.extractor.GaplessInfoHolder
import androidx.media3.extractor.mp4.BoxParser
import androidx.media3.extractor.mp4.TrackSampleTable
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Collections
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

internal class CachedMp4Block(val bytes: ByteArray, val dependency: CachedResourceRange)
internal fun interface Mp4MetadataBytes { fun read(position: Long, length: Int): CachedMp4Block? }

/** Exact original parent key; no DataSpec recreation, upstream, sink, lock span or cache mutation. */
internal class CacheOnlyMp4Bytes(private val cache: Cache, private val original: Mp4CacheRequest,
    private val context: CoroutineContext? = null) : Mp4MetadataBytes {
    override fun read(position: Long, length: Int): CachedMp4Block? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "MP4 cache reads require an IO worker" }
        context?.ensureActive()
        if(position < original.position || length <= 0 || length > MAX_METADATA_BYTES || position > Long.MAX_VALUE - length ||
            original.length > 0 && (original.position > Long.MAX_VALUE - original.length || position + length > original.position + original.length)) return null
        return try { synchronized(cache) {
            val spans = cache.getCachedSpans(original.key)
            val bytes = ByteArray(length)
            var cursor = position
            val end = position + length
            for(span in spans) {
                context?.ensureActive()
                if(!span.isCached || span.key != original.key || span.position < 0 || span.length <= 0 || span.position > Long.MAX_VALUE - span.length) continue
                val spanEnd = span.position + span.length
                if(spanEnd <= cursor) continue
                if(span.position > cursor) break
                val file = span.file ?: return@synchronized null
                if(!file.isFile || file.length() != span.length) return@synchronized null
                val stop = minOf(end, spanEnd)
                RandomAccessFile(file, "r").use { input ->
                    input.seek(cursor - span.position)
                    while(cursor < stop) {
                        context?.ensureActive()
                        val count = minOf(64 * 1024L, stop - cursor).toInt()
                        input.readFully(bytes, (cursor - position).toInt(), count)
                        cursor += count
                    }
                }
                if(cursor == end) return@synchronized CachedMp4Block(bytes, CachedResourceRange(original.key, position, length.toLong()))
            }
            null
        } } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: IOException) { null }
        catch(_: SecurityException) { null }
    }
    companion object { const val MAX_METADATA_BYTES = 32 * 1024 * 1024 }
}

/** Parsed structure only: callers still need native identity, timing/decode and I/O closure. */
internal class CachedMp4Metadata(tables: Collection<TrackSampleTable>, dependencies: Collection<CachedResourceRange>,
    val moov: Mp4Box.ContainerBox, val quickTime: Boolean) {
    val tables = Collections.unmodifiableList(ArrayList(tables))
    val structuralBytes = Collections.unmodifiableList(ArrayList(dependencies))
}

/** Bounded public BoxParser input from cache bytes. Missing/corrupt metadata never yields a partial table. */
internal object CachedMp4MetadataReader {
    private data class Box(val type: Int, val start: Long, val end: Long, val header: Int)
    private val containers = setOf(code("moov"), code("trak"), code("mdia"), code("minf"), code("stbl"), code("edts"), code("meta"))
    private val leaves = setOf("mvhd", "tkhd", "mdhd", "hdlr", "stsd", "stts", "ctts", "stss", "stsc", "stsz", "stz2", "stco", "co64", "elst", "udta", "keys", "ilst").map(::code).toSet()
    fun read(source: Mp4MetadataBytes, length: Long?, profile: Mp4ParserProfile, context: CoroutineContext? = null): CachedMp4Metadata? {
        if(!profile.trustedDefaults || length != null && length <= 0) return null
        return try {
            val scan = Scan(source, length, context)
            var position = 0L
            var quickTime = false
            var moov: Mp4Box.ContainerBox? = null
            for(ignored in 0 until 4096) {
                context?.ensureActive()
                val box = scan.header(position, length)
                when(box.type) {
                    code("ftyp") -> {
                        val data = scan.bytes(box.start, (box.end - box.start).exactInt())
                        require(box.header == 8 && data.size >= 16)
                        val brands = listOf(int(data, 8)) + (16 until data.size step 4).map { int(data, it) }
                        require(brands.none { it in setOf(code("heic"), code("heix"), code("hevc"), code("hevx")) })
                        quickTime = code("qt  ") in brands
                    }
                    code("moov") -> { require(box.end - box.start <= CacheOnlyMp4Bytes.MAX_METADATA_BYTES); moov = scan.tree(box, 0); break }
                }
                position = box.end
                if(length != null && position >= length) break
            }
            val movie = moov ?: return null
            validateCounts(movie)
            val gapless = GaplessInfoHolder()
            movie.getLeafBoxOfType(code("udta"))?.let { gapless.setFromMetadata(BoxParser.parseUdta(it, false)) }
            val tables = BoxParser.parseTraks(movie, gapless, C.TIME_UNSET, null, false, quickTime, { track ->
                if(track != null && !profile.parseHagcMetadata && CodecSpecificDataUtil.isHagcTrack(track.format)) null else track
            }, false)
            context?.ensureActive()
            require(tables.sumOf { it.sampleCount.toLong() } <= MAX_SAMPLES)
            for(table in tables) {
                require(table.hasSampleTableData())
                for(i in 0 until table.sampleCount) {
                    context?.ensureActive()
                    val offset = table.offsets[i]; val size = table.sizes[i]
                    require(offset >= 0 && size >= 0 && offset <= Long.MAX_VALUE - size && (length == null || offset + size <= length))
                }
            }
            CachedMp4Metadata(tables, scan.dependencies, movie, quickTime)
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { null }
    }

    private class Scan(val source: Mp4MetadataBytes, val total: Long?, val context: CoroutineContext?) {
        val dependencies = LinkedHashSet<CachedResourceRange>()
        var copied = 0L
        var boxes = 0
        fun bytes(position: Long, length: Int): ByteArray {
            context?.ensureActive()
            require(length > 0 && copied + length <= CacheOnlyMp4Bytes.MAX_METADATA_BYTES)
            val block = requireNotNull(source.read(position, length))
            require(block.bytes.size == length && block.dependency.position == position && block.dependency.length == length.toLong())
            copied += length; dependencies += block.dependency
            return block.bytes
        }
        fun header(position: Long, parentEnd: Long?): Box {
            require(++boxes <= 32768 && position >= 0 && position <= Long.MAX_VALUE - 16)
            val head = bytes(position, 8)
            val size = uint(head, 0)
            val header = if(size == 1L) 16 else 8
            val length = when(size) {
                0L -> Math.subtractExact(requireNotNull(parentEnd ?: total), position)
                1L -> long(bytes(position + 8, 8), 0)
                else -> size
            }
            require(length >= header && length <= Long.MAX_VALUE - position)
            val end = position + length
            require((parentEnd == null || end <= parentEnd) && (total == null || end <= total))
            return Box(int(head, 4), position, end, header)
        }
        fun tree(box: Box, depth: Int): Mp4Box.ContainerBox {
            require(depth < 32)
            val node = Mp4Box.ContainerBox(box.type, box.end)
            var cursor = box.start + box.header
            if(box.type == code("meta")) {
                val peek = bytes(cursor, 8)
                if(int(peek, 4) != code("hdlr")) cursor += 4
            }
            while(cursor < box.end) {
                context?.ensureActive()
                val child = header(cursor, box.end)
                if(child.type in containers) node.add(tree(child, depth + 1))
                else if(child.type in leaves) {
                    // The pinned native parser also rejects extended leaf headers.
                    require(child.header == 8)
                    node.add(Mp4Box.LeafBox(child.type, ParsableByteArray(bytes(child.start, (child.end - child.start).exactInt()))))
                }
                cursor = child.end
            }
            require(cursor == box.end)
            return node
        }
    }

    /** Bound allocations BEFORE public BoxParser expands sample arrays. */
    private fun validateCounts(moov: Mp4Box.ContainerBox) {
        var samples = 0L
        require(moov.getLeafBoxOfType(code("mvhd")) != null)
        require(moov.containerChildren.size <= 256)
        for(trak in moov.containerChildren.filter { it.type == code("trak") }) {
            val stbl = requireNotNull(trak.getContainerBoxOfType(code("mdia"))?.getContainerBoxOfType(code("minf"))?.getContainerBoxOfType(code("stbl")))
            val sizeBox = stbl.getLeafBoxOfType(code("stsz")) ?: stbl.getLeafBoxOfType(code("stz2"))
            val size = requireNotNull(sizeBox).data.data
            require(size.size >= 20)
            val count = uint(size, 16)
            samples = Math.addExact(samples, count); require(samples <= MAX_SAMPLES)
            val descriptions = requireNotNull(stbl.getLeafBoxOfType(code("stsd"))).data.data
            require(descriptions.size >= 16 && uint(descriptions, 12) in 1..256 && 16 + uint(descriptions, 12) * 8 <= descriptions.size)
            if(sizeBox.type == code("stsz") && uint(size, 12) == 0L) require(20L + count * 4 <= size.size)
            if(sizeBox.type == code("stz2")) {
                val bits = size[15].toInt() and 255; require(bits in setOf(4, 8, 16))
                require(20L + (count * bits + 7) / 8 <= size.size)
            }
            for((type, width) in listOf("stts" to 8, "ctts" to 8, "stsc" to 12, "stss" to 4, "stco" to 4, "co64" to 8)) {
                stbl.getLeafBoxOfType(code(type))?.data?.data?.let { data ->
                    require(data.size >= 16)
                    val entries = uint(data, 12)
                    require(entries <= MAX_SAMPLES && 16L + entries * width <= data.size)
                    if(type == "stts" || type == "ctts") {
                        var described = 0L
                        for(i in 0 until entries.toInt()) { described += uint(data, 16 + i * 8); require(described <= count) }
                        require(described == count || type == "ctts" && entries == 0L)
                    }
                }
            }
        }
    }
    private const val MAX_SAMPLES = 2_000_000L
    private fun Long.exactInt(): Int { require(this in 1..Int.MAX_VALUE.toLong()); return toInt() }
    private fun code(value: String) = value.fold(0) { a, b -> (a shl 8) or b.code }
    private fun int(data: ByteArray, at: Int) = ((data[at].toInt() and 255) shl 24) or ((data[at + 1].toInt() and 255) shl 16) or
        ((data[at + 2].toInt() and 255) shl 8) or (data[at + 3].toInt() and 255)
    private fun uint(data: ByteArray, at: Int) = int(data, at).toLong() and 0xffffffffL
    private fun long(data: ByteArray, at: Int) = (uint(data, at) shl 32) or uint(data, at + 4)
}
