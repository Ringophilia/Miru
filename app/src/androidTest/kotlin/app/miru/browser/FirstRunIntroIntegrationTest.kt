// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual first-run gate, queued external link, persistent skip and Android's browser role chooser. */
@RunWith(AndroidJUnit4::class)
class FirstRunIntroIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as MiruApplication).container
    private val role get() = context.getSystemService(RoleManager::class.java)
    private val owners = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
    private lateinit var scenario: ActivityScenario<MainActivity>
    private fun label(id: Int) = context.getString(id)
    private fun title() = compose.onNodeWithText(label(R.string.intro_title))
    private fun browserVisible() {
        compose.waitUntil(10000) { runBlocking { container.settings.settings.first() }.introCompleted }
        title().assertDoesNotExist()
    }

    @Test fun skipPreservesTheExternalLinkAcrossRotationAndDoesNotRepeatOrOpenItTwice() {
        IntroOrigin().use { origin ->
            fixture(origin.url) {
                title().assertIsDisplayed()
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.getExternalFilesDir(null), "onboarding-preview.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                scenario.recreate()
                title().assertIsDisplayed()
                compose.onNodeWithText(label(R.string.intro_skip)).performClick()
                browserVisible()
                compose.waitUntil(20000) { container.browser.state.value.selectedTab?.title == "Intro destination" }
                val matching = container.browser.state.value.tabs.filter { it.url == origin.url }.map { it.id }
                assertEquals(1, matching.size)
                scenario.recreate(); browserVisible()
                assertEquals(matching, container.browser.state.value.tabs.filter { it.url == origin.url }.map { it.id })
            }
            runBlocking { container.library.history.delete(origin.url) }
        }
    }

    @Test fun cancellingTheRealRoleChooserStillFinishesTheIntro() = fixture {
        compose.onNodeWithText(label(R.string.intro_set_default)).performClick()
        awaitRoleDialog()
        assertFalse(role.isRoleHeld(RoleManager.ROLE_BROWSER))
        shell("input keyevent 4")
        compose.waitUntil(5000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName }
        browserVisible()
        scenario.recreate(); browserVisible()
        assertFalse(role.isRoleHeld(RoleManager.ROLE_BROWSER))
    }

    @Test fun grantingTheRealRoleRoutesAnExternalLinkAndAlreadyDefaultOffersStartBrowsing() {
        IntroOrigin().use { origin ->
            fixture {
                compose.onNodeWithText(label(R.string.intro_set_default)).performClick()
                awaitRoleDialog()
                clickSystemText("Miru")
                var confirmed = false
                compose.waitUntil(5000) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    if (root?.packageName?.toString() in owners) {
                        val nodes = root?.findAccessibilityNodeInfosByViewId("android:id/button1").orEmpty()
                        confirmed = nodes.any { it.isEnabled && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    }
                    confirmed
                }
                compose.waitUntil(5000) { role.isRoleHeld(RoleManager.ROLE_BROWSER) }
                browserVisible()
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(origin.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                compose.waitUntil(20000) { container.browser.state.value.selectedTab?.title == "Intro destination" }
                runBlocking { container.settings.setIntroCompleted(false) }
                compose.waitUntil(5000) { compose.onAllNodesWithText(label(R.string.intro_already_default)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(label(R.string.intro_already_default)).assertIsDisplayed()
                compose.onNodeWithText(label(R.string.intro_start)).performClick()
                browserVisible()
                assertTrue(role.isRoleHeld(RoleManager.ROLE_BROWSER))
            }
            runBlocking { container.library.history.delete(origin.url) }
        }
    }

    private fun fixture(external: String? = null, block: () -> Unit) {
        val previous = runBlocking { container.settings.settings.first() }.introCompleted
        val previousHolders = shell("cmd role get-role-holders --user 0 android.app.role.BROWSER").lineSequence().map { it.trim() }.filter { it.matches(Regex("[A-Za-z][A-Za-z0-9_.]*")) }.toList()
        if (context.packageName in previousHolders) shell("cmd role remove-role-holder --user 0 android.app.role.BROWSER ${context.packageName} 0")
        runBlocking { container.settings.setIntroCompleted(false) }
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (external != null) { intent.action = Intent.ACTION_VIEW; intent.data = Uri.parse(external) }
        scenario = ActivityScenario.launch(intent)
        val tabs = BrowserTabFixture()
        var failure: Throwable? = null
        try {
            compose.waitUntil(10000) { compose.onAllNodesWithText(label(R.string.intro_title)).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(30000) { container.browser.state.value.ready }
            scenario.onActivity { tabs.capture(container.browser) }
            block()
        } catch (problem: Throwable) { failure = problem
        } finally {
            fun cleanup(action: () -> Unit) {
                try { action() } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure!!.addSuppressed(problem)
                }
            }
            cleanup { if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() in owners) shell("input keyevent 4") }
            // ActivityScenario matches lifecycle callbacks against its original Intent filter.
            // MainActivity correctly replaces its Intent on a real external launch; restore the
            // harness filter only during teardown so it can observe the final destroy callback.
            cleanup { scenario.onActivity { it.intent = intent; tabs.closeCreatedTabs(container.browser) } }
            cleanup { scenario.close() }
            cleanup { runBlocking { container.settings.setIntroCompleted(previous) } }
            cleanup { shell("cmd role clear-role-holders --user 0 android.app.role.BROWSER 0") }
            previousHolders.forEach { cleanup { shell("cmd role add-role-holder --user 0 android.app.role.BROWSER $it 0") } }
        }
        failure?.let { throw it }
    }
    private fun awaitRoleDialog() = compose.waitUntil(10000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() in owners }
    private fun clickSystemText(text: String) {
        var clicked = false
        compose.waitUntil(5000) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root?.packageName?.toString() in owners) {
                root?.findAccessibilityNodeInfosByText(text).orEmpty().firstOrNull { it.text?.toString() == text }?.let { found ->
                    var node = found
                    while (!node.isClickable && node.parent != null) node = node.parent
                    clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            clicked
        }
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}

private class IntroOrigin : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url get() = "http://127.0.0.1:${server.localPort}/intro"
    private val running = AtomicBoolean(true)
    init { thread(isDaemon = true) { while (running.get()) {
        val socket = try { server.accept() } catch (_: Exception) { break }
        thread(isDaemon = true) { socket.use { try {
            val reader = it.getInputStream().bufferedReader(); reader.readLine()
            while (reader.readLine().isNotEmpty()) { }
            val bytes = "<!doctype html><title>Intro destination</title><h1>External link retained</h1>".toByteArray()
            it.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
        } catch (_: Exception) { } } }
    } } }
    override fun close() { running.set(false); server.close() }
}
