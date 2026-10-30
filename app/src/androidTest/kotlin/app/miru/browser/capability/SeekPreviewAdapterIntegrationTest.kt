// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:Suppress("DEPRECATION")
package app.miru.browser.capability

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.player.PlaybackSourceSession
import app.miru.player.SeekPreviewExtractor
import app.miru.player.SeekPreviewFrame
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production adapter gates, deliberately separate from the bare-engine probes.
 * -e class app.miru.browser.capability.SeekPreviewAdapterIntegrationTest
 * -e miruSeekPreviewAdapter true
 * Exclude the capability package from ordinary broad instrumentation runs.
 */
@RunWith(AndroidJUnit4::class)
class SeekPreviewAdapterIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val decoderContext by lazy { seekPreviewDecoderContext() }

    @Before fun explicitlyEnabled() {
        assumeTrue("Production adapter gates require explicit opt-in",
            InstrumentationRegistry.getArguments().getString("miruSeekPreviewAdapter") == "true")
    }

    @Test fun httpMp4DecodesOwnedFrames() = httpFrames("colors.mp4", MimeTypes.VIDEO_MP4, listOf("colors.mp4"))
    @Test fun hlsTsDecodesOwnedFrames() = httpFrames("ts.m3u8", MimeTypes.APPLICATION_M3U8, TS)
    @Test fun hlsFmp4DecodesOwnedFrames() = httpFrames("fmp4.m3u8", MimeTypes.APPLICATION_M3U8, FMP4)
    @Test fun hlsAes128DecodesOwnedFrames() = httpFrames("aes.m3u8", MimeTypes.APPLICATION_M3U8, AES)
    @Test fun dashDecodesOwnedFrames() = httpFrames("dash.mpd", MimeTypes.APPLICATION_MPD, DASH)

    @Test fun contentUriDecodesOwnedFrames() {
        val reader = reader(session(SeekPreviewFixtureProvider.URI.toString(), MimeTypes.VIDEO_MP4))
        try { assertThreeFrames(reader) } finally { reader.close() }
    }

    @Test fun cancelClosesOnlyPreviewSocketAndRecoversWithinOriginalSixSeconds() = runBlocking {
        AdapterOrigin(instrumentation.context, hangFirst = true).use { origin ->
            val client = observedClient(origin)
            val session = session(origin.url + "/hang.mp4", MimeTypes.VIDEO_MP4, origin.headers(), client = client)
            val reader = reader(session)
            val siblingExecutor = Executors.newSingleThreadExecutor()
            val sibling = client.newCall(Request.Builder().url(origin.url + "/playback-hang.mp4")
                .apply { origin.headers().forEach { (name, value) -> header(name, value) } }.build())
            val siblingResult = siblingExecutor.submit<Int> { sibling.execute().use { it.body!!.bytes().size } }
            val hung = async(Dispatchers.Default) { runCatching { reader.frame(1_000) } }
            try {
                assertTrue("A separate playback-owned call must actually consume body bytes", origin.playback.bodyRead.await(10, TimeUnit.SECONDS))
                assertPending({ hung.isCompleted }, origin.preview)
                val actionAt = SystemClock.elapsedRealtime()
                val deadline = actionAt + 6_000
                reader.cancel()
                val recovered = runCatching {
                    assertTrue("Retired extraction must fail promptly", within(deadline) { hung.await() }.isFailure)
                    val frame = within(deadline) { reader.frame(9_000) }
                    try { assertFrame(frame, 9_000, Color.BLUE) } finally { frame.image.recycle() }
                }
                origin.preview.peerClosed.await(remaining(deadline), TimeUnit.MILLISECONDS)
                val released = assertSocketClock("cancel", origin.preview, actionAt, deadline)
                // This actual call shares the injected client's dispatcher/pool with preview.
                // Keep its body stalled until after preview's release/recovery observations.
                assertFalse("Preview must never cancel a playback-owned Call", sibling.isCanceled())
                assertFalse("Playback body must still be pending before its own release", siblingResult.isDone)
                assertEquals("Playback peer must remain open", 0L, origin.playback.peerClosedAt.get())
                origin.releasePlayback.countDown()
                assertTrue("Playback must finish normally after its own origin release", siblingResult.get(5, TimeUnit.SECONDS) > 32)
                origin.assertHealthy()
                assertTrue("Preview peer must close within six seconds, before the 30s source timeout", released)
                recovered.getOrThrow()
                assertTrue("Recovery must open a fresh real HTTP request", origin.calls.count { it.path == "/hang.mp4" } >= 2)
            } finally {
                reader.close()
                hung.cancel()
                sibling.cancel()
                siblingExecutor.shutdownNow()
            }
        }
    }

    @Test fun closeClosesPreviewSocketAndFreshReaderRecoversWithinOriginalFiveSeconds() = runBlocking {
        AdapterOrigin(instrumentation.context, hangFirst = true).use { origin ->
            val session = session(origin.url + "/hang.mp4", MimeTypes.VIDEO_MP4, origin.headers(), client = observedClient(origin))
            val reader = reader(session)
            val hung = async(Dispatchers.Default) { runCatching { reader.frame(1_000) } }
            try {
                assertPending({ hung.isCompleted }, origin.preview)
                val actionAt = SystemClock.elapsedRealtime()
                val deadline = actionAt + 5_000
                reader.close()
                val closeReturnedAt = SystemClock.elapsedRealtime()
                val fresh = reader(session)
                val recovered = try { runCatching {
                    assertTrue("Closed extraction must fail promptly", within(deadline) { hung.await() }.isFailure)
                    val frame = within(deadline) { fresh.frame(5_000) }
                    try { assertFrame(frame, 5_000, Color.GREEN) } finally { frame.image.recycle() }
                } } finally { fresh.close() }
                origin.preview.peerClosed.await(remaining(deadline), TimeUnit.MILLISECONDS)
                val released = assertSocketClock("close", origin.preview, actionAt, deadline)
                Log.i(TAG, "closeReturnedAt=" + closeReturnedAt + " recoveryWithin5s=" + recovered.isSuccess)
                origin.assertHealthy()
                assertTrue("Reader close and real peer release must finish within five seconds", released && closeReturnedAt <= deadline)
                recovered.getOrThrow()
            } finally { reader.close(); hung.cancel() }
        }
    }

    @Test fun crossOriginRedirectStripsCredentialsBeforeRequestReachesDestination() {
        AdapterOrigin(instrumentation.context, credentialsRequired = false).use { destination ->
            AdapterOrigin(instrumentation.context).use { source ->
                destination.expectedPublicHeaders = source.publicHeaders()
                source.redirectTo = destination.url + "/colors.mp4"
                val reader = reader(session(source.url + "/redirect.mp4", MimeTypes.VIDEO_MP4, source.headers()))
                try {
                    assertThreeFrames(reader)
                    assertRequests(source, listOf("redirect.mp4"))
                    assertRequests(destination, listOf("colors.mp4"))
                    assertTrue("Redirect destination must receive no Cookie or Authorization", destination.calls.none { it.credentialsPresent })
                } finally { reader.close() }
            }
        }
    }

    @Test fun crossOriginAesKeyAndSegmentsStripCredentialsButKeepPublicHeaders() =
        crossOriginAdaptive("aes.m3u8", MimeTypes.APPLICATION_M3U8, AES)

    @Test fun crossOriginFmp4InitAndSegmentsStripCredentialsButKeepPublicHeaders() =
        crossOriginAdaptive("fmp4.m3u8", MimeTypes.APPLICATION_M3U8, FMP4)

    @Test fun crossOriginDashInitAndSegmentsStripCredentialsButKeepPublicHeaders() =
        crossOriginAdaptive("dash.mpd", MimeTypes.APPLICATION_MPD, DASH)

    @Test fun privateSourceBypassesDiskAndLeavesNoCredentialOrThumbnailArtifacts() {
        AdapterDisk(context).use { disk ->
            AdapterOrigin(instrumentation.context).use { origin ->
                val beforeKeys = disk.cache.keys.toSet()
                val beforeBytes = disk.cache.cacheSpace
                val beforeFiles = snapshotPrivateFiles()
                // Null is the production private policy boundary; a real nearby
                // streaming cache remains present and monitored during extraction.
                val reader = reader(session(origin.url + "/aes.m3u8", MimeTypes.APPLICATION_M3U8, origin.headers(), cache = null))
                try { assertThreeFrames(reader); assertRequests(origin, AES) } finally { reader.close() }
                assertEquals("Private extraction must leave the streaming cache keys unchanged", beforeKeys, disk.cache.keys.toSet())
                assertEquals("Private extraction must write no streaming media bytes", beforeBytes, disk.cache.cacheSpace)
                val afterFiles = snapshotPrivateFiles()
                assertEquals("Private preview must create/change no app-owned files (including thumbnails/header/history)", beforeFiles, afterFiles)
            }
        }
    }

    @Test fun sharedCacheKeepsManifestsUpstreamAndPartitionsMediaByCurrentHeaders() {
        AdapterDisk(context).use { disk ->
            AdapterOrigin(instrumentation.context).use { origin ->
                val firstHeaders = origin.headers()
                var reader = reader(session(origin.url + "/aes.m3u8", MimeTypes.APPLICATION_M3U8, firstHeaders, cache = disk.cache))
                try { assertThreeFrames(reader) } finally { reader.close() }
                assertRequests(origin, AES)
                val oldKeys = disk.cache.keys.toSet()
                assertTrue("Segment bytes must reach the supplied streaming cache", disk.cache.cacheSpace > 0)
                assertTrue("Media keys must use v2 credential-partitioned hashes",
                    oldKeys.all { it.startsWith("miru-stream-v2:") && it.removePrefix("miru-stream-v2:").substringBefore(':').matches(Regex("[0-9a-f]{64}")) })
                assertTrue("HLS manifest must never be cached", oldKeys.none { it.contains(".m3u8") })
                assertTrue("No raw credential may appear in a cache key", oldKeys.none { it.contains("Bearer") || it.contains("miru_preview") })
                val beforeReuse = origin.calls.size
                reader = reader(session(origin.url + "/aes.m3u8", MimeTypes.APPLICATION_M3U8, firstHeaders, cache = disk.cache))
                try { assertThreeFrames(reader) } finally { reader.close() }
                val reused = origin.calls.drop(beforeReuse)
                assertTrue("Repeated HLS manifest must still hit upstream", reused.any { it.path == "/aes.m3u8" })
                assertTrue("Already cached encrypted segments must be reused", reused.none { it.path.endsWith(".ts") })
                origin.credentialGeneration = 2
                val beforeRefresh = origin.calls.size
                reader = reader(session(origin.url + "/aes.m3u8", MimeTypes.APPLICATION_M3U8, origin.headers(), cache = disk.cache))
                try { assertThreeFrames(reader) } finally { reader.close() }
                val refreshed = origin.calls.drop(beforeRefresh)
                assertTrue("Changed credentials must create a distinct media partition", disk.cache.keys.any { it !in oldKeys })
                assertTrue("Changed credentials must fetch their own real media bytes", refreshed.any { it.path.endsWith(".ts") })
                assertTrue(refreshed.all { it.headersValid })
                origin.assertHealthy()
            }
        }
    }

    @Test fun stoppedOriginOfflineProgressiveDecodesWithoutUpstreamOrStreamingDisk() =
        offlineFrames("colors.mp4", MimeTypes.VIDEO_MP4, listOf("colors.mp4"))
    @Test fun stoppedOriginOfflineTsHlsDecodesWithoutManifestOrSegmentUpstream() =
        offlineFrames("ts.m3u8", MimeTypes.APPLICATION_M3U8, TS)
    @Test fun stoppedOriginOfflineFmp4HlsDecodesWithoutManifestInitOrSegmentUpstream() =
        offlineFrames("fmp4.m3u8", MimeTypes.APPLICATION_M3U8, FMP4)
    @Test fun stoppedOriginOfflineAesHlsDecodesWithoutManifestKeyOrSegmentUpstream() =
        offlineFrames("aes.m3u8", MimeTypes.APPLICATION_M3U8, AES)
    @Test fun stoppedOriginOfflineDashDecodesWithoutManifestInitOrSegmentUpstream() =
        offlineFrames("dash.mpd", MimeTypes.APPLICATION_MPD, DASH)

    @Test fun offlineCacheMissFailsWithoutFallingBackToHttp() {
        AdapterDisk(context).use { offline ->
            val attempted = AtomicLong(0)
            val forbidden = OkHttpClient.Builder().addInterceptor {
                attempted.incrementAndGet()
                throw AssertionError("Offline cache miss attempted HTTP")
            }.build()
            val factory = CacheDataSource.Factory().setCache(offline.cache)
                .setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null)
            for((path, mime) in listOf("colors.mp4" to MimeTypes.VIDEO_MP4, "aes.m3u8" to MimeTypes.APPLICATION_M3U8,
                "dash.mpd" to MimeTypes.APPLICATION_MPD)) {
                val reader = reader(session("http://127.0.0.1:9/" + path, mime, offline = factory, client = forbidden))
                try {
                    val failed = runBlocking { runCatching { withTimeout(5_000) { reader.frame(1_000) } } }
                    assertTrue("A missing offline source must fail", failed.isFailure)
                    assertFalse("Failure must come from the missing source, not the test deadline",
                        failed.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
                } finally { reader.close() }
            }
            assertEquals("No missing offline source may construct an HTTP call", 0L, attempted.get())
            assertEquals(0L, offline.cache.cacheSpace)
        }
    }

    @Test fun signedProgressiveUrlsReuseRangeCacheWithoutMergingSemanticQueries() {
        AdapterDisk(context).use { disk ->
            AdapterOrigin(instrumentation.context).use { origin ->
                var reader = reader(session(origin.url + "/colors.mp4?token=one", MimeTypes.VIDEO_MP4, origin.headers(), cache = disk.cache))
                try { assertThreeFrames(reader) } finally { reader.close() }
                val original = disk.cache.keys.toSet()
                assertTrue(original.isNotEmpty())
                assertTrue("Signed media keys must drop signature-only queries", original.all { it.endsWith("/colors.mp4") })
                val beforeReuse = origin.calls.size
                reader = reader(session(origin.url + "/colors.mp4?token=two&expires=9999", MimeTypes.VIDEO_MP4, origin.headers(), cache = disk.cache))
                try { assertThreeFrames(reader) } finally { reader.close() }
                assertEquals("Re-signed progressive media must reuse real cached bytes", beforeReuse, origin.calls.size)
                assertEquals(original, disk.cache.keys.toSet())
                reader = reader(session(origin.url + "/colors.mp4?quality=2&token=three", MimeTypes.VIDEO_MP4, origin.headers(), cache = disk.cache))
                try { assertThreeFrames(reader) } finally { reader.close() }
                assertTrue("Semantic query must retain a separate cache key", disk.cache.keys.any { it.contains("quality=2") })
                assertTrue("Semantic media query must fetch its own bytes", origin.calls.size > beforeReuse)
                origin.assertHealthy()
            }
        }
    }

    @Test fun formerlyCollidingHeaderMapsCannotReuseEachOthersRealMediaBytes() {
        AdapterDisk(context).use { disk ->
            AdapterOrigin(instrumentation.context).use { origin ->
                origin.alternateFirstSegmentByPartition = true
                val first = origin.headers() + mapOf("X-Partition-A" to "one, x-partition-b=two")
                val second = origin.headers() + mapOf("X-Partition-A" to "one", "X-Partition-B" to "two")
                fun oldEncoding(headers: Map<String, String>) =
                    headers.entries.sortedBy { it.key.lowercase() }.joinToString { it.key.lowercase() + "=" + it.value }
                assertNotEquals(first, second)
                assertEquals("This must be an actual collision of the former encoding", oldEncoding(first), oldEncoding(second))
                var reader = reader(session(origin.url + "/ts.m3u8", MimeTypes.APPLICATION_M3U8, first, cache = disk.cache))
                try {
                    val frame = runBlocking { withTimeout(15_000) { reader.frame(1_000) } }
                    try { assertFrame(frame, 1_000, Color.RED) } finally { frame.image.recycle() }
                } finally { reader.close() }
                val firstKeys = disk.cache.keys.toSet()
                assertTrue("First mapping must persist its actual segment", firstKeys.any { it.endsWith("/ts-00.ts") })
                val beforeSecond = origin.calls.size
                reader = reader(session(origin.url + "/ts.m3u8", MimeTypes.APPLICATION_M3U8, second, cache = disk.cache))
                try {
                    val frame = runBlocking { withTimeout(15_000) { reader.frame(1_000) } }
                    // The same real segment URL serves a blue encoded TS for the
                    // second mapping. An old-partition cache hit would return red.
                    try { assertFrame(frame, 1_000, Color.BLUE) } finally { frame.image.recycle() }
                } finally { reader.close() }
                assertTrue("Second mapping must request its own segment bytes",
                    origin.calls.drop(beforeSecond).any { it.path == "/ts-00.ts" })
                assertTrue("Second mapping must persist a distinct versioned partition",
                    disk.cache.keys.any { it.endsWith("/ts-00.ts") && it !in firstKeys })
                origin.assertHealthy()
            }
        }
    }

    private fun httpFrames(path: String, mime: String, assets: List<String>) {
        AdapterOrigin(instrumentation.context).use { origin ->
            val reader = reader(session(origin.url + "/" + path, mime, origin.headers()))
            try { assertThreeFrames(reader); assertRequests(origin, assets) } finally { reader.close() }
        }
    }

    private fun crossOriginAdaptive(path: String, mime: String, assets: List<String>) {
        AdapterOrigin(instrumentation.context, credentialsRequired = false).use { destination ->
            AdapterOrigin(instrumentation.context).use { source ->
                destination.expectedPublicHeaders = source.publicHeaders()
                source.mediaOrigin = destination.url
                val reader = reader(session(source.url + "/" + path, mime, source.headers()))
                try {
                    assertThreeFrames(reader)
                    assertRequests(source, listOf(path))
                    assertRequests(destination, assets.filter { it != path })
                    assertTrue("No cross-origin media/key/init request may contain credentials", destination.calls.none { it.credentialsPresent })
                } finally { reader.close() }
            }
        }
    }

    private fun offlineFrames(path: String, mime: String, assets: List<String>) {
        AdapterDisk(context).use { offline ->
            AdapterDisk(context).use { ignoredStreaming ->
                AdapterOrigin(instrumentation.context).use { origin ->
                    val upstream = DefaultDataSource.Factory(context,
                        OkHttpDataSource.Factory(OkHttpClient()).setDefaultRequestProperties(origin.headers()))
                    val writerFactory = CacheDataSource.Factory().setCache(offline.cache).setUpstreamDataSourceFactory(upstream)
                    assets.forEach { asset ->
                        CacheWriter(writerFactory.createDataSource(), DataSpec(Uri.parse(origin.url + "/" + asset)), null, null).cache()
                    }
                    assertRequests(origin, assets)
                    assertTrue("Offline fixture must contain real persisted media bytes", offline.cache.cacheSpace > 0)
                    val callsBeforeStop = origin.calls.size
                    origin.close()
                    val offlineFactory = CacheDataSource.Factory().setCache(offline.cache)
                        .setCacheWriteDataSinkFactory(null).setUpstreamDataSourceFactory(null)
                    val forbiddenNetwork = AtomicLong(0)
                    val client = OkHttpClient.Builder().addInterceptor {
                        forbiddenNetwork.incrementAndGet()
                        throw AssertionError("Offline adapter attempted HTTP")
                    }.build()
                    // An available streaming disk must still be ignored for offline.
                    val session = session(origin.url + "/" + path, mime, origin.headers(),
                        cache = ignoredStreaming.cache, offline = offlineFactory, client = client)
                    val reader = reader(session)
                    try { assertThreeFrames(reader) } finally { reader.close() }
                    assertEquals("Stopped-origin extraction must make no HTTP call", 0L, forbiddenNetwork.get())
                    assertEquals(callsBeforeStop, origin.calls.size)
                    assertEquals("Offline must not populate streaming disk", 0L, ignoredStreaming.cache.cacheSpace)
                    assertTrue(ignoredStreaming.cache.keys.isEmpty())
                    origin.assertHealthy()
                }
            }
        }
    }

    private fun session(url: String, mime: String, headers: Map<String, String> = emptyMap(),
        cache: SimpleCache? = null, offline: DataSource.Factory? = null,
        client: OkHttpClient = OkHttpClient()): PlaybackSourceSession =
        PlaybackSourceSession(context, 7, MediaItem.Builder().setUri(url).setMimeType(mime).build(),
            headers, cache, offline, httpClient = client)

    private fun reader(session: PlaybackSourceSession) = SeekPreviewExtractor(decoderContext, session)

    private fun assertThreeFrames(reader: SeekPreviewExtractor) = runBlocking {
        val frames = mutableListOf<SeekPreviewFrame<Bitmap>>()
        try {
            for((time, color) in listOf(9_000L to Color.BLUE, 1_000L to Color.RED, 5_000L to Color.GREEN)) {
                val frame = withTimeout(15_000) { reader.frame(time) }
                assertFrame(frame, time, color)
                assertTrue("Each frame must own a distinct bitmap", frames.none { it.image === frame.image })
                frames += frame
                // A subsequent library extraction must not mutate a retained copy.
                frames.forEach { retained ->
                    val expected = when(retained.presentationTimeMs) { in 0L..3_999L -> Color.RED; in 4_000L..7_999L -> Color.GREEN; else -> Color.BLUE }
                    assertFrame(retained, retained.presentationTimeMs, expected)
                }
            }
            reader.close()
            // Reader release must preserve cache/UI-owned images as well.
            frames.forEach { assertFalse(it.image.isRecycled) }
        } finally { frames.forEach { it.image.recycle() } }
    }

    private fun assertFrame(frame: SeekPreviewFrame<Bitmap>, target: Long, color: Int) {
        assertTrue("Actual decoder PTS must match the requested time", abs(frame.presentationTimeMs - target) <= 150)
        assertEquals(160, frame.image.width)
        assertEquals(90, frame.image.height)
        var red = 0L; var green = 0L; var blue = 0L
        for(y in 15..35) for(x in 55..95) {
            val pixel = frame.image.getPixel(x, y)
            red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel)
        }
        val actual = listOf(red / 861, green / 861, blue / 861)
        val wanted = listOf(Color.red(color), Color.green(color), Color.blue(color))
        actual.zip(wanted).forEach { (value, expected) -> assertTrue("Decoded RGB=" + actual + " target=" + wanted, abs(value - expected) <= 45) }
        Log.i(TAG, "owned frame target=" + target + " pts=" + frame.presentationTimeMs + " rgb=" + actual)
    }

    private fun assertRequests(origin: AdapterOrigin, assets: List<String>) {
        origin.assertHealthy()
        assets.forEach { asset -> assertTrue("Decoder must really request " + asset, origin.calls.any { it.path == "/" + asset && it.status in 200..299 || it.path == "/" + asset && it.status == 302 }) }
        assertTrue("Every request must obey the current same/cross-origin header policy", origin.calls.isNotEmpty() && origin.calls.all { it.headersValid })
        assertTrue("No request may be rejected", origin.calls.none { it.status >= 400 })
    }

    private fun observedClient(origin: AdapterOrigin): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).addNetworkInterceptor { chain ->
            assertEquals("Adapter must keep the 30s source read timeout", 30_000, chain.readTimeoutMillis())
            val response = chain.proceed(chain.request())
            val event = when(chain.request().url.encodedPath) {
                "/hang.mp4" -> origin.preview
                "/playback-hang.mp4" -> origin.playback
                else -> null
            }
            if(event == null) response else {
                val body = requireNotNull(response.body)
                response.newBuilder().body(object : ResponseBody() {
                    private val input = object : ForwardingSource(body.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long =
                            super.read(sink, byteCount).also { if(it > 0) {
                                event.bodyReadAt.compareAndSet(0, SystemClock.elapsedRealtime())
                                event.bodyRead.countDown()
                            } }
                    }.buffer()
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = input
                }).build()
            }
        }.build()

    private fun assertPending(completed: () -> Boolean, event: AdapterSocketEvent) {
        assertTrue("Origin must enter a genuine hung response", event.started.await(10, TimeUnit.SECONDS))
        assertTrue("Client must consume actual body bytes before the action", event.bodyRead.await(5, TimeUnit.SECONDS))
        assertFalse("Extraction must still be pending", completed())
        assertEquals("Peer must remain open before cancellation", 0L, event.peerClosedAt.get())
        assertEquals(1L, event.peerClosed.count)
        val age = SystemClock.elapsedRealtime() - event.bodyReadAt.get()
        assertTrue("Action must precede the 30s source timeout", event.bodyReadAt.get() > 0 && age < 30_000)
    }

    private fun assertSocketClock(action: String, event: AdapterSocketEvent, actionAt: Long, deadline: Long): Boolean {
        val bodyAt = event.bodyReadAt.get()
        val peerAt = event.peerClosedAt.get()
        val released = peerAt in actionAt..deadline && peerAt >= bodyAt && peerAt - bodyAt < 30_000
        Log.i(TAG, action + " bodyReadAt=" + bodyAt + " actionAt=" + actionAt + " peerClosedAt=" + peerAt +
            " observedAt=" + SystemClock.elapsedRealtime() + " deadline=" + deadline + " readTimeoutMs=30000 released=" + released)
        return released
    }

    private suspend fun <T> within(deadline: Long, block: suspend () -> T): T {
        val time = deadline - SystemClock.elapsedRealtime()
        check(time > 0) { "Original deadline exhausted before dispatch" }
        return withTimeout(time) { block() }.also {
            check(SystemClock.elapsedRealtime() <= deadline) { "Original deadline exceeded" }
        }
    }

    private fun remaining(deadline: Long) = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)

    private fun snapshotPrivateFiles(): Map<String, String> =
        listOf(context.filesDir, context.cacheDir, File(context.applicationInfo.dataDir, "databases"),
            File(context.applicationInfo.dataDir, "shared_prefs")).flatMap { directory ->
            directory.walkTopDown().filter { it.isFile }.map { file ->
                val hash = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val bytes = ByteArray(16_384)
                    while(true) {
                        val count = input.read(bytes)
                        if(count < 0) break
                        hash.update(bytes, 0, count)
                    }
                }
                file.absolutePath to hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }.toList()
        }.toMap()

    private companion object {
        const val TAG = "MiruSeekPreviewAdapter"
        val TS = listOf("ts.m3u8", "ts-00.ts", "ts-01.ts", "ts-02.ts")
        val FMP4 = listOf("fmp4.m3u8", "fmp4-init.mp4", "fmp4-00.m4s", "fmp4-01.m4s", "fmp4-02.m4s")
        val AES = listOf("aes.m3u8", "aes.key", "aes-00.ts", "aes-01.ts", "aes-02.ts")
        val DASH = listOf("dash.mpd", "dash-init-0.m4s", "dash-0-00001.m4s", "dash-0-00002.m4s", "dash-0-00003.m4s")
    }
}

