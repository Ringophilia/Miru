// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.downloads

import java.net.URI
import java.net.URLDecoder

internal data class ByteRange(val start: Long, val end: Long)
internal object DownloadLogic {
    fun ranges(total: Long, threads: Int, acceptsRanges: Boolean): List<ByteRange> {
        if(total <= 0) return listOf(ByteRange(0, -1))
        val count = if(acceptsRanges && total > 4L * 1024 * 1024) threads.coerceIn(1, 8) else 1
        val chunk = total / count
        return (0 until count).map { index -> ByteRange(index * chunk, if(index == count - 1) total - 1 else (index + 1) * chunk - 1) }
    }
    fun fileName(disposition: String?, url: String, mime: String?): String {
        val encoded = disposition?.let { Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        val declared = disposition?.let { Regex("filename=\"([^\"]+)\"|filename=([^;]+)", RegexOption.IGNORE_CASE).find(it)?.let { match -> match.groupValues[1].ifBlank { match.groupValues[2].trim() } } }
        val fromUrl = runCatching { URI(url).rawPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() } }.getOrNull()
        val raw = encoded?.let { decode(it) } ?: declared ?: fromUrl?.let { decode(it.replace("+", "%2B")) } ?: "download"
        var clean = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(180).ifBlank { "download" }
        if(!clean.contains('.')) clean += when(mime?.substringBefore(';')?.lowercase()) { "application/pdf" -> ".pdf"; "video/mp4" -> ".mp4"; "audio/mpeg" -> ".mp3"; "image/jpeg" -> ".jpg"; "image/png" -> ".png"; "text/html" -> ".html"; "application/zip" -> ".zip"; "application/vnd.android.package-archive" -> ".apk"; else -> "" }
        return clean
    }
    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        if(!exists(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var n = 1
        while(exists(name.substring(0,dot) + " ($n)" + name.substring(dot))) n++
        return name.substring(0,dot) + " ($n)" + name.substring(dot)
    }
    fun validatorsChanged(oldEtag: String?, oldModified: String?, oldSize: Long, newEtag: String?, newModified: String?, newSize: Long): Boolean =
        (oldSize >= 0 && newSize >= 0 && oldSize != newSize) || (oldEtag != null && oldEtag != newEtag) ||
            ((oldEtag.isNullOrBlank() || oldEtag.startsWith("W/", ignoreCase = true)) && oldModified != null && oldModified != newModified)
    /** A weak ETag permits byte-different representations and cannot protect a saved byte range. */
    fun canResume(etag: String?, lastModified: String?): Boolean =
        (!etag.isNullOrBlank() && !etag.startsWith("W/", ignoreCase = true)) || !lastModified.isNullOrBlank()
    fun category(name: String, mime: String): DownloadCategory {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("video/") || ext in setOf("mp4","mkv","webm","mov","flv","avi","ts","m3u8") -> DownloadCategory.VIDEO
            mime.startsWith("audio/") || ext in setOf("mp3","flac","aac","m4a","ogg","wav") -> DownloadCategory.AUDIO
            mime.startsWith("image/") -> DownloadCategory.IMAGE
            ext in setOf("pdf","doc","docx","xls","xlsx","ppt","pptx","txt","epub","md","csv") -> DownloadCategory.DOCUMENT
            ext in setOf("zip","rar","7z","tar","gz","bz2","xz") -> DownloadCategory.ARCHIVE
            ext in setOf("apk","xapk","apks") -> DownloadCategory.PACKAGE
            else -> DownloadCategory.OTHER
        }
    }
    private fun decode(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
