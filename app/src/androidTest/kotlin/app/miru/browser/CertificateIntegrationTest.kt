// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.miru.browser.engine.CertificateDetails
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native TLS data from an actual HTTPS connection, plus navigation and private lifecycle. */
@RunWith(AndroidJUnit4::class)
class CertificateIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val browser get() = (compose.activity.application as MiruApplication).container.browser
    private val context get() = compose.activity
    private fun https(private: Boolean = false): Pair<String, CertificateDetails> {
        var id = ""
        compose.runOnIdle { id = browser.newTab("https://example.com/", isPrivate = private) }
        compose.waitUntil(45000) { browser.state.value.tabs.firstOrNull { it.id == id }?.let { !it.loading && it.secure && it.certificate != null } == true }
        return id to browser.state.value.tabs.first { it.id == id }.certificate!!
    }
    private fun info() = compose.onNodeWithContentDescription(context.getString(app.miru.toolbar.R.string.toolbar_site_info)).performClick()
    private fun view() = compose.onNodeWithText(context.getString(R.string.certificate_view)).performClick()
    private fun back() = compose.runOnIdle { context.onBackPressedDispatcher.onBackPressed() }

    @Test fun actualHttpsCertificateShowsHostSubjectIssuerDatesAndFingerprint() = fixture {
        val (_, details) = https()
        assertEquals("example.com", details.host)
        assertTrue(details.subject.isNotBlank()); assertTrue(details.issuer.isNotBlank())
        assertEquals(95, details.sha256.length)
        assertTrue(details.validUntil > details.validFrom)
        info(); view()
        compose.onNodeWithTag("certificate_host").assertTextEquals(details.host)
        compose.onNodeWithTag("certificate_subject").assertTextEquals(details.subject)
        compose.onNodeWithTag("certificate_issuer").performScrollTo().assertTextEquals(details.issuer)
        compose.onNodeWithTag("certificate_from").performScrollTo().assertExists()
        compose.onNodeWithTag("certificate_until").performScrollTo().assertExists()
        compose.onNodeWithTag("certificate_sha256").performScrollTo().assertTextEquals(details.sha256)
        compose.onNodeWithText(context.getString(android.R.string.ok)).performClick()
        compose.onNodeWithTag("certificate_details").assertDoesNotExist(); back()
    }

    @Test fun httpNavigationDropsThePreviousCertificateAndClosesItsViewer() = fixture {
        val (id, _) = https(); info(); view()
        CertificateHttpOrigin().use { origin ->
            compose.runOnIdle { browser.navigate(origin.url) }
            compose.waitUntil(15000) { browser.state.value.tabs.firstOrNull { it.id == id }?.let { it.title == "Certificate HTTP fixture" && !it.loading } == true }
            val tab = browser.state.value.tabs.first { it.id == id }
            assertFalse(tab.secure); assertNull(tab.certificate)
            compose.onNodeWithTag("certificate_details").assertDoesNotExist()
            // The site information sheet remains open across the navigation.
            compose.onNodeWithText(context.getString(R.string.certificate_view)).assertIsNotEnabled()
            compose.onNodeWithText(context.getString(R.string.certificate_unavailable)).assertExists()
            back()
        }
    }

    @Test fun privateCertificateViewerDoesNotSurviveRecreationOrPrivateTabClosure() = fixture {
        assertTrue("Controlled fixture requires no pre-existing private tabs", browser.state.value.tabs.none { it.isPrivate })
        val (id, _) = https(private = true); info(); view()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("certificate_details").assertDoesNotExist()
        assertEquals(id, browser.state.value.selectedTabId)
        info(); view()
        compose.runOnIdle { browser.closePrivateTabsPermanently() }
        compose.waitUntil(5000) { browser.state.value.tabs.none { it.isPrivate } }
        compose.onNodeWithTag("certificate_details").assertDoesNotExist()
    }

    private fun fixture(block: () -> Unit) {
        compose.waitUntil(30000) { browser.state.value.ready }
        val tabs = BrowserTabFixture(); compose.runOnIdle { tabs.capture(browser) }
        try { block() }
        finally { compose.runOnIdle { tabs.closeCreatedTabs(browser) } }
    }
}

private class CertificateHttpOrigin : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url get() = "http://127.0.0.1:${server.localPort}/"
    private val running = AtomicBoolean(true)
    init { thread(isDaemon = true) { while (running.get()) {
        val socket = try { server.accept() } catch (_: Exception) { break }
        thread(isDaemon = true) { socket.use { try {
            it.soTimeout = 5000
            val reader = it.getInputStream().bufferedReader(); reader.readLine()
            while (reader.readLine().isNotEmpty()) { }
            val bytes = "<!doctype html><title>Certificate HTTP fixture</title><h1>HTTP page</h1>".toByteArray()
            it.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
        } catch (_: Exception) { } } }
    } } }
    override fun close() { running.set(false); server.close() }
}
