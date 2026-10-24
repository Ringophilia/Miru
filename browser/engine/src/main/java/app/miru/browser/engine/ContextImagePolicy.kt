// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.net.URI
import java.util.Locale

/** Obtained from a matching image in its live document, never guessed from a missing header. */
class ContextImagePolicy private constructor(val documentUrl: String, val policy: String) {
    fun referrerFor(targetUrl: String): String? {
        val source = ReferrerAddress.parse(documentUrl) ?: return null
        val target = ReferrerAddress.parse(targetUrl) ?: return null
        val sameOrigin = source.origin == target.origin
        val downgrade = source.trustworthy && !target.trustworthy
        return when(policy) {
            "no-referrer" -> null
            "no-referrer-when-downgrade" -> source.document.takeUnless { downgrade }
            "same-origin" -> source.document.takeIf { sameOrigin }
            "origin" -> source.origin
            "strict-origin" -> source.origin.takeUnless { downgrade }
            "origin-when-cross-origin" -> if(sameOrigin) source.document else source.origin
            "strict-origin-when-cross-origin" -> if(sameOrigin) source.document else source.origin.takeUnless { downgrade }
            "unsafe-url" -> source.document
            else -> null
        }
    }

    override fun toString() = "ContextImagePolicy(policy=$policy)"

    companion object {
        private val policies = setOf("no-referrer", "no-referrer-when-downgrade", "same-origin", "origin", "strict-origin",
            "origin-when-cross-origin", "strict-origin-when-cross-origin", "unsafe-url")

        /** Empty is meaningful only after the live document query succeeded. Unknown values fail closed. */
        fun fromDocument(documentUrl: String, policy: String): ContextImagePolicy? {
            if(documentUrl.length !in 1..65_536 || documentUrl.any { it.code <= 0x20 || it.code == 0x7f }) return null
            val normalized = policy.lowercase(Locale.ROOT)
            if(normalized.isNotEmpty() && normalized !in policies) return null
            return ContextImagePolicy(documentUrl, normalized.ifEmpty { "strict-origin-when-cross-origin" })
        }

        /** Header lists use their last supported token, per Referrer Policy section 8.1. */
        fun responsePolicy(header: String): String? = header.split(',').map { it.trim().lowercase(Locale.ROOT) }.lastOrNull { it in policies }
    }
}

private class ReferrerAddress(val origin: String, val document: String, val trustworthy: Boolean) {
    companion object {
        fun parse(value: String): ReferrerAddress? = runCatching {
            if(value.any { it.code <= 0x20 || it.code == 0x7f }) return null
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "http" || it == "https" } ?: return null
            val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
            if(uri.port !in -1..65_535) return null
            val defaultPort = if(scheme == "https") 443 else 80
            val origin = "$scheme://$host" + (if(uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}") + "/"
            val document = origin.dropLast(1) + uri.rawPath.orEmpty().ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
            val plainHost = host.removePrefix("[").removeSuffix("]").removeSuffix(".")
            val ipv4 = plainHost.split('.')
            val loopback = ipv4.size == 4 && ipv4[0] == "127" && ipv4.all { (it.toIntOrNull() ?: -1) in 0..255 }
            val trustworthy = scheme == "https" || plainHost == "localhost" || plainHost.endsWith(".localhost") || plainHost == "::1" || loopback
            ReferrerAddress(origin, if(document.length > 4_096) origin else document, trustworthy)
        }.getOrNull()
    }
}
