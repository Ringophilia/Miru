// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.source.MediaSourceEventListener.EventDispatcher
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.Closeable
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Actual final tracker snapshots. A URL's presence does NOT mean it is selected. */
internal class HlsCacheCatalog : Closeable {
    class Snapshot internal constructor(
        val revision: Long,
        val initialStartTimeUs: Long,
        playlists: Map<Uri, HlsMediaPlaylist>,
    ) {
        val playlists: Map<Uri, HlsMediaPlaylist> = Collections.unmodifiableMap(LinkedHashMap(playlists))
    }
    private val gate = Any()
    private var owner: Any? = null
    private var closed = false
    private var initialStartTimeUs = C.TIME_UNSET
    private val playlists = LinkedHashMap<Uri, HlsMediaPlaylist>()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    val factory = HlsPlaylistTracker.Factory { sources, policy, parser, cmcd, executor ->
        ObservedHlsPlaylistTracker(DefaultHlsPlaylistTracker.FACTORY.createTracker(sources, policy, parser, cmcd, executor), this)
    }

    fun snapshot(): Snapshot = synchronized(gate) { Snapshot(mutableRevision.value, initialStartTimeUs, playlists) }

    internal fun begin(): Any? = synchronized(gate) {
        if(closed) return null
        Any().also { owner = it; playlists.clear(); initialStartTimeUs = C.TIME_UNSET; changed() }
    }

    internal fun observe(token: Any?, url: Uri, playlist: HlsMediaPlaylist, initialUs: Long): Unit = synchronized(gate) {
        if(closed || token == null || owner !== token) return
        if(playlists[url] === playlist && initialStartTimeUs == initialUs) return
        playlists[url] = playlist
        // This only bounds metadata; losing a snapshot cannot create coverage.
        if(playlists.size > 256) playlists.remove(playlists.keys.first())
        initialStartTimeUs = initialUs
        changed()
    }

    internal fun timebase(token: Any?, initialUs: Long) = synchronized(gate) {
        if(!closed && token != null && owner === token && initialStartTimeUs != initialUs) {
            initialStartTimeUs = initialUs; changed()
        }
    }

    internal fun urls(token: Any?): List<Uri> = synchronized(gate) {
        if(closed || token == null || owner !== token) emptyList() else playlists.keys.toList()
    }

    internal fun forget(token: Any?, url: Uri) = synchronized(gate) {
        if(!closed && token != null && owner === token && playlists.remove(url) != null) changed()
    }

    internal fun stop(token: Any?) = synchronized(gate) {
        if(token != null && owner === token) { owner = null; playlists.clear(); initialStartTimeUs = C.TIME_UNSET; changed() }
    }

    private fun changed() { mutableRevision.value++ }
    override fun close() = synchronized(gate) {
        if(!closed) { closed = true; owner = null; playlists.clear(); initialStartTimeUs = C.TIME_UNSET; changed() }
    }
}

/**
 * All other 1.11.1 tracker methods delegate unchanged, including its default
 * deactivate method. Observe the final snapshot, not a parallel playlist parser.
 * All tracker interaction stays on the original playback thread; no cache IO.
 */
internal class ObservedHlsPlaylistTracker(
    private val actual: HlsPlaylistTracker,
    private val catalog: HlsCacheCatalog,
) : HlsPlaylistTracker by actual {
    private var token: Any? = null
    private var listener: HlsPlaylistTracker.PlaylistEventListener? = null

    override fun start(uri: Uri, events: EventDispatcher, primary: HlsPlaylistTracker.PrimaryPlaylistListener, bandwidth: BandwidthMeter) {
        val generation = catalog.begin()
        token = generation
        val observer = object : HlsPlaylistTracker.PlaylistEventListener {
            override fun onPlaylistChanged() {
                // Refresh only already-observed URLs, using a side-effect-free
                // non-playback lookup. Never promote the primary on our own.
                catalog.urls(generation).forEach { url ->
                    val snapshot = runCatching { actual.getPlaylistSnapshot(url, false) }.getOrNull()
                    if(snapshot == null) catalog.forget(generation, url)
                    else catalog.observe(generation, url, snapshot, actual.initialStartTimeUs)
                }
            }
            override fun onPlaylistError(url: Uri, info: LoadErrorHandlingPolicy.LoadErrorInfo, forceRetry: Boolean) = true
        }
        listener = observer
        try {
            actual.addListener(observer)
            actual.start(uri, events, { playlist ->
                primary.onPrimaryPlaylistRefreshed(playlist)
                // baseUri may be a redirected URL unknown to the tracker. Only
                // real getPlaylistSnapshot calls establish canonical lookup URLs.
                catalog.timebase(generation, actual.initialStartTimeUs)
            }, bandwidth)
        } catch(error: Exception) {
            runCatching { actual.removeListener(observer) }; listener = null; token = null; catalog.stop(generation)
            throw error
        }
    }

    override fun getPlaylistSnapshot(url: Uri, isForPlayback: Boolean): HlsMediaPlaylist? {
        val snapshot = actual.getPlaylistSnapshot(url, isForPlayback)
        if(snapshot == null) catalog.forget(token, url)
        else catalog.observe(token, url, snapshot, actual.initialStartTimeUs)
        return snapshot
    }

    override fun stop() {
        val previous = token
        token = null
        try { listener?.let { runCatching { actual.removeListener(it) } }; listener = null; actual.stop() }
        finally { catalog.stop(previous) }
    }

    override fun deactivatePlaylistForPlayback(url: Uri) = actual.deactivatePlaylistForPlayback(url)
}
