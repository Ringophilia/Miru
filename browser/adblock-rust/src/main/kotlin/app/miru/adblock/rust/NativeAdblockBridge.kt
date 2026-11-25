// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.adblock.rust

import app.miru.nativecore.MiruNativeBridge
import java.io.File

/** Compatibility facade delegating to the unified `:core:native-core` JNI gateway (`libmiru_core`). */
object NativeAdblockBridge {
    fun ensureLoaded(libraryDirectory: File? = null) = MiruNativeBridge.ensureLoaded(libraryDirectory)
    fun nativeCreateFromRules(rules: String): Long = MiruNativeBridge.nativeCreateFromRules(rules)
    fun nativeCreateFromSnapshot(snapshot: ByteArray): Long = MiruNativeBridge.nativeCreateFromSnapshot(snapshot)
    fun nativeSerializeSnapshot(handle: Long): ByteArray = MiruNativeBridge.nativeSerializeSnapshot(handle)
    fun nativeCheckRequestJson(handle: Long, url: String, sourceUrl: String, requestType: String): String =
        MiruNativeBridge.nativeCheckRequestJson(handle, url, sourceUrl, requestType)
    fun nativeCosmeticResourcesJson(handle: Long, url: String): String =
        MiruNativeBridge.nativeCosmeticResourcesJson(handle, url)
    fun nativeSetEnabled(handle: Long, enabled: Boolean) = MiruNativeBridge.nativeSetEnabled(handle, enabled)
    fun nativeSetSiteAllowed(handle: Long, host: String, allowed: Boolean) =
        MiruNativeBridge.nativeSetSiteAllowed(handle, host, allowed)
    fun nativeIsSiteAllowed(handle: Long, host: String): Boolean =
        MiruNativeBridge.nativeIsSiteAllowed(handle, host)
    fun nativeAddCustomRule(handle: Long, rule: String): Boolean =
        MiruNativeBridge.nativeAddCustomRule(handle, rule)
    fun nativeRemoveCustomRule(handle: Long, rule: String): Boolean =
        MiruNativeBridge.nativeRemoveCustomRule(handle, rule)
    fun nativeCustomRulesJson(handle: Long): String = MiruNativeBridge.nativeCustomRulesJson(handle)
    fun nativeDestroy(handle: Long) = MiruNativeBridge.nativeDestroy(handle)
    fun nativeClassifyMedia(url: String, contentType: String, contentLength: Long): String? =
        MiruNativeBridge.nativeClassifyMedia(url, contentType, contentLength)
    fun nativeProbeMediaBytesJson(bytes: ByteArray): String? = MiruNativeBridge.nativeProbeMediaBytesJson(bytes)
    fun nativeStripCacheKeySignatures(url: String): String = MiruNativeBridge.nativeStripCacheKeySignatures(url)
    fun nativeSiteOrigin(url: String): String? = MiruNativeBridge.nativeSiteOrigin(url)
}
