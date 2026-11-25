// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.content.Context
import app.miru.data.BrowserSecuritySettings
import app.miru.data.BrowsingLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** Factory creating the frozen v1.0 GeckoView reference backend behind [BrowserEngineController]. */
object GeckoBrowserEngineFactory : BrowserEngineFactory {
    override fun create(
        context: Context,
        applicationScope: CoroutineScope,
        library: BrowsingLibrary,
        securitySettings: Flow<BrowserSecuritySettings>,
        webZoomPercent: Flow<Int>,
    ): BrowserEngineController = BrowserController(
        context = context,
        applicationScope = applicationScope,
        historyDelegate = LibraryHistoryDelegate(library),
        securitySettings = securitySettings,
        webZoomPercent = webZoomPercent,
    )
}
