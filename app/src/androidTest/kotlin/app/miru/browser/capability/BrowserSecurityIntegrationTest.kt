// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.capability

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.miru.browser.MainActivity
import app.miru.browser.MiruApplication
import app.miru.browser.R
import app.miru.data.BrowserSecuritySettings
import app.miru.data.SecureDnsMode
import app.miru.settings.R as SettingsR
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Browser settings exercised through the UI and observed on real Gecko connections. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class BrowserSecurityIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as MiruApplication).container
    private val browser get() = container.browser
    private fun label(id: Int) = compose.activity.getString(id)
    private fun security() = runBlocking { container.settings.settings.first().security }

    @Before fun startBrowser() {
        val skip = label(R.string.intro_skip)
        compose.waitUntil(15_000) {
            runBlocking { container.settings.settings.first().introCompleted } ||
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText(skip).performClick()
        }
        compose.waitUntil(30_000) { browser.state.value.ready }
        assertEquals(BrowserSecuritySettings(), security().copy(customDnsUrl = null))
    }

    @Test fun thirdPartySwitchChangesCookieOnAnEmbeddedRequest() = SecurityOrigin().use { origin ->
        openSettings()
        val setting = toggle(SettingsR.string.security_block_third_party)
        setting.assertIsOn()
        closeSettings()
        navigate(origin.url("localhost", "/seed"))
        awaitPage("/seed")
        navigate(origin.url("localhost", "/first-party"))
        awaitPage("/first-party")
        assertEquals(true, origin.cookies["/first-party"])

        navigate(origin.url("127.0.0.1", "/embed-blocked"))
        compose.waitUntil(15_000) { origin.cookies.containsKey("/third-blocked") }
        assertEquals(false, origin.cookies["/third-blocked"])

        try {
            openSettings()
            toggle(SettingsR.string.security_block_third_party).performClick()
            compose.waitUntil(5_000) { !security().blockThirdPartyCookies }
            toggle(SettingsR.string.security_block_third_party).assertIsOff()
            closeSettings()
            navigate(origin.url("127.0.0.1", "/embed-allowed"))
            compose.waitUntil(15_000) { origin.cookies.containsKey("/third-allowed") }
            assertEquals(true, origin.cookies["/third-allowed"])
        } finally {
            runBlocking { container.settings.setBlockThirdPartyCookies(true) }
        }
    }

    @Test fun httpsOnlyUpgradesNavigationAndOffAllowsHttpAgain() = SecurityOrigin().use { origin ->
        // A public hostname resolving to loopback exercises normal HTTPS upgrade rules.
        // Only DNS is external. All HTTP and TLS traffic goes to the local fixture.
        val host = "127-0-0-1.sslip.io"
        val addresses = InetAddress.getAllByName(host).filterIsInstance<Inet4Address>()
        assertTrue("Expected $host to resolve only to 127.0.0.1", addresses.isNotEmpty() &&
            addresses.all { it.hostAddress == "127.0.0.1" })
        navigate(origin.url(host, "/initial-http"))
        awaitPage("/initial-http")

        openSettings()
        val setting = toggle(SettingsR.string.security_https_only)
        setting.assertIsOff()
        try {
            setting.performClick()
            compose.waitUntil(5_000) { security().httpsOnly }
            setting.assertIsOn()
            closeSettings()
            val priorTlsConnections = origin.tlsConnections.get()
            navigate(origin.url(host, "/upgrade"))
            compose.waitUntil(15_000) {
                origin.tlsConnections.get() > priorTlsConnections || origin.cookies.containsKey("/upgrade")
            }
            assertTrue("Expected a TLS connection with HTTPS-only enabled", origin.tlsConnections.get() > priorTlsConnections)

            openSettings()
            toggle(SettingsR.string.security_https_only).performClick()
            compose.waitUntil(5_000) { !security().httpsOnly }
            closeSettings()
            navigate(origin.url(host, "/plain-http"))
            awaitPage("/plain-http")
            assertTrue(origin.cookies.containsKey("/plain-http"))
            assertFalse("The upgraded navigation loaded the plaintext document", origin.cookies.containsKey("/upgrade"))
        } finally {
            runBlocking { container.settings.setHttpsOnly(false) }
        }
    }

    @Test fun secureDnsValidatesAndContactsTheChosenHttpsProvider() = SecurityOrigin().use { origin ->
        val provider = "https://127.0.0.1:${origin.port}/dns-query"
        openSettings()
        val dns = { compose.onNode(hasText(label(SettingsR.string.security_dns)) and hasClickAction()).performScrollTo() }
        dns().assert(hasText(label(SettingsR.string.security_dns_auto)))
        try {
            dns().performClick()
            compose.onNodeWithText(label(SettingsR.string.security_dns_off)).performClick()
            compose.waitUntil(5_000) { security().secureDnsMode == SecureDnsMode.OFF }
            dns().assert(hasText(label(SettingsR.string.security_dns_off)))
            dns().performClick()
            compose.onNodeWithText(label(SettingsR.string.security_dns_custom)).performClick()
            val field = compose.onNodeWithTag("secure_dns_url")
            val save = compose.onNodeWithTag("secure_dns_save")
            field.performTextInput(provider.replace("https", "http"))
            save.assertIsNotEnabled()
            field.performTextReplacement(provider)
            save.performClick()
            compose.waitUntil(5_000) {
                val saved = security()
                saved.secureDnsMode == SecureDnsMode.CUSTOM && saved.customDnsUrl == provider
            }
            compose.activityRule.scenario.recreate()
            dns().assert(hasText(label(SettingsR.string.security_dns_custom)))
            closeSettings()
            navigate("https://miru-${UUID.randomUUID()}.example.org/")
            compose.waitUntil(15_000) { origin.tlsConnections.get() > 0 }

            openSettings()
            dns().performClick()
            compose.onNodeWithText(label(SettingsR.string.security_dns_auto)).performClick()
            compose.waitUntil(5_000) { security().secureDnsMode == SecureDnsMode.AUTOMATIC }
        } finally {
            runBlocking { container.settings.setSecureDns(SecureDnsMode.AUTOMATIC, null) }
        }
    }

    private fun openSettings() {
        compose.runOnIdle { browser.newTab() }
        compose.onAllNodesWithText(label(app.miru.ntp.R.string.ntp_search_hint))[1].performClick()
        val editor = compose.onNode(hasSetTextAction())
        editor.performTextInput("miru://settings")
        editor.performImeAction()
    }

    private fun closeSettings() = compose.onNodeWithContentDescription(label(SettingsR.string.back)).performClick()

    private fun toggle(id: Int) = compose.onNodeWithContentDescription(label(id)).performScrollTo()

    private fun navigate(url: String) = compose.runOnIdle { browser.navigate(url) }

    private fun awaitPage(path: String) = compose.waitUntil(15_000) { browser.state.value.selectedTab?.title == path }
}

