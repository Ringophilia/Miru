// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.media

import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** All URLs and credentials are process-only. Never serialize this model into Android saved state. */
class DetectedMedia(
    val id: String,
    val tabId: String,
    val url: String,
    val pageUrl: String,
    val title: String,
    val kind: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val duration: Double = 0.0,
    val position: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
    val playing: Boolean = false,
    val drm: Boolean = false,
    val isPrivate: Boolean = false,
) {
    val playable: Boolean get() = !drm && runCatching { URI(url).scheme in setOf("http", "https") }.getOrDefault(false)
    val canAutoTakeover: Boolean get() = playable && playing && kind in setOf("VIDEO", "HLS", "DASH") &&
        duration.isFinite() && duration > 0 && width > 0 && height > 0
    // Do not allow generated data-class toString() to print credentials.
    override fun toString(): String = "DetectedMedia(kind=$kind, playable=$playable)"
}

/** Owned by the browser's Main dispatcher; private tab state expires with its session. */
class MediaIndex {
    private val mutable = MutableStateFlow<Map<String, List<DetectedMedia>>>(emptyMap())
    val state = mutable.asStateFlow()
    fun replace(tabId: String, items: List<DetectedMedia>) {
        val bounded = items.sortedWith(compareByDescending<DetectedMedia> { it.playing }.thenByDescending { it.duration }).distinctBy { it.id }.take(64).sortedWith(
            compareByDescending<DetectedMedia> { it.playing }.thenBy { it.duration > 0 && it.duration < 30 }.thenByDescending { it.duration }
        )
        mutable.value = mutable.value + (tabId to bounded)
    }
    fun clear(tabId: String) { mutable.value = mutable.value - tabId }
    fun retain(ids: Set<String>) { mutable.value = mutable.value.filterKeys { it in ids } }
    fun find(tabId: String, id: String): DetectedMedia? = mutable.value[tabId]?.firstOrNull { it.id == id }
}
