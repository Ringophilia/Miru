// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual system-posted actions, background receiver and Gecko private cookies. */
@RunWith(AndroidJUnit4::class)
class PrivateTabsNotificationIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as MiruApplication).container
    private val browser get() = container.browser
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun posted() = manager.activeNotifications.singleOrNull { it.tag == PrivateTabsNotifications.TAG && it.id == PrivateTabsNotifications.ID }?.notification
    private fun awaitNotification(): Notification {
        compose.waitUntil(5000) { posted() != null }
        return posted()!!
    }
    private fun send(intent: PendingIntent) {
        assertEquals(context.packageName, intent.creatorPackage)
        if (Build.VERSION.SDK_INT >= 31) { assertTrue(intent.isImmutable); assertTrue(intent.isBroadcast) }
        intent.send()
    }

    @Test fun backgroundActionClosesPrivatePagesWithoutUndoOrCookiesAndPreservesOrdinaryTabs() = fixture {
        PrivateNotificationOrigin().use { origin ->
            val normalIds = browser.state.value.tabs.map { it.id }.toSet()
            compose.runOnIdle { browser.newTab(origin.url + "/set?private_secret=one", isPrivate = true) }
            compose.waitUntil(20000) { browser.state.value.selectedTab?.title == "Private secret sentinel" }
            compose.runOnIdle { browser.newTab(origin.url + "/check?private_secret=two", isPrivate = true) }
            compose.waitUntil(20000) { browser.state.value.selectedTab?.title == "cookie:private_sentinel=1" }
            val notification = awaitNotification()
            val text = notification.extras.toString()
            assertFalse(text.contains("private_secret")); assertFalse(text.contains(origin.url)); assertFalse(text.contains("sentinel"))
            assertEquals(Notification.VISIBILITY_SECRET, notification.visibility)
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(context.getString(R.string.private_close_all), notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            val close = notification.actions.single().actionIntent
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            try {
                send(close)
                compose.waitUntil(10000) { browser.state.value.tabs.none { it.isPrivate } && posted() == null }
                assertEquals(normalIds, browser.state.value.tabs.map { it.id }.toSet())
            } finally { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
            compose.runOnIdle { browser.undoClose() }
            assertTrue(browser.state.value.tabs.none { it.isPrivate })
            compose.runOnIdle { browser.newTab(origin.url + "/check", isPrivate = true) }
            compose.waitUntil(20000) { browser.state.value.selectedTab?.title?.startsWith("cookie:") == true }
            assertEquals("cookie:", browser.state.value.selectedTab?.title)
        }
    }

    @Test fun ordinaryCloseUndoAndNotificationBodyFollowThePrivateTabLifetime() = fixture {
        var id = ""
        compose.runOnIdle { id = browser.newTab(isPrivate = true) }
        awaitNotification()
        compose.runOnIdle { browser.closeTab(id) }
        compose.waitUntil(5000) { posted() == null }
        compose.runOnIdle { browser.undoClose() }
        compose.waitUntil(5000) { browser.state.value.tabs.any { it.id == id } }
        val notification = awaitNotification()
        send(notification.contentIntent)
        compose.waitUntil(5000) { browser.state.value.tabs.none { it.isPrivate } && posted() == null }
        compose.runOnIdle { browser.undoClose() }
        assertTrue(browser.state.value.tabs.none { it.isPrivate })
    }

    private fun fixture(block: () -> Unit) {
        compose.waitUntil(30000) { browser.state.value.ready }
        assertTrue("Controlled device must not contain pre-existing private tabs", browser.state.value.tabs.none { it.isPrivate })
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(manager.areNotificationsEnabled())
        val oldPrompted = runBlocking { container.settings.settings.first() }.privateNotificationPrompted
        runBlocking { container.settings.setPrivateNotificationPrompted(true) }
        val tabs = BrowserTabFixture()
        compose.runOnIdle { tabs.capture(browser) }
        try { block() }
        finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle { tabs.closeCreatedTabs(browser); browser.closePrivateTabsPermanently(); container.privateTabsNotifications.refresh() }
            runBlocking { container.settings.setPrivateNotificationPrompted(oldPrompted) }
        }
    }
}

private class PrivateNotificationOrigin : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url get() = "http://127.0.0.1:${server.localPort}"
    private val running = AtomicBoolean(true)
    init { thread(isDaemon = true) { while (running.get()) {
        val socket = try { server.accept() } catch (_: Exception) { break }
        thread(isDaemon = true) { socket.use { try {
            it.soTimeout = 5000
            val reader = it.getInputStream().bufferedReader()
            val path = reader.readLine().split(' ')[1]
            while (reader.readLine().isNotEmpty()) { }
            val body = if (path.startsWith("/check")) "<script>document.title='cookie:'+document.cookie</script>" else
                "<title>Private secret sentinel</title><script>document.cookie='private_sentinel=1; path=/'</script><h1>Private page</h1>"
            val bytes = ("<!doctype html>" + body).toByteArray()
            it.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush()
            }
        } catch (_: Exception) { } } }
    } } }
    override fun close() { running.set(false); server.close() }
}
