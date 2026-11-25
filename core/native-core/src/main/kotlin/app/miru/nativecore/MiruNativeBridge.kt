// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.nativecore

import java.io.File

/**
 * Unified JNI gateway to `libmiru_core` (`native/miru-core`):
 * adblock-rust, media classifier/prober, URL/referrer/cache-key policies, and omnibox storage index.
 */
object MiruNativeBridge {
    @Volatile
    private var loaded = false

    @Volatile
    private var loadAttempted = false

    val isLoaded: Boolean
        get() = loaded

    @Synchronized
    fun ensureLoaded(libraryDirectory: File? = null) {
        if (loaded) return
        val dir = libraryDirectory ?: System.getProperty("miru.core.native.dir")?.let(::File)
        if (dir != null && dir.isDirectory) {
            val file = File(dir, System.mapLibraryName("miru_core"))
            if (file.isFile) {
                System.load(file.absolutePath)
                loaded = true
                loadAttempted = true
                return
            }
        }
        System.loadLibrary("miru_core")
        loaded = true
        loadAttempted = true
    }

    /** Attempts loading once and caches failure cleanly for isolated JVM runs without native DLLs. */
    @Synchronized
    fun tryLoad(libraryDirectory: File? = null): Boolean {
        if (loaded) return true
        if (loadAttempted) return false
        return try {
            ensureLoaded(libraryDirectory)
            true
        } catch (_: UnsatisfiedLinkError) {
            loadAttempted = true
            false
        } catch (_: SecurityException) {
            loadAttempted = true
            false
        }
    }

    // Adblock
    @JvmStatic external fun nativeCreateFromRules(rules: String): Long
    @JvmStatic external fun nativeCreateFromSnapshot(snapshot: ByteArray): Long
    @JvmStatic external fun nativeSerializeSnapshot(handle: Long): ByteArray
    @JvmStatic external fun nativeCheckRequestJson(handle: Long, url: String, sourceUrl: String, requestType: String): String
    @JvmStatic external fun nativeCosmeticResourcesJson(handle: Long, url: String): String
    @JvmStatic external fun nativeSetEnabled(handle: Long, enabled: Boolean)
    @JvmStatic external fun nativeSetSiteAllowed(handle: Long, host: String, allowed: Boolean)
    @JvmStatic external fun nativeIsSiteAllowed(handle: Long, host: String): Boolean
    @JvmStatic external fun nativeAddCustomRule(handle: Long, rule: String): Boolean
    @JvmStatic external fun nativeRemoveCustomRule(handle: Long, rule: String): Boolean
    @JvmStatic external fun nativeCustomRulesJson(handle: Long): String
    @JvmStatic external fun nativeDestroy(handle: Long)

    // Media & URL core
    @JvmStatic external fun nativeClassifyMedia(url: String, contentType: String, contentLength: Long): String?
    @JvmStatic external fun nativeMediaDedupKey(url: String): String?
    @JvmStatic external fun nativeProbeMediaBytesJson(bytes: ByteArray): String?
    @JvmStatic external fun nativeProbeMediaBytesPacked(bytes: ByteArray): Long
    @JvmStatic external fun nativeProbeMp4Packed(bytes: ByteArray): Long
    @JvmStatic external fun nativeProbeHlsPacked(playlist: String): Long
    @JvmStatic external fun nativeNormalizedCacheUrl(url: String, isRange: Boolean): String
    @JvmStatic external fun nativeStripCacheKeySignatures(url: String): String
    @JvmStatic external fun nativeSiteOrigin(url: String?): String?
    @JvmStatic external fun nativeReferrerForPolicy(documentUrl: String, targetUrl: String, policy: String): String?
    @JvmStatic external fun nativeContextReferrer(pageUrl: String, targetUrl: String, capturedReferrer: String?): String?
    @JvmStatic external fun nativeExternalDataAllowed(url: String, hasPackage: Boolean): Boolean

    // Omnibox / storage index
    @JvmStatic external fun nativeCompleteInlineDomainJson(input: String, joinedUrls: String): String?
    @JvmStatic external fun nativeOmniboxCreate(): Long
    @JvmStatic external fun nativeOmniboxReplaceHistoryJson(handle: Long, recordsJson: String)
    @JvmStatic external fun nativeOmniboxRecordVisit(handle: Long, url: String, title: String, nowMs: Long)
    @JvmStatic external fun nativeOmniboxCompleteDomainJson(handle: Long, input: String): String?
    @JvmStatic external fun nativeOmniboxSuggestJson(handle: Long, query: String, limit: Int): String
    @JvmStatic external fun nativeOmniboxDestroy(handle: Long)
}
