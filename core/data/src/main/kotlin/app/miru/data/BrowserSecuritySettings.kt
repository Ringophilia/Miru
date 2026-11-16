// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.data

import java.net.URI

enum class SecureDnsMode { AUTOMATIC, OFF, CUSTOM }

data class BrowserSecuritySettings(
    val blockThirdPartyCookies: Boolean = true,
    val httpsOnly: Boolean = false,
    val secureDnsMode: SecureDnsMode = SecureDnsMode.AUTOMATIC,
    val customDnsUrl: String? = null,
)

fun validatedSecureDnsUrl(value: String): String? {
    val input = value.trim()
    if (input.length > 2_048) return null
    val uri = runCatching { URI(input) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty() ||
        uri.rawUserInfo != null || uri.rawFragment != null || (uri.port != -1 && uri.port !in 1..65535)
    ) return null
    return uri.toASCIIString()
}
