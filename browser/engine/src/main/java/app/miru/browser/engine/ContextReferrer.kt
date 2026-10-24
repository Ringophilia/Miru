// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.net.URI
import java.util.Locale

/**
 * The owning document bounds a native context action's referrer. In particular,
 * copying/sharing an image must not send a private document's path or query to
 * another origin. A captured header can further restrict this default, but can
 * never widen it or substitute another document's address.
 */
fun contextReferrer(pageUrl: String, targetUrl: String, capturedReferrer: String? = null): String? {
    val page = ReferrerUrl.parse(pageUrl) ?: return null
    val target = ReferrerUrl.parse(targetUrl) ?: return null
    if (page.scheme == "https" && target.scheme != "https") return null
    val sameOrigin = page.sameOrigin(target)
    val maximum = if (sameOrigin) page.document else page.origin
    if (capturedReferrer == null) return maximum
    // An explicitly empty header represents a more restrictive policy.
    if (capturedReferrer.isBlank()) return null
    val captured = ReferrerUrl.parse(capturedReferrer) ?: return null
    if (!page.sameOrigin(captured)) return null
    if (captured.document == captured.origin) return page.origin
    if (captured.document != page.document) return null
    return maximum
}

private class ReferrerUrl(
    val scheme: String,
    private val host: String,
    private val port: Int,
    val origin: String,
    val document: String,
) {
    fun sameOrigin(other: ReferrerUrl) = scheme == other.scheme && host == other.host && port == other.port

    companion object {
        fun parse(value: String): ReferrerUrl? = runCatching {
            // Reject control characters before URI parsing and header construction.
            if (value.any { it.code <= 0x20 || it.code == 0x7f }) return null
            val parsed = URI(value)
            val scheme = parsed.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "http" || it == "https" } ?: return null
            val host = parsed.host?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank) ?: return null
            if (parsed.port !in -1..65_535) return null
            val defaultPort = if (scheme == "https") 443 else 80
            val port = parsed.port.takeIf { it >= 0 } ?: defaultPort
            val origin = buildString {
                append(scheme).append("://").append(host)
                if (port != defaultPort) append(':').append(port)
                append('/')
            }
            val path = parsed.rawPath.orEmpty().ifEmpty { "/" }
            val document = origin.dropLast(1) + path + (parsed.rawQuery?.let { "?$it" } ?: "")
            // Match the browser's bounded referrer behavior for unusually long URLs.
            ReferrerUrl(scheme, host, port, origin, if (document.length > 4_096) origin else document)
        }.getOrNull()
    }
}
