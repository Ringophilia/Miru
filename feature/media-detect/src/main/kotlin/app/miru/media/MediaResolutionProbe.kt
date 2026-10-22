// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.media

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Small, cancellable header probes. Call in the media panel's coroutine scope; disposing
 * the panel cancels queued work and the active OkHttp call. Cache files contain only
 * SHA-256 keys, measured dimensions and timestamps, never addresses or request headers.
 */
class MediaResolutionProbe(context: Context, cacheDirectory: File = File(context.filesDir, "resolution")) {
    private val cache = ResolutionCache(cacheDirectory)

    suspend fun probe(media: DetectedMedia): MediaResolution? {
        if (!media.playable || media.kind == "AUDIO") return null
        val key = cacheKey(media) ?: return null
        val known = MediaResolution.valid(media.width, media.height)
        if (known != null) return record(media, known.width, known.height)
        cache.lookup(key, media.isPrivate)?.let { return it.resolution }
        val start = media.url.toHttpUrlOrNull() ?: return null
        val session = Session(start, media.headers)
        val result = try { withTimeoutOrNull(20_000) { session.inspect(start, 0) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        // Cancellation from a closed panel must not become a six-hour failure entry.
        currentCoroutineContext().ensureActive()
        if (!session.attempted) return null
        return cache.record(key, media.isPrivate, result)
    }

    /** Actual player measurements can improve a manifest/header result, including a cached failure. */
    suspend fun record(media: DetectedMedia, width: Int, height: Int): MediaResolution? {
        val size = MediaResolution.valid(width, height) ?: return null
        val key = cacheKey(media) ?: return size
        return cache.record(key, media.isPrivate, size)
    }

    /** Optional eager cleanup when the last private tab/player closes; private state never reaches disk. */
    fun clearPrivate() = cache.clearPrivate()

    private class Session(private val original: HttpUrl, headers: Map<String, String>) {
        private val headers = headers.filter { (name, value) -> name.lowercase() in allowedHeaders && '\r' !in value && '\n' !in value }
        private var requests = 0
        private var bytesRead = 0
        var attempted = false
            private set

        suspend fun inspect(url: HttpUrl, depth: Int): MediaResolution? {
            if (depth > 3) return null
            val head = fetch(url, PREFIX_BYTES) ?: return null
            val text = head.bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
            if (text.startsWith("#EXTM3U")) return playlist(head.url, text, depth)
            if (text.startsWith("<?xml") || text.startsWith("<MPD") || head.contentType.contains("dash+xml")) return ResolutionParsers.dash(head.bytes)
            ResolutionParsers.media(head.bytes)?.let { return it }
            // A complete moov often lives after a large mdat. A server ignoring suffix
            // Range cannot provide this header without a full download, so do not read it.
            if (head.bytes.size >= 12 && String(head.bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp") {
                return fetch(head.url, PREFIX_BYTES, suffix = true)?.let { ResolutionParsers.mp4(it.bytes) }
            }
            return null
        }

        private suspend fun playlist(base: HttpUrl, text: String, depth: Int): MediaResolution? {
            ResolutionParsers.hls(text)?.let { return it }
            val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF:") }) {
                val variant = lines.withIndex().firstOrNull { it.value.startsWith("#EXT-X-STREAM-INF:") }
                    ?.let { entry -> lines.drop(entry.index + 1).firstOrNull { !it.startsWith('#') } } ?: return null
                return base.resolve(variant)?.let { inspect(it, depth + 1) }
            }
            // Sample a third of the way into a finite playlist to avoid a lower-quality
            // intro. For live lists the same bounded sample strategy is safe.
            val count = lines.count { !it.startsWith('#') }
            val target = count / 3
            var index = 0
            var sequence = 0L
            var key: Map<String, String>? = null
            var initialization: String? = null
            for (line in lines) when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').toLongOrNull()?.coerceAtLeast(0) ?: 0
                line.startsWith("#EXT-X-KEY:") -> key = ResolutionParsers.attributes(line).takeUnless { it["METHOD"] == "NONE" }
                line.startsWith("#EXT-X-MAP:") -> initialization = ResolutionParsers.attributes(line)["URI"]
                !line.startsWith('#') && index++ == target -> {
                    // fMP4 dimensions belong to the initialization segment. Encrypted
                    // maps and byte-range maps need additional IV/range semantics.
                    if (initialization != null && key == null) {
                        base.resolve(initialization)?.let { fetch(it, PREFIX_BYTES) }?.let { ResolutionParsers.mp4(it.bytes) }?.let { return it }
                    }
                    val segment = base.resolve(line) ?: return null
                    var data = fetch(segment, PREFIX_BYTES)?.bytes ?: return null
                    if (key != null) {
                        if (key["METHOD"] != "AES-128" || (key["KEYFORMAT"] != null && key["KEYFORMAT"] != "identity")) return null
                        val keyUrl = key["URI"]?.let(base::resolve) ?: return null
                        val keyBytes = fetch(keyUrl, 17)?.bytes ?: return null
                        if (keyBytes.size != 16) return null
                        data = decryptPrefix(data, keyBytes, key["IV"], sequence + target) ?: return null
                    }
                    return ResolutionParsers.media(data)
                }
            }
            return null
        }

        private suspend fun fetch(initial: HttpUrl, limit: Int, suffix: Boolean = false): Fetched? {
            var url = initial
            repeat(6) {
                currentCoroutineContext().ensureActive()
                if (++requests > 12 || bytesRead >= MAX_TOTAL_BYTES) return null
                if (original.isHttps && !url.isHttps) return null
                val maxBytes = minOf(limit, MAX_TOTAL_BYTES - bytesRead)
                val builder = Request.Builder().url(url).header("Range", if (suffix) "bytes=-$maxBytes" else "bytes=0-${maxBytes - 1}")
                    .header("Accept-Encoding", "identity")
                headers.forEach { (name, value) ->
                    if ((name.equals("Cookie", true) || name.equals("Authorization", true)) && !sameOrigin(original, url)) return@forEach
                    runCatching { builder.header(name, value) }
                }
                val fetched = try {
                    val domain = domainPermit(url.host)
                    try { domain.semaphore.withPermit { global.withPermit { attempted = true; read(builder.build(), maxBytes, suffix) } } }
                    finally { releaseDomain(url.host, domain) }
                }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: IOException) { return null }
                if (fetched.redirect != null) {
                    url = url.resolve(fetched.redirect) ?: return null
                } else {
                    bytesRead += fetched.bytes.size
                    return if (fetched.accepted) fetched else null
                }
            }
            return null
        }
    }

