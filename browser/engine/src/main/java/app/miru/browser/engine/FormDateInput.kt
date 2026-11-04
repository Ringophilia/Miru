// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class FormDateType { DATE, MONTH, WEEK, TIME, DATE_AND_TIME }

/** HTML wall-clock values, not instants: DST and the device timezone must not shift them. */
data class FormDateInput(val type: FormDateType, val initial: String, val minimum: String?, val maximum: String?, val step: String?) {
    fun normalized(value: String): String? = runCatching {
        when(type) {
            FormDateType.DATE -> LocalDate.parse(value).takeIf { it.year in 1..9999 }?.toString()
            FormDateType.MONTH -> YearMonth.parse(value).takeIf { it.year in 1..9999 }?.toString()
            FormDateType.WEEK -> weekStart(value)?.let { "%04d-W%02d".format(Locale.ROOT, it.get(IsoFields.WEEK_BASED_YEAR), it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)) }
            FormDateType.TIME -> time(value)?.toString()
            FormDateType.DATE_AND_TIME -> {
                val parts = value.split('T')
                if(parts.size != 2) null else LocalDate.parse(parts[0]).takeIf { it.year in 1..9999 }?.let { date ->
                    time(parts[1])?.let { "$date" + "T" + it.toString() }
                }
            }
        }
    }.getOrNull()

    fun isValid(value: String): Boolean {
        val current = scalar(value) ?: return false
        val min = minimum?.let(::scalar); val max = maximum?.let(::scalar)
        if(type == FormDateType.TIME && min != null && max != null && min > max) {
            if(current < min && current > max) return false
        } else if((min != null && current < min) || (max != null && current > max)) return false
        if(step == "any") return true
        val increment = step?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            ?: if(type in setOf(FormDateType.TIME, FormDateType.DATE_AND_TIME)) BigDecimal(60) else BigDecimal.ONE
        val base = min ?: scalar(initial) ?: BigDecimal.ZERO
        return (current - base).remainder(increment).compareTo(BigDecimal.ZERO) == 0
    }

    fun suggested(): String = normalized(initial) ?: when(type) {
        FormDateType.DATE -> LocalDate.now().toString()
        FormDateType.MONTH -> YearMonth.now().toString()
        FormDateType.WEEK -> LocalDate.now().let { "%04d-W%02d".format(Locale.ROOT, it.get(IsoFields.WEEK_BASED_YEAR), it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)) }
        FormDateType.TIME -> LocalTime.now().withSecond(0).withNano(0).format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))
        FormDateType.DATE_AND_TIME -> LocalDateTime.now().withSecond(0).withNano(0).format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm", Locale.ROOT))
    }

    fun pickerDate(value: String): LocalDate {
        val clean = normalized(value) ?: suggested()
        return when(type) {
            FormDateType.DATE -> LocalDate.parse(clean)
            FormDateType.MONTH -> YearMonth.parse(clean).atDay(1)
            FormDateType.WEEK -> weekStart(clean)!!
            FormDateType.DATE_AND_TIME -> LocalDateTime.parse(clean).toLocalDate()
            FormDateType.TIME -> LocalDate.now()
        }
    }
    fun pickerTime(value: String): LocalTime {
        val clean = normalized(value) ?: suggested()
        return when(type) {
            FormDateType.TIME -> LocalTime.parse(clean)
            FormDateType.DATE_AND_TIME -> LocalDateTime.parse(clean).toLocalTime()
            else -> LocalTime.MIDNIGHT
        }
    }
    fun withDate(value: String, date: LocalDate): String = when(type) {
        FormDateType.DATE -> date.toString()
        FormDateType.MONTH -> YearMonth.from(date).toString()
        FormDateType.WEEK -> "%04d-W%02d".format(Locale.ROOT, date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
        FormDateType.DATE_AND_TIME -> "$date" + "T" + pickerTime(value)
        FormDateType.TIME -> value
    }
    fun withTime(value: String, hour: Int, minute: Int): String {
        val time = pickerTime(value).withHour(hour).withMinute(minute)
        return if(type == FormDateType.DATE_AND_TIME) pickerDate(value).toString() + "T" + time else time.toString()
    }

    private fun scalar(value: String): BigDecimal? = normalized(value)?.let { clean -> runCatching {
        when(type) {
            FormDateType.DATE -> BigDecimal(LocalDate.parse(clean).toEpochDay())
            FormDateType.MONTH -> YearMonth.parse(clean).let { BigDecimal((it.year - 1970L) * 12 + it.monthValue - 1) }
            FormDateType.WEEK -> BigDecimal((weekStart(clean)!!.toEpochDay() + 3) / 7)
            FormDateType.TIME -> LocalTime.parse(clean).let { BigDecimal(it.toNanoOfDay()).movePointLeft(9) }
            FormDateType.DATE_AND_TIME -> LocalDateTime.parse(clean).let { BigDecimal(it.toEpochSecond(ZoneOffset.UTC)) + BigDecimal(it.nano).movePointLeft(9) }
        }
    }.getOrNull() }

    private fun time(value: String): LocalTime? {
        if(!Regex("[0-9]{2}:[0-9]{2}(:[0-9]{2}(\\.[0-9]{1,3})?)?").matches(value)) return null
        return runCatching { LocalTime.parse(value) }.getOrNull()
    }
    private fun weekStart(value: String): LocalDate? {
        if(!Regex("[0-9]{4}-W[0-9]{2}").matches(value)) return null
        val year = value.take(4).toInt(); val week = value.takeLast(2).toInt()
        if(year !in 1..9999 || week !in 1..53) return null
        val day = LocalDate.of(year, 1, 4).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(week - 1L)
        return day.takeIf { it.get(IsoFields.WEEK_BASED_YEAR) == year }
    }
}

fun normalizedFormColor(value: String): String? = value.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) }?.lowercase(Locale.ROOT)
