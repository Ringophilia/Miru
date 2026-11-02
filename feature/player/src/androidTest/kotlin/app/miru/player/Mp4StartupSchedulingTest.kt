// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.*
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Hold only the preparation callback, never replace native parsing or seeking. */
@RunWith(AndroidJUnit4::class)
class Mp4StartupSchedulingTest {
    @Test fun defaultBudgetCanReadTheUncachedMiddleBeforeInitialSeekIsApplied() = probe(false)
    @Test fun boundedBudgetLetsNativePreparationAndInitialSeekRunBeforeTheMiddle() = probe(true)

    private fun probe(bounded: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val body = instrumentation.context.assets.open("colors.mp4").use { it.readBytes() }
        assertEquals("8259466bc2ec96cfd7d3866f9ae30d325192eea48e4663beed6b9b839f559357",
            MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) })
        val reference = JSONObject(instrumentation.context.assets.open("mp4-cache/colors.samples.json").bufferedReader().use { it.readText() })
        val targetOffset = reference.getJSONArray("tracks").getJSONObject(0).getJSONArray("samples").getJSONObject(90).getLong("offset")
        val root = File(context.cacheDir, "mp4-prepare-${UUID.randomUUID()}").apply { mkdirs() }
        @Suppress("DEPRECATION") val cache = SimpleCache(root, NoOpCacheEvictor())
        val uri = Uri.parse("https://owned.invalid/clip.mp4")
        val key = uri.toString()
        val thread = HandlerThread("MiruPrepareGate").apply { start() }
        val playback = Handler(thread.looper)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val readCheckpoint = CountDownLatch(1); val attempted = CountDownLatch(1)
        val attempts = CopyOnWriteArrayList<Long>(); val opens = CopyOnWriteArrayList<Long>()
        var player: ExoPlayer? = null
        try {
            for((from, to) in listOf(0L to 8482L, 15032L to body.size.toLong())) {
                val hole = cache.startReadWrite(key, from, to - from)
                try {
                    val file = cache.startFile(key, from, to - from)
                    file.writeBytes(body.copyOfRange(from.toInt(), to.toInt())); cache.commitFile(file, to - from)
                } finally { cache.releaseHoleSpan(hole) }
            }
            cache.applyContentMetadataMutations(key, ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, body.size.toLong()) })
            assertFalse(cache.isCached(key, 8482, 6550))
            val cacheFactory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory {
                object : BaseDataSource(true) {
                    override fun open(spec: DataSpec): Long { attempts += spec.position; attempted.countDown(); throw IOException("Owned origin stopped") }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("Not opened")
                    override fun getUri(): Uri = uri
                    override fun close() = Unit
                }
            }
            val data = DataSource.Factory {
                val actual = cacheFactory.createDataSource()
                object : DataSource by actual {
                    override fun open(spec: DataSpec): Long { opens += spec.position; return actual.open(spec) }
                }
            }
            val extractors = ExtractorsFactory {
                DefaultExtractorsFactory().createExtractors().map { native ->
                    if(native !is Mp4Extractor) native else object : ForwardingExtractor(native) {
                        override fun init(output: ExtractorOutput) {
                            super.init(object : ExtractorOutput by output {
                                override fun endTracks() {
                                    playback.post { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) { "Preparation gate was not released" } }
                                    check(entered.await(5, TimeUnit.SECONDS))
                                    output.endTracks()
                                }
                            })
                        }
                        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
                            val result = super.read(input, seekPosition)
                            if(input.position >= 4096) readCheckpoint.countDown()
                            return result
                        }
                    }
                }.toTypedArray()
            }
            val factory = ProgressiveMediaSource.Factory(data, extractors)
            if(bounded) factory.setContinueLoadingCheckIntervalBytes(STREAM_CACHE_LOADING_CHECK_BYTES)
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).setPlaybackLooper(thread.looper).build().apply {
                    setMediaSource(factory.createMediaSource(MediaItem.fromUri(uri)), 9000); prepare()
                }
            }
            assertTrue("Native metadata must be emitted before the gate is tested", entered.await(5, TimeUnit.SECONDS))
            assertTrue("Native loader must consume the known cached prefix", readCheckpoint.await(5, TimeUnit.SECONDS))
            if(bounded) assertFalse("A blocked prepare callback must not permit reading past the checkpoint", attempted.await(250, TimeUnit.MILLISECONDS))
            else assertTrue("The default 1MiB budget reproduces the extra middle request", attempted.await(5, TimeUnit.SECONDS))
            release.countDown()
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while(opens.none { it == targetOffset } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
            assertTrue("The real seek map must reopen at the independent 9s sample offset", opens.contains(targetOffset))
            if(bounded) assertTrue("Native initial seek must not request the stopped middle", attempts.isEmpty())
            else assertEquals(8482L, attempts.first())
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { player?.release() }
            thread.quitSafely(); thread.join(5000)
            assertFalse("Owned playback thread must exit", thread.isAlive)
            cache.release(); check(root.deleteRecursively())
        }
    }
}
