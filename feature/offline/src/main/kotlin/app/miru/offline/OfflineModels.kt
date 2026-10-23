// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.offline

import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.offline.DownloadRequest
import java.io.IOException

/** Page context and credentials exist only for this process. Never put this in saved state. */
class OfflineSource(
    val url: String,
    val title: String,
    val pageUrl: String = "",
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val isPrivate: Boolean = false,
    val drm: Boolean = false,
) {
    override fun toString() = "OfflineSource(private=$isPrivate, drm=$drm)"
}

data class OfflineQuality(val id: String, val label: String, val width: Int, val height: Int, val bitrate: Int)

class OfflineSelection internal constructor(
    val source: OfflineSource,
    val qualities: List<OfflineQuality>,
    internal val requests: Map<String, DownloadRequest>,
) {
    override fun toString() = "OfflineSelection(qualities=${qualities.size})"
}

class OfflineItem(
    val id: String,
    val url: String,
    val title: String,
    val mimeType: String,
    val sourcePage: String?,
    val qualityLabel: String,
    val status: String,
    val error: String?,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val percent: Float,
    val createdAt: Long,
    val completedAt: Long?,
    val isPrivate: Boolean,
) {
    override fun toString() = "OfflineItem(id=$id, status=$status)"
}

class OfflinePlayback(val mediaItem: MediaItem, val dataSourceFactory: DataSource.Factory)

/** Diagnostic type names are safe to expose; original HTTP exceptions can contain signed URLs. */
class OfflineException(val code: String, val reasonType: String? = null) : IOException(if(reasonType == null) code else "$code ($reasonType)")

internal fun offlineMimeType(path: String, declared: String?): String? {
    val mime = declared?.substringBefore(';')?.trim()?.lowercase()
    return when {
        mime in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl") || path.endsWith(".m3u8", true) -> "application/x-mpegURL"
        mime == "application/dash+xml" || path.endsWith(".mpd", true) -> "application/dash+xml"
        else -> null
    }
}

internal fun qualityLabel(width: Int, height: Int): String = when(minOf(width, height)) {
    in 2160..Int.MAX_VALUE -> "4K"
    in 1440..2159 -> "2K"
    in 1080..1439 -> "1080P"
    in 720..1079 -> "720P"
    in 1..719 -> "480P"
    else -> ""
}
