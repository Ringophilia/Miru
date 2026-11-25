// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import app.miru.data.BrowserSecuritySettings
import app.miru.data.SecureDnsMode
import app.miru.data.validatedSecureDnsUrl
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntimeSettings

internal fun GeckoRuntimeSettings.applyBrowserSecurity(settings: BrowserSecuritySettings) {
    setAllowInsecureConnections(if (settings.httpsOnly) GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL)
    val policy = if (settings.blockThirdPartyCookies) ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
        else ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS
    contentBlocking.setCookieBehavior(policy)
    contentBlocking.setCookieBehaviorPrivateMode(policy)

    val custom = if (settings.secureDnsMode == SecureDnsMode.CUSTOM) {
        settings.customDnsUrl?.let(::validatedSecureDnsUrl)
    } else null
    val mode = when {
        custom != null -> GeckoRuntimeSettings.TRR_MODE_ONLY
        settings.secureDnsMode == SecureDnsMode.OFF -> GeckoRuntimeSettings.TRR_MODE_DISABLED
        else -> GeckoRuntimeSettings.TRR_MODE_OFF
    }
    // Mode 0 delegates provider selection to Gecko's Android default-protection mode.
    // Clear a previous custom provider when switching away from it.
    setDohAutoselectEnabled(mode == GeckoRuntimeSettings.TRR_MODE_OFF)
    setTrustedRecursiveResolverUri(custom.orEmpty())
    setTrustedRecursiveResolverMode(mode)
}

internal fun GeckoRuntimeSettings.applyWebZoom(percent: Int) {
    val clamped = percent.coerceIn(50, 200)
    if (clamped == 100) {
        setAutomaticFontSizeAdjustment(true)
    } else {
        setAutomaticFontSizeAdjustment(false)
        setFontSizeFactor(clamped / 100f)
    }
}
