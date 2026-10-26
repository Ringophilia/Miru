// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import mozilla.components.concept.engine.EngineView
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Gecko requests and long-press/copy requests are independently observed by the origin. */
@RunWith(AndroidJUnit4::class)
class ContextImagePolicyIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val browser get() = (compose.activity.application as MiruApplication).container.browser
    private val tabFixture = BrowserTabFixture()

    @Before fun rememberPreExistingTabsAfterRestoration() {
        compose.waitUntil(30_000) { browser.state.value.ready }
        compose.runOnIdle { tabFixture.capture(browser) }
    }

    @After fun closeOnlyThisMethodsTabsAndRestoreSelection() {
        compose.runOnIdle { tabFixture.closeCreatedTabs(browser) }
    }

    @Test fun documentHttpNoReferrerRemainsAbsentDuringPrivateImageCopy() {
        PolicyOrigin().use { image -> PolicyOrigin().use { page ->
            page.document = { "<title>Policy HTTP private</title><img src='${image.url}/image.png' " +
                "onload=\"requestAnimationFrame(()=>requestAnimationFrame(()=>{document.title='Policy HTTP private image ready'}))\">" }
            page.referrerPolicy = "no-referrer"
            open("${page.url}/document?private=secret", "Policy HTTP private image ready", private = true)
            assertTrue("Private windows must retain screenshot protection", compose.activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
            compose.waitUntil(8_000) { image.images.isNotEmpty() }
            assertNull("Initial Gecko image must obey the document HTTP policy", image.images.first().referrer)
            copy(image, 45f)
            assertNull("Native private copy must retain no-referrer", image.images.last().referrer)
            assertTrue(context.getSystemService(ClipboardManager::class.java).primaryClip!!.description.extras!!.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE))
            val beforeReload = image.images.size
            compose.runOnIdle { browser.reload() }
            compose.waitUntil(25_000) { image.images.size > beforeReload && browser.state.value.selectedTab?.let { !it.loading && it.title == "Policy HTTP private image ready" } == true }
            assertNull("Reloaded Gecko image must still retain no-referrer", image.images.last().referrer)
            copy(image, 45f)
            assertNull("A stale same-URL relay must not block or widen the reloaded image copy", image.images.last().referrer)
        } }
    }

    @Test fun privateDocumentsWithTheObservedDefaultPolicyCopyAfterAnOrdinaryDocument() {
        PolicyOrigin().use { image -> PolicyOrigin().use { page ->
            page.document = { "<title>Policy default loading</title><img src='${image.url}/image.png' " +
                "onload=\"requestAnimationFrame(()=>requestAnimationFrame(()=>{document.title='Policy default ready'}))\">" }
            val url = "${page.url}/document?path=must-not-cross-origins"
            open(url, "Policy default ready")
            val ordinaryId = checkNotNull(browser.state.value.selectedTabId)
            copy(image, 45f)
            assertEquals("${page.url}/", image.images.last().referrer)
            repeat(3) {
                open(url, "Policy default ready", private = true)
                val privateId = checkNotNull(browser.state.value.selectedTabId)
                assertTrue(compose.activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                try {
                    copy(image, 45f)
                    assertEquals("An observed empty HTTP policy must use the actual default, without a private path or query",
                        "${page.url}/", image.images.last().referrer)
                    assertTrue(context.getSystemService(ClipboardManager::class.java).primaryClip!!.description.extras!!
                        .getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE))
                } finally { compose.runOnIdle { browser.closeTab(privateId); browser.selectTab(ordinaryId) } }
            }
        } }
    }

    @Test fun metaNoReferrerOverridesTheDocumentHttpBaseline() {
        PolicyOrigin().use { image -> PolicyOrigin().use { page ->
            page.document = { "<meta name=referrer content=no-referrer><title>Policy meta</title><img src='${image.url}/image.png'>" }
            page.referrerPolicy = "unsafe-url"
            open("${page.url}/document?private=meta-secret", "Policy meta")
            awaitImage(45f)
            compose.waitUntil(8_000) { image.images.isNotEmpty() }
            assertNull("Initial Gecko image must obey the meta override", image.images.first().referrer)
            copy(image, 45f)
            assertNull("Native copy must obey the processed meta override", image.images.last().referrer)
        } }
    }

    @Test fun aLaterPressInSrcdocNeverBorrowsThePreviousFramesPolicy() {
        PolicyOrigin().use { image -> PolicyOrigin().use { page ->
            page.referrerPolicy = "unsafe-url"
            val nested = "<body style=margin:0><img src='${image.url}/image.png' referrerpolicy=no-referrer style='width:80px;height:80px'>"
            page.document = { "<title>Policy srcdoc</title><img src='${image.url}/image.png'>" +
                "<iframe style='display:block;border:0;width:160px;height:110px' srcdoc=\"${nested.replace("\"", "&quot;")}\"></iframe>" }
            open("${page.url}/document?old-frame=secret", "Policy srcdoc")
            awaitImage(45f)
            // Gecko may coalesce simultaneous identical image URLs across frames.
            // Verify the second actual painted target rather than demand two fetches.
            awaitImage(125f)
            longPress(45f)
            compose.waitUntil(8_000) { browser.contextMenus.state.value?.imageUrl == "${image.url}/image.png" }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(8_000) { browser.contextMenus.state.value == null && compose.activity.hasWindowFocus() }
            val before = image.images.size
            longPress(125f)
            compose.waitUntil(8_000) { browser.contextMenus.state.value?.imageUrl == "${image.url}/image.png" }
            compose.onNodeWithTag("context_copy_image").performScrollTo().performClick()
            val failed = context.getString(R.string.context_image_failed)
            compose.waitUntil(20_000) { image.images.size > before || compose.onAllNodesWithText(failed).fetchSemanticsNodes().isNotEmpty() }
            // Unavailable is valid until this frame type has its own trusted helper.
            // Sending the old top document's path/query is never valid.
            image.images.drop(before).forEach { assertNull("A new srcdoc press must not borrow the old frame's Referer", it.referrer) }
        } }
    }

    @Test fun identicalSourcesUseTheActuallyPressedImagesOwnPolicy() {
        PolicyOrigin().use { image -> PolicyOrigin().use { page ->
            page.referrerPolicy = "no-referrer"
            page.document = { "<title>Policy identical sources</title>" +
                "<img src='${image.url}/image.png' referrerpolicy=no-referrer>" +
                "<div style='height:30px'></div><img src='${image.url}/image.png' referrerpolicy=unsafe-url>" }
            val address = "${page.url}/document?allowed=exact-image-policy"
            open(address, "Policy identical sources")
            awaitImage(45f); awaitImage(175f)
            copy(image, 175f)
            assertEquals("The second actual image explicitly permits its full source URL", address, image.images.last().referrer)
            copy(image, 45f)
            assertNull("The first identical-src image must retain its different policy", image.images.last().referrer)
        } }
    }

    @Test fun sameOriginIframeImageUsesTheActualFramesStrictPolicyAndAddress() {
        PolicyOrigin().use { frame -> PolicyOrigin().use { parent ->
            frame.referrerPolicy = "strict-origin-when-cross-origin"
            frame.document = { "<title>Policy frame</title><img src='${frame.url}/image.png'>" }
            val frameAddress = "${frame.url}/frame?source=actual-frame"
            parent.referrerPolicy = "no-referrer"
            parent.document = { "<title>Policy parent</title><iframe style='border:0;width:300px;height:230px' src='$frameAddress'></iframe>" }
            open("${parent.url}/parent?never=parent-address", "Policy parent")
            awaitImage(45f)
            compose.waitUntil(8_000) { frame.images.isNotEmpty() }
            assertEquals("Initial Gecko request must use the actual iframe", frameAddress, frame.images.first().referrer)
            copy(frame, 45f)
            assertEquals("Native same-origin copy must remain functional with the iframe referrer", frameAddress, frame.images.last().referrer)
        } }
    }

    @Test fun sameDocumentHistoryNavigationRetainsTheObservedHttpBaseline() {
        PolicyOrigin().use { origin ->
            origin.referrerPolicy = "strict-origin-when-cross-origin"
            origin.document = { "<title>Policy history original</title><img src='${origin.url}/image.png'>" +
                "<button style='display:block;height:90px' onclick=\"history.pushState(null,'','/changed?state=live');document.title='Policy history changed'\">Change address</button>" }
            open("${origin.url}/original?state=before", "Policy history original")
            awaitImage(45f)
            touch(145f, 60)
            compose.waitUntil(8_000) { browser.state.value.selectedTab?.let { it.title == "Policy history changed" && it.url == "${origin.url}/changed?state=live" } == true }
            copy(origin, 45f)
            assertEquals("A same-document navigation must retain its policy and use its current source address",
                "${origin.url}/changed?state=live", origin.images.last().referrer)
        }
    }

    @Test fun anUnobservableIntermediateMetaPolicyNeverFallsBackToTheHttpPolicy() {
        PolicyOrigin().use { origin ->
            origin.referrerPolicy = "unsafe-url"
            origin.document = { "<meta name=referrer content=unsafe-url><title>Policy mutable meta</title><img src='${origin.url}/image.png'>" +
                "<button style='display:block;height:90px' onclick=\"const m=document.querySelector('meta[name=referrer]');m.content='no-referrer';m.content='invalid';document.title='Policy intermediate hidden'\">Change policy</button>" }
            open("${origin.url}/document?must=remain-private", "Policy mutable meta")
            awaitImage(45f)
            touch(145f, 60)
            compose.waitUntil(8_000) { browser.state.value.selectedTab?.title == "Policy intermediate hidden" }
            val before = origin.images.size
            longPress(45f)
            compose.waitUntil(8_000) { browser.contextMenus.state.value?.imageUrl == "${origin.url}/image.png" }
            compose.onNodeWithTag("context_copy_image").performScrollTo().performClick()
            val failed = context.getString(R.string.context_image_failed)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(failed).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(failed).assertIsDisplayed()
            assertEquals("Unknown native meta state must not cause a new request with an invented Referer", before, origin.images.size)
        }
    }

    @Test fun anInsertedMetaWithAnUnobservedValidValueCannotWidenTheActualGeckoPolicy() {
        PolicyOrigin().use { origin ->
            origin.referrerPolicy = "unsafe-url"
            val cookieName = "miru_meta_probe_${System.nanoTime()}"
            val content = "<script>document.cookie='$cookieName=ready; Path=/; SameSite=Lax'</script><img src='${origin.url}/image.png'>" +
                "<button style='display:block;height:90px' onclick=\"const m=document.createElement('meta');" +
                "m.name='referrer';m.content='no-referrer';document.head.append(m);m.content='invalid';" +
                "const probe=new Image();probe.onload=()=>{document.title='Policy inserted observed'};" +
                "probe.src='${origin.url}/image.png?after-insert=1';document.body.append(probe)\">Change policy</button>"
            origin.document = { "<title>Policy inserted meta</title>$content" }
            val pageUrl = "${origin.url}/document?must=remain-private"
            open(pageUrl, "Policy inserted meta")
            awaitImage(45f)
            assertEquals("The initial Gecko request must establish the wider HTTP baseline", pageUrl, origin.images.first().referrer)
            // Establish a real working bridge, then a new HTTP document it can observe.
            // Otherwise an unavailable startup baseline could hide this meta regression.
            val headers = kotlinx.coroutines.runBlocking { browser.headersForMediaUrl(origin.url, false) }
            assertTrue("The background must actually answer with the synthetic Gecko cookie",
                headers.entries.any { it.key.equals("Cookie", true) && it.value.contains("$cookieName=ready") })
            val beforeReload = origin.images.size
            origin.document = { "<title>Policy baseline observed</title>$content" }
            compose.runOnIdle { browser.reload() }
            compose.waitUntil(20_000) { origin.images.size > beforeReload && browser.state.value.selectedTab?.let {
                it.title == "Policy baseline observed" && !it.loading
            } == true }
            copy(origin, 45f)
            assertEquals("A successful native copy proves the wider policy is available before mutation", pageUrl, origin.images.last().referrer)
            val beforeChange = origin.images.size
            touch(145f, 60)
            compose.waitUntil(8_000) { origin.images.size > beforeChange && browser.state.value.selectedTab?.title == "Policy inserted observed" }
            assertNull("A fresh actual Gecko image proves the inserted no-referrer survived the invalid write", origin.images.last().referrer)

            val clipboard = context.getSystemService(ClipboardManager::class.java)
            var previous = ""
            compose.runOnIdle { previous = clipboard.primaryClip?.getItemAt(0)?.uri?.toString().orEmpty() }
            val beforeCopy = origin.images.size
            longPress(45f)
            compose.waitUntil(8_000) { browser.contextMenus.state.value?.imageUrl == "${origin.url}/image.png" }
            compose.onNodeWithTag("context_copy_image").performScrollTo().performClick()
            val unavailable = context.getString(R.string.context_image_failed)
            compose.waitUntil(20_000) {
                origin.images.size > beforeCopy || compose.onAllNodesWithText(unavailable).fetchSemanticsNodes().isNotEmpty()
            }
            if(origin.images.size == beforeCopy) {
                compose.onNodeWithText(unavailable).assertIsDisplayed()
            } else {
                assertTrue("A native copy must never widen the actual document policy", origin.images.drop(beforeCopy).all { it.referrer == null })
                compose.waitUntil(8_000) {
                    var copied = false
                    compose.runOnIdle { val uri = clipboard.primaryClip?.getItemAt(0)?.uri
                        copied = uri?.authority == "${context.packageName}.context-images" && uri.toString() != previous }
                    copied
                }
                assertArrayEquals(origin.image, context.contentResolver.openInputStream(clipboard.primaryClip!!.getItemAt(0).uri)!!.use { it.readBytes() })
            }
        }
    }

    private fun open(url: String, title: String, private: Boolean = false) {
        var id = ""
        compose.runOnIdle { id = browser.newTab(url, private) }
        compose.waitUntil(25_000) { browser.state.value.selectedTab?.let { it.id == id && it.url == url && it.title == title && !it.loading } == true }
        compose.waitForIdle()
    }

    private fun copy(origin: PolicyOrigin, y: Float) {
        val count = origin.images.size
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        var previous = ""
        compose.runOnIdle { previous = clipboard.primaryClip?.getItemAt(0)?.uri?.toString().orEmpty() }
        longPress(y)
        compose.waitUntil(8_000) { browser.contextMenus.state.value?.imageUrl == "${origin.url}/image.png" }
        compose.onNodeWithTag("context_copy_image").performScrollTo().performClick()
        compose.waitUntil(20_000) {
            var ready = false
            compose.runOnIdle {
                val uri = clipboard.primaryClip?.getItemAt(0)?.uri
                ready = uri?.authority == "${context.packageName}.context-images" && uri.toString() != previous
            }
            ready && origin.images.size > count
        }
        val uri = clipboard.primaryClip!!.getItemAt(0).uri
        assertArrayEquals("The copy must publish the original PNG bytes", origin.image, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        compose.waitUntil(8_000) { browser.contextMenus.state.value == null }
        compose.waitForIdle()
    }

    private fun engineBounds(): Rect {
        var result: Rect? = null
        compose.runOnIdle {
            fun find(view: View): EngineView? = if(view is EngineView) view else (view as? ViewGroup)?.let { group ->
                (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
            }
            val view = checkNotNull(find(compose.activity.window.decorView)).asView()
            val location = IntArray(2); view.getLocationOnScreen(location)
            result = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }
        return result!!
    }

    private fun awaitImage(y: Float) {
        compose.waitUntil(8_000) {
            val bounds = engineBounds(); val density = context.resources.displayMetrics.density
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
            try {
                val pixel = bitmap.getPixel((bounds.left + 40 * density).toInt().coerceIn(0, bitmap.width - 1),
                    (bounds.top + y * density).toInt().coerceIn(0, bitmap.height - 1))
                android.graphics.Color.blue(pixel) > 240 && android.graphics.Color.red(pixel) < 16 && android.graphics.Color.green(pixel) < 16
            } finally { bitmap.recycle() }
        }
    }

    private fun longPress(y: Float) = touch(y, 850)

    private fun touch(y: Float, duration: Long) {
        compose.waitUntil(8_000) { compose.activity.hasWindowFocus() }
        val bounds = engineBounds(); val density = context.resources.displayMetrics.density
        val down = SystemClock.uptimeMillis()
        fun event(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.left + 40 * density, bounds.top + y * density, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        event(MotionEvent.ACTION_DOWN); SystemClock.sleep(duration); event(MotionEvent.ACTION_UP)
        compose.waitForIdle()
    }
}

private class PolicyOrigin : Closeable {
    private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    val url = "http://127.0.0.1:${server.localPort}"
    var referrerPolicy: String? = null
    var document: () -> String = { "<title>Policy fixture</title>" }
    data class ImageRequest(val referrer: String?)
    val images = CopyOnWriteArrayList<ImageRequest>()
    val image = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() } finally { bitmap.recycle() }
    }
    init {
        thread(isDaemon = true, name = "miru-image-policy-origin") {
            while(running.get()) runCatching {
                val socket = server.accept()
                thread(isDaemon = true) { socket.use { client -> runCatching {
                    client.soTimeout = 5_000
                    val reader = client.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1)?.substringBefore('?').orEmpty()
                    val headers = mutableMapOf<String, String>()
                    while(true) {
                        val line = reader.readLine() ?: break
                        if(line.isEmpty()) break
                        val colon = line.indexOf(':')
                        if(colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
                    }
                    val isImage = path == "/image.png"
                    if(isImage) images += ImageRequest(headers["referer"])
                    val body = if(isImage) image else ("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>" +
                        "<style>body{margin:0}img{display:block;width:120px;height:100px}</style>" + document()).toByteArray()
                    val policy = if(!isImage) referrerPolicy?.let { "Referrer-Policy: $it\r\n" }.orEmpty() else ""
                    client.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: ${if(isImage) "image/png" else "text/html; charset=utf-8"}\r\n" +
                            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n$policy\r\n").toByteArray())
                        write(body); flush()
                    }
                } } }
            }
        }
    }
    override fun close() { running.set(false); server.close() }
}
