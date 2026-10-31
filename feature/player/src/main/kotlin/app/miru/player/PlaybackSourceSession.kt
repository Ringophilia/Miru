// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.Closeable

/**
 * Immutable current-header/source revision, shared by playback and preview policy.
 * The owner decides whether disk is allowed: private playback passes null. Offline
 * always ignores disk and never creates an HTTP upstream, including for manifests.
 */
class PlaybackSourceSession(
    context: Context,
    val revision: Long,
    val mediaItem: MediaItem,
    headers: Map<String, String>,
    cache: Cache? = null,
    private val offlineDataSourceFactory: DataSource.Factory? = null,
    private val forceProgressive: Boolean = false,
    httpClient: OkHttpClient = OkHttpClient.Builder().build(),
) {
    private val context = context.applicationContext
    private val headers = headers.toMap()
    private val cache = if(offlineDataSourceFactory == null) cache else null
    private val configuration = requireNotNull(mediaItem.localConfiguration)
    private val url = configuration.uri.toString()
    // The v2 namespace prevents reuse of old comma/equal-joined partitions. Those
    // entries remain on disk and expire through the normal streaming-cache LRU.
    private val partition = "miru-stream-v2:" + PlaybackPolicy.digest(this.headers.entries.sortedWith(compareBy({ it.key.lowercase() }, { it.key }))
        .joinToString(separator = "") {
            val name = it.key.lowercase()
            "${name.length}:$name${it.value.length}:${it.value}"
        })
    // Timeline mapping must use this actual session's header partition, including
    // when preview-only authentication has already moved to a newer partition.
    // Keep one key factory for both committed cache spans and playback requests.
    val cacheKeyFactory = CacheKeyFactory(::cacheKey)

    fun cacheKey(spec: DataSpec): String = "$partition:${PlaybackPolicy.normalizedCacheUrl(
        spec.uri.toString(), spec.position > 0 || spec.length != C.LENGTH_UNSET.toLong())}"

    private val client by lazy {
        val origin = url.toHttpUrlOrNull()?.let { Triple(it.scheme, it.host, it.port) }
        httpClient.newBuilder().addNetworkInterceptor { chain ->
            val current = chain.request()
            val sameOrigin = Triple(current.url.scheme, current.url.host.lowercase(), current.url.port) == origin
            val safe = if(sameOrigin) current else current.newBuilder()
                .removeHeader("Cookie").removeHeader("Authorization").build()
            chain.proceed(safe)
        }.build()
    }

    fun createMediaSource(): MediaSource = factory(null).createMediaSource(mediaItem)

    fun previewLease(): PreviewSourceLease {
        // Offline still owns/interrupts its preview file/cache sources, but never
        // constructs an HTTP upstream (or even a preview HTTP client).
        val transport = PreviewTransport(if(offlineDataSourceFactory == null) client else null)
        val factory = factory(transport).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
        return PreviewSourceLease(revision, mediaItem, factory, transport)
    }

    private fun factory(preview: PreviewTransport?): MediaSource.Factory {
        val upstream = offlineDataSourceFactory ?: DefaultDataSource.Factory(context,
            OkHttpDataSource.Factory(preview ?: client as Call.Factory).setDefaultRequestProperties(headers))
        val cached = cache?.let { disk -> CacheDataSource.Factory().setCache(disk)
            .setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheKeyFactory(cacheKeyFactory)
        } ?: upstream
        val manifest = preview?.track(upstream) ?: upstream
        val media = preview?.track(cached) ?: cached
        val type = configuration.mimeType.orEmpty().lowercase()
        val path = configuration.uri.path.orEmpty().lowercase()
        val adaptive = path.endsWith(".m3u8") || path.endsWith(".mpd") || type.contains("mpegurl") || type.contains("dash+xml")
        return when {
            !forceProgressive && (path.endsWith(".m3u8") || type.contains("mpegurl")) ->
                HlsMediaSource.Factory { dataType -> (if(dataType == C.DATA_TYPE_MANIFEST) manifest else media).createDataSource() }
            !forceProgressive && (path.endsWith(".mpd") || type.contains("dash+xml")) ->
                DashMediaSource.Factory(DefaultDashChunkSource.Factory(media), manifest)
            else -> ProgressiveMediaSource.Factory(if(adaptive) manifest else media)
        }
    }
}

/** Owns only preview work; cancellation never reaches the main playback source. */
class PreviewSourceLease internal constructor(
    val revision: Long,
    val mediaItem: MediaItem,
    val mediaSourceFactory: MediaSource.Factory,
    private val transport: PreviewTransport,
) : Closeable {
    fun cancel() { transport.cancel() }
    override fun close() { transport.close() }
}
