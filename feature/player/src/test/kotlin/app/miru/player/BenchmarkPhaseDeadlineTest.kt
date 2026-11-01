// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import org.junit.Assert.*
import org.junit.Test

class BenchmarkPhaseDeadlineTest {
    @Test fun completedSixtyFrameMeasurementIsNotInvalidatedByTheObserved689msRelease() {
        val deadline = BenchmarkPhaseDeadline(0)
        assertTrue(deadline.beginRelease(44_722))
        assertFalse(deadline.expired(45_436))
    }

    @Test fun missingSamplesStillExpireAtTheOriginalFortyFiveSecondBoundary() {
        val deadline = BenchmarkPhaseDeadline(0)
        assertFalse(deadline.expired(44_999))
        assertTrue(deadline.expired(45_000))
        assertFalse(deadline.beginRelease(45_000))
        assertTrue(deadline.expired(45_001))
    }

    @Test fun stalledEglReleaseHasItsOwnTenSecondLimitIncludingTheExactBoundary() {
        val deadline = BenchmarkPhaseDeadline(1_000)
        assertTrue(deadline.beginRelease(2_000))
        assertFalse(deadline.expired(11_999))
        assertTrue(deadline.expired(12_000))
    }

    @Test fun duplicateReleaseCannotExtendOrReviveTheOriginalReleaseLimit() {
        val deadline = BenchmarkPhaseDeadline(0)
        assertTrue(deadline.beginRelease(10_000))
        assertFalse(deadline.beginRelease(19_999))
        assertTrue(deadline.expired(20_000))
        assertFalse(deadline.beginRelease(20_001))
    }

    @Test fun aFreshTierGetsASeparateMeasurementBudgetAndClockRegressionIsRejected() {
        val old = BenchmarkPhaseDeadline(0)
        assertTrue(old.beginRelease(10_000))
        assertTrue(old.expired(21_000))
        val next = BenchmarkPhaseDeadline(21_000)
        assertTrue(next.expired(20_999))
        assertFalse(next.expired(65_999))
        assertTrue(next.expired(66_000))
    }
}
