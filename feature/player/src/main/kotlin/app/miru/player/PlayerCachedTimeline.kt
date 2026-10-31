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
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Source-owned HLS selection/index publication. Player access is on its application thread; IO is not. */
internal class PlayerCachedTimeline(
    private val player: ExoPlayer,
    private val session: PlaybackSourceSession,
    cache: Cache,
    parent: CoroutineScope,
    private val publish: (List<CachedTimeRange>) -> Unit,
) : Closeable {
    private val catalog = requireNotNull(session.hlsCacheCatalog)
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val tracker = CachedTimelineTracker(cache, scope)
    private val generation = AtomicLong()
    private val retired = AtomicBoolean()
    private val closed get() = retired.get()
    private val applicationHandler = Handler(player.applicationLooper)
    private var periodUid: Any? = null
    private val downstream = LinkedHashMap<Int, Format>()
    private var input: Input? = null
    private var published: Input? = null
    private var mapping: Job? = null

    private data class Input(val periodUid: Any, val positionInWindowUs: Long, val durationUs: Long,
        val tracks: Tracks, val parameters: TrackSelectionParameters, val downstream: Map<Int, Format>,
        val catalogRevision: Long, val resourceRevision: Long)

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
            val uid = currentPeriod()?.uid ?: return
            if(eventTime.mediaPeriodId?.periodUid != uid) return
            usePeriod(uid)
            val format = mediaLoadData.trackFormat ?: return
            val kinds = catalog.snapshot().identities.values.filter { it.format == format }.map { it.kind }.distinct()
            kinds.forEach { downstream[it] = format }
            refresh()
        }
    }

    init {
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        scope.launch { catalog.revision.collect { refresh() } }
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
        if(periodUid != uid) { periodUid = uid; downstream.clear() }
    }

    /** Safe for the controller's application-thread ticker as well as player events. */
    fun refresh() {
        if(closed) return
        val period = currentPeriod()
        if(period?.uid == null) { invalidate(); input = null; return }
        usePeriod(requireNotNull(period.uid))
        val timeline = player.currentTimeline
        val window = timeline.getWindow(period.windowIndex, Timeline.Window())
        val snapshot = catalog.snapshot()
        val next = Input(requireNotNull(period.uid), period.positionInWindowUs, window.durationUs,
            player.currentTracks, player.trackSelectionParameters, downstream.toMap(), snapshot.revision, session.cacheBindingRevision.value)
        if(input == next) return
        input = next
        invalidate()
        if(snapshot.playlists.isEmpty()) return
        val selected = HlsCachedSelection.select(snapshot, next.tracks, next.parameters, next.downstream) ?: return
        val epoch = generation.get()
        mapping = scope.launch(Dispatchers.IO) {
            try {
                val resources = session.cacheResourceSnapshot()
                if(resources.revision != next.resourceRevision) return@launch
                val tracks = selected.mapIndexed { index, selectedPlaylist ->
                    ensureActive()
                    HlsCachedTimelineIndex.track("selected-$index", selectedPlaylist.playlist, snapshot.initialStartTimeUs,
                        next.positionInWindowUs, next.durationUs.takeIf { it >= 0 }, resources::rangeFor)
                }
                withContext(Dispatchers.Main.immediate) {
                    if(!closed && generation.get() == epoch && valid(next)) {
                        published = next
                        tracker.replace(tracks, next.durationUs.takeIf { it >= 0 }) {
                            !closed && generation.get() == epoch && valid(next)
                        }
                    }
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { /* Unknown/failed metadata cannot establish coverage. */ }
        }
    }

    private fun valid(current: Input) = current.catalogRevision == catalog.revision.value &&
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
        downstream.clear(); input = null; published = null
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
