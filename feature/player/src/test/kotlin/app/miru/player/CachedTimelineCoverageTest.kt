// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import org.junit.Assert.*
import org.junit.Test

class CachedTimelineCoverageTest {
    @Test fun discontinuousVbrBytesPreserveTheUncachedFourToEightSecondHole() {
        // Equal four-second intervals have strongly unequal encoded byte sizes.
        val first = bytes("movie", 10, 20)
        val middle = bytes("movie", 30, 4_000)
        val last = bytes("movie", 4_030, 37)
        val track = track("period0/video/hd", unit(0, 4, first), unit(4, 8, middle), unit(8, 12, last))
        assertEquals(listOf(time(0, 4), time(8, 12)), compute(listOf(track), first, last))
        assertEquals(listOf(time(0, 12)), compute(listOf(track), first, middle, last))
    }

    @Test fun oneByteHoleFailsButAdjacentFragmentsTogetherSupplyTheWholeDependency() {
        val requirement = bytes("chunk", 200, 100)
        val track = track("video", unit(0, 4, requirement))
        val beforeHole = bytes("chunk", 200, 49)
        val afterHole = bytes("chunk", 250, 50)
        assertTrue(compute(listOf(track), beforeHole, afterHole).isEmpty())
        assertEquals(listOf(time(0, 4)), compute(listOf(track), afterHole, bytes("chunk", 249, 1), beforeHole))
    }

    @Test fun overlappingUnsortedAndDuplicateSpansAreCompleteEvidenceWithoutLengthSummation() {
        val requirement = bytes("chunk", 100, 100)
        val track = track("video", unit(0, 4, requirement))
        assertEquals(listOf(time(0, 4)), compute(listOf(track),
            bytes("chunk", 170, 30), bytes("chunk", 100, 80), bytes("chunk", 100, 80)))
        // Two copies of the first half do not supply the missing second half.
        assertTrue(compute(listOf(track), bytes("chunk", 100, 50), bytes("chunk", 100, 50)).isEmpty())
    }

    @Test fun aSpanEndingAtTheRequiredStartDoesNotContainTheFollowingByte() {
        val track = track("video", unit(0, 4, bytes("chunk", 200, 1)))
        assertTrue(compute(listOf(track), bytes("chunk", 100, 100)).isEmpty())
        assertEquals(listOf(time(0, 4)), compute(listOf(track), bytes("chunk", 200, 1)))
    }

    @Test fun selectedAudioAndVideoCoverageIsTheirIntersection() {
        val videoBytes = bytes("video", 0, 10)
        val audioBytes = bytes("audio", 0, 10)
        val tracks = listOf(
            track("period0/video/hd", unit(0, 4, videoBytes), unit(8, 12, videoBytes)),
            track("period0/audio/en", unit(2, 6, audioBytes), unit(9, 11, audioBytes)),
        )
        assertEquals(listOf(time(2, 4), time(9, 11)), compute(tracks, videoBytes, audioBytes))
        assertTrue(compute(tracks, videoBytes).isEmpty())
    }

    @Test fun anEmptySelectedTrackAndAnEmptyPlanCannotClaimCoverage() {
        val media = bytes("video", 0, 10)
        val video = track("video", unit(0, 4, media))
        assertTrue(compute(listOf(video, track("audio")), media).isEmpty())
        assertTrue(compute(emptyList(), media).isEmpty())
    }

    @Test fun everyRequiredTrackParticipatesInTheIntersection() {
        val media = bytes("media", 0, 1)
        val tracks = listOf(track("first", unit(0, 12, media)), track("second", unit(2, 10, media)),
            track("third", unit(3, 4, media), unit(8, 11, media)))
        assertEquals(listOf(time(3, 4), time(8, 10)), compute(tracks, media))
    }

    @Test fun everyAesInitKeyAndMediaDependencyMustRemainCached() {
        val init = bytes("init", 50, 100)
        val key = bytes("key", 0, 16)
        val media = bytes("media", 200, 500)
        val dependencies = listOf(init, key, media)
        val track = track("video", unit(0, 4, *dependencies.toTypedArray()))
        for(missing in dependencies) {
            assertTrue("Missing ${missing.key} must invalidate the segment", compute(listOf(track),
                *dependencies.filterNot { it == missing }.toTypedArray()).isEmpty())
        }
        assertEquals(listOf(time(0, 4)), compute(listOf(track), *dependencies.toTypedArray()))
    }

    @Test fun cachedPredictedFrameRequiresTheCompleteRandomAccessDependencyClosure() {
        val sync = bytes("movie", 100, 20)
        val referenceFrame = bytes("movie", 1_000, 70)
        val currentFrame = bytes("movie", 3_000, 10)
        val target = track("video", unit(8, 9, sync, referenceFrame, currentFrame))
        assertTrue(compute(listOf(target), currentFrame).isEmpty())
        assertTrue(compute(listOf(target), sync, currentFrame).isEmpty())
        assertEquals(listOf(time(8, 9)), compute(listOf(target), sync, referenceFrame, currentFrame))
    }

    @Test fun partialSamplesNeverTurnIntoProportionalTimeCoverage() {
        val track = track("video", unit(0, 12, bytes("movie", 0, 1_000)))
        assertTrue(compute(listOf(track), bytes("movie", 0, 999)).isEmpty())
    }

    @Test fun emptyUnknownDependenciesNeverTurnIntoAvailableTime() {
        val unknown = track("video", unit(0, 12))
        assertTrue(compute(listOf(unknown), bytes("movie", 0, 1_000)).isEmpty())
    }

