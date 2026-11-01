// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.datasource.cache.Cache
import java.security.MessageDigest
import java.util.Collections
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** Bytes actually delivered before the native metadata outputs became complete. */
internal class Mp4MetadataProof(reads: Collection<Read>) {
    class Read(val request: Mp4CacheRequest, val length: Int, val sha256: String) {
        val range = CachedResourceRange(request.key, request.position, length.toLong())
    }
    val reads = Collections.unmodifiableList(ArrayList(reads))

    /** Verify every original parent independently; never scan other keys to fill a hole. */
    fun verifiedBytes(cache: Cache, context: CoroutineContext? = null): Mp4MetadataBytes? {
        val blocks = ArrayList<CachedMp4Block>()
        var total = 0L
        for(read in reads) {
            context?.ensureActive()
            total += read.length
            if(total > CacheOnlyMp4Bytes.MAX_METADATA_BYTES) return null
            val block = CacheOnlyMp4Bytes(cache, read.request, context).read(read.range.position, read.length) ?: return null
            if(digest(block.bytes) != read.sha256) return null
            blocks += block
        }
        if(blocks.isEmpty()) return null
        // Retry/peek footprints may overlap across parents. Contradictory bytes
        // cannot establish a single metadata image even if each hash is correct.
        val ordered = blocks.sortedBy { it.dependency.position }
        for(i in ordered.indices) for(j in i + 1 until ordered.size) {
            val a = ordered[i]; val b = ordered[j]
            if(b.dependency.position >= a.dependency.endExclusive) break
            val end = minOf(a.dependency.endExclusive, b.dependency.endExclusive)
            var p = b.dependency.position
            while(p < end) {
                context?.ensureActive()
                if(a.bytes[(p - a.dependency.position).toInt()] != b.bytes[(p - b.dependency.position).toInt()]) return null
                p++
            }
        }
        return Mp4MetadataBytes { position, length ->
            context?.ensureActive()
            if(length <= 0 || position < 0 || position > Long.MAX_VALUE - length) null else {
                // A returned block keeps its actual parent key. A structural
                // read spanning parent boundaries is split by the caller later;
                // do not synthesize a key for a combined virtual resource.
                val block = ordered.firstOrNull { it.dependency.position <= position && it.dependency.endExclusive >= position + length }
                block?.let { CachedMp4Block(it.bytes.copyOfRange((position - it.dependency.position).toInt(), (position - it.dependency.position + length).toInt()),
                    CachedResourceRange(it.dependency.key, position, length.toLong())) }
            }
        }
    }
    companion object {
        fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** One actual DataSource instance/period; no media buffering, only bounded digests. */
internal class Mp4MetadataTrace(private val maxBytes: Int = CacheOnlyMp4Bytes.MAX_METADATA_BYTES) {
    private class Reading(val request: Mp4CacheRequest) {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0
    }
    private val reads = ArrayList<Reading>()
    private var current: Reading? = null
    private var active = true
    private var valid = true
    private var total = 0L
    var request: Mp4CacheRequest? = null; private set
    fun opened(original: Mp4CacheRequest?) {
        request = original
        if(active && valid && original != null) {
            if(reads.size >= 256) { disable(); return }
            current = Reading(original).also(reads::add)
        } else current = null
    }
    fun record(buffer: ByteArray, offset: Int, count: Int) {
        val read = current ?: return
        if(!active || !valid || count <= 0) return
        if(total + count > maxBytes || read.request.position > Long.MAX_VALUE - read.bytes - count) { disable(); return }
        read.digest.update(buffer, offset, count); read.bytes += count; total += count
    }
    fun freeze(): Mp4MetadataProof? {
        if(!active || !valid) return null
        active = false
        val proof = reads.filter { it.bytes > 0 }.map { Mp4MetadataProof.Read(it.request, it.bytes, Mp4MetadataProof.hex(it.digest.digest())) }
        reads.clear(); current = null
        return proof.takeIf { it.isNotEmpty() }?.let(::Mp4MetadataProof)
    }
    fun restart() {
        val original = request
        reads.clear(); current = null; total = 0; active = true; valid = true
        opened(original)
    }
    fun disable() { active = false; valid = false; reads.clear(); current = null }
    fun closed() { request = null; current = null }
}