    companion object {
        private const val PREFIX_BYTES = 393216
        private const val MAX_TOTAL_BYTES = PREFIX_BYTES * 4
        private val allowedHeaders = setOf("cookie", "authorization", "referer", "origin", "user-agent", "accept", "accept-language")
        private val global = Semaphore(4)
        private data class DomainPermit(val semaphore: Semaphore = Semaphore(2), var users: Int = 0)
        private val domains = HashMap<String, DomainPermit>()
        private fun domainPermit(host: String): DomainPermit = synchronized(domains) { domains.getOrPut(host) { DomainPermit() }.also { it.users++ } }
        private fun releaseDomain(host: String, permit: DomainPermit) = synchronized(domains) { if (--permit.users == 0) domains.remove(host) }
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()

        internal fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = a.scheme == b.scheme && a.host == b.host && a.port == b.port
        internal fun cacheKey(media: DetectedMedia): String? {
            val url = media.url.toHttpUrlOrNull() ?: return null
            val path = url.newBuilder().query(null).fragment(null).build().toString()
            // Query tokens rotate frequently; a distinct request-header context must
            // still get a distinct entry. The digest is the only value persisted.
            val auth = media.headers.entries.sortedBy { it.key.lowercase() }.joinToString("\n") { "${it.key.lowercase()}:${it.value}" }
            val raw = "${if (media.isPrivate) "private:${media.tabId}" else "normal"}\n$path\n$auth"
            return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        private data class Fetched(val url: HttpUrl, val bytes: ByteArray, val contentType: String = "", val redirect: String? = null, val accepted: Boolean = true)
        private suspend fun read(request: Request, limit: Int, suffix: Boolean): Fetched = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (response.code in setOf(301, 302, 303, 307, 308)) return@use Fetched(request.url, byteArrayOf(), redirect = response.header("Location"), accepted = false)
                            if (!response.isSuccessful || suffix && response.code != 206) return@use Fetched(request.url, byteArrayOf(), accepted = false)
                            val body = response.body ?: return@use Fetched(request.url, byteArrayOf(), accepted = false)
                            val output = ByteArrayOutputStream(minOf(limit, 8192))
                            val buffer = ByteArray(minOf(limit, 8192))
                            val input = body.byteStream()
                            while (output.size() < limit && !call.isCanceled()) {
                                val read = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                            Fetched(request.url, output.toByteArray(), response.header("Content-Type").orEmpty())
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: IOException) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
            })
        }

        internal fun decryptPrefix(data: ByteArray, key: ByteArray, explicitIv: String?, sequence: Long): ByteArray? = runCatching {
            if (key.size != 16 || sequence < 0) return null
            val iv = ByteArray(16)
            if (explicitIv != null) {
                val hex = explicitIv.removePrefix("0x").removePrefix("0X")
                if (hex.isEmpty() || hex.length > 32 || !hex.all { it.digitToIntOrNull(16) != null }) return null
                val padded = hex.padStart(32, '0')
                repeat(16) { iv[it] = padded.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            } else repeat(8) { iv[15 - it] = (sequence ushr (8 * it)).toByte() }
            val aligned = data.size - data.size % 16
            if (aligned == 0) return null
            Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
                .doFinal(data, 0, aligned)
        }.getOrNull()
    }
}

