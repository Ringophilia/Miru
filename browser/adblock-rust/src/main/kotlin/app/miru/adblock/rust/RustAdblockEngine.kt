// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.adblock.rust

import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class RustNetworkMatch(
    val blocked: Boolean,
    val redirect: String? = null,
    val matchedRule: String? = null,
    val exceptionRule: String? = null,
    val important: Boolean = false,
)

data class RustCosmeticResources(
    val hideSelectors: List<String> = emptyList(),
    val injectedScript: String = "",
    val genericHide: Boolean = false,
)

/**
 * Pure Kotlin/JVM wrapper over the native `adblock-rust` engine prototype.
 * Thread-safe; backed by a Rust `RwLock` + atomic flags and explicit native handle disposal.
 */
class RustAdblockEngine private constructor(nativeHandle: Long) : Closeable {
    private val handle = AtomicLong(nativeHandle)

    private fun requireHandle(): Long {
        val ptr = handle.get()
        check(ptr != 0L) { "RustAdblockEngine is closed" }
        return ptr
    }

    fun serializeSnapshot(): ByteArray = NativeAdblockBridge.nativeSerializeSnapshot(requireHandle())

    fun checkNetworkRequest(url: String, sourceUrl: String, requestType: String): RustNetworkMatch {
        val raw = NativeAdblockBridge.nativeCheckRequestJson(requireHandle(), url, sourceUrl, requestType)
        return parseNetworkMatch(raw)
    }

    fun cosmeticResources(url: String): RustCosmeticResources {
        val raw = NativeAdblockBridge.nativeCosmeticResourcesJson(requireHandle(), url)
        return parseCosmeticResources(raw)
    }

    fun setEnabled(enabled: Boolean) {
        NativeAdblockBridge.nativeSetEnabled(requireHandle(), enabled)
    }

    fun setSiteAllowed(host: String, allowed: Boolean) {
        NativeAdblockBridge.nativeSetSiteAllowed(requireHandle(), host, allowed)
    }

    fun isSiteAllowed(host: String): Boolean =
        NativeAdblockBridge.nativeIsSiteAllowed(requireHandle(), host)

    fun addCustomRule(rule: String): Boolean =
        NativeAdblockBridge.nativeAddCustomRule(requireHandle(), rule)

    fun removeCustomRule(rule: String): Boolean =
        NativeAdblockBridge.nativeRemoveCustomRule(requireHandle(), rule)

    fun customRules(): List<String> =
        parseStringArray(NativeAdblockBridge.nativeCustomRulesJson(requireHandle()))

    override fun close() {
        val ptr = handle.getAndSet(0L)
        if (ptr != 0L) NativeAdblockBridge.nativeDestroy(ptr)
    }

    companion object {
        fun fromRules(rulesText: String, nativeDir: File? = null): RustAdblockEngine {
            NativeAdblockBridge.ensureLoaded(nativeDir)
            val ptr = NativeAdblockBridge.nativeCreateFromRules(rulesText)
            check(ptr != 0L) { "Failed to create RustAdblockEngine from rules" }
            return RustAdblockEngine(ptr)
        }

        fun fromSnapshot(snapshot: ByteArray, nativeDir: File? = null): RustAdblockEngine {
            NativeAdblockBridge.ensureLoaded(nativeDir)
            val ptr = NativeAdblockBridge.nativeCreateFromSnapshot(snapshot)
            check(ptr != 0L) { "Failed to deserialize RustAdblockEngine snapshot" }
            return RustAdblockEngine(ptr)
        }

        internal fun parseNetworkMatch(json: String): RustNetworkMatch = RustAdblockJson.parseNetworkMatch(json)
        internal fun parseCosmeticResources(json: String): RustCosmeticResources = RustAdblockJson.parseCosmeticResources(json)
        internal fun parseStringArray(json: String): List<String> = RustAdblockJson.parseStringArray(json)
    }
}

/** Zero-dependency JSON parser for the fixed serde_json payloads emitted by `native/adblock-rust`. */
internal object RustAdblockJson {
    fun parseNetworkMatch(json: String): RustNetworkMatch {
        val obj = parseObject(json)
        return RustNetworkMatch(
            blocked = obj["blocked"] as? Boolean ?: false,
            redirect = obj["redirect"] as? String,
            matchedRule = obj["matched_rule"] as? String,
            exceptionRule = obj["exception_rule"] as? String,
            important = obj["important"] as? Boolean ?: false,
        )
    }

    fun parseCosmeticResources(json: String): RustCosmeticResources {
        val obj = parseObject(json)
        @Suppress("UNCHECKED_CAST")
        val selectors = (obj["hide_selectors"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        return RustCosmeticResources(
            hideSelectors = selectors,
            injectedScript = obj["injected_script"] as? String ?: "",
            genericHide = obj["generic_hide"] as? Boolean ?: false,
        )
    }

    fun parseStringArray(json: String): List<String> {
        val reader = Reader(json)
        return (reader.readValue() as? List<*>)?.filterIsInstance<String>().orEmpty()
    }

    private fun parseObject(json: String): Map<String, Any?> {
        val reader = Reader(json)
        @Suppress("UNCHECKED_CAST")
        return reader.readValue() as? Map<String, Any?> ?: emptyMap()
    }

    private class Reader(private val src: String) {
        private var pos = 0

        fun readValue(): Any? {
            skipWhitespace()
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
            pos++ // '{'
            val map = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (pos < src.length && src[pos] == '}') { pos++; return map }
            while (pos < src.length) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                if (pos < src.length && src[pos] == ':') pos++
                map[key] = readValue()
                skipWhitespace()
                if (pos < src.length && src[pos] == ',') { pos++; continue }
                if (pos < src.length && src[pos] == '}') { pos++; break }
                break
            }
            return map
        }

        private fun readArray(): List<Any?> {
            pos++ // '['
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (pos < src.length && src[pos] == ']') { pos++; return list }
            while (pos < src.length) {
                list.add(readValue())
                skipWhitespace()
                if (pos < src.length && src[pos] == ',') { pos++; continue }
                if (pos < src.length && src[pos] == ']') { pos++; break }
                break
            }
            return list
        }

        private fun readString(): String {
            pos++ // '"'
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

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }
    }
}
