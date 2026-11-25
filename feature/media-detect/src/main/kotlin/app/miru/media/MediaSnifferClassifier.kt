// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.media

import java.net.URI
import java.util.Locale

/**
 * Pure-Kotlin media classifier mirroring the shared JS specification (`media-classifier.js`),
 * used directly by the v2.0 Chromium C++ `URLLoaderThrottle` media sniffing bridge.
 */
object MediaSnifferClassifier {
    private val segmentExtensions = Regex(".*\\.(ts|m4s|aac|cmfv|cmfa)$")
    private val directVideoExtensions = Regex(".*\\.(mp4|webm|mkv|flv|mov)$")
    private val adHosts = Regex("(^|\\.)(doubleclick\\.net|googlesyndication\\.com|adnxs\\.com|imasdk\\.googleapis\\.com)$")
    private val hlsMimes = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
    )
    private val allowedHeaders = setOf("referer", "origin", "user-agent", "cookie", "authorization")
    private const val MIN_VIDEO_BYTES = 200L * 1024L
    private const val MIN_AUDIO_BYTES = 1024L * 1024L

    fun dedupKey(rawUrl: String): String? = runCatching {
        val uri = URI(rawUrl)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank) ?: return null
        val defaultPort = if (scheme == "https") 443 else 80
        val port = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        "$scheme://$host$port$path"
    }.getOrNull()

    fun classify(rawUrl: String, contentType: String? = null, contentLength: Long? = null): String? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank) ?: return null
        if (adHosts.containsMatchIn(host)) return null

        val path = uri.rawPath.orEmpty().lowercase(Locale.ROOT)
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val size = contentLength ?: 0L

        if (segmentExtensions.matches(path)) return null
        if (mime == "application/xml" || mime == "text/xml") return null
        if (path.endsWith(".m3u8") || mime in hlsMimes) return "HLS"
        if (path.endsWith(".mpd") || mime == "application/dash+xml") return "DASH"
        if (mime.startsWith("video/") || directVideoExtensions.matches(path)) {
            return if (size > 0L && size < MIN_VIDEO_BYTES) null else "VIDEO"
        }
        if (mime.startsWith("audio/") && size > MIN_AUDIO_BYTES) return "AUDIO"
        return null
    }

    fun isVastPayload(contentType: String?, bodyPreview: String): Boolean {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (mime.isNotEmpty() && !mime.contains("xml")) return false
        return bodyPreview.contains("<VAST", ignoreCase = true) ||
            bodyPreview.contains("<vmap:VMAP", ignoreCase = true)
    }

    fun sanitizeCaptureHeaders(headers: Map<String, String>): Map<String, String> = buildMap {
        headers.forEach { (name, value) ->
            if (name.lowercase(Locale.ROOT) in allowedHeaders && value.isNotEmpty()) {
                put(name, value)
            }
        }
    }
}

/**
 * Engine-neutral coordinator that deduplicates and indexes network-captured media per tab,
 * preserving newest signed URLs/headers in memory while isolating private tabs.
 */
class MediaSnifferCoordinator(private val index: MediaIndex = MediaIndex()) {
    private data class TabBucket(
        var pageUrl: String,
        val isPrivate: Boolean,
        val byKey: LinkedHashMap<String, DetectedMedia> = linkedMapOf(),
    )

    private val buckets = mutableMapOf<String, TabBucket>()
    val state = index.state

    fun onNetworkMedia(
        tabId: String,
        pageUrl: String,
        title: String,
        isPrivate: Boolean,
        mediaUrl: String,
        contentType: String? = null,
        contentLength: Long? = null,
        headers: Map<String, String> = emptyMap(),
    ): DetectedMedia? {
        val kind = MediaSnifferClassifier.classify(mediaUrl, contentType, contentLength) ?: return null
        val key = MediaSnifferClassifier.dedupKey(mediaUrl) ?: return null
        val bucket = buckets.getOrPut(tabId) { TabBucket(pageUrl, isPrivate) }
        if (bucket.pageUrl != pageUrl || bucket.isPrivate != isPrivate) {
            bucket.pageUrl = pageUrl
            bucket.byKey.clear()
        }
        val existing = bucket.byKey[key]
        val entry = DetectedMedia(
            id = key,
            tabId = tabId,
            url = mediaUrl,
            pageUrl = pageUrl,
            title = title.ifBlank { existing?.title.orEmpty() },
            kind = kind,
            mimeType = contentType?.substringBefore(';')?.trim() ?: existing?.mimeType,
            headers = MediaSnifferClassifier.sanitizeCaptureHeaders(headers),
            duration = existing?.duration ?: 0.0,
            position = existing?.position ?: 0.0,
            width = existing?.width ?: 0,
            height = existing?.height ?: 0,
            playing = existing?.playing ?: false,
            drm = existing?.drm ?: false,
            isPrivate = isPrivate,
        )
        bucket.byKey[key] = entry
        index.replace(tabId, bucket.byKey.values.toList())
        return entry
    }

    fun onNavigation(tabId: String) {
        buckets.remove(tabId)
        index.clear(tabId)
    }

    fun retainTabs(activeTabIds: Set<String>) {
        buckets.keys.retainAll(activeTabIds)
        index.retain(activeTabIds)
    }
}
