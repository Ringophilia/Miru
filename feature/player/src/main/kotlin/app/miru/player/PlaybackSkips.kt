// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import java.net.URI
import java.util.Locale

internal data class PlaybackSkipScope(val host: String, val path: String, val exact: Boolean = false) {
    val key = "skip.${PlaybackPolicy.digest("$host|${if (exact) "page" else "prefix"}|$path")}"

    companion object {
        /** Most specific first, matching by whole path segments and the full host. */
        fun forPage(pageUrl: String): List<PlaybackSkipScope> {
            val uri = runCatching { URI(pageUrl).normalize() }.getOrNull() ?: return emptyList()
            if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http") || uri.rawUserInfo != null) return emptyList()
            val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return emptyList()
            val path = uri.rawPath.orEmpty().ifEmpty { "/" }
            return buildList {
                if (!path.endsWith('/')) add(PlaybackSkipScope(host, path, exact = true))
                var end = path.lastIndexOf('/')
                while (end > 0 && size < 12) {
                    add(PlaybackSkipScope(host, path.substring(0, end + 1)))
                    end = path.lastIndexOf('/', end - 1)
                }
                add(PlaybackSkipScope(host, "/"))
            }
        }

        fun suggested(scopes: List<PlaybackSkipScope>): PlaybackSkipScope {
            val first = scopes.first()
            val parent = scopes.getOrNull(1)
            return if (first.exact && parent != null && parent.path != "/") parent else first
        }
    }
}

internal data class PlaybackSkipRule(val introSeconds: Int = 0, val outroSeconds: Int = 0) {
    val introMs get() = introSeconds * 1_000L
    val outroMs get() = outroSeconds * 1_000L

    fun fits(durationMs: Long) =
        introSeconds >= 0 && outroSeconds >= 0 && introMs + outroMs < durationMs
}

internal enum class PlaybackSkipSection { INTRO, OUTRO }

/** Each automatic skip can fire at most once for this playback request, including decoder retries. */
internal class PlaybackSkipTracker {
    private var introConsidered = false
    private var outroSkipped = false
    private var previousPosition: Long? = null

    fun positionDiscontinuity(userSeek: Boolean) {
        previousPosition = null
        if (userSeek) introConsidered = true
    }

    fun next(rule: PlaybackSkipRule, position: Long, duration: Long): PlaybackSkipSection? {
        val previous = previousPosition
        previousPosition = position
        if (!introConsidered) {
            introConsidered = true
            if (rule.fits(duration) && position < rule.introMs) return PlaybackSkipSection.INTRO
        }
        if (!outroSkipped && rule.outroSeconds > 0 && rule.fits(duration)) {
            val boundary = duration - rule.outroMs
            // Start/resume or an explicit seek inside the credits must let the user watch them.
            if (previous != null && previous < boundary && position >= boundary) {
                outroSkipped = true
                return PlaybackSkipSection.OUTRO
            }
        }
        return null
    }
}
