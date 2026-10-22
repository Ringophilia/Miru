// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.miru.downloads.DownloadCoordinator
import app.miru.downloads.DownloadItem
import app.miru.downloads.DownloadRequest
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Reads real Gecko cookies through the native bridge; it never competes for downloadRequests. */
@RunWith(AndroidJUnit4::class)
class CookieBridgeIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val browser get() = (compose.activity.application as MiruApplication).container.browser
    private val context get() = compose.activity.applicationContext
    private val downloads get() = DownloadCoordinator.get(context)

    @Test fun retryAfterLoginRefreshUsesTheNewGeckoCookie() {
        CookieTestOrigin().use { origin ->
            compose.runOnIdle { browser.newTab("${origin.url}/regular") }
            awaitPage("Miru regular cookie")
            val staleHeaders = awaitRegularCookie(origin)
            origin.rotateRegularCookie()
            compose.runOnIdle { browser.navigate("${origin.url}/regular?rotated=1") }
            awaitRegularCookie(origin)
            val id = downloads.enqueue(DownloadRequest("${origin.url}/protected.bin", fileName = "miru-cookie-retry.bin", headers = staleHeaders, sourcePage = "${origin.url}/regular"))
            try {
                compose.waitUntil(20_000) { downloads.downloads.value.firstOrNull { it.id == id }?.status == "failed" }
                assertEquals("HTTP_403", downloads.downloads.value.first { it.id == id }.error)
                downloads.resume(id)
                compose.waitUntil(5_000) { downloads.downloads.value.firstOrNull { it.id == id }?.status != "failed" }
                val complete = awaitDownload(id)
                assertArrayEquals(origin.payload, context.contentResolver.openInputStream(Uri.parse(checkNotNull(complete.destUri)))!!.use { it.readBytes() })
            } finally {
                downloads.delete(id, deleteFile = true)
                compose.runOnIdle { browser.newTab("${origin.url}/clear") }
                awaitPage("Miru cookies cleared")
            }
        }
    }

    @Test fun regularCookieCanAuthenticateAnOrdinaryFileDownload() {
        CookieTestOrigin().use { origin ->
            compose.runOnIdle { browser.newTab("${origin.url}/regular") }
            awaitPage("Miru regular cookie")
            val headers = awaitRegularCookie(origin)
            assertEquals("${origin.url}/regular", headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value)
            val id = downloads.enqueue(DownloadRequest(
                url = "${origin.url}/protected.bin",
                fileName = "miru-cookie-test.bin",
                headers = headers,
                sourcePage = "${origin.url}/regular",
            ))
            try {
                val complete = awaitDownload(id)
                val bytes = context.contentResolver.openInputStream(Uri.parse(checkNotNull(complete.destUri)))!!.use { it.readBytes() }
                assertArrayEquals(origin.payload, bytes)
                assertEquals(origin.payload.size.toLong(), complete.downloadedBytes)
            } finally {
                downloads.delete(id, deleteFile = true)
                compose.runOnIdle { browser.newTab("${origin.url}/clear") }
                awaitPage("Miru cookies cleared")
            }
        }
    }

    @Test fun privateCookieJarDoesNotLeakIntoOrdinaryDownloadHeaders() {
        CookieTestOrigin().use { origin ->
            compose.runOnIdle { browser.newTab("${origin.url}/regular") }
            awaitPage("Miru regular cookie")
            awaitRegularCookie(origin)
            var privateTabId: String? = null
            try {
                compose.runOnIdle { privateTabId = browser.newTab("${origin.url}/private", isPrivate = true) }
                awaitPage("Miru private cookie")
                compose.runOnIdle { browser.navigate("${origin.url}/private-check") }
                awaitPage("Miru private cookie check")
                val privateRequest = origin.privateCookieHeader.get().orEmpty()
                assertTrue("The private session must actually receive its own cookie", privateRequest.contains(origin.privateCookie))
                assertFalse("The private session must not receive ordinary-session cookies", privateRequest.contains(origin.regularCookie))

                // This API deliberately reads the ordinary jar even while the selected tab is private.
                val normalHeaders = runBlocking { browser.downloadHeaders("${origin.url}/protected.bin", "${origin.url}/regular") }
                assertNotNull("The bridge must respond for the normal jar", normalHeaders)
                val cookieHeader = normalHeaders!!.entries.firstOrNull { it.key.equals("Cookie", true) }?.value.orEmpty()
                assertTrue("Ordinary cookies must remain in the ordinary jar", cookieHeader.contains(origin.regularCookie))
                assertFalse("Private cookies must never be returned for ordinary resumed downloads", cookieHeader.contains(origin.privateCookie))
            } finally {
                compose.runOnIdle { privateTabId?.let(browser::closeTab); browser.newTab("${origin.url}/clear") }
                awaitPage("Miru cookies cleared")
            }
        }
    }

    private fun awaitPage(title: String) {
        compose.waitUntil(30_000) { browser.state.value.selectedTab?.let { it.title == title && !it.loading } == true }
    }

    private fun awaitRegularCookie(origin: CookieTestOrigin): Map<String, String> {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val headers = runBlocking { browser.downloadHeaders("${origin.url}/protected.bin", "${origin.url}/regular") }
            if (headers?.entries?.firstOrNull { it.key.equals("Cookie", true) }?.value?.contains(origin.regularCookie) == true) return headers
            SystemClock.sleep(100)
        }
        error("The normal Gecko cookie did not become available through the native bridge")
    }

    private fun awaitDownload(id: String): DownloadItem {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val item = downloads.downloads.value.firstOrNull { it.id == id }
            if (item?.status == "completed") return item
            if (item?.status == "failed") error("Authenticated download failed: ${item.error}")
            SystemClock.sleep(50)
        }
        error("Authenticated download did not complete")
    }
}

