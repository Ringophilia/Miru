// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

/** A completed measurement gets its own bounded EGL disconnect phase. */
internal class BenchmarkPhaseDeadline(private val startedAtMs: Long) {
    private val measurementDeadlineMs = Math.addExact(startedAtMs, MEASUREMENT_MS)
    private var releaseDeadlineMs: Long? = null
    val deadlineMs: Long get() = releaseDeadlineMs ?: measurementDeadlineMs

    init { require(startedAtMs >= 0) }

    fun expired(nowMs: Long): Boolean = nowMs < startedAtMs || nowMs >= deadlineMs

    fun beginRelease(nowMs: Long): Boolean {
        // A late measurement or a duplicate callback cannot renew the deadline.
        if(releaseDeadlineMs != null || expired(nowMs)) return false
        releaseDeadlineMs = Math.addExact(nowMs, RELEASE_MS)
        return true
    }

    companion object {
        const val MEASUREMENT_MS = 45_000L
        const val RELEASE_MS = 10_000L
    }
}
