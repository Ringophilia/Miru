// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.adblock.rust

import java.io.File

/**
 * Low-level JNI bridge to the unified Rust core (`native/miru-core` → `miru_core`).
 * Exposes adblock-rust, zero-GC media classifier/prober, and URL/cache-key policies
 * through a single shared native binary.
 */
object NativeAdblockBridge {
    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded(libraryDirectory: File? = null) {
        if (loaded) return
        val dir = libraryDirectory ?: System.getProperty("miru.core.native.dir")?.let(::File)
        if (dir != null && dir.isDirectory) {
            val file = File(dir, System.mapLibraryName("miru_core"))
            if (file.isFile) {
                System.load(file.absolutePath)
                loaded = true
                return
            }
        }
        System.loadLibrary("miru_core")
        loaded = true
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
    @JvmStatic external fun nativeProbeMediaBytesJson(bytes: ByteArray): String?
    @JvmStatic external fun nativeStripCacheKeySignatures(url: String): String
    @JvmStatic external fun nativeSiteOrigin(url: String): String?
}
