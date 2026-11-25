// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

/** Ordinary choices persist; private choices exist only until the last private tab closes. */
internal class PopupPermissions(
    private val read: (String) -> Boolean,
    private val write: (String, Boolean) -> Unit,
    private val readAllAllowed: () -> Set<String> = { emptySet() },
    private val clearSaved: () -> Unit,
) {
    private val privateAllowed = mutableSetOf<String>()
    private fun key(url: String, contextId: String?): String? = siteOrigin(url)?.let { "$it|${contextId.orEmpty()}" }
    fun allowed(url: String, isPrivate: Boolean, contextId: String?): Boolean {
        val key = key(url, contextId) ?: return false
        return if (isPrivate) key in privateAllowed else read(key)
    }
    fun set(url: String, isPrivate: Boolean, contextId: String?, allowed: Boolean): Boolean {
        val key = key(url, contextId) ?: return false
        if (isPrivate) { if (allowed) privateAllowed.add(key) else privateAllowed.remove(key) }
        else write(key, allowed)
        return true
    }
    fun savedDefaultAllowedOrigins(): Set<String> = buildSet {
        readAllAllowed().forEach { key ->
            if (key.endsWith("|")) siteOrigin(key.removeSuffix("|"))?.let(::add)
        }
    }
    fun clearOrigin(origin: String) {
        siteOrigin(origin)?.let { normalized ->
            write("$normalized|", false)
            privateAllowed.remove("$normalized|")
        }
    }
    fun clearPrivate() = privateAllowed.clear()
    fun clearAll() { clearSaved(); clearPrivate() }
}

/** A popup permission never grants automatic access to local files or external apps. */
internal fun isWebPopupTarget(url: String): Boolean = url.isEmpty() || url == "about:blank" || siteOrigin(url) != null

data class BlockedPopup(val token: Long, val tabId: String, val origin: String, val isPrivate: Boolean) {
    override fun toString() = "BlockedPopup(token=$token, private=$isPrivate)"
}
data class PopupSiteState(val tabId: String, val origin: String, val isPrivate: Boolean, val allowed: Boolean) {
    override fun toString() = "PopupSiteState(private=$isPrivate, allowed=$allowed)"
}
