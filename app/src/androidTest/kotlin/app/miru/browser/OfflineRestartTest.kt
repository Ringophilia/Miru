// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.browser

import android.content.Intent
import android.os.Process
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.miru.offline.OfflineCoordinator
import app.miru.offline.OfflineSource
import app.miru.player.PlayerActivity
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MANUAL TWO-PHASE TEST. Exclude this class from routine discovery.
 * 1. Explicitly run OfflineRestartTest#prepareCompleted.
 * 2. adb shell am force-stop app.miru.browser
 * 3. Explicitly run OfflineRestartTest#verifyAfterForceStop.
 * This proves a completed offline download survives process death; it does not claim
 * interrupted-download resumption or a physical-device reboot has been tested.
 */
@RunWith(AndroidJUnit4::class)
class OfflineRestartTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val coordinator get() = OfflineCoordinator.get(context)
    private val marker get() = File(context.filesDir, "offline-restart-fixture-v1.txt")

    @Test fun prepareCompleted() {
        assertFalse("Run verifyAfterForceStop to clean the previous staged fixture before preparing again", marker.exists())
        OfflineOrigin(instrumentation.context).use { origin ->
            val selection = runBlocking { coordinator.prepare(OfflineSource(
                "${origin.url}/hls/master.m3u8", "Miru process restart fixture", mimeType = "application/vnd.apple.mpegurl", headers = origin.headers(),
            )) }
            val quality = selection.qualities.single { it.width == 160 && it.height == 90 }
            val id = onMain { coordinator.enqueue(selection, quality.id) }
            compose.waitUntil(60_000) {
                val row = coordinator.items.value.firstOrNull { it.id == id }
                if(row?.status == "failed") throw AssertionError("Staged offline fixture failed: ${row.error}")
                row?.status == "completed"
            }
            assertNotNull(runBlocking { coordinator.playback(id) })
            // Deliberately persist only this synthetic normal-mode identifier and seed PID.
            // The next process must reconstruct metadata and media entirely from its index/cache.
            marker.writeText("$id\n${Process.myPid()}\n")
            origin.close()
        }
    }

    @Test fun verifyAfterForceStop() {
        if(InstrumentationRegistry.getArguments().getString("miru.requireNoNetwork") == "true") {
            assertEquals("The explicit airplane-mode run must have airplane mode enabled", 1,
                android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0))
            val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
            compose.waitUntil(15_000) { connectivity.activeNetwork == null }
        }
        assertTrue("First explicitly run prepareCompleted, force-stop the app, then run this phase", marker.isFile)
        val values = marker.readLines()
        assertEquals(2, values.size)
        val id = values[0]
        assertNotEquals("The verification phase must run in a different application process", values[1].toInt(), Process.myPid())
        try {
            compose.waitUntil(20_000) { coordinator.items.value.any { it.id == id && it.status == "completed" } }
            val item = coordinator.items.value.single { it.id == id }
            assertFalse(item.isPrivate)
            assertNotNull("A new coordinator must reopen the persisted offline cache", runBlocking { coordinator.playback(id) })
            // The completed notification carries only this UUID to MainActivity. Drive the
            // same warm-Activity onNewIntent path after process death instead of constructing
            // a PlaybackRequest in the test and bypassing the browser's lookup/routing code.
            onMain { compose.activity.startActivity(Intent(compose.activity.intent)
                .putExtra("miru.offline.id", id)
                // Keep action/categories for ActivityScenario tracking, but discard the
                // rule's NEW_TASK/CLEAR_TASK flags so this exercises warm onNewIntent.
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
            var activity: PlayerActivity? = null
            try {
                compose.waitUntil(25_000) {
                    onMain {
                        activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<PlayerActivity>().firstOrNull()
                        activity != null
                    }
                }
                val playback = activity!!.playback
                assertEquals("MainActivity must resolve the notification UUID in the new process", id, playback.request.offlineId)
                compose.waitUntil(25_000) {
                    val state = playback.state.value
                    if(state.error || state.timedOut) throw AssertionError("Persisted offline source could not decode after process restart")
                    state.ready && state.playing && state.width == 160 && state.height == 90 && state.position >= 200
                }
                onMain { playback.seek(0); playback.resume() }
                compose.waitUntil(25_000) {
                    if(playback.state.value.error || playback.state.value.timedOut) throw AssertionError("A later persisted segment was missing after process restart")
                    onMain { playback.player?.playbackState == Player.STATE_ENDED }
                }
                assertTrue("The entire cached presentation must play, including its final segment", playback.state.value.position >= 11_500)
                assertTrue(playback.state.value.duration in 11_500..12_500)
            } finally { onMain { activity?.finish() } }
        } finally {
            onMain { coordinator.remove(id) }
            compose.waitUntil(20_000) { coordinator.items.value.none { it.id == id } }
            marker.delete()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
}
