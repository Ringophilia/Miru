// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import app.miru.nativecore.MiruNativeCore
import java.net.URI
import java.util.Locale

enum class SitePermissionKind { CAMERA, MICROPHONE, LOCATION, NOTIFICATIONS, PROTECTED_MEDIA }
enum class SitePermissionChoice { ALLOW, BLOCK, ASK }

enum class SiteAutoplayChoice {
    ALLOW_ALL, BLOCK_AUDIBLE, BLOCK_ALL;

    fun allows(inaudible: Boolean): Boolean = this == ALLOW_ALL || (this == BLOCK_AUDIBLE && inaudible)
}

/** These projections live only in memory. Never put them in SavedState, logs, or analytics. */
data class SitePermissionPrompt(
    val token: Long,
    val origin: String,
    val topLevelOrigin: String?,
    val isPrivate: Boolean,
    val kinds: Set<SitePermissionKind>,
) {
    override fun toString() = "SitePermissionPrompt(token=$token, private=$isPrivate)"
}

data class RuntimePermissionRequest(
    val token: Long,
    val permissions: List<String>,
    val isPrivate: Boolean,
    val launched: Boolean = false,
)

data class SitePermissionState(
    val token: Long,
    val tabId: String,
    val origin: String,
    val isPrivate: Boolean,
    val choices: Map<SitePermissionKind, SitePermissionChoice>,
    val systemGranted: Map<SitePermissionKind, Boolean>,
    val autoplay: SiteAutoplayChoice = SiteAutoplayChoice.BLOCK_AUDIBLE,
    val busy: Boolean = false,
    val error: Boolean = false,
) {
    override fun toString() = "SitePermissionState(token=$token, private=$isPrivate, busy=$busy, error=$error)"
}

data class SavedSiteEntry(
    val origin: String,
    val permissions: Map<SitePermissionKind, SitePermissionChoice>,
    val autoplay: SiteAutoplayChoice?,
    val allowPopups: Boolean,
)

/** A complete web origin; paths, queries and fragments never form permission keys. */
fun siteOrigin(value: String?): String? {
    if (value.isNullOrEmpty()) return null
    if (MiruNativeCore.isAvailable) return MiruNativeCore.siteOrigin(value)
    if (value.any { it.code <= 0x20 || it.code == 0x7f }) return null
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

/** Autoplay is a site setting, never an unsolicited permission dialog on page load. */
class AutoplayPermissions(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Unit,
    private val clearSaved: () -> Unit,
    private val readAll: () -> Map<String, String> = { emptyMap() },
) {
    private val privateChoices = mutableMapOf<String, SiteAutoplayChoice>()
    private fun key(url: String, contextId: String?) = siteOrigin(url)?.let {
        "$it|${if (contextId == null) "0" else "1$contextId"}"
    }
    fun choice(url: String, isPrivate: Boolean, contextId: String?): SiteAutoplayChoice {
        val key = key(url, contextId) ?: return SiteAutoplayChoice.BLOCK_AUDIBLE
        if (isPrivate) return privateChoices[key] ?: SiteAutoplayChoice.BLOCK_AUDIBLE
        return SiteAutoplayChoice.entries.firstOrNull { it.name == read(key) } ?: SiteAutoplayChoice.BLOCK_AUDIBLE
    }
    fun set(url: String, isPrivate: Boolean, contextId: String?, choice: SiteAutoplayChoice) {
        val key = key(url, contextId) ?: return
        if (isPrivate) {
            if (choice == SiteAutoplayChoice.BLOCK_AUDIBLE) privateChoices.remove(key) else privateChoices[key] = choice
        } else write(key, choice.name.takeUnless { choice == SiteAutoplayChoice.BLOCK_AUDIBLE })
    }
    fun savedDefaultEntries(): Map<String, SiteAutoplayChoice> = buildMap {
        readAll().forEach { (key, raw) ->
            if (!key.endsWith("|0")) return@forEach
            val origin = siteOrigin(key.removeSuffix("|0")) ?: return@forEach
            val choice = SiteAutoplayChoice.entries.firstOrNull { it.name == raw } ?: return@forEach
            if (choice != SiteAutoplayChoice.BLOCK_AUDIBLE) put(origin, choice)
        }
    }
    fun clearOrigin(origin: String) {
        siteOrigin(origin)?.let { normalized ->
            write("$normalized|0", null)
            privateChoices.remove("$normalized|0")
        }
    }
    fun clearPrivate() = privateChoices.clear()
    fun clearAll() { clearSaved(); clearPrivate() }
}

/** App-session observations of Android permissions that can no longer be requested. */
class RuntimePermissionRecovery {
    private val blocked = mutableSetOf<String>()

    fun requiresSettings(
        results: Map<String, Boolean>,
        rationaleBefore: Set<String>,
        rationaleAfter: Set<String>,
    ): Boolean {
        for ((permission, granted) in results) {
            when {
                granted || permission in rationaleAfter -> blocked.remove(permission)
                permission in rationaleBefore -> blocked.add(permission)
            }
        }
        return results.any { (permission, granted) -> !granted && permission in blocked }
    }

    fun clearGranted(isGranted: (String) -> Boolean) {
        blocked.removeAll(isGranted)
    }
}
