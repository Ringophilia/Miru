// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.offline

import org.junit.Assert.*
import org.junit.Test

class OfflinePolicyTest {
    @Test fun adaptiveMimeAliasesAreCanonicalBeforeDownloadHelperInference() {
        for(alias in listOf("application/vnd.apple.mpegurl", "application/x-mpegURL", "APPLICATION/VND.APPLE.MPEGURL", " application/vnd.apple.mpegurl; charset=UTF-8", "audio/x-mpegurl")) {
            assertEquals("application/x-mpegURL", offlineMimeType("/signed/media", alias))
        }
        assertEquals("application/x-mpegURL", offlineMimeType("/video.M3U8", "application/octet-stream"))
        assertEquals("application/x-mpegURL", offlineMimeType("/video.m3u8", null))
        assertEquals("application/dash+xml", offlineMimeType("/manifest", "Application/Dash+Xml; charset=utf-8"))
        assertEquals("application/dash+xml", offlineMimeType("/manifest.MPD", null))
        assertNull(offlineMimeType("/movie.mp4", "video/mp4"))
        assertNull(offlineMimeType("/file", "application/octet-stream"))
    }
    @Test fun persistentMetadataIsAnAllowlistWithoutCredentials() {
        val original = OfflineMetadata("Fixture", "https://page.invalid/watch", "720P", false, true, true)
        val data = original.encode()
        val decoded = OfflineMetadata.decode(data)
        assertEquals(original, decoded)
        assertFalse(data.toString(Charsets.ISO_8859_1).contains("Cookie"))
        assertFalse(data.toString(Charsets.ISO_8859_1).contains("Authorization"))
    }

    @Test fun explicitPrivateDownloadsNeverPersistTheirSourcePage() {
        val original = OfflineMetadata("Saved video", "https://private.invalid/sensitive", "1080P", true, true, false)
        val data = original.encode()
        assertFalse(data.toString(Charsets.ISO_8859_1).contains("private.invalid"))
        val decoded = OfflineMetadata.decode(data)
        assertNull(decoded.pageUrl)
        assertTrue(decoded.isPrivate)
        assertTrue(decoded.requiresCookie)
        assertFalse(decoded.requiresAuthorization)
    }

    @Test fun brokenMetadataFailsClosedForPrivateAuthentication() {
        for(data in listOf(byteArrayOf(), byteArrayOf(1, 2, 3), ByteArray(40_000))) {
            val decoded = OfflineMetadata.decode(data)
            assertTrue(decoded.isPrivate)
            assertTrue(decoded.requiresCookie)
            assertTrue(decoded.requiresAuthorization)
            assertNull(decoded.pageUrl)
        }
    }

    @Test fun sourceAndSelectionNeverPrintSignedUrlsOrHeaders() {
        val source = OfflineSource("https://media.invalid/file?token=fixture", "Private title", headers = mapOf("Authorization" to "Bearer credential-fixture"), isPrivate = true)
        assertFalse(source.toString().contains("token"))
        assertFalse(source.toString().contains("credential"))
        assertFalse(source.toString().contains("Private title"))
        val selection = OfflineSelection(source, emptyList(), emptyMap())
        assertFalse(selection.toString().contains("media.invalid"))
    }

    @Test fun headersAreLimitedToPlaybackContextAndCannotInjectLines() {
        val clean = OfflineHttp.clean(mapOf("Cookie" to "fixture=true", "Authorization" to "Bearer fixture", "User-Agent" to "Miru", "X-Forwarded-For" to "private", "Referer" to "bad\r\nInjected: value"))
        assertEquals(setOf("Cookie", "Authorization", "User-Agent"), clean.keys)
    }

    @Test fun UUIDNamespacesCannotCollideAtPrefixBoundaries() {
        assertNotEquals(OfflineStorage.prefix("abc"), OfflineStorage.prefix("abcd"))
        assertFalse((OfflineStorage.prefix("abcd") + "https://media.invalid/v.ts").startsWith(OfflineStorage.prefix("abc")))
        assertEquals("720P", qualityLabel(1280, 720))
        assertEquals("2K", qualityLabel(1440, 2560))
    }
}
