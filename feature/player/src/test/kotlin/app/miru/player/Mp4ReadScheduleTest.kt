// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import org.junit.Assert.*
import org.junit.Test

class Mp4ReadScheduleTest {
    private fun track(vararg offset: Long, size: Int = 10, times: LongArray = LongArray(offset.size) { it.toLong() },
        video: Boolean = false, metadata: Boolean = false) = Mp4ReadSchedule.Track(offset, IntArray(offset.size) { size }, times, video, metadata)
    private fun all(schedule: Mp4ReadSchedule) = generateSequence { schedule.next() }.toList()

    @Test fun smallGapIsConsumedFromTheExistingParentAndAdjacentSamplesFollowIt() {
        assertEquals(listOf(Mp4ReadSchedule.Read(0,0,0,0,110,false), Mp4ReadSchedule.Read(0,1,0,110,15,false)),
            all(Mp4ReadSchedule(listOf(track(100,115)),intArrayOf(0),0)))
    }
    @Test fun exactReloadBoundaryDoesNotConsumeTheGap() {
        val limit = 256*1024L
        assertFalse(Mp4ReadSchedule(listOf(track(limit-1)),intArrayOf(0),0).next()!!.reopened)
        assertEquals(Mp4ReadSchedule.Read(0,0,limit,limit,10,true), Mp4ReadSchedule(listOf(track(limit)),intArrayOf(0),0).next())
    }
    @Test fun backwardsReadStartsAnotherRealParent() {
        assertEquals(listOf(0L,10L), all(Mp4ReadSchedule(listOf(track(100,10)),intArrayOf(0),0)).map { it.parentPosition })
    }
    @Test fun aPhysicallyNearbyLaterTrackIsPreferredWhileReadAheadIsBounded() {
        val tracks = listOf(track(1_000_000, times=longArrayOf(0)),track(10,times=longArrayOf(100)))
        assertEquals(listOf(1,0),all(Mp4ReadSchedule(tracks,intArrayOf(0,0),0)).map { it.track })
    }
    @Test fun largeReadAheadForcesTheEarlierLogicalTrackEvenWhenReloading() {
        val earlier = track(30_000_000, size=10*1024*1024, times=longArrayOf(0))
        val nearby = track(0,times=longArrayOf(1))
        assertEquals(0,Mp4ReadSchedule(listOf(earlier,nearby),intArrayOf(0,0),0).next()!!.track)
    }
    @Test fun equalTimestampsUseTheLaterTrackForAccumulatedByteOrder() {
        val first = track(0,times=longArrayOf(0))
        val largeLater = track(30_000_000,size=10*1024*1024,times=longArrayOf(0))
        assertEquals(1,Mp4ReadSchedule(listOf(first,largeLater),intArrayOf(0,0),0).next()!!.track)
    }
    @Test fun dependentMetadataBehindVideoTakesPriorityOverNearbyMedia() {
        val tracks = listOf(track(0,times=longArrayOf(100),video=true),track(20_000_000,times=longArrayOf(100),metadata=true))
        assertEquals(1,Mp4ReadSchedule(tracks,intArrayOf(0,0),0).next()!!.track)
    }
    @Test fun futureMetadataDoesNotTakePriorityAndNonMonotonicPtsKeepDecodeOrder() {
        val tracks = listOf(track(0,10,20,times=longArrayOf(0,200,100),video=true),track(99,times=longArrayOf(300),metadata=true))
        assertEquals(listOf(0,1,2),all(Mp4ReadSchedule(tracks,intArrayOf(0,0),0)).filter { it.track==0 }.map { it.sample })
    }
    @Test fun alreadyFinishedTracksAndInitialSeekIndicesDoNotReadEarlierSamples() {
        val tracks = listOf(track(0,10,20),track(100))
        assertEquals(listOf(2),all(Mp4ReadSchedule(tracks,intArrayOf(2,1),20)).map { it.sample })
        assertNull(Mp4ReadSchedule(emptyList(),intArrayOf(),0).next())
    }
    @Test fun callerMutationCannotChangeAnExistingTrackOrCursor() {
        val offsets=longArrayOf(10);val sizes=intArrayOf(10);val times=longArrayOf(0);val indices=intArrayOf(0)
        val t=Mp4ReadSchedule.Track(offsets,sizes,times);val tracks=mutableListOf(t)
        val schedule=Mp4ReadSchedule(tracks,indices,0)
        offsets[0]=999;sizes[0]=1;times[0]=Long.MAX_VALUE;indices[0]=1;tracks.clear()
        assertEquals(Mp4ReadSchedule.Read(0,0,0,0,20,false),schedule.next())
    }
    @Test fun invalidCoordinatesAndOverflowFailBeforePublication() {
        assertThrows(IllegalArgumentException::class.java) { track(-1) }
        assertThrows(IllegalArgumentException::class.java) { track(Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { Mp4ReadSchedule(listOf(track(0)),intArrayOf(2),0) }
        assertThrows(IllegalArgumentException::class.java) { Mp4ReadSchedule(listOf(track(0)),intArrayOf(),0) }
    }
}
