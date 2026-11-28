// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns popup blocking state, 6-second expiry banners, and per-origin popup allowlists
 * (persisted for normal tabs, memory-only for private tabs) for [ChromiumPromptsController].
 */
internal class ChromiumPopupBlocker(
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    private val popupPermissions: PopupPermissions,
    private val lookupTab: (String) -> ChromiumPromptsController.TabMeta?,
    private val selectedTabId: () -> String?,
    private val hasPrivateTabs: () -> Boolean,
    private val openPopupTarget: (openerTabId: String, targetUrl: String, isPrivate: Boolean) -> Unit,
    private val maxPendingPerTab: Int = 8,
    private val bannerTimeoutMs: Long = 6_000L,
) {
    private class PendingPopup(
        val token: Long,
        val tabId: String,
        val origin: String,
        val targetUrl: String,
        val isPrivate: Boolean,
        val hardGeneration: Long,
        val locationGeneration: Long,
        val attemptGeneration: Long,
        var expiryJob: Job? = null,
    )

    private val pendingPopups = mutableListOf<PendingPopup>()
    private val mutablePopup = MutableStateFlow<BlockedPopup?>(null)
    val blockedPopup: StateFlow<BlockedPopup?> = mutablePopup.asStateFlow()

    private val mutablePopupSite = MutableStateFlow<PopupSiteState?>(null)
    val popupSite: StateFlow<PopupSiteState?> = mutablePopupSite.asStateFlow()

    fun shouldAllowPopupWindow(
        tabId: String,
        targetUrl: String,
        userGesture: Boolean,
        openerSuppressed: Boolean,
        allocateToken: () -> Long,
    ): Boolean {
        val tab = lookupTab(tabId) ?: return false
        if (tabId != selectedTabId() || openerSuppressed) return false
        val origin = siteOrigin(tab.pageUrl) ?: return false
        if (!isWebPopupTarget(targetUrl)) return false
        val contextKey = contextKeyFor(tab.isPrivate)
        if (popupPermissions.allowed(origin, tab.isPrivate, contextKey)) return true
        if (userGesture) return true
        queueBlockedPopup(tab, origin, targetUrl, allocateToken())
        return false
    }

    private fun queueBlockedPopup(
        tab: ChromiumPromptsController.TabMeta,
        origin: String,
        targetUrl: String,
        token: Long,
    ) {
        if (pendingPopups.count { it.tabId == tab.tabId } >= maxPendingPerTab) return
        val popup = PendingPopup(
            token = token,
            tabId = tab.tabId,
            origin = origin,
            targetUrl = targetUrl,
            isPrivate = tab.isPrivate,
            hardGeneration = tab.hardGeneration,
            locationGeneration = tab.locationGeneration,
            attemptGeneration = tab.attemptGeneration,
        )
        pendingPopups += popup
        popup.expiryJob = scope.launch(mainDispatcher) {
            delay(bannerTimeoutMs)
            if (popup in pendingPopups) {
                finishPopup(popup, allow = false)
                publish()
            }
        }
        publish()
    }

    fun allowPopup(token: Long) {
        val popup = pendingPopups.firstOrNull { it.token == token } ?: return
        if (!isValid(popup)) {
            finishPopup(popup, allow = false)
            publish()
            return
        }
        val tab = lookupTab(popup.tabId) ?: return
        popupPermissions.set(tab.pageUrl, tab.isPrivate, contextKeyFor(tab.isPrivate), true)
        finishPopup(popup, allow = true)
        publish()
    }

    fun dismissPopup(token: Long) {
        val popup = pendingPopups.firstOrNull { it.token == token } ?: return
        finishPopup(popup, allow = false)
        publish()
    }

    fun setPopupAllowed(site: PopupSiteState, allowed: Boolean) {
        if (mutablePopupSite.value != site) return
        val tab = lookupTab(site.tabId) ?: return
        if (siteOrigin(tab.pageUrl) != site.origin || selectedTabId() != site.tabId) return
        popupPermissions.set(site.origin, tab.isPrivate, contextKeyFor(tab.isPrivate), allowed)
        if (allowed) {
            pendingPopups.filter { it.tabId == site.tabId }.toList().forEach { finishPopup(it, allow = true) }
        }
        publish()
    }

    fun cancelTab(tabId: String) {
        pendingPopups.filter { it.tabId == tabId }.toList().forEach { finishPopup(it, allow = false) }
    }

    fun cancelAll() {
        pendingPopups.toList().forEach { finishPopup(it, allow = false) }
    }

    fun syncState() {
        if (!hasPrivateTabs()) popupPermissions.clearPrivate()
        pendingPopups.toList().filterNot(::isValid).forEach { finishPopup(it, allow = false) }
        publish()
    }

    fun savedOrigins(): Set<String> = popupPermissions.savedDefaultAllowedOrigins()

    fun clearOrigin(origin: String) {
        popupPermissions.clearOrigin(origin)
        publish()
    }

    fun clearAll() {
        popupPermissions.clearAll()
        pendingPopups.toList().forEach { finishPopup(it, allow = false) }
        publish()
    }

    fun publish() {
        mutablePopup.value = pendingPopups.filter(::isValid).minByOrNull { it.token }?.let {
            BlockedPopup(it.token, it.tabId, it.origin, it.isPrivate)
        }
        val selected = selectedTabId()?.let(lookupTab)
        val origin = selected?.pageUrl?.let(::siteOrigin)
        mutablePopupSite.value = if (selected != null && origin != null) {
            PopupSiteState(
                tabId = selected.tabId,
                origin = origin,
                isPrivate = selected.isPrivate,
                allowed = popupPermissions.allowed(origin, selected.isPrivate, contextKeyFor(selected.isPrivate)),
            )
        } else {
            null
        }
    }

    private fun isValid(popup: PendingPopup): Boolean {
        val tab = lookupTab(popup.tabId) ?: return false
        return selectedTabId() == popup.tabId &&
            tab.isPrivate == popup.isPrivate &&
            tab.hardGeneration == popup.hardGeneration &&
            tab.locationGeneration == popup.locationGeneration &&
            tab.attemptGeneration == popup.attemptGeneration
    }

    private fun finishPopup(popup: PendingPopup, allow: Boolean) {
        if (!pendingPopups.remove(popup)) return
        popup.expiryJob?.cancel()
        if (allow) openPopupTarget(popup.tabId, popup.targetUrl, popup.isPrivate)
    }

    private fun contextKeyFor(isPrivate: Boolean): String? = if (isPrivate) "chromium-private" else null

    companion object {
        fun sharedPreferencesPopups(context: Context): PopupPermissions {
            val prefs = context.getSharedPreferences("miru_chromium_popups", Context.MODE_PRIVATE)
            return PopupPermissions(
                { prefs.getBoolean(it, false) },
                { key, allowed ->
                    prefs.edit().apply {
                        if (allowed) putBoolean(key, true) else remove(key)
                    }.apply()
                },
                { prefs.edit().clear().apply() },
                { prefs.all.filterValues { it == true }.keys.toSet() },
            )
        }
    }
}
