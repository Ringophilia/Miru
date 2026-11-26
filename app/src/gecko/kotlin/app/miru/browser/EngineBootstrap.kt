// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.app.Application
import android.content.Context
import app.miru.browser.engine.BrowserEngineController
import app.miru.browser.engine.GeckoBrowserEngineFactory
import app.miru.data.BrowserSecuritySettings
import app.miru.data.BrowsingLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

object EngineBootstrap {
    fun attachBaseContext(application: Application) = Unit

    fun createEngine(
        context: Context,
        applicationScope: CoroutineScope,
        library: BrowsingLibrary,
        securitySettings: Flow<BrowserSecuritySettings>,
        webZoomPercent: Flow<Int>,
    ): BrowserEngineController = GeckoBrowserEngineFactory.create(
        context = context,
        applicationScope = applicationScope,
        library = library,
        securitySettings = securitySettings,
        webZoomPercent = webZoomPercent,
    )
}
