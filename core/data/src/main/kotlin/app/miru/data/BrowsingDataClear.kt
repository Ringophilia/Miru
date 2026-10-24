// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.data

/** Capabilities are deliberately per type: Gecko 158 does not time-filter every store. */
enum class BrowsingDataType(val supportsTimeRange: Boolean) {
    HISTORY(true), COOKIES(true), SITE_DATA(false), WEB_CACHE(false), SITE_SETTINGS(false),
    AUTH_SESSIONS(false), FORM_DATA(false), PLAYBACK_PROGRESS(true), PLAYBACK_CACHE(false),
}

enum class BrowsingDataRange(private val durationMs: Long?) {
    LAST_HOUR(60 * 60_000L), LAST_DAY(24 * 60 * 60_000L), LAST_WEEK(7 * 24 * 60 * 60_000L),
    LAST_FOUR_WEEKS(28 * 24 * 60 * 60_000L), ALL_TIME(null);
    fun since(now: Long): Long = durationMs?.let { (now - it).coerceAtLeast(0) } ?: 0
}

data class BrowsingDataClearRequest(
    val types: Set<BrowsingDataType>,
    val range: BrowsingDataRange,
    val requestedAt: Long = System.currentTimeMillis(),
) {
    val since: Long get() = range.since(requestedAt)
    val isValid: Boolean get() = types.isNotEmpty() && requestedAt >= 0 &&
        (range == BrowsingDataRange.ALL_TIME || types.all { it.supportsTimeRange })
}

/** Safe to display and retain across Activity recreation: no site identifiers or exceptions. */
data class BrowsingDataClearResult(
    val completed: Set<BrowsingDataType> = emptySet(),
    val failed: Set<BrowsingDataType> = emptySet(),
) {
    val successful: Boolean get() = completed.isNotEmpty() && failed.isEmpty()
}
