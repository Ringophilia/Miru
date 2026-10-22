// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.downloads.DownloadCoordinator
import app.miru.downloads.DownloadItem
import app.miru.downloads.DownloadRequest
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real HTTP/service/Room path, then drives the actual download-list controls. */
@RunWith(AndroidJUnit4::class)
class DownloadsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity.applicationContext
    private val browser get() = (compose.activity.application as MiruApplication).container.browser
    private val coordinator get() = DownloadCoordinator.get(context)
    private fun label(id: Int): String = context.getString(id)

    @Test fun completedDownloadCanBeSelectedDeletedAndUndone() {
        DownloadUiOrigin().use { origin ->
            val fileName = "miru-ui-${System.nanoTime()}.bin"
            val oldPreferences = coordinator.settings.value
            coordinator.updatePreferences { it.copy(deleteFileWithRecord = true, directoryUri = null) }
            compose.runOnIdle { browser.newTab() }
            val id = coordinator.enqueue(DownloadRequest("${origin.url}/file", fileName = fileName))
            var completed: DownloadItem? = null
            try {
                completed = awaitComplete(id)
                val uri = Uri.parse(checkNotNull(completed.destUri))
                assertArrayEquals(origin.payload, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })

                compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_menu)).performClick()
                compose.onNodeWithText(label(app.miru.menu.R.string.menu_downloads)).performClick()
                compose.onNodeWithText(label(app.miru.downloads.R.string.downloads_title)).assertIsDisplayed()
                compose.onNodeWithText(fileName).assertIsDisplayed()

                compose.onNodeWithText(fileName).performTouchInput { longClick() }
                compose.onNodeWithText(context.getString(app.miru.downloads.R.string.download_selected, 1)).assertIsDisplayed()
                // The click-action matcher excludes the decorative delete icon behind each swipe row.
                compose.onNode(hasContentDescription(label(app.miru.downloads.R.string.download_delete)) and hasClickAction()).performClick()
                compose.onNodeWithText(label(app.miru.downloads.R.string.download_delete_files)).assertIsDisplayed()
                compose.onNodeWithText(label(app.miru.downloads.R.string.download_record_only)).performClick()

                compose.waitUntil(3_000) { id in coordinator.pendingDeletionIds.value }
                compose.onNodeWithText(fileName).assertDoesNotExist()
                assertTrue("Undo must defer the actual database deletion", coordinator.downloads.value.any { it.id == id })
                assertArrayEquals("Choosing records only must preserve the downloaded file", origin.payload, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                compose.onNodeWithText(label(app.miru.downloads.R.string.download_undo)).performClick()
                compose.waitUntil(3_000) { id !in coordinator.pendingDeletionIds.value }
                compose.onNodeWithText(fileName).assertIsDisplayed()
                assertTrue("Undo must leave the original completed record", coordinator.downloads.value.any { it.id == id && it.status == "completed" })
                compose.waitForIdle()

                // Save outside application-private storage so it survives Gradle's test-package cleanup.
                val capture = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    "screencap -p /sdcard/Download/miru-m1-downloads.png",
                )
                ParcelFileDescriptor.AutoCloseInputStream(capture).use { output ->
                    val buffer = ByteArray(4096)
                    while (output.read(buffer) != -1) { /* Drain command output to wait for completion. */ }
                }
            } finally {
                coordinator.delete(id, deleteFile = true)
                // Also retain the URI for cleanup if an assertion failed after the record-only undo expired.
                completed?.destUri?.let { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } }
                coordinator.updatePreferences { oldPreferences }
            }
        }
    }

    private fun awaitComplete(id: String): DownloadItem {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val item = coordinator.downloads.value.firstOrNull { it.id == id }
            if (item?.status == "completed") return item
            if (item?.status == "failed") error("UI fixture download failed: ${item.error}")
            SystemClock.sleep(50)
        }
        error("UI fixture download did not complete")
    }
}

/** A 64 KiB reachable device-local file; no internet or host fixture is needed. */
private class DownloadUiOrigin : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val open = AtomicBoolean(true)
    val payload = ByteArray(64 * 1024) { ((it * 29 + 17) and 255).toByte() }
    val url = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true, name = "miru-download-ui-origin") {
            while (open.get()) runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    val method = reader.readLine()?.substringBefore(' ') ?: return@use
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/octet-stream\r\n" +
                            "Content-Length: ${payload.size}\r\n" +
                            "ETag: \"miru-download-ui-v1\"\r\n" +
                            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                        if (method != "HEAD") write(payload)
                        flush()
                    }
                }
            }
        }
    }

    override fun close() { open.set(false); server.close() }
}