    @Test fun cacheEvictionShrinksNewCoverageWithoutChangingAnEarlierResult() {
        val first = bytes("movie", 0, 100)
        val last = bytes("movie", 500, 100)
        val track = track("video", unit(0, 4, first), unit(8, 12, last))
        val original = compute(listOf(track), first, last)
        assertEquals(listOf(time(8, 12)), compute(listOf(track), last))
        assertTrue(compute(listOf(track)).isEmpty())
        assertEquals(listOf(time(0, 4), time(8, 12)), original)
    }

    @Test fun identicalByteOffsetsInAnotherAuthOrRepresentationKeyDoNotMatch() {
        val expected = bytes("auth-a/video-hd", 0, 100)
        val track = track("period0/video/hd", unit(0, 4, expected))
        assertTrue(compute(listOf(track), bytes("auth-b/video-hd", 0, 100)).isEmpty())
        assertTrue(compute(listOf(track), bytes("auth-a/video-sd", 0, 100)).isEmpty())
        assertEquals(listOf(time(0, 4)), compute(listOf(track), expected))
    }

    @Test fun adjacentTimesMergeButNeitherGapsNorTouchingTracksProduceFalseIntersections() {
        val media = bytes("media", 0, 1)
        val track = track("video", unit(8, 12, media), unit(2, 4, media), unit(0, 2, media))
        assertEquals(listOf(time(0, 4), time(8, 12)), compute(listOf(track), media))
        assertTrue(compute(listOf(track("video", unit(0, 4, media)),
            track("audio", unit(4, 8, media))), media).isEmpty())
    }

    @Test fun durationClipsTheWindowWithoutOverflowOrNegativeIntervals() {
        val media = bytes("media", Long.MAX_VALUE - 1, 1)
        val track = CachedTrackCoverage("video", listOf(CachedMediaInterval(CachedTimeRange(5, Long.MAX_VALUE), listOf(media))))
        assertEquals(listOf(CachedTimeRange(5, 10)), CachedTimelineCoverage.compute(listOf(track), spans(media), durationUs = 10))
        assertTrue(CachedTimelineCoverage.compute(listOf(track), spans(media), durationUs = 5).isEmpty())
        assertTrue(CachedTimelineCoverage.compute(listOf(track), spans(media), durationUs = 0).isEmpty())
        assertEquals(listOf(CachedTimeRange(5, Long.MAX_VALUE)), CachedTimelineCoverage.compute(listOf(track), spans(media)))
    }

    @Test fun invalidOrUnknownRangeCoordinatesAndDuplicateIdentitiesAreRejected() {
        rejects { bytes("", 0, 1) }
        rejects { bytes("movie", -1, 1) }
        rejects { bytes("movie", 0, -1) }
        rejects { bytes("movie", 0, 0) }
        rejects { bytes("movie", Long.MAX_VALUE, 1) }
        rejects { bytes("movie", 1, Long.MAX_VALUE) }
        rejects { CachedTimeRange(-1, 1) }
        rejects { CachedTimeRange(1, 1) }
        rejects { CachedTimeRange(2, 1) }
        rejects { track(" ") }
        rejects { CachedTimelineCoverage.compute(emptyList(), emptyMap(), durationUs = -1) }
        rejects { CachedTimelineCoverage.compute(listOf(track("same"), track("same")), emptyMap()) }
        rejects { CachedTimelineCoverage.compute(listOf(track("video", unit(0, 1, bytes("a", 0, 1)))),
            mapOf("a" to listOf(bytes("b", 0, 1)))) }
    }

    @Test fun intervalTrackAndReturnedListsAreIndependentUnmodifiableSnapshots() {
        val media = bytes("media", 0, 1)
        val requirements = arrayListOf(media)
        val interval = CachedMediaInterval(time(0, 4), requirements)
        requirements.clear()
        val intervals = arrayListOf(interval)
        val track = CachedTrackCoverage("video", intervals)
        intervals.clear()
        val inputSpans = arrayListOf(media)
        val byKey = mutableMapOf<String, Collection<CachedResourceRange>>(media.key to inputSpans)
        val result = CachedTimelineCoverage.compute(listOf(track), byKey)
        inputSpans.clear()
        byKey.clear()
        assertEquals(listOf(media), interval.requiredBytes)
        assertEquals(listOf(interval), track.intervals)
        assertEquals(listOf(time(0, 4)), result)
        rejectsMutation { (interval.requiredBytes as MutableList<*>).clear() }
        rejectsMutation { (track.intervals as MutableList<*>).clear() }
        rejectsMutation { (result as MutableList<*>).clear() }
    }

    private fun bytes(key: String, position: Long, length: Long) = CachedResourceRange(key, position, length)
    private fun time(startSeconds: Long, endSeconds: Long) = CachedTimeRange(startSeconds * 1_000_000, endSeconds * 1_000_000)
    private fun unit(start: Long, end: Long, vararg required: CachedResourceRange) = CachedMediaInterval(time(start, end), required.toList())
    private fun track(id: String, vararg intervals: CachedMediaInterval) = CachedTrackCoverage(id, intervals.toList())
    private fun spans(vararg spans: CachedResourceRange): Map<String, Collection<CachedResourceRange>> = spans.groupBy { it.key }
    private fun compute(tracks: List<CachedTrackCoverage>, vararg committed: CachedResourceRange) =
        CachedTimelineCoverage.compute(tracks, spans(*committed))

    private inline fun rejects(block: () -> Unit) {
        try { block(); fail("Expected IllegalArgumentException") } catch(_: IllegalArgumentException) { }
    }

    private inline fun rejectsMutation(block: () -> Unit) {
        try { block(); fail("Expected UnsupportedOperationException") } catch(_: UnsupportedOperationException) { }
    }
}
