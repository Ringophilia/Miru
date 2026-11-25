// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.nativecore

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

data class NativeHistoryEntry(
    val url: String,
    val title: String,
    val lastVisited: Long,
    val visitCount: Int = 1,
)

data class NativeInlineDomain(val text: String, val url: String)

/**
 * High-level Kotlin facade over `libmiru_core`.
 * When the native library is unavailable (e.g. an isolated JVM unit-test process without DLL path),
 * [isAvailable] returns `false` so callers cleanly use their Kotlin fallback.
 */
object MiruNativeCore {
    val isAvailable: Boolean
        get() = MiruNativeBridge.tryLoad()

    fun classifyMedia(url: String, contentType: String? = null, contentLength: Long? = null): String? {
        if (!isAvailable) return null
        return MiruNativeBridge.nativeClassifyMedia(url, contentType.orEmpty(), contentLength ?: -1L)
    }

    fun mediaDedupKey(url: String): String? {
        if (!isAvailable) return null
        return MiruNativeBridge.nativeMediaDedupKey(url)
    }

    fun probeHls(playlist: String): Pair<Int, Int>? =
        unpackResolution(if (isAvailable) MiruNativeBridge.nativeProbeHlsPacked(playlist) else 0L)

    fun probeMp4(bytes: ByteArray): Pair<Int, Int>? =
        unpackResolution(if (isAvailable) MiruNativeBridge.nativeProbeMp4Packed(bytes) else 0L)

    fun probeMediaBytes(bytes: ByteArray): Pair<Int, Int>? =
        unpackResolution(if (isAvailable) MiruNativeBridge.nativeProbeMediaBytesPacked(bytes) else 0L)

    fun siteOrigin(url: String?): String? {
        if (!isAvailable || url.isNullOrEmpty()) return null
        return MiruNativeBridge.nativeSiteOrigin(url)
    }

    fun normalizedCacheUrl(url: String, isRange: Boolean): String? {
        if (!isAvailable) return null
        return MiruNativeBridge.nativeNormalizedCacheUrl(url, isRange)
    }

    fun referrerForPolicy(documentUrl: String, targetUrl: String, policy: String): String? {
        if (!isAvailable) return null
        return MiruNativeBridge.nativeReferrerForPolicy(documentUrl, targetUrl, policy)
    }

    fun contextReferrer(pageUrl: String, targetUrl: String, capturedReferrer: String? = null): String? {
        if (!isAvailable) return null
        return MiruNativeBridge.nativeContextReferrer(pageUrl, targetUrl, capturedReferrer)
    }

    fun externalDataAllowed(url: String, hasPackage: Boolean): Boolean? {
        if (!isAvailable) return null
        return MiruNativeBridge.nativeExternalDataAllowed(url, hasPackage)
    }

    fun completeInlineDomain(input: String, historyUrls: List<String>): NativeInlineDomain? {
        if (!isAvailable || historyUrls.isEmpty()) return null
        val joined = historyUrls.asSequence()
            .filter { '\n' !in it && '\r' !in it }
            .joinToString("\n")
        val raw = MiruNativeBridge.nativeCompleteInlineDomainJson(input, joined) ?: return null
        return NativeJson.parseInlineDomain(raw)
    }

    private fun unpackResolution(packed: Long): Pair<Int, Int>? {
        if (packed == 0L) return null
        val width = (packed ushr 32).toInt()
        val height = (packed and 0xFFFF_FFFFL).toInt()
        return if (width > 0 && height > 0) width to height else null
    }
}

class RustOmniboxIndex : Closeable {
    private val handle: AtomicLong

    init {
        MiruNativeBridge.ensureLoaded()
        val ptr = MiruNativeBridge.nativeOmniboxCreate()
        check(ptr != 0L) { "Failed to allocate native OmniboxIndex" }
        handle = AtomicLong(ptr)
    }

    private fun requireHandle(): Long = handle.get().also { check(it != 0L) { "RustOmniboxIndex is closed" } }

