// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

object PlaybackPolicy {
    private val signatures = setOf("sign", "token", "expires", "timestamp", "t", "auth_key", "Expires", "Signature", "Key-Pair-Id", "Policy")
    fun normalizedCacheUrl(raw: String, range: Boolean = false): String = runCatching {
        val uri = URI(raw)
        val path = uri.path.orEmpty().lowercase()
        if(path.endsWith(".m3u8") || path.endsWith(".mpd")) return raw
        val segment = range || listOf(".ts", ".m4s", ".mp4").any(path::endsWith)
        val keys = uri.rawQuery?.split('&')?.map { URLDecoder.decode(it.substringBefore('='), "UTF-8") }.orEmpty()
        if(segment && keys.isNotEmpty() && keys.all { it in signatures }) raw.substringBefore('?') else raw
    }.getOrDefault(raw)
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun resume(position: Long, duration: Long): Long = if(position > 5_000 && duration > position + 15_000) position else 0
    fun progressKey(page: String, media: String): String = digest("$page|${media.substringBefore('?')}")
}

object PlayerGestures {
    const val SINGLE_TAP_MS = 260L
    const val DOUBLE_TAP_MS = 280L
    const val SEEK_STREAK_MS = 750L
    const val DOUBLE_TAP_DISTANCE_DP = 80
    const val DRAG_SLOP_DP = 10
    const val SEEK_STEP_MS = 10_000L
    const val DRAG_RANGE_MS = 120_000L
    const val HOLD_SPEED = 2f
    const val VERTICAL_HEIGHT_FRACTION = 0.75f
}
