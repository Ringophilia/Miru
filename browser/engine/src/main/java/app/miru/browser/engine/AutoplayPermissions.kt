// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

enum class SiteAutoplayChoice {
    ALLOW_ALL, BLOCK_AUDIBLE, BLOCK_ALL;

    fun allows(inaudible: Boolean): Boolean = this == ALLOW_ALL || (this == BLOCK_AUDIBLE && inaudible)
}

/** Autoplay is a site setting, never an unsolicited permission dialog on page load. */
internal class AutoplayPermissions(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Unit,
    private val readAll: () -> Map<String, String> = { emptyMap() },
    private val clearSaved: () -> Unit,
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
