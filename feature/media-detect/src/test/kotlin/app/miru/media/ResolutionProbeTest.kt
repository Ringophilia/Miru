// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.media

import java.io.File
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResolutionProbeTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun media(url: String = "https://media.test/video.mp4?token=one", private: Boolean = false, headers: Map<String, String> = emptyMap()) =
        DetectedMedia("id", "tab", url, "https://page.test/path", "Private title", "VIDEO", headers = headers, isPrivate = private)
    private fun key(value: DetectedMedia) = requireNotNull(MediaResolutionProbe.cacheKey(value))

    @Test fun dimensionsLabelsUseThePictureShortEdge() {
        assertEquals("4K", MediaResolution(3840, 2160).label)
        assertEquals("2K", MediaResolution(1440, 2560).label)
        assertEquals("1080P", MediaResolution(1920, 1080).label)
        assertEquals("720P", MediaResolution(1280, 720).label)
        assertEquals("480P", MediaResolution(320, 180).label)
        assertNull(MediaResolution.valid(0, 720))
        assertNull(MediaResolution.valid(Int.MAX_VALUE, 720))
    }

    @Test fun hlsSelectsLargestValidRepresentationWithoutGuessingFromNames() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=9999,RESOLUTION=1920x1080,CODECS="avc1,mp4a"
            low-named.m3u8
            #EXT-X-STREAM-INF:RESOLUTION=3840x2160,BANDWIDTH=8888
            high.m3u8
            #EXT-X-STREAM-INF:RESOLUTION=999999x999999,BANDWIDTH=1
            bad.m3u8
        """.trimIndent()
        assertEquals(MediaResolution(3840, 2160), ResolutionParsers.hls(playlist))
        assertNull(ResolutionParsers.hls("#EXTM3U\n#EXTINF:4,\n4k-video.ts"))
        assertEquals("a,b", ResolutionParsers.attributes("#EXT-X-KEY:METHOD=AES-128,URI=\"a,b\"")["URI"])
    }

    @Test fun dashInheritsSetDimensionsAndRejectsEntities() {
        val xml = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period>
            <AdaptationSet contentType="audio" width="9999" height="9999"><Representation/></AdaptationSet>
            <AdaptationSet mimeType="video/mp4" width="1280" height="720"><Representation id="one"/><Representation id="two" width="1920" height="1080"/></AdaptationSet>
            </Period></MPD>"""
        assertEquals(MediaResolution(1920, 1080), ResolutionParsers.dash(xml.toByteArray()))
        assertNull(ResolutionParsers.dash("<!DOCTYPE MPD [<!ENTITY secret SYSTEM 'file:///secret'>]><MPD>&secret;</MPD>".toByteArray()))
        assertNull(ResolutionParsers.dash("<!DOCTYPE MPD [<!ENTITY secret 'test'>]><MPD>&secret;</MPD>".toByteArray(Charsets.UTF_16)))
        assertNull(ResolutionParsers.dash("<html width='1920' height='1080'/>".toByteArray()))
    }

    @Test fun mp4RequiresVideoHandlerAndSampleHierarchy() {
        val real = mp4(640, 360)
        assertEquals(MediaResolution(640, 360), ResolutionParsers.mp4(real))
        assertEquals(MediaResolution(640, 360), ResolutionParsers.mp4(ByteArray(99) + real))
        assertNull(ResolutionParsers.mp4(mp4(640, 360, handler = "soun")))
        assertNull(ResolutionParsers.mp4(real.copyOf(real.size - 20)))
        assertNull(ResolutionParsers.mp4("arbitrary stsd avc1 1920x1080".toByteArray()))
    }

    @Test fun realTsAndImagePrefixedTsExposeAvcDimensions() {
        val ts = fixture("segment00.ts").readBytes()
        assertEquals(MediaResolution(320, 180), ResolutionParsers.media(ts))
        val disguised = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()) + ByteArray(37) + ts
        assertEquals(MediaResolution(320, 180), ResolutionParsers.media(disguised))
        assertNull(ResolutionParsers.media(ts.copyOf(100)))
    }

    @Test fun realMp4HeaderAndDashInitializationExposeDimensions() {
        val mp4 = fixture("fixture.mp4").inputStream().use { it.readNBytes(393216) }
        assertEquals(MediaResolution(320, 180), ResolutionParsers.mp4(mp4))
        assertEquals(MediaResolution(320, 180), ResolutionParsers.mp4(fixture("dash/dash-init-0.m4s").readBytes()))
    }

    @Test fun hevcTsReadsActualCroppedPictureSize() {
        val data = requireNotNull(javaClass.getResourceAsStream("/resolution/hevc.ts")).use { it.readBytes() }
        assertEquals(MediaResolution(320, 180), ResolutionParsers.media(data))
        assertEquals(MediaResolution(320, 180), ResolutionParsers.media(ByteArray(61) + data))
    }

    @Test fun malformedContainerInputsAreBoundedAndDoNotThrow() {
        val random = java.util.Random(42)
        repeat(150) {
            val data = ByteArray(it * 23).also(random::nextBytes)
            assertNull(ResolutionParsers.media(data))
            assertNull(ResolutionParsers.avcSps(data.copyOf(minOf(data.size, 2))))
            assertNull(ResolutionParsers.hevcSps(data.copyOf(minOf(data.size, 2))))
        }
    }

    @Test fun aesPrefixSupportsExplicitAndSequenceIvWithoutWholeSegment() {
        val plain = ByteArray(117) { it.toByte() }
        val secret = ByteArray(16) { (15 - it).toByte() }
        val iv = ByteArray(16).apply { this[15] = 7 }
        val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), IvParameterSpec(iv)) }.doFinal(plain)
        assertArrayEquals(plain.copyOf(112), MediaResolutionProbe.decryptPrefix(encrypted.copyOf(117), secret, null, 7))
        assertArrayEquals(plain.copyOf(112), MediaResolutionProbe.decryptPrefix(encrypted.copyOf(117), secret, "0x07", 999))
        assertNull(MediaResolutionProbe.decryptPrefix(encrypted, secret, "0xZZ", 7))
        assertNull(MediaResolutionProbe.decryptPrefix(encrypted, ByteArray(15), null, 7))
    }

    @Test fun cacheKeyRemovesQueryAndSeparatesHeadersAndPrivateContext() {
        val one = key(media(headers = mapOf("Authorization" to "Bearer secret")))
        val rotated = key(media("https://media.test/video.mp4?token=two#fragment", headers = mapOf("authorization" to "Bearer secret")))
        assertEquals(one, rotated)
        assertNotEquals(one, key(media(headers = mapOf("Authorization" to "Bearer other"))))
        assertNotEquals(one, key(media(private = true, headers = mapOf("Authorization" to "Bearer secret"))))
        assertTrue(one.matches(Regex("[a-f0-9]{64}")))
        assertTrue(MediaResolutionProbe.sameOrigin("https://a.test/a".toHttpUrl(), "https://a.test/b".toHttpUrl()))
        assertFalse(MediaResolutionProbe.sameOrigin("https://a.test:8443/a".toHttpUrl(), "https://a.test/b".toHttpUrl()))
    }

    @Test fun cachePersistsOnlyDigestsAndRetainsHigherMeasurements() = runBlocking {
        val folder = File(temporary.root, "new/path")
        var clock = 10_000L
        val cache = ResolutionCache(folder) { clock }
        val id = key(media(headers = mapOf("Cookie" to "session=secret")))
        assertEquals(MediaResolution(1920, 1080), cache.record(id, false, MediaResolution(1920, 1080)))
        assertEquals(MediaResolution(1920, 1080), cache.record(id, false, MediaResolution(320, 180)))
        assertEquals(MediaResolution(1920, 1080), ResolutionCache(folder) { clock }.lookup(id, false)?.resolution)
        val raw = folder.walk().filter(File::isFile).flatMap { it.readBytes().asIterable() }.toList().toByteArray().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("media.test")); assertFalse(raw.contains("secret")); assertFalse(raw.contains("Private title"))
        clock += ResolutionCache.SUCCESS_TTL
        assertNull(cache.lookup(id, false))
    }

    @Test fun failuresExpireAfterSixHoursAndPrivateNeverCreatesAFile() = runBlocking {
        var clock = 10_000L
        val folder = File(temporary.root, "private-only")
        val cache = ResolutionCache(folder) { clock }
        val privateKey = key(media(private = true))
        cache.record(privateKey, true, MediaResolution(320, 180))
        assertFalse(folder.exists())
        assertNotNull(cache.lookup(privateKey, true))
        cache.clearPrivate()
        assertNull(cache.lookup(privateKey, true))
        val id = key(media())
        cache.record(id, false, null)
        assertNotNull(cache.lookup(id, false))
        clock += ResolutionCache.FAILURE_TTL - 1
        assertNotNull(cache.lookup(id, false))
        clock++
        assertNull(cache.lookup(id, false))
    }

    private fun mp4(width: Int, height: Int, handler: String = "vide"): ByteArray {
        fun box(name: String, body: ByteArray): ByteArray = ByteBuffer.allocate(8 + body.size).putInt(body.size + 8).put(name.toByteArray()).put(body).array()
        val sample = ByteBuffer.allocate(28).apply { position(24); putShort(width.toShort()); putShort(height.toShort()) }.array()
        val stsd = box("stsd", ByteBuffer.allocate(8).putInt(0).putInt(1).array() + box("avc1", sample))
        val hdlr = box("hdlr", ByteArray(8) + handler.toByteArray())
        return box("moov", box("trak", box("mdia", hdlr + box("minf", box("stbl", stsd)))))
    }
    private fun fixture(name: String): File = listOf(File("../../app/src/androidTest/assets/media/$name"), File("app/src/androidTest/assets/media/$name")).first { it.exists() }
}
