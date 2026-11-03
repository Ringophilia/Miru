// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real decoder output at an interior seek and the known final video frame. */
@RunWith(AndroidJUnit4::class)
class Mp4VideoSeekBoundaryTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext

    @Test fun singleVideoInteriorSeekRequiresTheNextPresentationSample() = probe(false,true)
    @Test fun singleVideoTailSeekOutputsTheKnownLastFrame() = probe(false,false)
    @Test fun muxedAvTailSeekOutputsTheKnownLastVideoFrame() = probe(true,false)

    private data class Attempt(val selected: Mp4CachedSelection.Selected?,val pts: Long,
        val rgb: List<Double>?,val error: PlaybackException?)

    private fun attempt(session: PlaybackSourceSession,positionMs: Long,expectError: Boolean): Attempt {
        val frames=HandlerThread("MiruSeekBoundary").apply { start() }
        val reader=ImageReader.newInstance(160,90,ImageFormat.YUV_420_888,3)
        val pts=AtomicLong(-1);val rgb=AtomicReference<List<Double>?>()
        val failure=AtomicReference<PlaybackException?>();val imageFailure=AtomicReference<Throwable?>()
        reader.setOnImageAvailableListener({ source ->
            try { source.acquireLatestImage()?.use { image ->
                check(image.format==ImageFormat.YUV_420_888)
                fun component(plane: Int,x: Int,y: Int): Int {
                    val p=image.planes[plane];return p.buffer.get(p.buffer.position()+y*p.rowStride+x*p.pixelStride).toInt() and 255
                }
                val channels=DoubleArray(3)
                for(y in 15..35)for(x in 55..95) {
                    val yy=component(0,x,y)-16;val u=component(1,x/2,y/2)-128;val v=component(2,x/2,y/2)-128
                    channels[0]+=(1.164383*yy+1.596027*v).coerceIn(0.0,255.0)
                    channels[1]+=(1.164383*yy-0.391762*u-0.812968*v).coerceIn(0.0,255.0)
                    channels[2]+=(1.164383*yy+2.017232*u).coerceIn(0.0,255.0)
                }
                rgb.set(channels.map { it/(21*41) })
            } } catch(error: Throwable) { imageFailure.compareAndSet(null,error) }
        },Handler(frames.looper))
        var player: ExoPlayer?=null;var selected: Mp4CachedSelection.Selected?=null
        try {
            instrumentation.runOnMainSync {
                player=ExoPlayer.Builder(context).build().apply {
                    volume=0f
                    trackSelectionParameters=trackSelectionParameters.buildUpon().setPreferredAudioLanguage("en").build()
                    addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failure.set(error) } })
                    setVideoSurface(reader.surface)
                    setVideoFrameMetadataListener { time,_,_,_ -> pts.compareAndSet(-1,time) }
                    setMediaSource(session.createMediaSource(),positionMs);prepare()
                }
            }
            val deadline=SystemClock.elapsedRealtime()+20_000
            while(SystemClock.elapsedRealtime()<deadline) {
                imageFailure.get()?.let { throw AssertionError("Boundary YUV output failed",it) }
                instrumentation.runOnMainSync {
                    selected=Mp4CachedSelection.select(session.mp4CacheCatalog!!.snapshot(),player!!.currentTracks,player!!.trackSelectionParameters,emptyMap())
                }
                if(expectError && failure.get()!=null || !expectError && selected!=null && pts.get()>=0 && rgb.get()!=null)break
                if(!expectError)failure.get()?.let { throw AssertionError("Full-cache boundary decode failed",it) }
                SystemClock.sleep(10)
            }
            return Attempt(selected,pts.get(),rgb.get(),failure.get())
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            reader.close();frames.quitSafely();frames.join(5000)
        }
    }

    private fun probe(av: Boolean,interior: Boolean) {
        val assets=instrumentation.context.assets
        val reference=JSONObject(assets.open("mp4-cache/${if(av) "av-colors.raw" else "colors"}.samples.json").bufferedReader().use { it.readText() })
        val body=assets.open(if(av) "mp4-cache/av-colors.mp4" else "colors.mp4").use { it.readBytes() }
        assertEquals(reference.getLong("bytes"),body.size.toLong())
        assertEquals(reference.getString("sha256"),MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) })
        val root=File(context.cacheDir,"mp4-boundary-${UUID.randomUUID()}").apply { mkdirs() }
        @Suppress("DEPRECATION") val cache=SimpleCache(File(root,"cache"),NoOpCacheEvictor())
        val movie=File(root,"owned.mp4").apply { writeBytes(body) }
        val item=MediaItem.fromUri(movie.toURI().toString())
        val session=PlaybackSourceSession(context,1,item,emptyMap(),cache)
        val key=session.cacheKey(DataSpec.Builder().setUri(item.localConfiguration!!.uri).build())
        fun put(from: Long,to: Long) {
            if(from==to)return
            val span=cache.startReadWrite(key,from,to-from)
            try { val file=cache.startFile(key,from,to-from);file.writeBytes(body.copyOfRange(from.toInt(),to.toInt()));cache.commitFile(file,to-from) }
            finally { cache.releaseHoleSpan(span) }
        }
        fun length()=cache.applyContentMetadataMutations(key,ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it,body.size.toLong()) })
        val report=JSONObject().put("av",av).put("interior",interior).put("sourceSha256",reference.getString("sha256"))
        val name=if(interior) "single-interior" else if(av) "av-tail" else "single-tail"
        try {
            put(0,body.size.toLong());length();assertTrue(movie.delete())
            val positionMs=if(interior)3950L else 11950L
            val full=attempt(session,positionMs,false)
            report.put("fullCacheFirstVideoPtsUs",full.pts).put("fullCacheRgb",full.rgb?.let(::JSONArray))
                .put("fullCacheErrorCode",full.error?.errorCode)
            val binding=checkNotNull(full.selected) { "Actual native boundary owner could not bind" }
            assertEquals(if(av)2 else 1,binding.outputs.size)
            val expectedPts=if(interior)4_000_000L else 11_900_000L
            assertEquals(expectedPts,full.pts)
            val color=checkNotNull(full.rgb)
            val expected=if(interior)listOf(0.0,255.0,0.0) else listOf(0.0,0.0,255.0)
            assertTrue(color.indices.all { abs(color[it]-expected[it])<=45 })
            report.put("fullCacheFirstVideoPtsUs",full.pts).put("fullCacheRgb",JSONArray(color))
            fun coverage(): List<CachedTimeRange> {
                val plan=Mp4AvcCachedTimelineIndex.tracks(binding,session,cache,0,12_000_000,
                    { ContentMetadata.getContentLength(cache.getContentMetadata(it)) },EmptyCoroutineContext)
                val spans=cache.keys.associateWith { k -> cache.getCachedSpans(k).map { CachedResourceRange(k,it.position,it.length) } }
                return CachedTimelineCoverage.compute(plan,spans,12_000_000)
            }
            val complete=coverage();report.put("fullCacheRanges",complete.toString())
            assertEquals(listOf(CachedTimeRange(0,12_000_000)),complete)
            if(interior) {
                val sample=reference.getJSONArray("tracks").getJSONObject(0).getJSONArray("samples").getJSONObject(40)
                val missing=sample.getLong("offset")+sample.getLong("size")/2
                cache.removeResource(key);put(0,missing);put(missing+1,body.size.toLong());length()
                assertFalse(cache.isCached(key,missing,1))
                val ranges=coverage();val failed=attempt(session,positionMs,true)
                report.put("missingByte",missing).put("rangesAfterHole",ranges.toString())
                    .put("holeErrorCode",failed.error?.errorCode).put("holeFirstVideoPtsUs",failed.pts)
                assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,checkNotNull(failed.error).errorCode)
                assertFalse("An actual missing next frame cannot prove this interior seek",ranges.any { 3_950_000L in it.startUs until it.endUs })
                put(missing,missing+1)
                assertEquals(listOf(CachedTimeRange(0,12_000_000)),coverage())
                report.put("refillRestoresCoverage",true)
            }
            report.put("status","PASSED")
        } catch(error: Throwable) { report.put("status","FAILED").put("failure",error.toString());throw error }
        finally {
            File(context.filesDir,"mp4-boundary-$name.json").writeText(report.toString(2))
            session.close();cache.release();check(root.deleteRecursively())
        }
    }
}
