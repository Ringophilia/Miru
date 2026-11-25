// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import app.miru.nativecore.MiruNativeCore
import java.net.URI
import java.util.Locale

enum class BrowserContextMenuKind { LINK, IMAGE, VIDEO }

/** Process-only hit target. No cookies or headers, SavedState serialization, or URL-bearing logs. */
class BrowserContextMenu(
    val token: Long,
    val tabId: String,
    val pageUrl: String,
    val isPrivate: Boolean,
    val kind: BrowserContextMenuKind,
    val linkUrl: String? = null,
    val linkText: String? = null,
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val title: String? = null,
    val pageGeneration: Long,
    val gestureStartedAtMillis: Long,
    val pressedAtMillis: Long = System.currentTimeMillis(),
) {
    override fun toString() = "BrowserContextMenu(token=$token, kind=$kind, private=$isPrivate)"
}

data class ExternalLinkPrompt(
    val token: Long,
    val tabId: String,
    val origin: String,
    val isPrivate: Boolean,
    val url: String,
    val appName: String?,
    val canOpenApp: Boolean,
    val canOpenWeb: Boolean,
    val webUrl: String?,
) {
    override fun toString() = "ExternalLinkPrompt(token=$token, private=$isPrivate)"
}

fun linkScheme(value: String): String? {
    if (value.length > 8192 || value.any { it.code < 0x20 || it.code == 0x7f }) return null
    return Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(value)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
}

fun isBrowserScheme(scheme: String) = scheme in setOf(
    "http", "https", "about", "data", "blob", "javascript",
    "resource", "chrome", "moz-safe-about", "moz-extension", "view-source",
)

fun externalDataAllowed(value: String, hasPackage: Boolean): Boolean {
    if (MiruNativeCore.isAvailable) {
        MiruNativeCore.externalDataAllowed(value, hasPackage)?.let { return it }
    }
    val scheme = linkScheme(value) ?: return false
    if (scheme in setOf("http", "https")) return hasPackage && webFallback(value) != null
    return scheme !in setOf("intent", "android-app", "file", "content", "miru") &&
        !isBrowserScheme(scheme) &&
        runCatching { URI(value).isAbsolute }.getOrDefault(false)
}

fun webFallback(value: String?): String? = value?.takeIf {
    linkScheme(it) in setOf("http", "https") && siteOrigin(it) != null
}

/** Obtained from a matching image in its live document, never guessed from a missing header. */
class ContextImagePolicy private constructor(val documentUrl: String, val policy: String) {
    fun referrerFor(targetUrl: String): String? {
        if (MiruNativeCore.isAvailable) {
            return MiruNativeCore.referrerForPolicy(documentUrl, targetUrl, policy)
        }
        val source = ReferrerAddress.parse(documentUrl) ?: return null
        val target = ReferrerAddress.parse(targetUrl) ?: return null
        val sameOrigin = source.origin == target.origin
        val downgrade = source.trustworthy && !target.trustworthy
        return when (policy) {
            "no-referrer" -> null
            "no-referrer-when-downgrade" -> source.document.takeUnless { downgrade }
            "same-origin" -> source.document.takeIf { sameOrigin }
            "origin" -> source.origin
            "strict-origin" -> source.origin.takeUnless { downgrade }
            "origin-when-cross-origin" -> if (sameOrigin) source.document else source.origin
            "strict-origin-when-cross-origin" -> if (sameOrigin) source.document else source.origin.takeUnless { downgrade }
            "unsafe-url" -> source.document
            else -> null
        }
    }

    override fun toString() = "ContextImagePolicy(policy=$policy)"

    companion object {
        private val policies = setOf(
            "no-referrer", "no-referrer-when-downgrade", "same-origin", "origin", "strict-origin",
            "origin-when-cross-origin", "strict-origin-when-cross-origin", "unsafe-url",
        )

        /** Empty is meaningful only after the live document query succeeded. Unknown values fail closed. */
        fun fromDocument(documentUrl: String, policy: String): ContextImagePolicy? {
            if (documentUrl.length !in 1..65_536 || documentUrl.any { it.code <= 0x20 || it.code == 0x7f }) return null
            val normalized = policy.lowercase(Locale.ROOT)
            if (normalized.isNotEmpty() && normalized !in policies) return null
            return ContextImagePolicy(documentUrl, normalized.ifEmpty { "strict-origin-when-cross-origin" })
        }

        /** Header lists use their last supported token, per Referrer Policy section 8.1. */
        fun responsePolicy(header: String): String? = header.split(',').map { it.trim().lowercase(Locale.ROOT) }.lastOrNull { it in policies }
    }
}

private class ReferrerAddress(val origin: String, val document: String, val trustworthy: Boolean) {
    companion object {
        fun parse(value: String): ReferrerAddress? = runCatching {
            if (value.any { it.code <= 0x20 || it.code == 0x7f }) return null
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "http" || it == "https" } ?: return null
            val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
            if (uri.port !in -1..65_535) return null
            val defaultPort = if (scheme == "https") 443 else 80
            val origin = "$scheme://$host" + (if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}") + "/"
            val document = origin.dropLast(1) + uri.rawPath.orEmpty().ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
            val plainHost = host.removePrefix("[").removeSuffix("]").removeSuffix(".")
            val ipv4 = plainHost.split('.')
            val loopback = ipv4.size == 4 && ipv4[0] == "127" && ipv4.all { (it.toIntOrNull() ?: -1) in 0..255 }
            val trustworthy = scheme == "https" || plainHost == "localhost" || plainHost.endsWith(".localhost") || plainHost == "::1" || loopback
            ReferrerAddress(origin, if (document.length > 4_096) origin else document, trustworthy)
        }.getOrNull()
    }
}

/**
 * The owning document bounds a native context action's referrer.
 */
fun contextReferrer(pageUrl: String, targetUrl: String, capturedReferrer: String? = null): String? {
    if (MiruNativeCore.isAvailable) {
        return MiruNativeCore.contextReferrer(pageUrl, targetUrl, capturedReferrer)
    }
    val page = ReferrerUrl.parse(pageUrl) ?: return null
    val target = ReferrerUrl.parse(targetUrl) ?: return null
    if (page.scheme == "https" && target.scheme != "https") return null
    val sameOrigin = page.sameOrigin(target)
    val maximum = if (sameOrigin) page.document else page.origin
    if (capturedReferrer == null) return maximum
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
            ReferrerUrl(scheme, host, port, origin, if (document.length > 4_096) origin else document)
        }.getOrNull()
    }
}
