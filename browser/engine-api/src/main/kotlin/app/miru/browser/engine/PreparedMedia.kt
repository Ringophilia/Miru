// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import app.miru.media.DetectedMedia

class PreparedMedia(
    val media: DetectedMedia,
    val headers: Map<String, String>,
    val positionMs: Long,
)

/**
 * Engine-agnostic network media observation delivered by Chromium C++ URLLoaderThrottle (Phase C2)
 * or legacy WebExtension bridge (frozen GeckoView baseline). Headers stay in process memory only.
 */
data class SniffedNetworkMedia(
    val tabId: String,
    val pageUrl: String,
    val isPrivate: Boolean,
    val mediaUrl: String,
    val contentType: String? = null,
    val contentLength: Long? = null,
    val headers: Map<String, String> = emptyMap(),
    val fromSubframe: Boolean = false,
) {
    override fun toString() = "SniffedNetworkMedia(tabId=$tabId, private=$isPrivate, type=$contentType)"
}

/** Snapshot returned by the lightweight page script when pausing a web video for native handoff. */
data class MediaPagePlaybackSnapshot(
    val positionSeconds: Double = 0.0,
    val drmProtected: Boolean = false,
    val durationSeconds: Double? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
)
