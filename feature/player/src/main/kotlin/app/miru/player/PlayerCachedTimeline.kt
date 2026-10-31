// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.dash.manifest.DashManifest
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

internal enum class CachedSourceKind { HLS, DASH }

/** Source-owned selection/index publication. Player access is on its application thread; IO is not. */
internal class PlayerCachedTimeline(
    private val player: ExoPlayer,
    private val session: PlaybackSourceSession,
    cache: Cache,
    parent: CoroutineScope,
    private val kind: CachedSourceKind = CachedSourceKind.HLS,
    private val publish: (List<CachedTimeRange>) -> Unit,
) : Closeable {
    private val catalog = if(kind == CachedSourceKind.HLS) requireNotNull(session.hlsCacheCatalog) else null
    private val dashCatalog = if(kind == CachedSourceKind.DASH) requireNotNull(session.dashCacheCatalog) else null
    private val revision = catalog?.revision ?: requireNotNull(dashCatalog).revision
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val tracker = CachedTimelineTracker(cache, scope)
    private val generation = AtomicLong()
    private val retired = AtomicBoolean()
    private val closed get() = retired.get()
    private val applicationHandler = Handler(player.applicationLooper)
    private var periodUid: Any? = null
    private val downstream = LinkedHashMap<Int, Format>()
    private val pendingDownstream = LinkedHashMap<Any, MutableMap<Int, Format>>()
    private var input: Input? = null
    private var published: Input? = null
    private var mapping: Job? = null

    private data class Input(val periodUid: Any, val positionInWindowUs: Long, val durationUs: Long,
        val tracks: Tracks, val parameters: TrackSelectionParameters, val downstream: Map<Int, Format>,
        val catalogRevision: Long, val resourceRevision: Long, val manifest: Any?, val localPeriodIndex: Int)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if(events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_TRACKS_CHANGED,
                Player.EVENT_TRACK_SELECTION_PARAMETERS_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAYBACK_STATE_CHANGED)) refresh()
        }
    }
    private val analytics = object : AnalyticsListener {
        override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
            if(closed) return
            val uid = eventTime.mediaPeriodId?.periodUid ?: return
            if(player.currentTimeline.getIndexOfPeriod(uid) < 0) return
            val format = mediaLoadData.trackFormat ?: return
            val types = if(kind == CachedSourceKind.HLS) requireNotNull(catalog).snapshot().identities.values
                .filter { it.format == format }.map { it.kind }.distinct()
                else listOf(mediaLoadData.trackType)
            val pending = pendingDownstream.getOrPut(uid) { linkedMapOf() }
            types.forEach { pending[it] = format }
            while(pendingDownstream.size > 4) pendingDownstream.remove(pendingDownstream.keys.first())
            if(currentPeriod()?.uid == uid) { usePeriod(uid); downstream.putAll(pending); pendingDownstream.remove(uid) }
            refresh()
        }
    }

    init {
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        scope.launch { revision.collect { refresh() } }
        scope.launch { session.cacheBindingRevision.collect { refresh() } }
        scope.launch { tracker.ranges.collect { ranges ->
            val current = published
            if(!closed) publish(if(current != null && valid(current)) ranges else emptyList())
        } }
        // Withdraw UI before potentially blocked cache-listener cleanup finishes.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { retirePublication() }
        }
        job.invokeOnCompletion { retirePublication() }
    }

    private fun currentPeriod(): Timeline.Period? {
        val timeline = player.currentTimeline
        if(timeline.isEmpty || player.currentPeriodIndex !in 0 until timeline.periodCount) return null
        return timeline.getPeriod(player.currentPeriodIndex, Timeline.Period(), true)
    }

    private fun usePeriod(uid: Any) {
        if(periodUid != uid) { periodUid = uid; downstream.clear(); pendingDownstream.remove(uid)?.let(downstream::putAll) }
    }

    /** Safe for the controller's application-thread ticker as well as player events. */
    fun refresh() {
        if(closed) return
        val period = currentPeriod()
        if(period?.uid == null) { invalidate(); input = null; return }
        usePeriod(requireNotNull(period.uid))
        val timeline = player.currentTimeline
        val window = timeline.getWindow(period.windowIndex, Timeline.Window())
        val snapshot = catalog?.snapshot()
        val dashSnapshot = dashCatalog?.snapshot()
        val next = Input(requireNotNull(period.uid), period.positionInWindowUs, window.durationUs,
            player.currentTracks, player.trackSelectionParameters, downstream.toMap(), snapshot?.revision ?: requireNotNull(dashSnapshot).revision, session.cacheBindingRevision.value,
            window.manifest, player.currentPeriodIndex - window.firstPeriodIndex)
        if(input == next) return
        input = next
        invalidate()
        val hlsSelected = if(snapshot == null) null else {
            HlsCachedSelection.select(snapshot, next.tracks, next.parameters, next.downstream) ?: return
        }
        val dashSelected = if(dashSnapshot == null) null else {
            DashCachedSelection.select(dashSnapshot, next.manifest as? DashManifest ?: return,
                next.localPeriodIndex, next.tracks, next.parameters, next.downstream) ?: return
        }
        val epoch = generation.get()
        mapping = scope.launch(Dispatchers.IO) {
            try {
                val resources = session.cacheResourceSnapshot()
                if(resources.revision != next.resourceRevision) return@launch
                withContext(Dispatchers.Main.immediate) {
                    if(!closed && generation.get() == epoch && valid(next)) {
                        published = next
                        tracker.replaceIndexed(next.durationUs.takeIf { it >= 0 },
                            { !closed && generation.get() == epoch && valid(next) }) { workerContext, lengths ->
                            val lookup = session.cacheResourceResolver(resources, lengths)
                            if(hlsSelected != null) hlsSelected.mapIndexed { index, selectedPlaylist ->
                                workerContext.ensureActive()
                                HlsCachedTimelineIndex.track("selected-$index", selectedPlaylist.playlist, requireNotNull(snapshot).initialStartTimeUs,
                                    next.positionInWindowUs, next.durationUs.takeIf { it >= 0 }, lookup::rangeFor, context = workerContext)
                            } else requireNotNull(dashSelected).mapIndexed { index, selectedRepresentation ->
                                workerContext.ensureActive()
                                DashCachedTimelineIndex.track("selected-$index", selectedRepresentation, requireNotNull(dashSnapshot).independent(selectedRepresentation.representation),
                                    next.positionInWindowUs, next.durationUs.takeIf { it >= 0 }, resources, workerContext, lookup)
                            }
                        }
                    }
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { /* Unknown/failed metadata cannot establish coverage. */ }
        }
    }

    private fun valid(current: Input) = current.catalogRevision == revision.value &&
        current.resourceRevision == session.cacheBindingRevision.value

    private fun invalidate() {
        generation.incrementAndGet()
        mapping?.cancel(); mapping = null
        published = null
        tracker.replace(emptyList())
        publish(emptyList())
    }

    override fun close() {
        retirePublication()
        job.cancel()
        downstream.clear(); pendingDownstream.clear(); input = null; published = null
    }

    private fun retirePublication() {
        if(!retired.compareAndSet(false, true)) return
        generation.incrementAndGet()
        removeListeners()
        tracker.close()
        publish(emptyList())
    }

    private fun removeListeners() {
        val remove = Runnable { player.removeListener(listener); player.removeAnalyticsListener(analytics) }
        if(Looper.myLooper() == applicationHandler.looper) remove.run() else applicationHandler.post(remove)
    }
}
