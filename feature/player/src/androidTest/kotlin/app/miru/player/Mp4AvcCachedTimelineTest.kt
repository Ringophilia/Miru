// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual native owner + precise GOP/cache dependency calculation. UI/decode is tested separately. */
@RunWith(AndroidJUnit4::class)
class Mp4AvcCachedTimelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun aOneByteHoleRevokesAllLaterSamplesInThatGopButNotTheNextIdr() = prepared { f ->
        val sample = f.samples.getJSONObject(92)
        val missing = sample.getLong("offset") + sample.getInt("size") / 2
        f.reseed(listOf(0L to missing, missing + 1 to f.body.size.toLong()))
        assertEquals(listOf(CachedTimeRange(0, 9_200_000), CachedTimeRange(10_000_000, 12_000_000)), f.ranges())
        f.put(missing, byteArrayOf(f.body[missing.toInt()]))
        assertEquals(listOf(CachedTimeRange(0, 12_000_000)), f.ranges())
    }

    @Test fun anStssFlagWithoutAnActualIdrDoesNotEstablishRandomAccess() = prepared { f ->
        val changed = f.body.copyOf()
        val sample = f.samples.getJSONObject(90)
        var cursor = sample.getInt("offset")
        val end = cursor + sample.getInt("size")
        var replaced = false
        while(cursor < end) {
            val length = (0..3).fold(0) { a, i -> a shl 8 or (changed[cursor + i].toInt() and 255) }
            cursor += 4
            if(changed[cursor].toInt() and 31 == 5) { changed[cursor] = ((changed[cursor].toInt() and 0xe0) or 1).toByte(); replaced = true }
            cursor += length
        }
        assertTrue(replaced)
        f.reseed(listOf(0L to changed.size.toLong()), changed)
        assertEquals(listOf(CachedTimeRange(0, 9_000_000), CachedTimeRange(10_000_000, 12_000_000)), f.ranges())
    }

    @Test fun equalFormatValuesFromAnotherMetadataOwnerCannotBindToThisSource() = prepared { f ->
        assertEquals(listOf(CachedTimeRange(0, 12_000_000)), f.ranges())
        val native = f.tracks.groups.single().getTrackFormat(0)
        val data = requireNotNull(native.metadata)
        val equalCopy = Metadata(*Array(data.length()) { data[it] })
        assertEquals(data, equalCopy); assertNotSame(data, equalCopy)
        val foreign = TrackGroup(f.tracks.groups.single().mediaTrackGroup.id, native.buildUpon().setMetadata(equalCopy).build())
        val tracks = Tracks(listOf(Tracks.Group(foreign, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))))
        assertNull(Mp4CachedSelection.select(f.session.mp4CacheCatalog!!.snapshot(), tracks, f.parameters, emptyMap()))
    }

    private inner class Fixture(val root: File, val cache: SimpleCache, val body: ByteArray, val session: PlaybackSourceSession,
        val key: String, val tracks: Tracks, val parameters: TrackSelectionParameters, val selected: Mp4CachedSelection.Selected) {
        val samples = JSONObject(instrumentation.context.assets.open("mp4-cache/colors.samples.json").bufferedReader().use { it.readText() })
            .getJSONArray("tracks").getJSONObject(0).getJSONArray("samples")
        fun ranges(): List<CachedTimeRange> {
            val indexed = Mp4AvcCachedTimelineIndex.tracks(selected, session, cache, 0, 12_000_000,
                { ContentMetadata.getContentLength(cache.getContentMetadata(it)) }, currentCoroutineContextForTest())
            val spans = cache.keys.associateWith { key -> cache.getCachedSpans(key).map { CachedResourceRange(key, it.position, it.length) } }
            return CachedTimelineCoverage.compute(indexed, spans, 12_000_000)
        }
        fun reseed(ranges: List<Pair<Long, Long>>, bytes: ByteArray = body) {
            cache.removeResource(key)
            for((from, to) in ranges) put(from, bytes.copyOfRange(from.toInt(), to.toInt()))
            cache.applyContentMetadataMutations(key, ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, body.size.toLong()) })
        }
        fun put(position: Long, bytes: ByteArray) {
            val hole = requireNotNull(cache.startReadWriteNonBlocking(key, position, bytes.size.toLong())); check(hole.isHoleSpan)
            try { val file = cache.startFile(key, position, bytes.size.toLong()); file.writeBytes(bytes); cache.commitFile(file, bytes.size.toLong()) }
            finally { cache.releaseHoleSpan(hole) }
        }
    }
    private fun currentCoroutineContextForTest() = kotlin.coroutines.EmptyCoroutineContext
    private fun prepared(verify: (Fixture) -> Unit) = runBlocking { withContext(Dispatchers.IO) {
        val root = File(context.cacheDir, "mp4-avc-index-${UUID.randomUUID()}").apply { mkdirs() }
        val body = instrumentation.context.assets.open("colors.mp4").use { it.readBytes() }
        val movie = File(root, "owned.mp4").apply { writeBytes(body) }
        @Suppress("DEPRECATION") val cache = SimpleCache(File(root, "cache"), NoOpCacheEvictor())
        val item = MediaItem.fromUri(movie.toURI().toString())
        val session = PlaybackSourceSession(context, 1, item, emptyMap(), cache)
        val spec = DataSpec.Builder().setUri(item.localConfiguration!!.uri).build(); val key = session.cacheKey(spec)
        val data = CacheDataSource.Factory().setCache(cache).setCacheKeyFactory(session.cacheKeyFactory)
            .setUpstreamDataSourceFactory { ByteArrayDataSource(body) }.createDataSource()
        try { data.open(spec); val scratch = ByteArray(4096); while(data.read(scratch, 0, scratch.size) >= 0) { } } finally { data.close() }
        val error = AtomicReference<PlaybackException?>(); var player: ExoPlayer? = null
        var tracks = Tracks.EMPTY; var parameters = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
        var selected: Mp4CachedSelection.Selected? = null
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener { override fun onPlayerError(failure: PlaybackException) { error.set(failure) } })
                setMediaSource(session.createMediaSource()); prepare()
            }
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while(selected == null) {
                error.get()?.let { throw AssertionError("Native AVC fixture failed", it) }
                instrumentation.runOnMainSync {
                    tracks = player!!.currentTracks; parameters = player!!.trackSelectionParameters
                    selected = Mp4CachedSelection.select(session.mp4CacheCatalog!!.snapshot(), tracks, parameters, emptyMap())
                }
                check(SystemClock.elapsedRealtime() < deadline) { "Native owner/format was not selected" }; if(selected == null) SystemClock.sleep(20)
            }
            verify(Fixture(root, cache, body, session, key, tracks, parameters, requireNotNull(selected)))
        } finally {
            instrumentation.runOnMainSync { player?.release() }; session.close(); cache.release(); check(root.deleteRecursively())
        }
    } }
}
