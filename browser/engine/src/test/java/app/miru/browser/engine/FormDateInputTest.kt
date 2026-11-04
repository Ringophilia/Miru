// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.time.LocalDate
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class FormDateInputTest {
    @Test fun dateRangeLeapDaysAndStepUseTheHtmlMinimumAsTheStepBase() {
        val date = FormDateInput(FormDateType.DATE, "2026-04-10", "2026-04-06", "2026-04-30", "2")
        assertTrue(date.isValid("2026-04-26"))
        for(value in listOf("2026-04-25", "2026-05-02", "2026-02-30", "2026-4-6", "")) assertFalse(value, date.isValid(value))
        assertTrue(FormDateInput(FormDateType.DATE, "", null, null, null).isValid("2024-02-29"))
    }
    @Test fun monthAndIsoWeekRangesHaveTheirOwnUnitsAndRejectInvalidWeek53() {
        val month = FormDateInput(FormDateType.MONTH, "2026-01", "2026-01", "2026-12", "2")
        assertTrue(month.isValid("2026-05")); assertFalse(month.isValid("2026-04"))
        assertFalse(month.isValid("2026-13"))
        val week = FormDateInput(FormDateType.WEEK, "2020-W51", "2020-W51", "2021-W04", "2")
        assertTrue(week.isValid("2020-W53")); assertTrue(week.isValid("2021-W02"))
        assertFalse(week.isValid("2021-W53")); assertFalse(week.isValid("2021-W01"))
        assertEquals("2020-W53", week.withDate("", LocalDate.of(2021, 1, 1)))
    }
    @Test fun timeSupportsFractionalSecondsAndRangesThatCrossMidnight() {
        val time = FormDateInput(FormDateType.TIME, "23:30", "23:00", "01:00", "0.001")
        assertTrue(time.isValid("00:30:15.125")); assertTrue(time.isValid("23:45"))
        assertFalse(time.isValid("12:00")); assertFalse(time.isValid("24:00"))
        assertFalse(time.isValid("00:30:15.1234"))
        assertEquals("00:45:15.125", time.withTime("00:30:15.125", 0, 45))
    }
    @Test fun datetimeLocalIsAWallClockEvenDuringADaylightSavingGap() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val spec = FormDateInput(FormDateType.DATE_AND_TIME, "2026-03-08T01:30", "2026-03-08T01:00", "2026-03-08T04:00", "900")
            assertEquals("2026-03-08T02:30", spec.normalized("2026-03-08T02:30"))
            assertTrue(spec.isValid("2026-03-08T02:30")); assertFalse(spec.isValid("2026-03-08T02:31"))
            assertEquals("2026-03-09T02:30", spec.withDate("2026-03-08T02:30", LocalDate.of(2026, 3, 9)))
        } finally { TimeZone.setDefault(previous) }
    }
    @Test fun anyStepAndAnInitialValueBaseAreDistinctFromTheDefaultStep() {
        val date = FormDateInput(FormDateType.DATE, "2026-04-05", null, null, "2")
        assertTrue(date.isValid("2026-04-07")); assertFalse(date.isValid("2026-04-08"))
        assertTrue(FormDateInput(FormDateType.TIME, "", null, null, "any").isValid("10:23:12.123"))
        assertFalse(FormDateInput(FormDateType.TIME, "", null, null, null).isValid("10:23:12"))
    }
    @Test fun colorOnlyAcceptsSixHexDigitsAndNormalizesCase() {
        assertEquals("#12ab34", normalizedFormColor("#12AB34"))
        for(value in listOf("red", "#fff", "#12345678", "#gg0000", " #123456", "#123456\n")) assertNull(value, normalizedFormColor(value))
    }
}