    fun replaceHistory(records: List<NativeHistoryEntry>) {
        val json = buildString {
            append('[')
            records.forEachIndexed { index, r ->
                if (index > 0) append(',')
                append("{\"url\":").append(NativeJson.quote(r.url))
                append(",\"title\":").append(NativeJson.quote(r.title))
                append(",\"last_visited\":").append(r.lastVisited)
                append(",\"visit_count\":").append(r.visitCount).append('}')
            }
            append(']')
        }
        MiruNativeBridge.nativeOmniboxReplaceHistoryJson(requireHandle(), json)
    }

    fun recordVisit(url: String, title: String = "", nowMs: Long = System.currentTimeMillis()) {
        MiruNativeBridge.nativeOmniboxRecordVisit(requireHandle(), url, title, nowMs)
    }

    fun completeDomain(input: String): NativeInlineDomain? {
        val raw = MiruNativeBridge.nativeOmniboxCompleteDomainJson(requireHandle(), input) ?: return null
        return NativeJson.parseInlineDomain(raw)
    }

    fun suggest(query: String, limit: Int = 5): List<NativeHistoryEntry> {
        val raw = MiruNativeBridge.nativeOmniboxSuggestJson(requireHandle(), query, limit)
        return NativeJson.parseHistoryList(raw)
    }

    override fun close() {
        val ptr = handle.getAndSet(0L)
        if (ptr != 0L) MiruNativeBridge.nativeOmniboxDestroy(ptr)
    }
}

internal object NativeJson {
    fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    fun parseInlineDomain(json: String): NativeInlineDomain? {
        val obj = Reader(json).readValue() as? Map<*, *> ?: return null
        val text = obj["text"] as? String ?: return null
        val url = obj["url"] as? String ?: return null
        return NativeInlineDomain(text, url)
    }

    fun parseHistoryList(json: String): List<NativeHistoryEntry> {
        val list = Reader(json).readValue() as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val obj = item as? Map<*, *> ?: return@mapNotNull null
            val url = obj["url"] as? String ?: return@mapNotNull null
            NativeHistoryEntry(
                url = url,
                title = obj["title"] as? String ?: "",
                lastVisited = (obj["last_visited"] as? Number)?.toLong() ?: 0L,
                visitCount = (obj["visit_count"] as? Number)?.toInt() ?: 1,
            )
        }
    }

    private class Reader(private val src: String) {
        private var pos = 0

        fun readValue(): Any? {
            skipWs()
            if (pos >= src.length) return null
            return when (src[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> { pos += 4; true }
                'f' -> { pos += 5; false }
                'n' -> { pos += 4; null }
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            pos++
            val map = linkedMapOf<String, Any?>()
            skipWs()
            if (pos < src.length && src[pos] == '}') { pos++; return map }
            while (pos < src.length) {
                skipWs()
                val key = readString()
                skipWs()
                if (pos < src.length && src[pos] == ':') pos++
                map[key] = readValue()
                skipWs()
                if (pos < src.length && src[pos] == ',') { pos++; continue }
                if (pos < src.length && src[pos] == '}') { pos++; break }
                break
            }
            return map
        }

        private fun readArray(): List<Any?> {
            pos++
            val list = mutableListOf<Any?>()
            skipWs()
            if (pos < src.length && src[pos] == ']') { pos++; return list }
            while (pos < src.length) {
                list.add(readValue())
                skipWs()
                if (pos < src.length && src[pos] == ',') { pos++; continue }
                if (pos < src.length && src[pos] == ']') { pos++; break }
                break
            }
            return list
        }

        private fun readString(): String {
            pos++
            val out = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                if (c == '"') break
                if (c == '\\' && pos < src.length) {
                    when (val esc = src[pos++]) {
                        '"', '\\', '/' -> out.append(esc)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> if (pos + 4 <= src.length) {
                            out.append(src.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> out.append(esc)
                    }
                } else {
                    out.append(c)
                }
            }
            return out.toString()
        }

        private fun readNumber(): Number? {
            val start = pos
            while (pos < src.length && src[pos] in "0123456789+-eE.") pos++
            val token = src.substring(start, pos)
            return token.toLongOrNull() ?: token.toDoubleOrNull()
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }
    }
}
