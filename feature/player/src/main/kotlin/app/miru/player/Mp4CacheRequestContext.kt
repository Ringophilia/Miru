// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/** Progressive source open/read are serialized on its loader thread, including retries. */
internal class Mp4CacheRequestContext(private val key: (DataSpec) -> String) {
    private val current = ThreadLocal<Mp4CacheRequest?>()
    fun request(): Mp4CacheRequest? = current.get()
    fun factory(actual: DataSource.Factory) = DataSource.Factory {
        val data = actual.createDataSource()
        object : DataSource by data {
            private var own: Mp4CacheRequest? = null
            override fun open(spec: DataSpec): Long {
                own = if(spec.httpMethod == DataSpec.HTTP_METHOD_GET)
                    Mp4CacheRequest(spec.uri.toString(), spec.position, spec.length, key(spec)) else null
                current.set(own)
                return try { data.open(spec) } catch(failure: Throwable) { current.remove(); own = null; throw failure }
            }
            override fun close() {
                try { data.close() } finally {
                    if(current.get() === own) current.remove()
                    own = null
                }
            }
        }
    }
}
