// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.net.URI
import java.util.Locale

/** A complete web origin; paths, queries and fragments never form permission keys. */
internal fun siteOrigin(value: String?): String? {
    if (value.isNullOrEmpty() || value.any { it.code <= 0x20 || it.code == 0x7f }) return null
    return runCatching {
        val uri = URI(value)
        if (uri.isOpaque || uri.rawUserInfo != null) return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) ?: return null
        if (uri.port !in -1..65_535 || uri.rawAuthority.orEmpty().endsWith(':')) return null
        val defaultPort = if (scheme == "https") 443 else 80
        buildString {
            append(scheme).append("://")
            if (':' in host && !host.startsWith('[')) append('[').append(host).append(']') else append(host)
            if (uri.port != -1 && uri.port != defaultPort) append(':').append(uri.port)
        }
    }.getOrNull()
}
