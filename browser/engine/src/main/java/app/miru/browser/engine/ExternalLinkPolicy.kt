// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.net.URI
import java.util.Locale

internal fun linkScheme(value: String): String? {
    if(value.length > 8192 || value.any { it.code < 0x20 || it.code == 0x7f }) return null
    return Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(value)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
}

internal fun isBrowserScheme(scheme: String) = scheme in setOf("http", "https", "about", "data", "blob", "javascript", "resource", "chrome", "moz-safe-about", "moz-extension", "view-source")

internal fun externalDataAllowed(value: String, hasPackage: Boolean): Boolean {
    val scheme = linkScheme(value) ?: return false
    if(scheme in setOf("http", "https")) return hasPackage && webFallback(value) != null
    return scheme !in setOf("intent", "android-app", "file", "content", "miru") && !isBrowserScheme(scheme) &&
        runCatching { URI(value).isAbsolute }.getOrDefault(false)
}

internal fun webFallback(value: String?): String? = value?.takeIf {
    linkScheme(it) in setOf("http", "https") && siteOrigin(it) != null
}
