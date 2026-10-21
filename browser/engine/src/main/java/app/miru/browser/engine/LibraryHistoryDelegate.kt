// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser.engine

import app.miru.data.BrowsingLibrary
import mozilla.components.concept.engine.history.HistoryTrackingDelegate
import mozilla.components.concept.storage.PageVisit

/**
 * Records regular-tab visits for history and visited-link styling.
 * GeckoEngineSession never calls the delegate for private sessions.
 */
class LibraryHistoryDelegate(private val library: BrowsingLibrary) : HistoryTrackingDelegate {
    override suspend fun onVisited(uri: String, visit: PageVisit) {
        // A redirect source is an intermediate hop, not a page the person saw.
        if (visit.redirectSource == null) library.history.recordVisit(uri)
    }

    override suspend fun onTitleChanged(uri: String, title: String) = library.history.updateTitle(uri, title)
    override suspend fun onPreviewImageChange(uri: String, previewImageUrl: String) = Unit
    override suspend fun getVisited(uris: List<String>): List<Boolean> = library.history.visited(uris)
    override suspend fun getVisited(): List<String> = library.history.allUrls()
    override fun shouldStoreUri(uri: String): Boolean = uri.startsWith("https://") || uri.startsWith("http://")
}