/** Reachable device-local origin; a 403 proves missing credentials cannot masquerade as a download. */
private class CookieTestOrigin : Closeable {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val open = AtomicBoolean(true)
    private val suffix = System.nanoTime().toString()
    @Volatile var regularCookie = "miru_regular_$suffix=normal_$suffix"
        private set
    fun rotateRegularCookie() { regularCookie = "miru_regular_$suffix=rotated_$suffix" }
    val privateCookie = "miru_private_$suffix=private_$suffix"
    val privateCookieHeader = AtomicReference<String?>()
    val payload = ByteArray(256 * 1024) { ((it * 17 + 43) and 255).toByte() }
    val url = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true, name = "miru-cookie-test-origin") {
            while (open.get()) runCatching {
                socket.accept().use { client ->
                    client.soTimeout = 5_000
                    val reader = client.getInputStream().bufferedReader()
                    val request = reader.readLine()?.split(' ') ?: return@use
                    val method = request[0]
                    val path = request.getOrNull(1)?.substringBefore('?')
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: return@use
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    if (path == "/private-check") privateCookieHeader.set(headers["cookie"])
                    val authenticated = path != "/protected.bin" || headers["cookie"].orEmpty().contains(regularCookie)
                    val title = when (path) {
                        "/private" -> "Miru private cookie"
                        "/private-check" -> "Miru private cookie check"
                        "/clear" -> "Miru cookies cleared"
                        else -> "Miru regular cookie"
                    }
                    val body = if (path == "/protected.bin" && authenticated) payload else "<!doctype html><title>$title</title><h1>$title</h1>".toByteArray()
                    val response = buildString {
                        append("HTTP/1.1 ${if (authenticated) "200 OK" else "403 Forbidden"}\r\n")
                        append("Content-Type: ${if (path == "/protected.bin") "application/octet-stream" else "text/html"}; charset=utf-8\r\n")
                        append("Content-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n")
                        when (path) {
                            "/regular" -> append("Set-Cookie: $regularCookie; Path=/; SameSite=Lax\r\n")
                            "/private" -> append("Set-Cookie: $privateCookie; Path=/; SameSite=Lax\r\n")
                            "/clear" -> append("Set-Cookie: ${regularCookie.substringBefore('=')}=; Path=/; Max-Age=0\r\n")
                        }
                        if (path == "/protected.bin") append("Content-Disposition: attachment; filename=miru-cookie-test.bin\r\n")
                        append("\r\n")
                    }
                    client.getOutputStream().apply { write(response.toByteArray()); if (method != "HEAD") write(body); flush() }
                }
            }
        }
    }

    override fun close() { open.set(false); socket.close() }
}
