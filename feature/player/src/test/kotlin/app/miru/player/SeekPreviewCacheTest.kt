// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import org.junit.Assert.*
import org.junit.Test

class SeekPreviewCacheTest {
    @Test fun sixtyEntriesEvictTheLeastRecentlyAccessedRatherThanTheOldestInserted() {
        val cache = SeekPreviewCache<String>()
        val first = SeekPreviewFrame("first", 0)
        cache.put(0, first)
        for(index in 1L..59) cache.put(index * 10_000, SeekPreviewFrame("$index", index * 10_000))
        assertEquals(60, cache.size)
        assertSame(first, cache[9_999]) // Reading the oldest entry makes it most recent.
        cache.put(600_000, SeekPreviewFrame("sixty", 600_000))
        assertEquals(60, cache.size)
        assertNull(cache[10_000])
        assertSame(first, cache[0])
        assertNotNull(cache[20_000])
        assertEquals("sixty", cache[609_999]?.image)
    }

    @Test fun positionsShareFloorBucketsAndNegativeOrExtremePositionsCannotOverflow() {
        val cache = SeekPreviewCache<Any>()
        val image = Any()
        val first = SeekPreviewFrame(image, 120)
        cache.put(-100, first)
        assertSame(first, cache[Long.MIN_VALUE])
        assertSame(first, cache[9_999])
        assertNull(cache[10_000])
        val replacement = SeekPreviewFrame(Any(), 9_900)
        cache.put(8_000, replacement)
        assertEquals(1, cache.size)
        assertSame(replacement, cache[0])
        cache.put(Long.MAX_VALUE, first)
        assertSame(first, cache[Long.MAX_VALUE])
        assertEquals(9_223_372_036_854_770_000L, seekPreviewBucket(Long.MAX_VALUE))
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache[0])
        assertSame(image, first.image) // Clearing only drops the cache's references.
    }

    @Test fun configurableCapacityStaysWithinOneAndSixty() {
        for((requested, expected) in listOf(0 to 1, Int.MAX_VALUE to 60)) {
            val cache = SeekPreviewCache<Int>(requested)
            for(index in 0..70) cache.put(index * 10_000L, SeekPreviewFrame(index, index * 10_000L))
            assertEquals(expected, cache.size)
            assertEquals(70, cache[700_000]?.image)
        }
    }
}
