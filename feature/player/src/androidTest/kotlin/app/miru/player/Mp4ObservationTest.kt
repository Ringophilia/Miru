// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class)
package app.miru.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.*
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Public factory/output contract and actual selected native extractor, not disk-time coverage. */
@RunWith(AndroidJUnit4::class)
class Mp4ObservationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val profile = Mp4ParserProfile(false, true, true, C.VIDEO_CODEC_FLAG_H264 or C.VIDEO_CODEC_FLAG_H265)

    @Test fun factoryForwardsEverySettingAndBothCreationMethodsWithoutReplacingNativePolicy() {
        val calls = ArrayList<String>()
        val native = Mp4Extractor()
        val parser = DefaultSubtitleParserFactory()
        val uri = Uri.parse("https://owned.invalid/clip.mp4")
        val headers = mapOf("Content-Type" to listOf("video/mp4"))
        fun spy(): ExtractorsFactory = object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> { calls += "plain"; return arrayOf(native) }
            override fun createExtractors(u: Uri, h: Map<String, List<String>>): Array<Extractor> {
                assertSame(uri, u); assertSame(headers, h); calls += "uri"; return arrayOf(native)
            }
            override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory { assertSame(parser, factory); calls += "parser"; return spy() }
            @Suppress("DEPRECATION")
            override fun experimentalSetTextTrackTranscodingEnabled(value: Boolean): ExtractorsFactory { assertFalse(value); calls += "text"; return spy() }
            override fun experimentalSetCodecsToParseWithinGopSampleDependencies(value: Int): ExtractorsFactory { assertEquals(C.VIDEO_CODEC_FLAG_H264, value); calls += "gop"; return spy() }
            override fun setParseHagcMetadata(value: Boolean): ExtractorsFactory { assertFalse(value); calls += "hagc"; return spy() }
        }
        val factory = ObservedMp4ExtractorsFactory(Mp4CacheCatalog(), Mp4CacheRequestContext { "actual" }, spy())
        assertSame(factory, factory.setSubtitleParserFactory(parser))
        @Suppress("DEPRECATION") val same = factory.experimentalSetTextTrackTranscodingEnabled(false)
        assertSame(factory, same)
        factory.experimentalSetCodecsToParseWithinGopSampleDependencies(C.VIDEO_CODEC_FLAG_H264)
        factory.setParseHagcMetadata(false)
        assertSame(native, factory.createExtractors().single().underlyingImplementation)
        assertSame(native, factory.createExtractors(uri, headers).single().underlyingImplementation)
        assertEquals(listOf("parser", "text", "gop", "hagc", "plain", "uri"), calls)
    }

    @Test fun icyAddressConversionAndNonMp4ExtractorsAreNotObserved() {
        val catalog = Mp4CacheCatalog(); val request = Mp4CacheRequestContext { "actual" }
        val native = Mp4Extractor(); val other = FakeExtractor()
        val delegate = object : ExtractorsFactory { override fun createExtractors(): Array<Extractor> = arrayOf(native, other) }
        val factory = ObservedMp4ExtractorsFactory(catalog, request, delegate)
        val all = factory.createExtractors(Uri.parse("https://owned.invalid/clip"), mapOf("icy-metaint" to listOf("16")))
        assertSame(native, all[0]); assertSame(other, all[1])
        assertTrue(catalog.snapshot().entries.isEmpty())
    }

    @Test fun originalRequestCoordinatesRemainThreadLocalAndClearOnFailureOrClose() {
        val requests = Mp4CacheRequestContext { "${it.position}/${it.length}" }
        val source = requests.factory { ByteArrayDataSource(ByteArray(100)) }.createDataSource()
        val pool = Executors.newSingleThreadExecutor()
        try {
            assertEquals(90L, source.open(DataSpec.Builder().setUri("https://owned.invalid/resource?token=one").setPosition(10).build()))
            val actual = requireNotNull(requests.request())
            assertEquals(10L, actual.position); assertEquals(-1L, actual.length); assertEquals("10/-1", actual.key)
            assertNull(pool.submit<Mp4CacheRequest?> { requests.request() }.get())
            source.close(); assertNull(requests.request())
            val failed = requests.factory { object : DataSource by ByteArrayDataSource(ByteArray(1)) {
                override fun open(spec: DataSpec): Long = throw IOException("Owned failure")
            } }.createDataSource()
            try { failed.open(DataSpec.Builder().setUri("https://owned.invalid/fail").build()); fail() } catch(_: IOException) { }
            finally { failed.close() }
            assertNull(requests.request())
        } finally { source.close(); pool.shutdownNow() }
    }

    @Test fun outputIdentityAndAllCallbacksSurviveWrappingButLateReleaseCannotRepublish() {
        val actual = FakeExtractor(); val catalog = Mp4CacheCatalog()
        val extractor = ObservedMp4Extractor(actual, catalog, Mp4CacheRequestContext { "actual" }, profile)
        val events = ArrayList<String>(); val dummy = DiscardingTrackOutput()
        val target = object : TrackOutput by dummy {
            override fun format(format: Format) { events += "format" }
            override fun durationUs(durationUs: Long) { events += "duration:$durationUs" }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, crypto: TrackOutput.CryptoData?) { events += "sample:$timeUs/$size" }
        }
        val map = SeekMap.Unseekable(12_000_000)
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = target
            override fun endTracks() { events += "end" }
            override fun seekMap(seekMap: SeekMap) { assertSame(map, seekMap); events += "map" }
        })
        val output = requireNotNull(actual.output)
        val track = output.track(7, C.TRACK_TYPE_VIDEO)
        assertSame(track, output.track(7, C.TRACK_TYPE_VIDEO))
        val format = Format.Builder().setId(1).setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(160).setHeight(90).build()
        track.format(format); track.durationUs(12_000_000)
        track.sampleData(ParsableByteArray(byteArrayOf(1, 2)), 2)
        track.sampleMetadata(9_000_000, C.BUFFER_FLAG_KEY_FRAME, 2, 0, null)
        output.endTracks(); output.seekMap(map)
        val before = catalog.snapshot()
        assertSame(format, before.entries.single().tracks.single().format)
        assertTrue(before.entries.single().tracksEnded)
        assertEquals(listOf("format", "duration:12000000", "sample:9000000/2", "end", "map"), events)
        extractor.release()
        track.format(format); output.endTracks(); output.seekMap(map)
        assertEquals(1, actual.releases)
        assertTrue(catalog.snapshot().entries.isEmpty())
        assertSame(format, before.entries.single().tracks.single().format)
    }

    @Test fun excessiveOutputDeclarationsWithdrawTheWholeObservation() {
        val actual = FakeExtractor(); val catalog = Mp4CacheCatalog()
        val extractor = ObservedMp4Extractor(actual, catalog, Mp4CacheRequestContext { "actual" }, profile)
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = DiscardingTrackOutput()
            override fun endTracks() { }
            override fun seekMap(seekMap: SeekMap) { }
        })
        val output = requireNotNull(actual.output)
        repeat(257) { output.track(it, C.TRACK_TYPE_VIDEO).format(Format.Builder().setId(it).build()) }
        output.endTracks(); assertTrue(catalog.snapshot().entries.isEmpty())
        extractor.release()
    }

    @Test fun actualMainMp4KeepsNativeFormatsMetadataIdentityAndSeekMap() = Disk().use { disk ->
        val file = movie(disk)
        val session = PlaybackSourceSession(context, 1, MediaItem.fromUri(file.toURI().toString()), emptyMap(), disk.cache)
        try {
            prepare(session.createMediaSource()) { player ->
                val entry = requireNotNull(session.mp4CacheCatalog).snapshot().entries.single()
                assertTrue(entry.profile.trustedDefaults)
                assertTrue(entry.tracksEnded); assertTrue(requireNotNull(entry.seekMap).isSeekable)
                assertEquals(12_000_000L, entry.seekMap.durationUs)
                assertEquals(file.length(), entry.fileLength)
                val emitted = entry.tracks.single { it.type == C.TRACK_TYPE_VIDEO }
                val group = player.currentTracks.groups.single { it.type == C.TRACK_TYPE_VIDEO }.mediaTrackGroup
                assertEquals(emitted.ordinal.toString(), group.id)
                assertEquals(requireNotNull(emitted.format).id, group.getFormat(0).id)
                assertNotNull(emitted.format.metadata)
                assertSame(emitted.format.metadata, group.getFormat(0).metadata)
                val original = requireNotNull(entry.request)
                assertEquals(file.toURI().toString(), original.uri)
                assertEquals(-1L, original.length)
                assertEquals(session.cacheKey(DataSpec.Builder().setUri(original.uri).setPosition(original.position).build()), original.key)
                session.close()
                assertTrue(session.mp4CacheCatalog.snapshot().entries.isEmpty())
            }
        } finally { session.close() }
    }

    @Test fun actualPreviewPrivateOfflineAndNonMp4SourcesDoNotPublishMp4Observations() = Disk().use { disk ->
        val file = movie(disk); val item = MediaItem.fromUri(file.toURI().toString())
        val normal = PlaybackSourceSession(context, 1, item, emptyMap(), disk.cache)
        try {
            normal.previewLease().use { preview ->
                prepare(preview.mediaSourceFactory.createMediaSource(item)) {
                    assertTrue(requireNotNull(normal.mp4CacheCatalog).snapshot().entries.isEmpty())
                    assertEquals(0, normal.cacheResourceSnapshot().resourceCount)
                }
            }
        } finally { normal.close() }
        val privateSession = PlaybackSourceSession(context, 2, item, emptyMap())
        val offline = PlaybackSourceSession(context, 3, item, emptyMap(), disk.cache,
            DataSource.Factory { ByteArrayDataSource(file.readBytes()) })
        try {
            for(session in listOf(privateSession, offline)) prepare(session.createMediaSource()) { assertNull(session.mp4CacheCatalog) }
        } finally { privateSession.close(); offline.close() }
        val pcm = ByteArray(32_000)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(36 + pcm.size)
            .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).array()
        val wav = File(disk.root, "owned.wav").apply { writeBytes(header + pcm) }
        val other = PlaybackSourceSession(context, 4, MediaItem.fromUri(wav.toURI().toString()), emptyMap(), disk.cache)
        try { prepare(other.createMediaSource()) { assertTrue(requireNotNull(other.mp4CacheCatalog).snapshot().entries.isEmpty()) } }
        finally { other.close() }
    }

    @Test fun closedCatalogRejectsLateOwnersAndSnapshotsRemainImmutable() {
        val catalog = Mp4CacheCatalog(); val owner = Any()
        val entry = Mp4CacheCatalog.Entry(profile, emptyList(), false, null, -1, null)
        catalog.update(owner, entry)
        val old = catalog.snapshot()
        catalog.close(); catalog.update(owner, entry)
        assertEquals(1, old.entries.size); assertTrue(catalog.snapshot().entries.isEmpty())
        assertTrue(catalog.revision.value > old.revision)
    }

    private fun movie(disk: Disk) = File(disk.root, "owned.mp4").apply {
        instrumentation.context.assets.open("colors.mp4").use { input -> outputStream().use { input.copyTo(it) } }
    }
    private fun prepare(source: androidx.media3.exoplayer.source.MediaSource, verify: (ExoPlayer) -> Unit) {
        val error = AtomicReference<PlaybackException?>(); var player: ExoPlayer? = null
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener { override fun onPlayerError(failure: PlaybackException) { error.set(failure) } })
                setMediaSource(source); prepare()
            }
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while(true) {
                error.get()?.let { throw AssertionError("Native observation source failed", it) }
                var ready = false
                instrumentation.runOnMainSync { ready = player!!.currentTracks.groups.isNotEmpty() }
                if(ready) break
                check(SystemClock.elapsedRealtime() < deadline) { "Native formats not prepared" }; SystemClock.sleep(20)
            }
            instrumentation.runOnMainSync { verify(requireNotNull(player)) }
        } finally { instrumentation.runOnMainSync { player?.release() } }
    }
    private inner class Disk : Closeable {
        val root = File(context.cacheDir, "mp4-observation-${UUID.randomUUID()}").apply { mkdirs() }
        @Suppress("DEPRECATION") val cache = SimpleCache(File(root, "stream"), NoOpCacheEvictor())
        override fun close() { cache.release(); check(root.deleteRecursively()) }
    }
    private class FakeExtractor : Extractor {
        var output: ExtractorOutput? = null; var releases = 0
        override fun sniff(input: ExtractorInput) = false
        override fun init(output: ExtractorOutput) { this.output = output }
        override fun read(input: ExtractorInput, seekPosition: PositionHolder) = Extractor.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) { }
        override fun release() { releases++ }
    }
}

