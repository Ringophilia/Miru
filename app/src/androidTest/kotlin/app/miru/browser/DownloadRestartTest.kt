// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.downloads.DownloadCoordinator
import app.miru.downloads.DownloadItem
import app.miru.downloads.DownloadRequest
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit two-process acceptance protocol. Run prepare, force-stop the target, then run verify.
 * A host-owned deterministic origin must remain alive across the process death.
 * These manual acceptance phases are excluded from the normal suite unless restartPhase is set.
 */
@RunWith(AndroidJUnit4::class)
class DownloadRestartTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val coordinator get() = DownloadCoordinator.get(context)
    private val state get() = context.getSharedPreferences("miru_restart_acceptance", Context.MODE_PRIVATE)

    @Test fun prepareLargeDownload() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("restartPhase") == "prepare")
        ActivityScenario.launch(MainActivity::class.java).use {
            val origin = InstrumentationRegistry.getArguments().getString("fixtureOrigin") ?: "http://10.0.2.2:8744"
            val size = 500_000_000L
            val manifest = JSONObject(URL("$origin/manifest?bytes=$size").openConnection().apply {
                connectTimeout = 5_000; readTimeout = 10_000
            }.getInputStream().bufferedReader().use { reader -> reader.readText() })
            assertEquals(size, manifest.getLong("bytes"))
            coordinator.updatePreferences { preferences -> preferences.copy(threadsPerFile = 4, maxConcurrent = 1, directoryUri = null) }
            val id = coordinator.enqueue(DownloadRequest("$origin/download?bytes=$size&delay=8", fileName = "miru-process-restart.bin"))
            val partial = await(id, 30_000) { item -> item.status == "running" && item.downloadedBytes >= 4 * 1024 * 1024 }
            assertTrue(partial.downloadedBytes < size)
            assertTrue(state.edit().putString("id", id).putString("sha256", manifest.getString("sha256"))
                .putLong("size", size).putLong("checkpoint", partial.downloadedBytes).putString("origin", origin).commit())
            android.util.Log.i("MiruRestartAcceptance", "Prepared 500 MB partial download; checkpoint=${partial.downloadedBytes}")
        }
    }

    @Test fun verifyLargeDownloadAfterProcessDeath() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("restartPhase") == "verify")
        val id = checkNotNull(state.getString("id", null)) { "Run prepare phase first without uninstalling the application" }
        val expectedSize = state.getLong("size", -1)
        val checkpoint = state.getLong("checkpoint", -1)
        assertTrue(checkpoint > 0 && checkpoint < expectedSize)
        ActivityScenario.launch(MainActivity::class.java).use {
            // onStart must resume the existing Room record; do not enqueue or manually resume it here.
            val complete = await(id, 180_000) { item -> item.status == "completed" }
            assertEquals(expectedSize, complete.totalBytes)
            assertEquals(expectedSize, complete.downloadedBytes)
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            context.contentResolver.openInputStream(Uri.parse(checkNotNull(complete.destUri)))!!.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read; digest.update(buffer, 0, read)
                }
            }
            assertEquals(expectedSize, count)
            assertEquals(state.getString("sha256", null), digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 255) })
            val origin = checkNotNull(state.getString("origin", null))
            val requests = JSONArray(readUrl("$origin/requests"))
            val segmentSize = expectedSize / 4
            assertTrue("The restarted transfer must request a saved offset rather than restart every range", (0 until requests.length()).any { index ->
                val request = requests.getJSONObject(index)
                val from = Regex("bytes=(\\d+)-.*").matchEntire(request.optString("range"))?.groupValues?.get(1)?.toLong()
                request.optString("method") == "GET" && request.optString("path") == "/download" && from != null && from % segmentSize != 0L
            })
            android.util.Log.i("MiruRestartAcceptance", "Verified 500 MB after process death: byte count and SHA-256 match")
            coordinator.delete(id, deleteFile = true)
            awaitRemoved(id)
            state.edit().clear().commit()
        }
    }

    @Test fun prepareProtectedSessionForColdStart() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("restartPhase") == "cold-prepare")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val browser = (context.applicationContext as MiruApplication).container.browser
            waitFor(260_000) { browser.adblock.value.status in setOf(app.miru.browser.engine.AdblockStatus.READY, app.miru.browser.engine.AdblockStatus.FAILED) }
            assertEquals(browser.adblock.value.toString(), app.miru.browser.engine.AdblockStatus.READY, browser.adblock.value.status)
            val origin = InstrumentationRegistry.getArguments().getString("fixtureOrigin") ?: "http://10.0.2.2:8744"
            readUrl("$origin/control?reset=1")
            scenario.onActivity { browser.newTab("$origin/cold-page?warm=1") }
            waitFor(30_000) { browser.state.value.selectedTab?.title in setOf("Miru cold allowed", "Miru cold blocked") }
            val deadline = SystemClock.elapsedRealtime() + 120_000
            while(browser.state.value.selectedTab?.title != "Miru cold blocked" || browser.adblock.value.blockedCount == 0) {
                if(SystemClock.elapsedRealtime() >= deadline) error("Unable to prepare a filtered page: ${browser.adblock.value}")
                if(browser.state.value.selectedTab?.loading == false) scenario.onActivity { browser.reload() }
                SystemClock.sleep(2_000)
            }
            val before = adRequests(origin)
            assertTrue(state.edit().putString("coldOrigin", origin).putInt("coldRequests", before).commit())
            scenario.onActivity { browser.saveSession() }
            SystemClock.sleep(1_000)
        }
        SystemClock.sleep(1_000)
    }

    @Test fun restoredColdPageBlocksItsFirstAdRequest() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("restartPhase") == "cold-verify")
        val origin = checkNotNull(state.getString("coldOrigin", null)) { "Run cold-prepare first" }
        val before = state.getInt("coldRequests", -1)
        ActivityScenario.launch(MainActivity::class.java).use {
            val browser = (context.applicationContext as MiruApplication).container.browser
            // Observe the automatically restored page. Deliberately do not navigate, wait for uBO,
            // or reload before checking whether its very first script request was blocked.
            waitFor(60_000) { browser.state.value.selectedTab?.title in setOf("Miru cold allowed", "Miru cold blocked") }
            assertEquals("Cold restored page leaked its script", "Miru cold blocked", browser.state.value.selectedTab?.title)
            waitFor(10_000) { browser.adblock.value.blockedCount > 0 }
            assertEquals("The first restored-page ad request must not reach the server", before, adRequests(origin))
            android.util.Log.i("MiruRestartAcceptance", "Cold restored page blocked its first ad request without reloading")
            state.edit().clear().commit()
        }
    }

    private fun readUrl(url: String): String = URL(url).openConnection().apply {
        connectTimeout = 5_000; readTimeout = 10_000
    }.getInputStream().bufferedReader().use { it.readText() }

    private fun adRequests(origin: String): Int {
        val requests = JSONArray(readUrl("$origin/requests"))
        return (0 until requests.length()).count { requests.getJSONObject(it).optString("path") == "/ads/custom_ads.js" }
    }

    private fun waitFor(timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while(SystemClock.elapsedRealtime() < deadline) { if(condition()) return; SystemClock.sleep(50) }
        assertTrue("Acceptance condition timed out after $timeout ms", condition())
    }

    private fun await(id: String, timeout: Long, predicate: (DownloadItem) -> Boolean): DownloadItem {
        val deadline = SystemClock.elapsedRealtime() + timeout
        var last: DownloadItem? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            last = coordinator.downloads.value.firstOrNull { item -> item.id == id }
            if (last != null && predicate(last)) return last
            if (last?.status == "failed" || last?.status == "cancelled") error("Restart acceptance failed: status=${last.status}, error=${last.error}")
            SystemClock.sleep(50)
        }
        error("Restart acceptance timed out: $last")
    }

    private fun awaitRemoved(id: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline && coordinator.downloads.value.any { it.id == id }) SystemClock.sleep(20)
        assertFalse(coordinator.downloads.value.any { it.id == id })
    }
}
