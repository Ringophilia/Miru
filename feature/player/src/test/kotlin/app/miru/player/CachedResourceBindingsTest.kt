// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CachedResourceBindingsTest {
    @Test fun unknownOpenOnlyBindsAfterActualEof() {
        val bindings = CachedResourceBindings()
        val request = bindings.begin(URL, 0, -1, "original-key")
        bindings.opened(request, -1)
        assertNull(bindings.snapshot().rangeFor(URL))
        bindings.ended(request, 73)
        assertEquals(CachedResourceRange("original-key", 0, 73), bindings.snapshot().rangeFor(URL))
        // Resolving length is not a second, ranged request with another key.
        assertNull(bindings.snapshot().rangeFor(URL, requestedLength = 73))
    }

    @Test fun trustedResolvedLengthDoesNotWaitForAReadOrInferCacheCommit() {
        val bindings = CachedResourceBindings()
        val request = bindings.begin(URL, 90, -1, "actual-key")
        bindings.opened(request, 30)
        val range = requireNotNull(bindings.snapshot().rangeFor(URL, 90))
        assertEquals(CachedResourceRange("actual-key", 90, 30), range)
        val tracks = listOf(CachedTrackCoverage("v",
            listOf(CachedMediaInterval(CachedTimeRange(0, 100), listOf(range)))))
        assertTrue(CachedTimelineCoverage.compute(tracks, emptyMap()).isEmpty())
    }

    @Test fun boundedRequestRequiresAllBytesAndKeepsItsOriginalCoordinates() {
        val bindings = CachedResourceBindings()
        val request = bindings.begin(URL, 41, 19, "key-for-41-19")
        bindings.opened(request, -1)
        assertEquals(CachedResourceRange("key-for-41-19", 41, 19), bindings.snapshot().rangeFor(URL, 41, 19))
        bindings.ended(request, 18)
        assertNull(bindings.snapshot().rangeFor(URL, 41, 19))
    }

    @Test fun shortResolvedBodyCannotShrinkABoundedSegmentRequirement() {
        val bindings = CachedResourceBindings()
        val request = bindings.begin(URL, 50, 100, "key")
        bindings.opened(request, 20)
        assertNull(bindings.snapshot().rangeFor(URL, 50, 100))
        bindings.ended(request, 20)
        assertNull(bindings.snapshot().rangeFor(URL, 50, 100))
    }

    @Test fun prematureEofInvalidatesThePreviouslyClaimedLength() {
        val bindings = CachedResourceBindings()
        val request = bindings.begin(URL, 0, -1, "key")
        bindings.opened(request, 100)
        assertNotNull(bindings.snapshot().rangeFor(URL))
        bindings.ended(request, 99)
        assertNull(bindings.snapshot().rangeFor(URL))
    }

    @Test fun failedReadOrOpenClearsOnlyItsOwnObservation() {
        val bindings = CachedResourceBindings()
        val old = bindings.begin(URL, 0, -1, "old")
        bindings.opened(old, 100)
        val current = bindings.begin(URL, 0, -1, "new")
        assertNull(bindings.snapshot().rangeFor(URL))
        bindings.opened(current, 77)
        bindings.failed(old)
        assertEquals("new", bindings.snapshot().rangeFor(URL)?.key)
        bindings.failed(current)
        assertNull(bindings.snapshot().rangeFor(URL))
    }

    @Test fun delayedOldOpenAndEofCannotReplaceNewRequestMetadata() {
        val bindings = CachedResourceBindings()
        val old = bindings.begin(URL, 0, -1, "old")
        val current = bindings.begin(URL, 0, -1, "new")
        bindings.opened(current, 77)
        val revision = bindings.revision.value
        bindings.opened(old, 1_000)
        bindings.ended(old, 1_000)
        assertEquals(CachedResourceRange("new", 0, 77), bindings.snapshot().rangeFor(URL))
        assertEquals(revision, bindings.revision.value)
    }

    @Test fun failedObservationCannotBeResurrectedByItsOwnLateEofOrOpen() {
        val bindings = CachedResourceBindings()
        val failed = bindings.begin(URL, 0, -1, "key")
        bindings.opened(failed, -1)
        bindings.failed(failed)
        bindings.ended(failed, 128)
        bindings.opened(failed, 128)
        assertNull("A failed prefix is not a newly discovered complete resource", bindings.snapshot().rangeFor(URL))
        val retry = bindings.begin(URL, 0, -1, "key")
        bindings.opened(retry, 1_000)
        assertEquals(1_000L, bindings.snapshot().rangeFor(URL)?.length)
    }

    @Test fun allOriginalRequestVariantsRemainDistinct() {
        val bindings = CachedResourceBindings()
        listOf(Triple(0L, -1L, "whole"), Triple(0L, 80L, "bounded"), Triple(10L, -1L, "tail"),
            Triple(10L, 20L, "range")).forEach { (position, length, key) ->
            bindings.opened(bindings.begin(URL, position, length, key), if(length == -1L) 80 else length)
        }
        assertEquals(4, bindings.snapshot().resourceCount)
        assertEquals("whole", bindings.snapshot().rangeFor(URL)?.key)
        assertEquals("bounded", bindings.snapshot().rangeFor(URL, 0, 80)?.key)
        assertEquals("tail", bindings.snapshot().rangeFor(URL, 10)?.key)
        assertEquals("range", bindings.snapshot().rangeFor(URL, 10, 20)?.key)
    }

    @Test fun exactUrlsAreNeverGuessedFromAnotherSignatureOrSemanticQuery() {
        val bindings = CachedResourceBindings()
        bindings.opened(bindings.begin(URL, 0, -1, "key"), 80)
        assertNull(bindings.snapshot().rangeFor("$URL&token=next"))
        assertNull(bindings.snapshot().rangeFor("$URL&quality=1080"))
    }

    @Test fun emptyInvalidAndOverflowingResourcesCannotBind() {
        val bindings = CachedResourceBindings()
        assertNull(bindings.begin("", 0, -1, "key"))
        assertNull(bindings.begin(URL, -1, -1, "key"))
        assertNull(bindings.begin(URL, 0, 0, "key"))
        assertNull(bindings.begin(URL, 0, -2, "key"))
        assertNull(bindings.begin(URL, 0, -1, ""))
        assertNull(bindings.begin(URL, 2, Long.MAX_VALUE, "key"))
        val observation = bindings.begin(URL, 2, -1, "key")
        bindings.opened(observation, Long.MAX_VALUE)
        assertNull(bindings.snapshot().rangeFor(URL, 2))
        bindings.ended(observation, Long.MAX_VALUE)
        assertNull(bindings.snapshot().rangeFor(URL, 2))
        bindings.ended(observation, 0)
        assertNull(bindings.snapshot().rangeFor(URL, 2))
    }

    @Test fun snapshotsDoNotChangeAfterReopenOrOwnerRetirement() {
        val bindings = CachedResourceBindings()
        val observation = bindings.begin(URL, 0, -1, "old")
        bindings.opened(observation, 80)
        val snapshot = bindings.snapshot()
        bindings.begin(URL, 0, -1, "replacement")
        assertEquals("old", snapshot.rangeFor(URL)?.key)
        bindings.close()
        assertEquals("old", snapshot.rangeFor(URL)?.key)
        assertEquals(0, bindings.snapshot().resourceCount)
        assertTrue(bindings.revision.value > snapshot.revision)
    }

    @Test fun terminalCloseRejectsNewRequestsAndAllLateCallbacks() {
        val bindings = CachedResourceBindings()
        val observation = bindings.begin(URL, 0, -1, "key")
        bindings.close()
        val revision = bindings.revision.value
        bindings.opened(observation, 80)
        bindings.ended(observation, 80)
        bindings.failed(observation)
        bindings.close()
        assertNull(bindings.begin(URL, 0, -1, "key"))
        assertEquals(0, bindings.snapshot().resourceCount)
        assertEquals(revision, bindings.revision.value)
    }

    @Test fun boundedMetadataEvictsOldestRequestsAndRejectsTheirLateEof() {
        val bindings = CachedResourceBindings(2)
        val old = bindings.begin("$URL&n=0", 0, -1, "old")
        bindings.opened(old, 80)
        bindings.opened(bindings.begin("$URL&n=1", 0, -1, "one"), 80)
        bindings.opened(bindings.begin("$URL&n=2", 0, -1, "two"), 80)
        bindings.ended(old, 80)
        assertEquals(2, bindings.snapshot().resourceCount)
        assertNull(bindings.snapshot().rangeFor("$URL&n=0"))
        assertEquals("one", bindings.snapshot().rangeFor("$URL&n=1")?.key)
        assertEquals("two", bindings.snapshot().rangeFor("$URL&n=2")?.key)
    }

    @Test fun reopeningRenewsEvictionOrder() {
        val bindings = CachedResourceBindings(2)
        bindings.begin(URL, 0, -1, "old")
        bindings.begin("other", 0, -1, "other")
        bindings.opened(bindings.begin(URL, 0, -1, "new"), 80)
        bindings.begin("third", 0, -1, "third")
        assertEquals("new", bindings.snapshot().rangeFor(URL)?.key)
        assertEquals(2, bindings.snapshot().resourceCount)
        assertNull(bindings.snapshot().rangeFor("other"))
    }

    @Test fun anotherThreadCannotResurrectRetiredSourceMetadata() {
        val bindings = CachedResourceBindings()
        val began = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit {
                val observation = bindings.begin(URL, 0, -1, "key")
                began.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                bindings.opened(observation, 80)
                bindings.ended(observation, 80)
            }
            assertTrue(began.await(5, TimeUnit.SECONDS))
            bindings.close()
            resume.countDown()
            result.get(5, TimeUnit.SECONDS)
            assertEquals(0, bindings.snapshot().resourceCount)
        } finally { resume.countDown(); executor.shutdownNow() }
    }

    companion object { private const val URL = "https://media.invalid/resource?token=synthetic" }
}
