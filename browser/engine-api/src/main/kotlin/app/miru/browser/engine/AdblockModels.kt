// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.content.Context
import java.io.Closeable

enum class AdblockStatus { STARTING, INSTALLING, READY, DISABLED, FAILED }

enum class AdblockBackendKind {
    /** v2.0 Chromium C++ URLLoaderThrottle + native adblock-rust engine. */
    NATIVE_RUST,
    /** Frozen v1.0 GeckoView reference baseline hosting AMO-signed uBlock Origin. */
    LEGACY_WEBEXTENSION,
}

data class AdblockSnapshot(
    val status: AdblockStatus = AdblockStatus.STARTING,
    val installed: Boolean = false,
    val enabled: Boolean = true,
    val version: String? = null,
    val privateAllowed: Boolean = false,
    val blockedCount: Int = 0,
    val totalBlockedCount: Long = 0L,
    val badgeText: String = "",
    val popupAvailable: Boolean = false,
    val nativeControlsAvailable: Boolean = false,
    val siteBlockingEnabled: Boolean = true,
    val backend: AdblockBackendKind = AdblockBackendKind.LEGACY_WEBEXTENSION,
    val error: String? = null,
    val startupTimedOut: Boolean = false,
    val pageNeedsReload: Boolean = false,
    val badgeTabId: String? = null,
)

/** Metadata for a subscription filter list managed by the native adblock-rust backend. */
data class AdblockFilterList(
    val id: String,
    val title: String,
    val url: String,
    val enabled: Boolean = true,
    val ruleCount: Int = 0,
    val lastUpdatedEpochMs: Long = 0L,
    val builtIn: Boolean = true,
)

/** Outcome of evaluating a network request against native adblock-rust rules. */
data class AdblockMatchResult(
    val blocked: Boolean,
    val redirect: String? = null,
    val matchedRule: String? = null,
    val exceptionRule: String? = null,
    val important: Boolean = false,
)

/** Renderer-side cosmetic CSS selectors and scriptlets returned for a document URL. */
data class AdblockCosmeticResources(
    val hideSelectors: List<String> = emptyList(),
    val injectedScript: String = "",
    val genericHide: Boolean = false,
)

/** Engine-agnostic contract for the native adblock-rust rule engine (Phase C0 prototype & Phase C2 Chromium C++ hook). */
interface NativeAdblockEngine : Closeable {
    fun loadFilterRules(rulesText: String): Boolean
    fun serializeSnapshot(): ByteArray
    fun deserializeSnapshot(snapshot: ByteArray): Boolean
    fun checkNetworkRequest(url: String, sourceUrl: String, requestType: String): AdblockMatchResult
    fun cosmeticResources(url: String): AdblockCosmeticResources
    fun setSiteAllowed(host: String, allowed: Boolean)
    fun isSiteAllowed(host: String): Boolean
    fun addCustomRule(rule: String): Boolean
    fun removeCustomRule(rule: String): Boolean
    fun customRules(): List<String>
}

/** Opaque host for a backend-provided extension popup (frozen GeckoView uBO baseline). */
interface BrowserPopupSession : Closeable {
    fun createView(context: Context): BrowserEngineView
    override fun close()
}
