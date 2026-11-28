// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import app.miru.chromium.engine.MiruChromiumRuntime
import app.miru.data.BrowserSecuritySettings
import app.miru.data.BrowsingDataClearRequest
import app.miru.data.BrowsingDataClearResult
import app.miru.data.BrowsingDataRange
import app.miru.data.BrowsingDataType
import app.miru.data.SecureDnsMode
import app.miru.data.validatedSecureDnsUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.coroutines.resume

/**
 * Pure security, DoH/cookie settings, `BrowsingDataRemover` bitmask bridging, and X.509 certificate
 * extraction helpers for [ChromiumEngineController].
 */
internal object ChromiumSecurityHelper {
    private const val BIT_COOKIES = 1
    private const val BIT_SITE_DATA = 2
    private const val BIT_CACHE = 4
    private const val BIT_AUTH_SESSIONS = 8

    fun applySecuritySettings(settings: BrowserSecuritySettings) {
        val customUrl = if (settings.secureDnsMode == SecureDnsMode.CUSTOM) {
            settings.customDnsUrl?.let(::validatedSecureDnsUrl).orEmpty()
        } else ""
        val modeInt = when {
            settings.secureDnsMode == SecureDnsMode.CUSTOM && customUrl.isNotEmpty() -> 2
            settings.secureDnsMode == SecureDnsMode.OFF -> 0
            else -> 1
        }
        MiruChromiumRuntime.applySecuritySettings(
            settings.blockThirdPartyCookies,
            settings.httpsOnly,
            modeInt,
            customUrl,
        )
    }

    fun toClearMask(selected: Set<BrowsingDataType>): Int {
        var mask = 0
        if (BrowsingDataType.COOKIES in selected) mask = mask or BIT_COOKIES
        if (BrowsingDataType.SITE_DATA in selected) mask = mask or BIT_SITE_DATA
        if (BrowsingDataType.WEB_CACHE in selected) mask = mask or BIT_CACHE
        if (BrowsingDataType.AUTH_SESSIONS in selected) mask = mask or BIT_AUTH_SESSIONS
        return mask
    }

    fun fromFailedMask(failedMask: Int, selected: Set<BrowsingDataType>): Set<BrowsingDataType> = buildSet {
        if ((failedMask and BIT_COOKIES) != 0 && BrowsingDataType.COOKIES in selected) add(BrowsingDataType.COOKIES)
        if ((failedMask and BIT_SITE_DATA) != 0 && BrowsingDataType.SITE_DATA in selected) add(BrowsingDataType.SITE_DATA)
        if ((failedMask and BIT_CACHE) != 0 && BrowsingDataType.WEB_CACHE in selected) add(BrowsingDataType.WEB_CACHE)
        if ((failedMask and BIT_AUTH_SESSIONS) != 0 && BrowsingDataType.AUTH_SESSIONS in selected) add(BrowsingDataType.AUTH_SESSIONS)
    }

    suspend fun clearBrowsingData(
        request: BrowsingDataClearRequest,
        initialized: Boolean,
        hasPrivateTabs: Boolean,
        onClearMemoryCaches: () -> Unit,
        onClearSiteSettings: () -> Unit = {},
    ): BrowsingDataClearResult = withContext(Dispatchers.Main.immediate) {
        val selected = request.types.intersect(BrowserEngineDataTypes.ENGINE_TYPES)
        if (!request.isValid) return@withContext BrowsingDataClearResult(failed = selected)
        if (BrowsingDataType.WEB_CACHE in selected) onClearMemoryCaches()
        if (BrowsingDataType.SITE_SETTINGS in selected) onClearSiteSettings()
        if (initialized && !hasPrivateTabs && (BrowsingDataType.COOKIES in selected || BrowsingDataType.SITE_DATA in selected)) {
            MiruChromiumRuntime.destroyOffTheRecordProfile()
        }
        val miruMask = toClearMask(selected)
        val failedMask = if (initialized && miruMask != 0) {
            val sinceMs = if (request.range == BrowsingDataRange.ALL_TIME) 0L else request.since
            withTimeoutOrNull(30_000) {
                suspendCancellableCoroutine<Int> { cont ->
                    MiruChromiumRuntime.clearBrowsingData(miruMask, sinceMs) { failed ->
                        if (cont.isActive) cont.resume(failed)
                    }
                }
            } ?: miruMask
        } else {
            0
        }
        val failed = fromFailedMask(failedMask, selected)
        BrowsingDataClearResult(completed = selected - failed, failed = failed)
    }

    fun parseCertificateDetails(currentUrl: String, isSecure: Boolean, certDer: ByteArray?): CertificateDetails? {
        if (!isSecure || certDer == null || certDer.isEmpty()) return null
        val cert = runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certDer)) as? X509Certificate
        }.getOrNull() ?: return null
        val host = runCatching { URI(currentUrl).host.orEmpty() }.getOrDefault("")
        return CertificateDetails.from(currentUrl, host, cert, loading = false)
    }
}
