// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.DataReader
import androidx.media3.extractor.*
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Public native extractor output and actual consumed byte coordinates, without a decoder. */
@RunWith(AndroidJUnit4::class)
class Mp4ReadScheduleNativeTest {
    @Test fun interleavedVideoAndBothAudioTracksMatchEveryNativeReadAfterFourSeeks() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val body = assets.open("mp4-cache/av-colors.mp4").use { it.readBytes() }
        val reference = JSONObject(assets.open("mp4-cache/av-colors.raw.samples.json").bufferedReader().use { it.readText() })
        assertEquals(reference.getLong("bytes"), body.size.toLong())
        assertEquals(reference.getString("sha256"), MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) })
        val metadata = checkNotNull(CachedMp4MetadataReader.read({ position, length ->
            if(position < 0 || position + length > body.size) null else
                CachedMp4Block(body.copyOfRange(position.toInt(), position.toInt()+length), CachedResourceRange("owned",position,length.toLong()))
        }, body.size.toLong(), Mp4ParserProfile(true,true,true,C.VIDEO_CODEC_FLAG_H264 or C.VIDEO_CODEC_FLAG_H265)))
        val tables = metadata.tables.filter { it.sampleCount > 0 && it.track.shouldBeExposed }
        assertEquals(3,tables.size)
        // Check offsets/sizes against the raw, independent packet truth before using the native tables.
        tables.forEachIndexed { index, table ->
            val samples=reference.getJSONArray("tracks").getJSONObject(index).getJSONArray("samples")
            assertEquals(samples.length(),table.sampleCount)
            for(i in 0 until samples.length()) {
                assertEquals(samples.getJSONObject(i).getLong("offset"),table.offsets[i])
                assertEquals(samples.getJSONObject(i).getInt("size"),table.sizes[i])
            }
        }
        val events=ArrayList<Pair<Int,Long>>()
        val reads=ArrayList<Pair<Long,Int>>()
        var map: SeekMap?=null
        var cursor=0L
        fun input(position: Long): DefaultExtractorInput {
            cursor=position
            return DefaultExtractorInput(DataReader { buffer, offset, length ->
                if(cursor >= body.size) C.RESULT_END_OF_INPUT else {
                    val count=minOf(length.toLong(),body.size-cursor).toInt()
                    reads += cursor to count
                    body.copyInto(buffer,offset,cursor.toInt(),cursor.toInt()+count);cursor+=count;count
                }
            },position,body.size.toLong())
        }
        val native = DefaultExtractorsFactory().createExtractors().filterIsInstance<Mp4Extractor>().single()
        native.init(object : ExtractorOutput {
            override fun track(id: Int,type: Int): TrackOutput = object : TrackOutput {
                override fun format(format: Format) = Unit
                override fun sampleData(input: DataReader,length: Int,allowEndOfInput: Boolean,sampleDataPart: Int): Int {
                    val scratch=ByteArray(minOf(length,32768));return input.read(scratch,0,scratch.size)
                }
                override fun sampleData(data: ParsableByteArray,length: Int,sampleDataPart: Int) { data.skipBytes(length) }
                override fun sampleMetadata(timeUs: Long,flags: Int,size: Int,offset: Int,cryptoData: TrackOutput.CryptoData?) { events += id to timeUs }
            }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) { map=seekMap }
        })
        val holder=PositionHolder();var current=input(0)
        try {
            var attempts=0
            while(map==null) {
                check(++attempts<10000)
                when(native.read(current,holder)) {
                    Extractor.RESULT_SEEK -> current=input(holder.position)
                    Extractor.RESULT_END_OF_INPUT -> error("No native seek map")
                }
            }
            for(time in listOf(0L,4_500_000L,9_000_000L,11_900_000L)) {
                val position=checkNotNull(map).getSeekPoints(time).first.position
                val indices=IntArray(tables.size) { track ->
                    val table=tables[track]
                    table.getIndexOfEarlierOrEqualSynchronizationSample(time).let {
                        if(it==C.INDEX_UNSET)table.getIndexOfLaterOrEqualSynchronizationSample(time) else it
                    }
                }
                assertTrue(indices.all { it>=0 })
                val schedule=Mp4ReadSchedule(tables.map { Mp4ReadSchedule.Track(it.offsets,it.sizes,it.timestampsUs,it.track.type==C.TRACK_TYPE_VIDEO) },indices,position)
                native.seek(position,time);current=input(position);events.clear();reads.clear()
                var parent=position;var total=0
                while(true) {
                    val expected=schedule.next() ?: break
                    events.clear();reads.clear();var reopen=false;attempts=0
                    while(events.isEmpty()) {
                        check(++attempts<1000)
                        when(native.read(current,holder)) {
                            Extractor.RESULT_SEEK -> { parent=holder.position;current=input(parent);reopen=true }
                            Extractor.RESULT_END_OF_INPUT -> error("Native ended before schedule")
                        }
                    }
                    assertEquals(1,events.size)
                    assertEquals("$time track read $total",expected.track,events.single().first)
                    assertEquals(tables[expected.track].timestampsUs[expected.sample],events.single().second)
                    assertEquals(expected.sample,indices[expected.track]++)
                    assertEquals(expected.parentPosition,parent)
                    assertEquals(expected.reopened,reopen)
                    var at=expected.position
                    for((start,count) in reads) { assertEquals(at,start);at+=count }
                    assertEquals(expected.length,at-expected.position)
                    total++
                }
                assertTrue(total>0)
                assertEquals(Extractor.RESULT_END_OF_INPUT,native.read(current,holder))
                assertEquals(tables.map { it.sampleCount },indices.toList())
            }
        } finally { native.release() }
    }
}
