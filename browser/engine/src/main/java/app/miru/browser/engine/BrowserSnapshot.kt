// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser.engine

import android.graphics.Bitmap

const val NEW_TAB_URL = "about:newtab"

/** An immutable UI projection. BrowserStore remains the sole source of tab/session state. */
data class BrowserSnapshot(
    val tabs: List<BrowserTab> = emptyList(),
    val selectedTabId: String? = null,
    val ready: Boolean = false,
    val error: String? = null,
) {
    val selectedTab: BrowserTab? get() = tabs.firstOrNull { it.id == selectedTabId }
}

data class BrowserTab(
    val id: String,
    val url: String,
    val title: String = "",
    val isPrivate: Boolean = false,
    val loading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val desktopMode: Boolean = false,
    val secure: Boolean = false,
    val crashed: Boolean = false,
    val thumbnail: Bitmap? = null,
    val findCurrent: Int = 0,
    val findTotal: Int = 0,
    val fullScreen: Boolean = false,
) {
    val isNewTab: Boolean get() = url == NEW_TAB_URL
}
