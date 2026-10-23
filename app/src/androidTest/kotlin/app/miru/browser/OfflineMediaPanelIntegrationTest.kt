// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.browser

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The download begins at a real Gecko media row, rather than seeding the coordinator directly. */
@RunWith(AndroidJUnit4::class)
class OfflineMediaPanelIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (compose.activity.application as MiruApplication).container
    private fun label(id: Int) = context.getString(id)

    @Test fun privateMediaDownloadRequiresAcknowledgementAndDoesNotPersistItsSourcePage() {
        OfflineOrigin(instrumentation.context, requireHeaders = false).use { origin ->
            if(Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
            val privatePageMarker = "miru-private-page-${UUID.randomUUID()}"
            val page = "${origin.url}/page?private_page=$privatePageMarker"
            val title = "Miru private media ${System.nanoTime()}"
            var privateTabId: String? = null
            var downloadId: String? = null
            try {
                compose.runOnIdle { privateTabId = container.browser.newTab(page, isPrivate = true) }
                compose.waitUntil(60_000) {
                    container.browser.media.value[privateTabId]?.any { it.kind == "HLS" && it.playable } == true
                }
                val detected = container.browser.media.value[privateTabId]!!.first { it.kind == "HLS" && it.playable }
                assertTrue("The actual Gecko event must retain its private tab context", detected.isPrivate)
                assertEquals(page, detected.pageUrl)
                compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_media)).performClick()
                compose.onNodeWithTag("media_download_${detected.id}").performClick()
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("offline_download_start").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(label(app.miru.downloads.R.string.offline_download_title)).assertIsDisplayed()
                compose.runOnIdle {
                    assertTrue("A private offline confirmation must retain FLAG_SECURE", compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                }
                compose.onNodeWithTag("offline_private_acknowledgement").performScrollTo().assertIsOff()
                compose.onNodeWithTag("offline_download_start").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithTag("offline_download_title").performScrollTo().performTextReplacement(title)
                // Drive both real quality choices; changing selection must expose its selected state.
                compose.onNodeWithTag("offline_quality_1").performScrollTo().performClick().assertIsSelected()
                compose.onNodeWithTag("offline_quality_0").performScrollTo().performClick().assertIsSelected()
                compose.onNodeWithTag("offline_private_acknowledgement").performScrollTo().performClick().assertIsOn()
                compose.onNodeWithTag("offline_download_start").performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil(45_000) {
                    val item = container.offline.items.value.firstOrNull { it.title == title }
                    if(item?.status == "failed") throw AssertionError("Media-panel download failed: ${item.error}")
                    if(item != null) downloadId = item.id
                    item?.status == "completed"
                }
                val id = checkNotNull(downloadId)
                val item = container.offline.items.value.single { it.id == id }
                assertTrue(item.isPrivate)
                assertNull("An offline item created from a private tab must omit its page URL", item.sourcePage)
                assertTrue(item.qualityLabel.isNotBlank())
                val database = StandaloneDatabaseProvider(context)
                try {
                    val persisted = DefaultDownloadIndex(database, "miru_offline").getDownload(id)
                    assertNotNull("The actual Media3 index must contain the completed download", persisted)
                    assertFalse("Private source URLs must not enter request metadata", persisted!!.request.data.toString(Charsets.ISO_8859_1).contains(privatePageMarker))
                } finally { database.close() }
                assertTrue("The origin must receive actual media segments", origin.calls.any { it.path.endsWith(".ts") })

                // An explicit download survives closing its private source, and uses the common list.
                compose.runOnIdle { privateTabId?.let(container.browser::closeTab); privateTabId = null }
                compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_menu)).performClick()
                compose.onNodeWithText(label(app.miru.menu.R.string.menu_downloads)).performClick()
                compose.onNodeWithText(title).assertIsDisplayed()
                compose.onNodeWithTag("offline_download_$id").assertIsDisplayed()
                compose.onNodeWithText(label(app.miru.downloads.R.string.download_video)).performClick()
                compose.onNodeWithText(title).assertIsDisplayed()
            } finally {
                compose.runOnIdle { privateTabId?.let(container.browser::closeTab) }
                val ids = container.offline.items.value.filter { it.id == downloadId || it.title == title }.map { it.id }
                ids.forEach(container.offline::remove)
                compose.waitUntil(20_000) { container.offline.items.value.none { it.id in ids } }
            }
        }
    }
}
