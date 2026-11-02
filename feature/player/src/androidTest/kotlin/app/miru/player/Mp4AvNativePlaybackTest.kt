// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Whole-object cache baseline for codec/selection; not a partial-cache interval proof. */
@RunWith(AndroidJUnit4::class)
class Mp4AvNativePlaybackTest {
    @Test fun englishAacAndVideoColdSeekDecodeFromCache() = probe("en",440.0)
    @Test fun japaneseAacAndVideoColdSeekDecodeFromCache() = probe("ja",880.0)
    @Test fun englishAacAndVideoColdSeekWithTheRealMiddleMissing() = probe("en",440.0,true)
    @Test fun japaneseAacAndVideoColdSeekWithTheRealMiddleMissing() = probe("ja",880.0,true)

    private fun probe(language: String, expectedFrequency: Double, partial: Boolean = false) {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val body=instrumentation.context.assets.open("mp4-cache/av-colors.mp4").use { it.readBytes() }
        val root=File(context.cacheDir,"mp4-av-native-${UUID.randomUUID()}").apply { mkdirs() }
        val origin=File(root,"owned.mp4").apply { writeBytes(body) }
        @Suppress("DEPRECATION") val cache=SimpleCache(File(root,"cache"),NoOpCacheEvictor())
        val item=MediaItem.fromUri(origin.toURI().toString());val session=PlaybackSourceSession(context,1,item,emptyMap(),cache)
        val key=session.cacheKey(DataSpec.Builder().setUri(item.localConfiguration!!.uri).build())
        val reference=JSONObject(instrumentation.context.assets.open("mp4-cache/av-colors.raw.samples.json").bufferedReader().use { it.readText() })
        val missing=ArrayList<Pair<Long,Long>>()
        if(partial) for(track in 0 until reference.getJSONArray("tracks").length()) {
            val info=reference.getJSONArray("tracks").getJSONObject(track)
            val scale=info.getString("timeBase").substringAfter('/').toLong()
            val samples=info.getJSONArray("samples")
            for(i in 0 until samples.length()) {
                val sample=samples.getJSONObject(i)
                if(sample.getLong("ptsTicks") in 4*scale until 8*scale) {
                    val at=sample.getLong("offset");missing+=at to at+sample.getLong("size")
                }
            }
        }
        val holes=missing.sortedBy { it.first }.fold(ArrayList<Pair<Long,Long>>()) { merged,next ->
            val last=merged.lastOrNull()
            if(last!=null && next.first<=last.second)merged[merged.lastIndex]=last.first to maxOf(last.second,next.second) else merged+=next
            merged
        }
        fun put(from: Long,to: Long) {
            if(from==to)return
            val hole=cache.startReadWrite(key,from,to-from)
            try { val file=cache.startFile(key,from,to-from);file.writeBytes(body.copyOfRange(from.toInt(),to.toInt()));cache.commitFile(file,to-from) }
            finally { cache.releaseHoleSpan(hole) }
        }
        var at=0L
        for((from,to)in holes) { put(at,from);at=to }
        put(at,body.size.toLong())
        if(partial) { assertTrue(holes.isNotEmpty());for((from,to)in holes)assertFalse(cache.isCached(key,from,to-from)) }
        cache.applyContentMetadataMutations(key,ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it,body.size.toLong()) })
        assertTrue(origin.delete()) // Any native upstream fallback now fails rather than serving another copy.
        val frameThread=HandlerThread("MiruAvFrames").apply { start() }
        val reader=ImageReader.newInstance(160,90,ImageFormat.YUV_420_888,3)
        val rgb=AtomicReference<List<Double>?>();val firstPts=AtomicLong(-1)
        val failure=AtomicReference<PlaybackException?>();val audioFormat=AtomicReference<Format?>()
        val imageFailure=AtomicReference<Throwable?>()
        val streamOffset=AtomicLong();val firstAudioTime=AtomicLong(Long.MIN_VALUE)
        val pcm=ArrayList<Short>()
        reader.setOnImageAvailableListener({ source ->
            try { source.acquireLatestImage()?.use { image ->
                check(image.format==ImageFormat.YUV_420_888)
                fun component(plane: Int,x: Int,y: Int): Int {
                    val data=image.planes[plane];val bytes=data.buffer
                    return bytes.get(bytes.position()+y*data.rowStride+x*data.pixelStride).toInt() and 255
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
        },Handler(frameThread.looper))
        val renderers=object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context,enableFloatOutput: Boolean,enableAudioOutputPlaybackParams: Boolean): AudioSink? {
                val sink=super.buildAudioSink(context,false,enableAudioOutputPlaybackParams) ?: return null
                return object : ForwardingAudioSink(sink) {
                    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
                        streamOffset.set(outputStreamOffsetUs);super.setOutputStreamOffsetUs(outputStreamOffsetUs)
                    }
                    override fun configure(config: AudioSink.AudioSinkConfig) { audioFormat.set(config.format);super.configure(config) }
                    override fun handleBuffer(buffer: ByteBuffer,presentationTimeUs: Long,encodedAccessUnitCount: Int): Boolean {
                        firstAudioTime.compareAndSet(Long.MIN_VALUE,presentationTimeUs)
                        val before=buffer.position();val copy=buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                        val handled=super.handleBuffer(buffer,presentationTimeUs,encodedAccessUnitCount)
                        val format=audioFormat.get()
                        if(format?.pcmEncoding==C.ENCODING_PCM_16BIT && format.channelCount==1 && format.sampleRate==44100) {
                            synchronized(pcm) {
                                for(at in before until buffer.position()-1 step 2) {
                                    val time=presentationTimeUs-streamOffset.get()+at/2L*1_000_000/44100
                                    if(time in 9_250_000 until 9_750_000) pcm+=copy.getShort(at)
                                }
                            }
                        }
                        return handled
                    }
                }
            }
        }
        var player: ExoPlayer?=null;var selected: Mp4CachedSelection.Selected?=null
        val report=JSONObject().put("language",language).put("partial",partial)
            .put("missingByteRanges",JSONArray(holes.map { JSONArray(listOf(it.first,it.second)) }))
            .put("scope","Native AV cold seek and selected tone; not production intervals or exact initial AAC preroll")
        try {
            instrumentation.runOnMainSync {
                player=ExoPlayer.Builder(context,renderers).build().apply {
                    volume=0f
                    trackSelectionParameters=trackSelectionParameters.buildUpon().setPreferredAudioLanguage(language).build()
                    addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failure.set(error) } })
                    setVideoSurface(reader.surface)
                    setVideoFrameMetadataListener { pts,_,_,_ -> firstPts.compareAndSet(-1,pts) }
                    setMediaSource(session.createMediaSource(),9000);prepare();play()
                }
            }
            val deadline=SystemClock.elapsedRealtime()+20_000
            while(SystemClock.elapsedRealtime()<deadline) {
                failure.get()?.let { throw AssertionError("Cached AV decode failed",it) }
                imageFailure.get()?.let { throw AssertionError("Native YUV capture failed",it) }
                instrumentation.runOnMainSync {
                    val actual=player!!
                    selected=Mp4CachedSelection.select(session.mp4CacheCatalog!!.snapshot(),actual.currentTracks,actual.trackSelectionParameters,emptyMap())
                }
                if(selected!=null && synchronized(pcm) { pcm.size>=18000 } && rgb.get()?.let { abs(it[0])<=45 && abs(it[1])<=45 && abs(it[2]-255)<=45 }==true)break
                SystemClock.sleep(10)
            }
            val binding=checkNotNull(selected) { "Actual selected AV outputs could not bind to their native owner" }
            assertEquals(setOf(C.TRACK_TYPE_VIDEO,C.TRACK_TYPE_AUDIO),binding.outputs.map { it.type }.toSet())
            assertEquals(2,binding.outputs.size)
            assertEquals(language,Util.normalizeLanguageCode(requireNotNull(binding.outputs.single { it.type==C.TRACK_TYPE_AUDIO }.format!!.language)))
            assertTrue(abs(firstPts.get()-9_000_000)<=150_000)
            val color=checkNotNull(rgb.get());assertTrue(abs(color[0])<=45 && abs(color[1])<=45 && abs(color[2]-255)<=45)
            val format=checkNotNull(audioFormat.get());assertEquals(C.ENCODING_PCM_16BIT,format.pcmEncoding);assertEquals(44100,format.sampleRate);assertEquals(1,format.channelCount)
            val samples=synchronized(pcm) { pcm.toShortArray() };assertTrue(samples.size>=18000)
            val crossings=(1 until samples.size).count { samples[it-1]<0 && samples[it]>=0 }
            val frequency=crossings*44100.0/samples.size
            val rms=sqrt(samples.sumOf { it.toDouble()*it }/samples.size)
            assertEquals(expectedFrequency,frequency,5.0);assertTrue(rms>100 && rms<10000)
            report.put("status","PASSED").put("firstVideoPtsUs",firstPts.get()).put("rgb",JSONArray(color))
                .put("audioStreamOffsetUs",streamOffset.get()).put("firstAudioSinkTimeUs",firstAudioTime.get())
                .put("pcmSampleCount",samples.size).put("toneHz",frequency).put("rms",rms)
                .put("selectedOutputOrdinals",JSONArray(binding.outputs.map { it.ordinal }))
        } catch(error: Throwable) {
            report.put("status","FAILED").put("failure",error.toString()).put("firstVideoPtsUs",firstPts.get())
                .put("audioStreamOffsetUs",streamOffset.get()).put("firstAudioSinkTimeUs",firstAudioTime.get())
                .put("rgb",rgb.get()?.let(::JSONArray)).put("pcmCount",synchronized(pcm) { pcm.size })
            throw error
        } finally {
            File(context.filesDir,"mp4-av-native-$language${if(partial) "-partial" else ""}.json").writeText(report.toString(2))
            instrumentation.runOnMainSync { player?.release() }
            reader.close();frameThread.quitSafely();frameThread.join(5000)
            session.close();cache.release();check(root.deleteRecursively())
        }
    }
}
