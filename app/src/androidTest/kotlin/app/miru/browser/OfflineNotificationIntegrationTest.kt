// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.browser

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.miru.offline.MiruOfflineService
import app.miru.offline.OfflineCoordinator
import app.miru.offline.OfflineItem
import app.miru.offline.OfflineSource
import app.miru.player.PlayerActivity
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Sends the actual system-posted PendingIntents; never substitutes a coordinator API call. */
@RunWith(AndroidJUnit4::class)
class OfflineNotificationIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val coordinator get() = OfflineCoordinator.get(context)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    @Test fun collidingUuidHashesKeepDistinctNotificationsAndContentIntents() {
        allowNotifications()
        val ids = listOf("e0ccdb2e-ff63-4c76-8de0-9247fd821c0d", "70412c11-52a8-4d37-8e3d-d3cea4232125")
        assertNotEquals(ids[0], ids[1])
        assertEquals("The fixture must exercise an actual Java hash collision", ids[0].hashCode(), ids[1].hashCode())
        val titles = ids.indices.map { "Miru collision notification $it ${System.nanoTime()}" }
        try {
            ids.indices.forEach { index ->
                publisherCall("completed", OfflineItem(ids[index], "", titles[index], "application/x-mpegURL", null,
                    "480P", "completed", null, 1, 1, 100f, 0, 0, false))
            }
            val first = completedNotification(titles[0])
            val second = completedNotification(titles[1])
            assertEquals(ids[0], first.tag)
            assertEquals(ids[1], second.tag)
            assertNotEquals("Distinct UUIDs must not update each other's PendingIntent extras", first.notification.contentIntent, second.notification.contentIntent)
            publisherCall("cancel", ids[0])
            compose.waitUntil(10_000) { posted(titles[0]) == null }
            assertNotNull("Canceling one colliding UUID must retain the other notification", posted(titles[1]))
        } finally { ids.forEach { publisherCall("cancel", it) } }
    }

    @Test fun authenticationFailureNotificationCancelConvergesAndCannotBeResumedByItsOldAction() {
        allowNotifications()
        val oldResolver = coordinator.headerResolver
        coordinator.headerResolver = { null }
        OfflineOrigin(instrumentation.context).use { origin ->
            var id: String? = null
            try {
                val title = "Miru auth notification ${System.nanoTime()}"
                val headers = origin.headers().toMutableMap()
                val selection = runBlocking { coordinator.prepare(OfflineSource("${origin.url}/hls/master.m3u8", title,
                    mimeType = "application/vnd.apple.mpegurl", headers = headers)) }
                // Preparation succeeds with real credentials. The subsequent native transfer
                // receives 403 and cannot refresh this deliberately expired synthetic token.
                headers["Authorization"] = "Bearer deliberately-expired-offline-fixture"
                id = onMain { coordinator.enqueue(selection, selection.qualities.first().id) }
                val downloadId = id
                compose.waitUntil(30_000) { coordinator.items.value.any { it.id == downloadId && it.status == "failed" && it.error == "AUTH_REQUIRED" } }
                assertTrue("The fixture must have actually rejected a transfer request", origin.calls.any { !it.headersValid })
                waitForTransferServiceToStop()
                val notification = stoppedNotification(title)
                assertEquals(context.getString(app.miru.offline.R.string.offline_auth_required), notification.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
                val oldResume = action(notification, app.miru.offline.R.string.offline_resume)
                val cancel = action(notification, app.miru.offline.R.string.offline_cancel)
                send(cancel)
                compose.waitUntil(10_000) { coordinator.items.value.any { it.id == downloadId && it.status == "cancelled" } }
                compose.waitUntil(10_000) { runBlocking { coordinator.cachedBytes(downloadId) } == 0L && posted(title) == null }
                assertNull(runBlocking { coordinator.playback(downloadId) })
                val requestsAfterCancel = origin.calls.size
                send(oldResume)
                SystemClock.sleep(750)
                assertEquals("An already delivered resume PendingIntent must not revive a cancelled UUID", "cancelled", coordinator.items.value.single { it.id == downloadId }.status)
                assertEquals("A cancelled notification action must never send another HTTP request", requestsAfterCancel, origin.calls.size)
            } finally {
                try { id?.let { cleanup(listOf(it)) } }
                finally { coordinator.headerResolver = oldResolver }
            }
        }
    }

    @Test fun notificationPauseResumeAndCancelAllPreserveCompletedVideoAndItsActualOpenIntent() {
        allowNotifications()
        val browser = compose.activity
        val scenarioIntent = onMain { Intent(browser.intent) }
        OfflineOrigin(instrumentation.context).use { completedOrigin ->
            OfflineOrigin(instrumentation.context).use { activeOrigin ->
                val ids = mutableListOf<String>()
                var player: PlayerActivity? = null
                try {
                    val completedTitle = "Miru completed notification ${System.nanoTime()}"
                    val completeSelection = runBlocking { coordinator.prepare(OfflineSource("${completedOrigin.url}/hls/master.m3u8", completedTitle,
                        mimeType = "application/vnd.apple.mpegurl", headers = completedOrigin.headers())) }
                    val completeQuality = completeSelection.qualities.single { it.width == 160 && it.height == 90 }
                    val completeId = onMain { coordinator.enqueue(completeSelection, completeQuality.id) }.also(ids::add)
                    compose.waitUntil(45_000) { coordinator.items.value.any { it.id == completeId && it.status == "completed" } }
                    waitForTransferServiceToStop()
                    val completeNotification = completedNotification(completedTitle)
                    val cachedBytes = runBlocking { coordinator.cachedBytes(completeId) }
                    assertTrue(cachedBytes > 0)
                    completedOrigin.close()

                    val activeTitle = "Miru active notification ${System.nanoTime()}"
                    val activeSelection = runBlocking { coordinator.prepare(OfflineSource("${activeOrigin.url}/hls/master.m3u8", activeTitle,
                        mimeType = "application/vnd.apple.mpegurl", headers = activeOrigin.headers())) }
                    activeOrigin.holdSegments.set(true)
                    val activeId = onMain { coordinator.enqueue(activeSelection, activeSelection.qualities.first().id) }.also(ids::add)
                    compose.waitUntil(15_000) { activeOrigin.segmentStarted.get() }
                    send(action(summaryNotification(), app.miru.offline.R.string.offline_pause_all))
                    compose.waitUntil(10_000) { coordinator.items.value.any { it.id == activeId && it.status == "paused" } }
                    assertTrue("Pausing must retain committed cache bytes", runBlocking { coordinator.cachedBytes(activeId) } > 0)
                    waitForTransferServiceToStop()
                    assertEquals("The paused worker must stop without waiting for the origin's held body", 1L, activeOrigin.releaseSegments.count)
                    val paused = stoppedNotification(activeTitle)
                    assertNotNull("Pausing the final transfer must retain the historical completed notification", posted(completedTitle))
                    val beforeResume = activeOrigin.calls.size
                    send(action(paused, app.miru.offline.R.string.offline_resume))
                    compose.waitUntil(15_000) {
                        coordinator.items.value.any { it.id == activeId && it.status in setOf("running", "queued") } && activeOrigin.calls.size > beforeResume
                    }
                    send(action(summaryNotification(), app.miru.offline.R.string.offline_cancel_all))
                    compose.waitUntil(10_000) { coordinator.items.value.any { it.id == activeId && it.status == "cancelled" } }
                    activeOrigin.holdSegments.set(false); activeOrigin.releaseSegments.countDown()
                    compose.waitUntil(15_000) { runBlocking { coordinator.cachedBytes(activeId) } == 0L }
                    waitForTransferServiceToStop()
                    assertEquals("Global cancel must retain historical complete copies", "completed", coordinator.items.value.single { it.id == completeId }.status)
                    assertEquals(cachedBytes, runBlocking { coordinator.cachedBytes(completeId) })
                    assertNotNull(runBlocking { coordinator.playback(completeId) })
                    assertNotNull("Global cancel must retain the completed notification", posted(completedTitle))

                    // Send the actual contentIntent posted by NotificationManager, including
                    // its persisted UUID -> MainActivity lookup -> opaque player-token route.
                    val requestsBeforeOpen = completedOrigin.calls.size
                    send(requireNotNull(completeNotification.notification.contentIntent))
                    compose.waitUntil(25_000) {
                        onMain {
                            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                                .filterIsInstance<PlayerActivity>().firstOrNull()?.let { player = it }
                        }
                        val snapshot = player?.playback?.state?.value
                        if(snapshot?.error == true || snapshot?.timedOut == true) throw AssertionError("Completed notification could not decode its offline UUID: $snapshot")
                        snapshot?.let { it.ready && it.playing && it.width == 160 && it.height == 90 && it.position >= 200 } == true
                    }
                    assertEquals(completeId, player!!.playback.request.offlineId)
                    assertEquals("The real warm notification Intent must retain its full UUID identity", completeId,
                        onMain { browser.intent.data?.lastPathSegment })
                    onMain { player!!.playback.seek(0); player!!.playback.resume() }
                    compose.waitUntil(20_000) {
                        val snapshot = player!!.playback.state.value
                        if(snapshot.error || snapshot.timedOut) throw AssertionError("Notification offline playback failed before its final segment: $snapshot")
                        onMain { player!!.playback.player?.playbackState == Player.STATE_ENDED }
                    }
                    assertTrue(player!!.playback.state.value.position >= 11_500)
                    assertEquals("The completed notification must open cache-only playback", requestsBeforeOpen, completedOrigin.calls.size)
                } finally {
                    activeOrigin.releaseSegments.countDown()
                    onMain {
                        // MainActivity correctly stores the real notification's new data in
                        // onNewIntent. ActivityScenario filters lifecycle events by its original
                        // launch Intent.data; restore only after the end-to-end assertions so
                        // the rule can observe the browser resume/destroy during cleanup.
                        browser.intent = Intent(scenarioIntent)
                        player?.finish()
                    }
                    cleanup(ids)
                }
            }
        }
    }

    private fun allowNotifications() {
        if(Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue("These tests require actual system notifications", notifications.areNotificationsEnabled())
    }

    /** Publish only synthetic identity fixtures through the real internal publisher. No fake manager. */
    private fun publisherCall(method: String, argument: Any) {
        val publisher = Class.forName("app.miru.offline.OfflineNotifications")
        publisher.getDeclaredMethod(method, android.content.Context::class.java, argument.javaClass)
            .invoke(publisher.getField("INSTANCE").get(null), context, argument)
    }

    private fun posted(title: String): StatusBarNotification? = notifications.activeNotifications.firstOrNull {
        it.notification.channelId == MiruOfflineService.CHANNEL && it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title
    }

    private fun waitForTransferServiceToStop() {
        // A terminal notification must outlive stopForeground, rather than merely appear
        // during the short interval before Android removes a canceled summary's children.
        compose.waitUntil(10_000) { notifications.activeNotifications.none { it.id == MiruOfflineService.FOREGROUND_ID } }
    }

    private fun stoppedNotification(title: String): StatusBarNotification {
        compose.waitUntil(10_000) { posted(title)?.notification?.actions?.isNotEmpty() == true }
        return requireNotNull(posted(title))
    }

    private fun completedNotification(title: String): StatusBarNotification {
        compose.waitUntil(10_000) { posted(title)?.notification?.contentIntent != null }
        return requireNotNull(posted(title))
    }

    private fun summaryNotification(): StatusBarNotification {
        compose.waitUntil(10_000) { notifications.activeNotifications.any { it.id == MiruOfflineService.FOREGROUND_ID && it.notification.channelId == MiruOfflineService.CHANNEL } }
        return notifications.activeNotifications.single { it.id == MiruOfflineService.FOREGROUND_ID && it.notification.channelId == MiruOfflineService.CHANNEL }
    }

    private fun action(notification: StatusBarNotification, label: Int): PendingIntent = requireNotNull(notification.notification.actions)
        .single { it.title.toString() == context.getString(label) }.actionIntent

    private fun send(intent: PendingIntent) {
        assertEquals("A notification action must belong to this application", context.packageName, intent.creatorPackage)
        if(Build.VERSION.SDK_INT >= 31) assertTrue("All notification PendingIntents must be immutable", intent.isImmutable)
        intent.send()
    }

    private fun cleanup(ids: List<String>) {
        onMain { ids.forEach(coordinator::remove) }
        compose.waitUntil(20_000) { coordinator.items.value.none { it.id in ids } }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
}
