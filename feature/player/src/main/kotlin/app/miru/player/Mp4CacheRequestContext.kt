// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/** Progressive source open/read are serialized on its loader thread, including retries. */
internal class Mp4CacheRequestContext(private val key: (DataSpec) -> String) {
    private val current = ThreadLocal<Mp4MetadataTrace?>()
    fun request(): Mp4CacheRequest? = current.get()?.request
    fun freezeMetadata(): Mp4MetadataProof? = current.get()?.freeze()
    fun restartMetadata() { current.get()?.restart() }
    fun discardMetadata() { current.get()?.disable() }
    fun factory(actual: DataSource.Factory) = DataSource.Factory {
        val data = actual.createDataSource()
        val trace = Mp4MetadataTrace()
        object : DataSource by data {
            private var own: Mp4CacheRequest? = null
            override fun open(spec: DataSpec): Long {
                own = if(spec.httpMethod == DataSpec.HTTP_METHOD_GET)
                    Mp4CacheRequest(spec.uri.toString(), spec.position, spec.length, key(spec)) else null
                trace.opened(own)
                current.set(trace)
                return try { data.open(spec) } catch(failure: Throwable) { current.remove(); trace.closed(); own = null; throw failure }
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = data.read(buffer, offset, length).also {
                if(it > 0) trace.record(buffer, offset, it)
            }
            override fun close() {
                try { data.close() } finally {
                    if(current.get() === trace) current.remove()
                    trace.closed()
                    own = null
                }
            }
        }
    }
}
