// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import java.math.BigInteger

/** Original decode-order timing runs, before edits or PCM rechunking. Not presentation intervals. */
internal class Mp4RawSampleTiming private constructor(val sampleCount: Int, val timescale: Long,
    private val stts: ByteArray, private val ends: IntArray, private val tickStarts: LongArray,
    private val ctts: ByteArray?, private val compositionEnds: IntArray) {
    fun durationTicks(sample: Int): Long = uint(stts, 20 + run(ends, sample) * 8)
    fun decodeTicks(sample: Int): Long {
        val r = run(ends, sample)
        val before = if(r == 0) 0 else ends[r - 1]
        return Math.addExact(tickStarts[r], Math.multiplyExact((sample - before).toLong(), uint(stts, 20 + r * 8)))
    }
    fun compositionOffsetTicks(sample: Int): Long {
        require(sample in 0 until sampleCount)
        if(compositionEnds.isEmpty()) return 0
        // Pinned BoxParser 1.11.1 reads the offset as signed int for both
        // supported ctts versions. Retain its actual extraction time origin.
        return int(requireNotNull(ctts), 20 + run(compositionEnds, sample) * 8).toLong()
    }
    fun presentationTicks(sample: Int) = Math.addExact(decodeTicks(sample), compositionOffsetTicks(sample))
    fun decodeUs(sample: Int) = micros(decodeTicks(sample))
    fun presentationUs(sample: Int) = micros(presentationTicks(sample))
    fun durationUs(sample: Int) = micros(durationTicks(sample))
    fun micros(ticks: Long): Long = try { Math.multiplyExact(ticks, 1_000_000L) / timescale }
        catch(_: ArithmeticException) { BigInteger.valueOf(ticks).multiply(BigInteger.valueOf(1_000_000L)).divide(BigInteger.valueOf(timescale)).longValueExact() }

    private fun run(bounds: IntArray, sample: Int): Int {
        require(sample in 0 until sampleCount)
        var low = 0; var high = bounds.size
        while(low < high) { val mid = (low + high) ushr 1; if(bounds[mid] <= sample) low = mid + 1 else high = mid }
        check(low < bounds.size)
        return low
    }
    companion object {
        fun from(metadata: CachedMp4Metadata, trackId: Int): Mp4RawSampleTiming? {
            val table = metadata.tables.singleOrNull { it.track.id == trackId } ?: return null
            val trak = metadata.moov.containerChildren.singleOrNull { node ->
                if(node.type != code("trak")) false else node.getLeafBoxOfType(code("tkhd"))?.data?.data?.let {
                    val at = if(it.size >= 12 && (int(it, 8) ushr 24) == 1) 28 else 20
                    it.size >= at + 4 && int(it, at) == trackId
                } == true
            } ?: return null
            val stbl = trak.getContainerBoxOfType(code("mdia"))?.getContainerBoxOfType(code("minf"))?.getContainerBoxOfType(code("stbl")) ?: return null
            val size = (stbl.getLeafBoxOfType(code("stsz")) ?: stbl.getLeafBoxOfType(code("stz2")))?.data?.data ?: return null
            if(size.size < 20) return null
            val count = uint(size, 16)
            val decode = stbl.getLeafBoxOfType(code("stts"))?.data?.data ?: return null
            return parse(count, table.track.timescale, decode, stbl.getLeafBoxOfType(code("ctts"))?.data?.data)
        }
        internal fun parse(count: Long, scale: Long, decode: ByteArray, composition: ByteArray?): Mp4RawSampleTiming? = try {
            require(count in 0..2_000_000 && scale > 0 && decode.size >= 16 && (int(decode, 8) ushr 24) == 0)
            fun boundaries(data: ByteArray): IntArray {
                require(data.size >= 16)
                val entries = uint(data, 12)
                require(entries <= count && 16 + entries * 8 <= data.size)
                var sum = 0L
                return IntArray(entries.toInt()) { i ->
                    val n = uint(data, 16 + i * 8); require(n > 0)
                    sum += n; require(sum <= count)
                    sum.toInt()
                }.also { require((it.lastOrNull() ?: 0).toLong() == count) }
            }
            val ends = boundaries(decode)
            var ticks = 0L
            val starts = LongArray(ends.size) { i ->
                val before = ticks
                val n = ends[i] - if(i == 0) 0 else ends[i - 1]
                ticks = Math.addExact(ticks, Math.multiplyExact(n.toLong(), uint(decode, 20 + i * 8)))
                before
            }
            val cEnds = if(composition == null || composition.size >= 16 && uint(composition, 12) == 0L) IntArray(0) else {
                require((int(composition, 8) ushr 24) in 0..1)
                boundaries(composition)
            }
            Mp4RawSampleTiming(count.toInt(), scale, decode, ends, starts, composition, cEnds)
        } catch(_: Exception) { null }
        private fun code(text: String) = text.fold(0) { a, b -> a shl 8 or b.code }
        private fun int(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 255) shl 24) or ((bytes[at + 1].toInt() and 255) shl 16) or
            ((bytes[at + 2].toInt() and 255) shl 8) or (bytes[at + 3].toInt() and 255)
        private fun uint(bytes: ByteArray, at: Int) = int(bytes, at).toLong() and 0xffffffffL
    }
}
