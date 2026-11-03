// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.extractor.mp4.Track
import androidx.media3.extractor.mp4.TrackSampleTable
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** Native ordered AVC and AVC/AAC read closure. Other profiles still require adapters. */
internal object Mp4AvCachedTimelineIndex {
    fun tracks(selected: Mp4CachedSelection.Selected, session: PlaybackSourceSession, cache: Cache,
        metadata: CachedMp4Metadata, positionInWindowUs: Long, durationUs: Long,
        lengths: (String) -> Long, context: CoroutineContext): List<CachedTrackCoverage> {
        if(durationUs<=0)return emptyList()
        val tables=metadata.tables.filter { it.sampleCount>0 && it.track.shouldBeExposed }
        val outputs=selected.entry.tracks
        val videoOnly=tables.size==1 && selected.outputs.map { it.type }==listOf(C.TRACK_TYPE_VIDEO)
        val av=tables.size>=2 && selected.outputs.size==2 &&
            selected.outputs.map { it.type }.toSet()==setOf(C.TRACK_TYPE_VIDEO,C.TRACK_TYPE_AUDIO)
        if(outputs.size!=tables.size || !videoOnly && !av)return emptyList()
        val ends=ArrayList<LongArray>()
        for((ordinal,table)in tables.withIndex()) {
            context.ensureActive()
            val native=outputs.singleOrNull { it.ordinal==ordinal } ?: return emptyList()
            if(native.type!=table.track.type || native.format?.id!=table.track.id.toString() ||
                table.track.sampleTransformation!=Track.TRANSFORMATION_NONE || table.track.chapterTrackId!=C.INDEX_UNSET)return emptyList()
            val mime=table.track.format.sampleMimeType
            if(mime!=MimeTypes.VIDEO_H264 && mime!=MimeTypes.AUDIO_AAC)return emptyList()
            if(mime==MimeTypes.AUDIO_AAC && (table.track.format.codecs!="mp4a.40.2" || table.track.nalUnitLengthFieldLength!=0))return emptyList()
            if(mime==MimeTypes.VIDEO_H264 && table.track.nalUnitLengthFieldLength !in 1..4)return emptyList()
            if(table.track.format.initializationData.isEmpty() || native.format?.drmInitData!=null)return emptyList()
            try { for(description in 0 until 256)if(table.track.getSampleDescriptionEncryptionBox(description)!=null)return emptyList() }
            catch(_: IndexOutOfBoundsException) { }
            val timing=Mp4RawSampleTiming.from(metadata,table.track.id) ?: return emptyList()
            if(timing.sampleCount!=table.sampleCount)return emptyList()
            val edits=table.track.editListMediaTimes
            if(edits!=null && (edits.length()!=1 || edits.get(0)<0))return emptyList()
            // Gapless AAC may retain raw timestamps while signalling delay/padding
            // through Format. Infer no shift unless every native timestamp proves it.
            val origins=listOfNotNull(0L,edits?.get(0)).distinct().filter { origin ->
                table.timestampsUs.indices.all { i ->
                    context.ensureActive()
                    table.timestampsUs[i]==timing.micros(Math.subtractExact(timing.presentationTicks(i),origin))
                }
            }
            if(origins.size!=1)return emptyList()
            val origin=origins.single()
            ends+=LongArray(table.sampleCount) { i ->
                require(table.sizes[i]>0)
                timing.micros(Math.addExact(Math.subtractExact(timing.presentationTicks(i),origin),timing.durationTicks(i)))
            }
        }
        val selectedOrdinals=selected.outputs.map { it.ordinal }
        if(selectedOrdinals.any { it !in tables.indices })return emptyList()
        // Reordered video needs a decoder-output/reorder closure in addition to
        // sample input dependencies. Keep that profile pending rather than
        // treating input of a B-frame as proof that it can already be output.
        for(ordinal in selectedOrdinals)if(tables[ordinal].track.type==C.TRACK_TYPE_VIDEO) {
            val times=tables[ordinal].timestampsUs
            if((1 until times.size).any { times[it]<times[it-1] })return emptyList()
        }
        val proof=selected.entry.metadataProof ?: return emptyList()
        val uri=proof.reads.map { it.request.uri }.distinct().singleOrNull() ?: return emptyList()
        val map=selected.entry.seekMap ?: return emptyList()
        val metadataBytes=proof.reads.map { it.range }
        val layout=Mp4ReadSchedule.Layout(tables.map { Mp4ReadSchedule.Track(it.offsets,it.sizes,it.timestampsUs,it.track.type==C.TRACK_TYPE_VIDEO) })
        // Include unselected tracks' boundaries too: native seek indices and
        // interleaving can change without a selected output changing its sample.
        val cuts=java.util.TreeSet<Long>()
        for((track,table)in tables.withIndex())for(i in 0 until table.sampleCount) {
            context.ensureActive();cuts+=table.timestampsUs[i];cuts+=ends[track][i]
            // Renderer decode-only changes immediately after an exact PTS.
            // Keep the one-microsecond boundary cell distinct from its interior.
            if(track in selectedOrdinals && table.timestampsUs[i]<Long.MAX_VALUE)cuts+=Math.addExact(table.timestampsUs[i],1)
        }
        val localEnd=Math.subtractExact(durationUs,positionInWindowUs)
        val localStart=maxOf(0,Math.negateExact(positionInWindowUs))
        cuts+=localStart;cuts+=localEnd
        val sorted=cuts.filter { it>=localStart && it<=localEnd }
        val presentation=tables.map { t -> t.timestampsUs.indices.sortedBy { t.timestampsUs[it] } }
        for(ordinal in selectedOrdinals) {
            val order=presentation[ordinal]
            for(i in 0 until order.lastIndex) {
                context.ensureActive()
                // Ambiguous/overlapping edits need a different presentation map.
                if(ends[ordinal][order[i]]>tables[ordinal].timestampsUs[order[i+1]])return emptyList()
            }
        }
        val rap=HashMap<Triple<Int,Int,String>,Boolean>();val intervals=ArrayList<CachedMediaInterval>()
        // URI and main-session headers are immutable during this mapping. Keep
        // the exact parent position in the key cache; different Range parents
        // must remain distinct even if today's key factory normalizes them alike.
        val parentKeys=HashMap<Long,String>()
        val queriedKeys=HashSet<String>()
        for(cell in 0 until sorted.lastIndex) {
            context.ensureActive()
            val from=sorted[cell];val to=sorted[cell+1]
            if(from>=to)continue
            val targets=IntArray(tables.size) { -1 };var valid=true
            for(ordinal in selectedOrdinals) {
                val table=tables[ordinal]
                val order=presentation[ordinal]
                var low=0;var high=order.size
                while(low<high) { val mid=(low+high) ushr 1;if(table.timestampsUs[order[mid]]<=from)low=mid+1 else high=mid }
                val at=low-1
                if(at<0 || ends[ordinal][order[at]]<to) { valid=false;break }
                var target=order[at]
                // Prove every discrete microsecond in the cell, including the
                // first non-decode-only output after an interior seek. Native
                // video may display its final frame at EOS despite decode-only.
                if(table.timestampsUs[target]<to-1) {
                    if(low<order.size)target=maxOf(target,order[low])
                    else if(table.track.type!=C.TRACK_TYPE_VIDEO || target!=table.sampleCount-1) { valid=false;break }
                }
                targets[ordinal]=target
            }
            if(!valid)continue
            val initial=IntArray(tables.size) { i -> sync(tables[i],from) }
            if(initial.any { it<0 } || selectedOrdinals.any { initial[it]>targets[it] })continue
            val position=map.getSeekPoints(from).first.position
            if(position<0)continue
            val schedule=layout.cursor(initial,position)
            val required=ArrayList<CachedResourceRange>(metadataBytes)
            val reached=BooleanArray(tables.size) { it !in selectedOrdinals }
            while(reached.any { !it }) {
                context.ensureActive()
                val read=schedule.next() ?: run { valid=false;break }
                val key=parentKeys.getOrPut(read.parentPosition) {
                    // Memoization must not grow with every fragment of a long
                    // or physically reordered file. Eviction only recomputes a key.
                    if(parentKeys.size>=256)parentKeys.clear()
                    session.cacheKey(DataSpec.Builder().setUri(uri).setPosition(read.parentPosition).build())
                }
                // This callback registers metadata dependencies in the coherent
                // snapshot. Each unique key needs registration once per build.
                if(queriedKeys.add(key))lengths(key)
                if(tables[read.track].track.type==C.TRACK_TYPE_VIDEO && read.sample==initial[read.track]) {
                    val table=tables[read.track]
                    val isIdr=rap.getOrPut(Triple(read.track,read.sample,key)) {
                        Mp4AvcCachedTimelineIndex.idr(cache,Mp4CacheRequest(uri,read.parentPosition,-1,key),table.offsets[read.sample],table.sizes[read.sample],table.track.nalUnitLengthFieldLength,context)
                    }
                    if(!isIdr) { valid=false;break }
                }
                val last=required.lastOrNull()
                if(last!=null && last.key==key && last.endExclusive==read.position) {
                    required[required.lastIndex]=CachedResourceRange(key,last.position,Math.addExact(last.length,read.length))
                } else required+=CachedResourceRange(key,read.position,read.length)
                if(targets[read.track]>=0 && read.sample>=targets[read.track])reached[read.track]=true
            }
            if(valid)intervals+=CachedMediaInterval(CachedTimeRange(Math.addExact(from,positionInWindowUs),Math.addExact(to,positionInWindowUs)),required)
        }
        return listOf(CachedTrackCoverage(if(videoOnly) "mp4-selected-video" else "mp4-selected-av",intervals))
    }

    private fun sync(table: TrackSampleTable,time: Long): Int = table.getIndexOfEarlierOrEqualSynchronizationSample(time).let {
        if(it==C.INDEX_UNSET)table.getIndexOfLaterOrEqualSynchronizationSample(time) else it
    }
}
