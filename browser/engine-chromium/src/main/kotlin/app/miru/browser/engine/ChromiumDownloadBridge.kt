// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Translates Chromium C++ download interceptions (`http`/`https`, `data:`, and `blob:` temp files)
 * into process-memory-only [BrowserDownloadRequest] instances consumed by `:feature:downloads`.
 */
internal object ChromiumDownloadBridge {
    private const val MAX_DATA_URI_BYTES = 32 * 1024 * 1024

    internal data class ParsedDataUri(
        val mimeType: String?,
        val bytes: ByteArray,
    )

    fun buildRequest(
        url: String,
        fileName: String?,
        mimeType: String?,
        contentLength: Long,
        sourcePage: String?,
        userAgent: String?,
        cookieHeader: String?,
        isPrivate: Boolean,
        tempPath: String? = null,
    ): BrowserDownloadRequest? {
        if (url.isBlank()) return null
        val cleanSource = sourcePage?.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
        val cleanName = fileName?.trim()?.takeIf { it.isNotEmpty() }

        if (!tempPath.isNullOrBlank()) {
            val tempFile = File(tempPath)
            if (!tempFile.isFile) return null
            val size = tempFile.length().takeIf { it >= 0L } ?: contentLength.takeIf { it >= 0L }
            return BrowserDownloadRequest(
                url = url,
                fileName = cleanName,
                mimeType = mimeType?.trim()?.takeIf { it.isNotEmpty() },
                contentLength = size,
                headers = emptyMap(),
                sourcePage = cleanSource,
                isPrivate = isPrivate,
                bodyStream = DeleteOnCloseFileInputStream(tempFile),
            )
        }

        if (url.startsWith("data:", ignoreCase = true)) {
            val parsed = parseDataUri(url) ?: return null
            return BrowserDownloadRequest(
                url = url,
                fileName = cleanName,
                mimeType = mimeType?.trim()?.takeIf { it.isNotEmpty() } ?: parsed.mimeType,
                contentLength = parsed.bytes.size.toLong(),
                headers = emptyMap(),
                sourcePage = cleanSource,
                isPrivate = isPrivate,
                bodyStream = ByteArrayInputStream(parsed.bytes),
            )
        }

        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return null
        }

        val headers = buildMap {
            cookieHeader?.trim()?.takeIf { it.isNotEmpty() }?.let { put("Cookie", it) }
            userAgent?.trim()?.takeIf { it.isNotEmpty() }?.let { put("User-Agent", it) }
            cleanSource?.let { page ->
                val ref = contextReferrer(page, url) ?: page
                if (ref.isNotEmpty()) put("Referer", ref)
            }
        }
        return BrowserDownloadRequest(
            url = url,
            fileName = cleanName,
            mimeType = mimeType?.trim()?.takeIf { it.isNotEmpty() },
            contentLength = contentLength.takeIf { it >= 0L },
            headers = headers,
            sourcePage = cleanSource,
            isPrivate = isPrivate,
            bodyStream = null,
        )
    }

    internal fun parseDataUri(uri: String): ParsedDataUri? {
        if (!uri.startsWith("data:", ignoreCase = true)) return null
        val comma = uri.indexOf(',')
        if (comma < 5) return null
        val meta = uri.substring(5, comma)
        val payload = uri.substring(comma + 1)
        val parts = meta.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val isBase64 = parts.lastOrNull()?.equals("base64", ignoreCase = true) == true
        val mime = parts.firstOrNull()?.takeIf { !it.equals("base64", ignoreCase = true) && '/' in it }
        val bytes = runCatching {
            if (isBase64) {
                val cleaned = payload.filterNot(Char::isWhitespace)
                if (cleaned.length > (MAX_DATA_URI_BYTES * 4L) / 3L + 8L) return null
                Base64.getMimeDecoder().decode(cleaned)
            } else {
                val decoded = URLDecoder.decode(payload, StandardCharsets.UTF_8.name())
                val raw = decoded.toByteArray(StandardCharsets.UTF_8)
                if (raw.size > MAX_DATA_URI_BYTES) return null
                raw
            }
        }.getOrNull() ?: return null
        if (bytes.size > MAX_DATA_URI_BYTES) return null
        return ParsedDataUri(mimeType = mime, bytes = bytes)
    }

    fun cleanupStaleBlobTempDir(dataDir: File) {
        runCatching {
            File(dataDir, "blob_tmp").takeIf { it.isDirectory }?.deleteRecursively()
        }
    }

    private class DeleteOnCloseFileInputStream(private val file: File) :
        FilterInputStream(FileInputStream(file)) {
        override fun close() {
            try {
                super.close()
            } finally {
                runCatching { file.delete() }
            }
        }
    }
}
