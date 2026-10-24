// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

/**
 * Remembers the protection state at the start of a navigation, independently of late store updates.
 * Native extension readiness cannot retrospectively protect a document that has already loaded.
 */
internal class UblockPageWarnings {
    private data class Navigation(
        var url: String,
        val unprotected: Boolean,
        var awaitingRequest: Boolean,
        var loading: Boolean = false,
        var locationChanged: Boolean = false,
    )
    private data class Page(var needsReload: Boolean = false, var navigation: Navigation? = null)
    private val pages = mutableMapOf<String, Page>()

    fun needsReload(tabId: String?) = tabId?.let { pages[it]?.needsReload } == true

    /** Called at the engine delegation boundary, after the bounded startup gate has opened. */
    fun loadRequested(tabId: String, url: String, unprotected: Boolean) {
        begin(tabId, url, unprotected, awaitingRequest = true)
    }

    /** The request's URL is authoritative; the store may still contain the previous NTP URL. */
    fun loadRequest(tabId: String, url: String, redirect: Boolean, unprotected: Boolean) {
        val navigation = pages[tabId]?.navigation
        if (navigation != null && (redirect || navigation.awaitingRequest)) {
            if (navigation.awaitingRequest) {
                // Loading/location callbacks from the previous document have no navigation id.
                // Only the real request can confirm which pending command they belong to.
                navigation.loading = false
                navigation.locationChanged = false
            }
            navigation.awaitingRequest = false
            navigation.url = url
            if (isWebUrl(url) && navigation.unprotected) pages.getValue(tabId).needsReload = true
            return
        }
        begin(tabId, url, unprotected, awaitingRequest = false)
    }

    fun loadingChanged(tabId: String, loading: Boolean, fallbackUrl: String, unprotected: Boolean) {
        if (!loading) {
            // A previous page can stop after the next command has already been dispatched.
            // Its stop must not discard the new navigation's captured protection state.
            pages[tabId]?.let {
                if (it.navigation?.loading == true && it.navigation?.awaitingRequest == false) it.navigation = null
            }
            return
        }
        if (pages[tabId]?.navigation == null) {
            // A bare loading callback can be late. READY alone cannot prove the old document
            // was replaced under protection; require a real subsequent request to clear it.
            begin(tabId, fallbackUrl, unprotected || needsReload(tabId), awaitingRequest = true)
        }
        val page = pages.getValue(tabId)
        page.navigation?.let {
            if (it.awaitingRequest) return
            it.loading = true
            commitIfLoaded(page, it)
        }
    }

    fun locationChanged(tabId: String, url: String) {
        val page = pages[tabId] ?: return
        val navigation = page.navigation ?: return
        // Old-document fragment/location events cannot commit a different pending destination.
        // A genuine HTTP redirect updates the expected URL through loadRequest first.
        if (navigation.awaitingRequest || navigation.url != url) return
        navigation.locationChanged = true
        commitIfLoaded(page, navigation)
    }

    fun retainTabs(tabIds: Set<String>) { pages.keys.retainAll(tabIds) }

    private fun begin(tabId: String, url: String, unprotected: Boolean, awaitingRequest: Boolean) {
        val page = pages.getOrPut(tabId) { Page() }
        val needsReload = isWebUrl(url) && unprotected
        // A failed later navigation can leave the old document visible. Keep its warning until
        // a subsequent load actually changes location, including a same-URL reload's commit.
        if (needsReload) page.needsReload = true
        page.navigation = Navigation(url, unprotected, awaitingRequest)
    }

    private fun commitIfLoaded(page: Page, navigation: Navigation) {
        if (!navigation.awaitingRequest && navigation.loading && navigation.locationChanged) {
            page.needsReload = isWebUrl(navigation.url) && navigation.unprotected
        }
    }

    private fun isWebUrl(url: String) = url.startsWith("http://") || url.startsWith("https://")
}
