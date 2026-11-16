// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.capability

import android.net.Uri
import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.browser.DownloadTestServer
import app.miru.browser.MainActivity
import app.miru.browser.MiruApplication
import app.miru.browser.OfflineDownloadsUiOrigin
import app.miru.browser.R
import app.miru.downloads.DownloadRequest
import app.miru.downloads.R as DownloadsR
import app.miru.offline.OfflineSource
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run in a fresh Android test user so the storage totals belong to these fixtures. */
@RunWith(AndroidJUnit4::class)
class DownloadStorageIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (compose.activity.application as MiruApplication).container
    private val downloads get() = container.downloads
    private val offline get() = container.offline
    private fun label(id: Int) = context.getString(id)

    @Before fun openDownloadPage() {
        val skip = label(R.string.intro_skip)
        compose.waitUntil(15_000) {
            runBlocking { container.settings.settings.first().introCompleted } ||
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText(skip).performClick()
        }
        compose.waitUntil(30_000) { container.browser.state.value.ready }
        compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_menu)).performClick()
        compose.onNodeWithText(label(app.miru.menu.R.string.menu_downloads)).performClick()
        assertStorage(0)
    }

    @Test fun partialDownloadCountsAllocatedBlocksAndCancellationReleasesThem() {
        DownloadTestServer(size = 16L * 1024 * 1024, delayMillis = 125).use { server ->
            val original = downloads.settings.value
            downloads.updatePreferences { it.copy(directoryUri = null, threadsPerFile = 2) }
            val id = downloads.enqueue(DownloadRequest("${server.origin}/file", fileName = "storage-partial.bin"))
            val partial = File(context.filesDir, "download-parts/$id")
            try {
                compose.waitUntil(20_000) {
                    downloads.downloads.value.any { it.id == id && it.status == "running" && it.downloadedBytes > 0 }
                }
                downloads.pause(id)
                compose.waitUntil(5_000) { downloads.downloads.value.any { it.id == id && it.status == "paused" } }
                val stats = Os.stat(partial.absolutePath)
                assertEquals(server.size, stats.st_size)
                assertTrue("The fixture must have real data and sparse bytes", stats.st_blocks * 512L in 1 until stats.st_size)
                // A write can finish while the pause command closes its sockets. Compare the settled file.
                assertStorage { Os.stat(partial.absolutePath).st_blocks * 512L }
                downloads.cancel(id)
                compose.waitUntil(5_000) { !partial.exists() }
                assertStorage(0)
            } finally {
                downloads.delete(id, deleteFile = true)
                downloads.updatePreferences { original }
            }
        }
    }

    @Test fun publishedAndOfflineFilesUpdateWithoutReopeningThePage() {
        OfflineDownloadsUiOrigin(instrumentation.context).use { origin ->
            val original = downloads.settings.value
            downloads.updatePreferences { it.copy(directoryUri = null) }
            val id = downloads.enqueue(DownloadRequest("${origin.url}/regular.bin", fileName = "storage-complete.bin"))
            var savedUri: Uri? = null
            var offlineId: String? = null
            try {
                compose.waitUntil(25_000) { downloads.downloads.value.any { it.id == id && it.status == "completed" } }
                val item = downloads.downloads.value.single { it.id == id }
                val uri = Uri.parse(requireNotNull(item.destUri))
                savedUri = uri
                val actualBytes = context.contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize }
                assertEquals(65_536L, actualBytes)
                assertStorage(actualBytes)

                val selection = runBlocking { withTimeout(25_000) {
                    offline.prepare(OfflineSource("${origin.url}/master.m3u8", "Storage offline", mimeType = "application/vnd.apple.mpegurl", headers = origin.headers()))
                } }
                val mediaId = offline.enqueue(selection, selection.qualities.first().id)
                offlineId = mediaId
                compose.waitUntil(40_000) { offline.items.value.any { it.id == mediaId && it.status == "completed" } }
                val mediaBytes = runBlocking { offline.cachedBytes(mediaId) }
                assertTrue(mediaBytes > 0)
                assertStorage(actualBytes + mediaBytes)

                compose.onNodeWithText(label(DownloadsR.string.download_video)).performClick()
                compose.onNodeWithText(item.fileName).assertDoesNotExist()
                assertStorage(actualBytes + mediaBytes)

                val deletion = downloads.stageDelete(listOf(item), deleteFile = true)
                SystemClock.sleep(1_200) // Allow a storage sample while the record is hidden for undo.
                assertTrue(id in downloads.pendingDeletionIds.value)
                assertStorage(actualBytes + mediaBytes)
                downloads.undoDelete(deletion)
                assertEquals(actualBytes, context.contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize })

                assertEquals(1, context.contentResolver.delete(uri, null, null))
                assertStorage(mediaBytes)
                assertTrue(downloads.downloads.value.any { it.id == id && it.status == "completed" })
                offline.remove(mediaId)
                compose.waitUntil(15_000) { offline.items.value.none { it.id == mediaId } }
                assertStorage(0)
            } finally {
                offlineId?.let(offline::remove)
                downloads.delete(id, deleteFile = true)
                savedUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                downloads.updatePreferences { original }
            }
        }
    }

    private fun assertStorage(used: Long) = assertStorage { used }

    private fun assertStorage(used: () -> Long) {
        val bar = hasTestTag("download_storage")
        compose.waitUntil(15_000) {
            val available = StatFs(context.filesDir.absolutePath).availableBytes
            val summary = context.getString(DownloadsR.string.download_storage_summary, displayBytes(used()), displayBytes(available))
            compose.onAllNodes(bar and hasText(summary)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(bar).assertIsDisplayed()
    }

    private fun displayBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