private class SecurityOrigin : Closeable {
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port get() = listener.localPort
    val cookies = ConcurrentHashMap<String, Boolean>()
    val tlsConnections = AtomicInteger()

    init {
        thread(isDaemon = true, name = "miru-security-origin") {
            while (!listener.isClosed) {
                val connection = try { listener.accept() } catch (_: IOException) { break }
                thread(isDaemon = true) { connection.use(::respond) }
            }
        }
    }

    fun url(host: String, path: String) = "http://$host:$port$path"

    private fun respond(connection: Socket) {
        try {
            connection.soTimeout = 5_000
            val input = connection.getInputStream().buffered()
            input.mark(1)
            val first = input.read()
            if (first < 0) return
            if (first == 0x16) {
                tlsConnections.incrementAndGet()
                return
            }
            input.reset()
            val reader = input.bufferedReader()
            val request = reader.readLine()?.split(' ') ?: return
            val path = request.getOrNull(1) ?: return
            val headers = generateSequence(reader::readLine).takeWhile { it.isNotEmpty() }.toList()
            if (request.first() == "GET") {
                cookies[path] = headers.any { it.startsWith("Cookie:", ignoreCase = true) && "miru_security=present" in it }
            }
            val iframe = if (path.startsWith("/embed-")) {
                "<iframe src='${url("localhost", path.replace("/embed-", "/third-"))}'></iframe>"
            } else ""
            val body = "<!doctype html><title>$path</title>$iframe"
            val cookie = if (path == "/seed") "Set-Cookie: miru_security=present; Path=/; Secure; SameSite=None\r\n" else ""
            connection.getOutputStream().write((
                "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nCache-Control: no-store\r\n" +
                    "${cookie}Content-Length: ${body.length}\r\nConnection: close\r\n\r\n" +
                    if (request.first() == "HEAD") "" else body
                ).toByteArray())
        } catch (_: IOException) {
            // Gecko can cancel an unused speculative connection.
        }
    }

    override fun close() = listener.close()
}
