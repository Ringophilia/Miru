// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.offline

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object OfflineHttp {
    private val allowed = setOf("cookie", "authorization", "referer", "origin", "user-agent", "accept", "accept-language")
    fun clean(headers: Map<String, String>) = headers.filter { (key, value) -> key.lowercase() in allowed && '\r' !in value && '\n' !in value }
    fun factory(url: String, headers: Map<String, String>): HttpDataSource.Factory {
        val original = requireNotNull(url.toHttpUrlOrNull())
        val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build().newBuilder()
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val current = request.url
                if(original.isHttps && !current.isHttps) throw IOException("HTTPS_DOWNGRADE")
                val same = original.scheme == current.scheme && original.host == current.host && original.port == current.port
                chain.proceed(if(same) request else request.newBuilder().removeHeader("Cookie").removeHeader("Authorization").build())
            }.build()
        return OkHttpDataSource.Factory(client).setDefaultRequestProperties(clean(headers))
    }
}

internal suspend fun prepareOffline(context: Context, source: OfflineSource): OfflineSelection = withContext(Dispatchers.Main.immediate) {
    if(source.drm) throw OfflineException("DRM_UNSUPPORTED")
    val uri = source.url.toHttpUrlOrNull() ?: throw OfflineException("UNSUPPORTED")
    // DownloadHelper delegates to DefaultMediaSourceFactory, whose content-type inference
    // expects Media3's canonical MIME. The Apple HLS alias must not select a progressive source.
    val mime = offlineMimeType(uri.encodedPath, source.mimeType) ?: throw OfflineException("DIRECT_FILE")
    val helper = DownloadHelper.Factory().setDataSourceFactory(OfflineHttp.factory(source.url, source.headers))
        .setRenderersFactory(DefaultRenderersFactory(context)).create(MediaItem.Builder().setUri(source.url).setMimeType(mime).build())
    try {
        val tracksAvailable = withTimeout(25_000) { suspendCancellableCoroutine { continuation ->
            helper.prepare(object : DownloadHelper.Callback {
                override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
                    if(continuation.isActive) continuation.resume(tracksInfoAvailable)
                }
                override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                    val types = generateSequence<Throwable>(e) { it.cause }.take(8).map { it.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_.$]"), "") }.joinToString("/").take(240)
                    val live = generateSequence<Throwable>(e) { it.cause }.take(8).any { it is DownloadHelper.LiveContentUnsupportedException }
                    if(continuation.isActive) continuation.resumeWithException(OfflineException(if(live) "LIVE_UNSUPPORTED" else "PREPARE_FAILED", types))
                }
            })
        } }
        if(!tracksAvailable || helper.periodCount <= 0) throw OfflineException("NO_TRACKS")
        for(period in 0 until helper.periodCount) for(group in helper.getTracks(period).groups) for(index in 0 until group.length) {
            if(group.getTrackFormat(index).drmInitData != null) throw OfflineException("DRM_UNSUPPORTED")
        }
        val formats = helper.getTracks(0).groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
            (0 until group.length).filter(group::isTrackSupported).map(group::getTrackFormat)
        }.filter { it.width > 0 && it.height > 0 }.distinctBy { Triple(it.width, it.height, it.bitrate) }
            .sortedWith(compareByDescending<androidx.media3.common.Format> { it.width.toLong() * it.height }.thenByDescending { it.bitrate })
        if(formats.isEmpty()) throw OfflineException("NO_VIDEO")
        val qualities = formats.mapIndexed { index, format ->
            OfflineQuality("quality-$index", "${qualityLabel(format.width, format.height)} · ${format.width}×${format.height}", format.width, format.height, format.bitrate)
        }
        val requests = linkedMapOf<String, DownloadRequest>()
        for(quality in qualities) {
            for(period in 0 until helper.periodCount) {
                helper.clearTrackSelections(period)
                val groups = helper.getTracks(period).groups
                val video = groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
                    (0 until group.length).filter(group::isTrackSupported).map { index -> group to index }
                }.minByOrNull { (group, index) ->
                    val format = group.getTrackFormat(index)
                    kotlin.math.abs(format.width.toLong() * format.height - quality.width.toLong() * quality.height) * 1_000_000L + kotlin.math.abs(format.bitrate.toLong() - quality.bitrate)
                } ?: throw OfflineException("NO_VIDEO")
                fun select(type: Int, group: androidx.media3.common.Tracks.Group, indices: List<Int>) {
                    if(indices.isEmpty()) return
                    val parameters = TrackSelectionParameters.Builder(context)
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, type != C.TRACK_TYPE_VIDEO)
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, type != C.TRACK_TYPE_AUDIO)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, type != C.TRACK_TYPE_TEXT)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, indices)).build()
                    helper.addTrackSelection(period, parameters)
                }
                select(C.TRACK_TYPE_VIDEO, video.first, listOf(video.second))
                // Keep every associated supported language available offline; no network fallback
                // is needed when switching audio or subtitles in the native player later.
                for(group in groups.filter { it.type == C.TRACK_TYPE_AUDIO || it.type == C.TRACK_TYPE_TEXT }) {
                    select(group.type, group, (0 until group.length).filter(group::isTrackSupported))
                }
            }
            val request = helper.getDownloadRequest("prepared", null)
            if(request.streamKeys.isEmpty()) throw OfflineException("NO_TRACKS")
            requests[quality.id] = request
        }
        OfflineSelection(source, qualities, requests)
    } finally { withContext(NonCancellable + Dispatchers.Main.immediate) { helper.release() } }
}
