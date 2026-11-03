// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.net.URI

/** Never turn an internal page, credentials, path or search term into an icon request. */
internal fun faviconUrl(url: String): String? = runCatching {
    val uri = URI(url)
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty() || uri.userInfo != null) null
    else URI(uri.scheme.lowercase(), null, uri.host, uri.port, "/favicon.ico", null, null).toASCIIString()
}.getOrNull()
