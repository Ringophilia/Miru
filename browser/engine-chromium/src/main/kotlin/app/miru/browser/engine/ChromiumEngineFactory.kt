// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.app.Application
import android.content.Context
import app.miru.chromium.engine.MiruChromiumRuntime
import app.miru.data.BrowserSecuritySettings
import app.miru.data.BrowsingLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** Process-wide Chromium initialization invoked from `Application.attachBaseContext`. */
object MiruChromiumBootstrap {
    fun attachBaseContext(application: Application) {
        MiruChromiumRuntime.attachBaseContext(application)
    }
}

/** Factory creating the de-Googled Chromium backend behind [BrowserEngineController]. */
object ChromiumBrowserEngineFactory : BrowserEngineFactory {
    override fun create(
        context: Context,
        applicationScope: CoroutineScope,
        library: BrowsingLibrary,
        securitySettings: Flow<BrowserSecuritySettings>,
        webZoomPercent: Flow<Int>,
    ): BrowserEngineController = ChromiumEngineController(
        context = context,
        applicationScope = applicationScope,
        library = library,
        securitySettings = securitySettings,
        webZoomPercent = webZoomPercent,
    )
}
