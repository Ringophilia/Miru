// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.downloads

data class DownloadRequest(
    val url: String,
    val fileName: String? = null,
    val mimeType: String? = null,
    val contentLength: Long = -1,
    val headers: Map<String, String> = emptyMap(),
    val sourcePage: String? = null,
    val isPrivate: Boolean = false,
    val directoryUri: String? = null,
    val bodyStream: java.io.InputStream? = null,
)

enum class DownloadCategory { ALL, VIDEO, AUDIO, IMAGE, DOCUMENT, ARCHIVE, PACKAGE, OTHER }
data class DownloadItem(
    val id: String,
    val url: String,
    val fileName: String,
    val mimeType: String,
    val destUri: String? = null,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: String = "queued",
    val error: String? = null,
    val sourcePage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val directoryUri: String? = null,
    val category: DownloadCategory = DownloadCategory.OTHER,
    val speedBytesPerSec: Long = 0,
    val isPrivate: Boolean = false,
)
data class DownloadPreferences(
    val askBeforeDownload: Boolean = true,
    val maxConcurrent: Int = 3,
    val threadsPerFile: Int = 4,
    val directoryUri: String? = null,
    val deleteFileWithRecord: Boolean = true,
)

internal fun DownloadEntity.item() = DownloadItem(id, url, fileName, mimeType, destUri, totalBytes, downloadedBytes, status, error, sourcePage, createdAt, completedAt, directoryUri, DownloadLogic.category(fileName, mimeType), isPrivate=isPrivate)