/** One bounded file, hashed keys only. Private entries have a separate in-memory map. */
internal class ResolutionCache(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    internal data class Entry(val resolution: MediaResolution?, val timestamp: Long)
    private val monitor = Any()
    private val normal = LinkedHashMap<String, Entry>()
    private val private = LinkedHashMap<String, Entry>()
    private var loaded = false
    private val file get() = File(directory, "measurements-v1.bin")

    suspend fun lookup(key: String, isPrivate: Boolean): Entry? = withContext(Dispatchers.IO) { synchronized(monitor) {
        if (!isPrivate) load()
        val target = if (isPrivate) private else normal
        target[key]?.takeIf(::valid).also { if (it == null) target.remove(key) }
    } }

    suspend fun record(key: String, isPrivate: Boolean, resolution: MediaResolution?): MediaResolution? = withContext(Dispatchers.IO) { synchronized(monitor) {
        if (!isPrivate) load()
        val target = if (isPrivate) private else normal
        val old = target[key]?.takeIf(::valid)
        // A lower ABR track must not replace a known higher available resolution.
        if (old?.resolution != null && old.resolution.pixels >= (resolution?.pixels ?: 0)) return@synchronized old.resolution
        target[key] = Entry(resolution, now())
        target.entries.removeAll { !valid(it.value) }
        while (target.size > 1000) target.remove(target.minBy { it.value.timestamp }.key)
        if (!isPrivate) persist()
        resolution
    } }

    fun clearPrivate() = synchronized(monitor) { private.clear() }
    private fun valid(entry: Entry): Boolean { val age = now() - entry.timestamp; return age in 0 until if (entry.resolution != null) SUCCESS_TTL else FAILURE_TTL }
    private fun load() {
        if (loaded) return
        loaded = true
        runCatching { file.inputStream().buffered().use { stream -> java.io.DataInputStream(stream).use { input ->
            require(input.readInt() == 1)
            val count = input.readInt(); require(count in 0..1000)
            repeat(count) {
                val key = input.readUTF(); require(key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' })
                val width = input.readInt(); val height = input.readInt(); val timestamp = input.readLong()
                val entry = Entry(MediaResolution.valid(width, height), timestamp)
                if (valid(entry)) normal[key] = entry
            }
        } } }.onFailure { normal.clear() }
    }
    private fun persist() {
        runCatching {
            if (!directory.exists() && !directory.mkdirs()) return
            val temporary = File(directory, "measurements-v1.tmp")
            java.io.FileOutputStream(temporary).use { stream ->
                val output = java.io.DataOutputStream(stream.buffered())
                output.writeInt(1); output.writeInt(normal.size)
                normal.forEach { (key, entry) -> output.writeUTF(key); output.writeInt(entry.resolution?.width ?: 0); output.writeInt(entry.resolution?.height ?: 0); output.writeLong(entry.timestamp) }
                output.flush(); stream.fd.sync()
            }
            if (!temporary.renameTo(file)) { file.delete(); temporary.renameTo(file) }
        }
    }
    companion object {
        internal const val SUCCESS_TTL = 30L * 24 * 60 * 60 * 1000
        internal const val FAILURE_TTL = 6L * 60 * 60 * 1000
    }
}
