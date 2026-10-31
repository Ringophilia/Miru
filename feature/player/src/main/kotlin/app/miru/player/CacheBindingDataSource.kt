// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheKeyFactory

/** Observe the OUTER CacheDataSource request, in its original byte coordinates. */
internal class CacheBindingDataSource(
    private val actual: DataSource,
    private val keys: CacheKeyFactory,
    private val bindings: CachedResourceBindings,
) : DataSource {
    private var observation: CachedResourceBindings.Observation? = null
    private var bytesRead = 0L
    private var eof = false

    override fun addTransferListener(transferListener: TransferListener) = actual.addTransferListener(transferListener)
    override fun getUri(): Uri? = actual.uri
    override fun getResponseHeaders(): Map<String, List<String>> = actual.responseHeaders

    override fun open(dataSpec: DataSpec): Long {
        bytesRead = 0
        eof = false
        // DataSpec.position is the cache coordinate. uriPositionOffset belongs to
        // extractor/resource addressing and must NOT be added to cache positions.
        observation = if(dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET)
            bindings.begin(dataSpec.uri.toString(), dataSpec.position, dataSpec.length, keys.buildCacheKey(dataSpec))
            else null
        return try { actual.open(dataSpec).also { bindings.opened(observation, it) } }
        catch(error: Exception) { bindings.failed(observation); observation = null; throw error }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        actual.read(buffer, offset, length).also { count ->
            if(count == C.RESULT_END_OF_INPUT && !eof) {
                eof = true
                bindings.ended(observation, bytesRead)
            } else if(count > 0) {
                if(bytesRead <= Long.MAX_VALUE - count) bytesRead += count
                else { bindings.failed(observation); observation = null }
            }
        }
    } catch(error: Exception) { bindings.failed(observation); observation = null; throw error }

    override fun close() {
        // Closing a partially read, unbounded resource does not discover EOF.
        try { actual.close() }
        finally { observation = null; bytesRead = 0; eof = false }
    }
}
