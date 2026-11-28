// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import app.miru.data.BrowsingDataType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChromiumPhaseC3Batch1UnitTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun sessionStorePersistsAndRestoresNormalTabsAndBoundsUndoStack() {
        val file = File(tempFolder.root, "chromium_session.json")
        val store = ChromiumSessionStore(file)
        val saved = listOf(
            ChromiumSessionStore.SavedTabState("tab-a", "https://example.com/", "Example", desktopMode = false),
            ChromiumSessionStore.SavedTabState("tab-b", "https://miru.test/", "Miru", desktopMode = true),
        )
        store.save(saved, selectedNormalTabId = "tab-b")
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals("tab-b", loaded!!.selectedTabId)
        assertEquals(2, loaded.tabs.size)
        assertTrue(loaded.tabs[1].desktopMode)

        repeat(12) { idx ->
            store.recordClosedNormalTabs(
                listOf(
                    ChromiumSessionStore.SavedTabEntry(
                        ChromiumSessionStore.SavedTabState("closed-$idx", "https://example.com/$idx", "T$idx", false),
                        index = idx,
                    ),
                ),
                selectedTabId = "closed-$idx",
            )
        }
        val poppedIds = generateSequence { store.popUndoBatch()?.tabs?.single()?.state?.id }.toList()
        assertEquals(10, poppedIds.size)
        assertEquals("closed-11", poppedIds.first())
        assertEquals("closed-2", poppedIds.last())
        assertNull(store.popUndoBatch())
    }

    @Test
    fun downloadBridgeHandlesHttpHeadersDataUriAndDeleteOnCloseBlobTemp() {
        val httpReq = ChromiumDownloadBridge.buildRequest(
            url = "https://cdn.example.com/files/archive.zip",
            fileName = "archive.zip",
            mimeType = "application/zip",
            contentLength = 1024L,
            sourcePage = "https://cdn.example.com/page",
            userAgent = "MiruTest/Chrome159",
            cookieHeader = "sid=abc",
            isPrivate = true,
        )
        assertNotNull(httpReq)
        assertTrue(httpReq!!.isPrivate)
        assertEquals("sid=abc", httpReq.headers["Cookie"])
        assertEquals("MiruTest/Chrome159", httpReq.headers["User-Agent"])
        assertEquals("https://cdn.example.com/page", httpReq.headers["Referer"])
        assertNull(httpReq.bodyStream)

        val plainData = ChromiumDownloadBridge.parseDataUri("data:text/plain;charset=utf-8,hello%20miru")
        assertNotNull(plainData)
        assertEquals("text/plain", plainData!!.mimeType)
        assertArrayEquals("hello miru".toByteArray(Charsets.UTF_8), plainData.bytes)

        val b64Data = ChromiumDownloadBridge.parseDataUri("data:application/octet-stream;base64,bWlydS1kYXRhLW9r")
        assertNotNull(b64Data)
        assertEquals("application/octet-stream", b64Data!!.mimeType)
        assertArrayEquals("miru-data-ok".toByteArray(Charsets.UTF_8), b64Data.bytes)

        val tmpFile = File(tempFolder.root, "blob_1234.bin").apply { writeBytes("blob-payload".toByteArray()) }
        val blobReq = ChromiumDownloadBridge.buildRequest(
            url = "blob:https://example.com/uuid",
            fileName = "blob.txt",
            mimeType = "text/plain",
            contentLength = -1L,
            sourcePage = "https://example.com/",
            userAgent = "MiruTest",
            cookieHeader = "",
            isPrivate = false,
            tempPath = tmpFile.absolutePath,
        )
        assertNotNull(blobReq)
        val body = blobReq!!.bodyStream!!.use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals("blob-payload", body)
        assertTrue("Temp blob file must be deleted on stream close", !tmpFile.exists())
    }

    @Test
    fun securityHelperMapsBrowsingDataMasksRoundTrip() {
        val selected = setOf(BrowsingDataType.COOKIES, BrowsingDataType.SITE_DATA, BrowsingDataType.AUTH_SESSIONS)
        val mask = ChromiumSecurityHelper.toClearMask(selected)
        assertEquals(1 or 2 or 8, mask)
        assertEquals(setOf(BrowsingDataType.COOKIES, BrowsingDataType.AUTH_SESSIONS), ChromiumSecurityHelper.fromFailedMask(1 or 8, selected))
        assertTrue(ChromiumSecurityHelper.fromFailedMask(0, selected).isEmpty())
    }
}