private class AdapterDisk(context: Context) : Closeable {
    private val directory = File(context.cacheDir, "preview-adapter-" + UUID.randomUUID()).apply { mkdirs() }
    val cache = SimpleCache(directory, NoOpCacheEvictor())
    override fun close() { cache.release(); directory.deleteRecursively() }
}

private class AdapterSocketEvent {
    val started = CountDownLatch(1)
    val bodyRead = CountDownLatch(1)
    val bodyReadAt = AtomicLong(0)
    val peerClosed = CountDownLatch(1)
    val peerClosedAt = AtomicLong(0)
}

private data class AdapterCall(val path: String, val status: Int, val headersValid: Boolean, val credentialsPresent: Boolean)

/** Real range server. Observations store only credential booleans, never header values. */
private class AdapterOrigin(private val assets: Context, private val hangFirst: Boolean = false,
    private val credentialsRequired: Boolean = true) : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val didHang = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val failure = AtomicReference<String?>(null)
    val calls = CopyOnWriteArrayList<AdapterCall>()
    val preview = AdapterSocketEvent()
    val playback = AdapterSocketEvent()
    val releasePlayback = CountDownLatch(1)
    val url = "http://127.0.0.1:" + server.localPort
    @Volatile var redirectTo: String? = null
    @Volatile var mediaOrigin: String? = null
    @Volatile var credentialGeneration = 1
    @Volatile var alternateFirstSegmentByPartition = false
    @Volatile var expectedPublicHeaders: Map<String, String>? = null
    fun publicHeaders() = mapOf("Referer" to url + "/source", "Origin" to url, "User-Agent" to "Miru-Seek-Preview-Adapter")
    fun headers() = publicHeaders() + mapOf("Cookie" to "miru_preview=synthetic-" + credentialGeneration,
        "Authorization" to "Bearer preview-fixture-" + credentialGeneration)

    init { thread(isDaemon = true, name = "miru-adapter-origin") {
        while(running.get()) {
            val socket = try { server.accept() } catch(_: java.io.IOException) { break }
            sockets += socket
            thread(isDaemon = true, name = "miru-adapter-response") {
                socket.use {
                    try { serve(it) }
                    catch(_: java.io.IOException) { /* owned cancellation may abort a write */ }
                    catch(error: Exception) { failure.compareAndSet(null, error.javaClass.simpleName) }
                    finally { sockets -= it }
                }
            }
        }
    } }

    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream().bufferedReader()
        val request = input.readLine()?.split(' ') ?: return
        val path = request.getOrNull(1)?.substringBefore('?') ?: return
        val received = linkedMapOf<String, String>()
        while(true) {
            val line = input.readLine()?.takeIf(String::isNotBlank) ?: break
            received[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        val credentials = received.containsKey("cookie") || received.containsKey("authorization")
        val expected = if(credentialsRequired) headers() else expectedPublicHeaders ?: publicHeaders()
        val valid = expected.all { (key, value) -> received[key.lowercase()] == value } &&
            (credentialsRequired || !credentials)
        val output = socket.getOutputStream()
        if(!valid) {
            calls += AdapterCall(path, 403, false, credentials)
            output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush()
            return
        }
        if(path == "/redirect.mp4" && redirectTo != null) {
            calls += AdapterCall(path, 302, true, credentials)
            output.write(("HTTP/1.1 302 Found\r\nLocation: " + redirectTo + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()); output.flush()
            return
        }
        val name = if(path == "/hang.mp4" || path == "/playback-hang.mp4") "colors.mp4" else path.removePrefix("/")
        val known = name.matches(Regex("(colors\\.mp4|(?:ts|fmp4|aes)\\.m3u8|(?:ts|aes)-0[0-2]\\.ts|fmp4-0[0-2]\\.m4s|fmp4-init\\.mp4|aes\\.key|dash\\.mpd|dash-init-0\\.m4s|dash-0-0000[1-3]\\.m4s)"))
        if(!known) {
            calls += AdapterCall(path, 404, true, credentials)
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush()
            return
        }
        val assetName = if(alternateFirstSegmentByPartition && name == "ts-00.ts" &&
            received["x-partition-b"] == "two") "ts-02.ts" else name
        var body = assets.assets.open("media/seek-preview/" + assetName).use { it.readBytes() }
        mediaOrigin?.let { remote ->
            if(name.endsWith(".m3u8")) {
                body = String(body).replace(Regex("(aes\\.key|(?:aes|ts)-0[0-2]\\.ts|fmp4-init\\.mp4|fmp4-0[0-2]\\.m4s)")) {
                    remote + "/" + it.value
                }.toByteArray()
            } else if(name.endsWith(".mpd")) {
                body = String(body).replace("initialization=\"", "initialization=\"" + remote + "/")
                    .replace("media=\"", "media=\"" + remote + "/").toByteArray()
            }
        }
        val range = received["range"]?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val from = range?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val to = range?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(body.lastIndex.toLong()) ?: body.lastIndex.toLong()
        if(from > to || from >= body.size) {
            calls += AdapterCall(path, 416, true, credentials)
            output.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nContent-Range: bytes */" +
                body.size + "\r\nConnection: close\r\n\r\n").toByteArray()); output.flush()
            return
        }
        val status = if(range == null) 200 else 206
        calls += AdapterCall(path, status, true, credentials)
        val length = (to - from + 1).toInt()
        val mime = when {
            name.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            name.endsWith(".mpd") -> "application/dash+xml"
            name.endsWith(".ts") -> "video/mp2t"
            name.endsWith(".key") -> "application/octet-stream"
            else -> "video/mp4"
        }
        output.write(("HTTP/1.1 " + status + " OK\r\nContent-Type: " + mime + "\r\nContent-Length: " + length +
            "\r\nAccept-Ranges: bytes\r\nCache-Control: no-store\r\n" +
            (if(range != null) "Content-Range: bytes " + from + "-" + to + "/" + body.size + "\r\n" else "") +
            "Connection: close\r\n\r\n").toByteArray())
        val event = when {
            path == "/hang.mp4" && hangFirst && didHang.compareAndSet(false, true) -> preview
            path == "/playback-hang.mp4" -> playback
            else -> null
        }
        if(event == null) {
            if(request[0] != "HEAD") output.write(body, from.toInt(), length)
            output.flush()
            return
        }
        val sent = minOf(32, length)
        output.write(body, from.toInt(), sent); output.flush()
        event.started.countDown()
        socket.soTimeout = 50
        while(running.get()) {
            if(event === playback && releasePlayback.count == 0L) {
                output.write(body, from.toInt() + sent, length - sent); output.flush()
                return
            }
            try {
                if(input.read() == -1) { recordPeerClose(event); return }
            } catch(_: SocketTimeoutException) { /* observing EOF/RST, never closing a stalled socket */ }
            catch(_: java.io.IOException) { recordPeerClose(event); return }
        }
    }

    private fun recordPeerClose(event: AdapterSocketEvent) {
        if(running.get()) {
            event.peerClosedAt.compareAndSet(0, SystemClock.elapsedRealtime())
            event.peerClosed.countDown()
        }
    }

    fun assertHealthy() { failure.get()?.let { throw AssertionError("Synthetic adapter server failed: " + it) } }
    override fun close() {
        if(!running.getAndSet(false)) return
        // Disable observations before forced fixture cleanup: cleanup cannot pass a gate.
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }
}
