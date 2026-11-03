// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.*
import androidx.media3.extractor.mp4.Mp4Extractor
import org.json.JSONObject
import org.junit.Assert.*

/** Independent native reads, without calling Miru's read scheduler or target lookup. */
internal object Mp4AvNativeReadOracle {
    fun verify(body: ByteArray, session: PlaybackSourceSession, selected: Mp4CachedSelection.Selected,
        indexed: List<CachedTrackCoverage>, report: JSONObject) {
        assertEquals(1,indexed.size)
        val intervals=indexed.single().intervals
        assertTrue(intervals.isNotEmpty())
        val uri=checkNotNull(selected.entry.metadataProof).reads.map { it.request.uri }.distinct().single()
        val selectedIds=selected.outputs.map { it.ordinal }.toSet()
        val videoIds=selected.outputs.filter { it.type==C.TRACK_TYPE_VIDEO }.map { it.ordinal }.toSet()
        val scratch=ByteArray(32768)
        var map: SeekMap?=null
        var cursor=0L;var parent=0L;var requested=0L
        var checking=false
        var required=emptyList<CachedResourceRange>()
        val reached=HashSet<Int>()
        var checks=0L;var reads=0L;var bytes=0L
        fun input(position: Long): DefaultExtractorInput {
            cursor=position
            return DefaultExtractorInput(DataReader { target,offset,length ->
                if(cursor>=body.size) C.RESULT_END_OF_INPUT else {
                    val count=minOf(length.toLong(),body.size-cursor).toInt()
                    if(checking) {
                        val key=session.cacheKey(DataSpec.Builder().setUri(uri).setPosition(parent).build())
                        assertTrue("Native seek $requested consumed unproved bytes $cursor+$count under parent $parent",
                            required.any { it.key==key && it.position<=cursor && it.endExclusive>=cursor+count })
                        reads++;bytes+=count
                    }
                    body.copyInto(target,offset,cursor.toInt(),cursor.toInt()+count)
                    cursor+=count;count
                }
            },position,body.size.toLong())
        }
        val native=DefaultExtractorsFactory().createExtractors().filterIsInstance<Mp4Extractor>().single()
        native.init(object : ExtractorOutput {
            override fun track(id: Int,type: Int): TrackOutput = object : TrackOutput {
                override fun format(format: Format) = Unit
                override fun sampleData(input: DataReader,length: Int,allowEndOfInput: Boolean,sampleDataPart: Int): Int =
                    input.read(scratch,0,minOf(length,scratch.size))
                override fun sampleData(data: ParsableByteArray,length: Int,sampleDataPart: Int) { data.skipBytes(length) }
                override fun sampleMetadata(timeUs: Long,flags: Int,size: Int,offset: Int,cryptoData: TrackOutput.CryptoData?) {
                    if(checking && id in selectedIds &&
                        (timeUs>=requested || id in videoIds && flags and C.BUFFER_FLAG_LAST_SAMPLE!=0))reached+=id
                }
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
                    Extractor.RESULT_SEEK -> { parent=holder.position;current=input(parent) }
                    Extractor.RESULT_END_OF_INPUT -> error("No native seek map")
                }
            }
            for(interval in intervals)for(time in listOf(interval.time.startUs,interval.time.endUs-1).distinct()) {
                requested=time;required=interval.requiredBytes;reached.clear()
                parent=checkNotNull(map).getSeekPoints(time).first.position
                native.seek(parent,time);current=input(parent);checking=true;attempts=0
                while(!reached.containsAll(selectedIds)) {
                    check(++attempts<10000)
                    when(native.read(current,holder)) {
                        Extractor.RESULT_SEEK -> { parent=holder.position;current=input(parent) }
                        Extractor.RESULT_END_OF_INPUT -> error("Native seek $time ended before selected outputs $selectedIds; reached $reached")
                    }
                }
                checking=false;checks++
            }
        } finally { native.release() }
        report.put("nativePresentationCells",intervals.size).put("nativeEndpointSeekChecks",checks)
            .put("nativeConsumedReads",reads).put("nativeConsumedBytes",bytes)
    }
}
