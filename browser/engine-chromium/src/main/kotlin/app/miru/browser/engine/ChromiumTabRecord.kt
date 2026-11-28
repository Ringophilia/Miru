// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.graphics.Bitmap
import app.miru.chromium.engine.MiruChromiumTab

internal data class ChromiumTabRecord(
    val id: String,
    val isPrivate: Boolean,
    var nativeTab: MiruChromiumTab? = null,
    var url: String = NEW_TAB_URL,
    var title: String = "",
    var loading: Boolean = false,
    var progress: Int = 0,
    var canGoBack: Boolean = false,
    var canGoForward: Boolean = false,
    var desktopMode: Boolean = false,
    var secure: Boolean = false,
    var certificate: CertificateDetails? = null,
    var fullScreen: Boolean = false,
    var findCurrent: Int = 0,
    var findTotal: Int = 0,
    var lastFindQuery: String = "",
    var showToolbarAsExpanded: Boolean = false,
    var lastRecordedUrl: String? = null,
    var lastRecordedTitle: String? = null,
    var hardGeneration: Long = 0L,
    var locationGeneration: Long = 0L,
    var attemptGeneration: Long = 0L,
    var documentGeneration: Long = 0L,
) {
    fun toSavedState() = ChromiumSessionStore.SavedTabState(id, url, title, desktopMode)

    fun toBrowserTab(thumbnail: Bitmap?): BrowserTab = BrowserTab(
        id = id,
        url = url,
        title = title,
        isPrivate = isPrivate,
        loading = loading,
        progress = progress,
        canGoBack = canGoBack,
        canGoForward = canGoForward,
        desktopMode = desktopMode,
        secure = secure && url.startsWith("https://", ignoreCase = true),
        certificate = certificate,
        thumbnail = if (isPrivate) null else thumbnail,
        findCurrent = findCurrent,
        findTotal = findTotal,
        fullScreen = fullScreen,
        showToolbarAsExpanded = showToolbarAsExpanded,
    )
}
