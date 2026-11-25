// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.downloads

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadDateGroupTest {
    @Test fun bucketsClassifyTodayYesterdayThisWeekAndEarlier() {
        val zone = ZoneOffset.UTC
        // Wednesday 2026-11-25
        val today = LocalDate.of(2026, 11, 25)
        fun epoch(date: LocalDate) = date.atTime(12, 0).toInstant(zone).toEpochMilli()

        assertEquals(DownloadDateGroup.TODAY, DownloadLogic.dateGroup(epoch(today), zone, today))
        assertEquals(DownloadDateGroup.YESTERDAY, DownloadLogic.dateGroup(epoch(today.minusDays(1)), zone, today))
        assertEquals(DownloadDateGroup.THIS_WEEK, DownloadLogic.dateGroup(epoch(today.minusDays(2)), zone, today))
        assertEquals(DownloadDateGroup.EARLIER, DownloadLogic.dateGroup(epoch(today.minusDays(10)), zone, today))
    }
}
