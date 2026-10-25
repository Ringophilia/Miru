// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.Manifest
import android.graphics.Color
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
import app.miru.browser.engine.SitePermissionChoice
import app.miru.browser.engine.SitePermissionKind
import app.miru.browser.engine.SitePermissionPrompt
import app.miru.browser.engine.SitePermissionState
import app.miru.data.BrowsingDataClearRequest
import app.miru.data.BrowsingDataRange
import app.miru.data.BrowsingDataType
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import mozilla.components.concept.engine.EngineView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real website prompts and Gecko callbacks. Android is pregranted for this site-policy suite. */
@RunWith(AndroidJUnit4::class)
class SitePermissionIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val browser get() = (compose.activity.application as MiruApplication).container.browser
    private val tabs = mutableListOf<String>()
    private var tracePermissions = false
    private var restoreConsole: (() -> Unit)? = null

    @Before fun allowAndroidForSitePolicyTests() {
        // This deliberately does not claim to test the Android permission dialog.
        for(permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        tracePermissions = InstrumentationRegistry.getArguments().getString("miru.permissionTrace") == "true"
        if(tracePermissions) {
            val allowed = setOf("app.miru.browser.SitePermissionIntegrationTest#combinedCameraAndMicrophonePermissionProducesActualLiveTracks",
                "app.miru.browser.SitePermissionIntegrationTest#temporaryLocationGrantMustAskAgainForTheNextSameOriginDocument")
            val selected = InstrumentationRegistry.getArguments().getString("class").orEmpty().split(',')
            assertTrue("Console tracing is restricted to the two ordinary synthetic fixtures", selected.isNotEmpty() && selected.all { it in allowed })
            assertTrue(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            compose.waitUntil(30_000) { browser.state.value.ready }
            assertTrue("Never trace a private document", browser.state.value.tabs.none { it.isPrivate })
            compose.runOnIdle {
                // Diagnostic-only reflection changes logging, never permission callbacks,
                // source IDs or engine policy. Restore it before fixture cleanup.
                val field = browser.javaClass.getDeclaredField("runtime").apply { isAccessible = true }
                val runtime = checkNotNull(field.get(browser))
                val settings = runtime.javaClass.getMethod("getSettings").invoke(runtime)
                val getter = settings.javaClass.getMethod("getConsoleOutputEnabled")
                val setter = settings.javaClass.getMethod("setConsoleOutputEnabled", java.lang.Boolean.TYPE)
                val previous = getter.invoke(settings) as Boolean
                setter.invoke(settings, true)
                restoreConsole = { setter.invoke(settings, previous); Unit }
            }
        }
    }

    @After fun cleanSyntheticDecisionsAndCloseFixtures() {
        compose.runOnIdle { restoreConsole?.invoke(); restoreConsole = null }
        val result = runBlocking { browser.clearBrowsingData(BrowsingDataClearRequest(setOf(BrowsingDataType.SITE_SETTINGS), BrowsingDataRange.ALL_TIME)) }
        assertTrue("Both Miru decisions and Gecko settings must finish clearing", result.successful)
        compose.runOnIdle { tabs.forEach(browser::closeTab) }
    }

    @Test fun rememberedLocationBlockResetsAndAnotherPortStillAsks() {
        PermissionOrigin().use { first -> PermissionOrigin().use { second ->
            val owner = page(first, "location")
            request()
            assertEquals(setOf(SitePermissionKind.LOCATION), awaitPrompt(first).kinds)
            compose.onNodeWithText(context.getString(R.string.site_permission_remember)).onParent().performClick()
            compose.onNodeWithText(context.getString(R.string.site_permission_block)).performClick()
            title(owner, "Permission denied")
            reload(owner)
            request()
            title(owner, "Permission denied")
            assertNull("A remembered block must not reopen the site prompt", browser.permissions.prompt.value)

            val other = page(second, "location")
            request()
            val otherPrompt = awaitPrompt(second)
            compose.runOnIdle { browser.permissions.dismiss(otherPrompt.token) }
            title(other, "Permission denied")

            compose.runOnIdle { browser.selectTab(owner) }
            compose.onNodeWithContentDescription(context.getString(app.miru.toolbar.R.string.toolbar_site_info)).performClick()
            val state = awaitSite(owner)
            assertEquals(SitePermissionChoice.BLOCK, state.choices[SitePermissionKind.LOCATION])
            compose.onNodeWithText(context.getString(R.string.site_permission_reset)).performScrollTo().performClick()
            compose.waitUntil(10_000) { browser.permissions.siteState.value?.let {
                !it.busy && !it.error && it.choices[SitePermissionKind.LOCATION] == SitePermissionChoice.ASK
            } == true }
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            reload(owner)
            request()
            awaitPrompt(first)
        } }
    }

    @Test fun privateResetDoesNotDeleteOrdinaryDecisionAndClearAllMakesBothAsk() {
        PermissionOrigin().use { origin ->
            val ordinary = page(origin, "location")
            val ordinaryState = awaitSite(ordinary)
            compose.runOnIdle { browser.permissions.setForSite(ordinaryState.token, SitePermissionKind.LOCATION, SitePermissionChoice.BLOCK) }
            awaitChoice(ordinary, SitePermissionChoice.BLOCK)
            // Private windows correctly block screenshots. Use a real site's
            // automatic geolocation request rather than bypassing FLAG_SECURE
            // to wait for the fixture button's pixels.
            val privateTab = page(origin, "location-auto", private = true, expectedTitle = "Permission pending")
            val privatePrompt = awaitPrompt(origin)
            assertTrue(privatePrompt.isPrivate)
            compose.runOnIdle { browser.permissions.answer(privatePrompt.token, allow = false, remember = true) }
            title(privateTab, "Permission denied")
            val privateState = awaitSite(privateTab)
            assertTrue(privateState.isPrivate)
            assertEquals(SitePermissionChoice.BLOCK, privateState.choices[SitePermissionKind.LOCATION])
            compose.runOnIdle { browser.permissions.resetSite(privateState.token) }
            awaitChoice(privateTab, SitePermissionChoice.ASK)
            compose.runOnIdle { browser.selectTab(ordinary) }
            assertEquals(SitePermissionChoice.BLOCK, awaitSite(ordinary).choices[SitePermissionKind.LOCATION])

            val result = runBlocking { browser.clearBrowsingData(BrowsingDataClearRequest(setOf(BrowsingDataType.SITE_SETTINGS), BrowsingDataRange.ALL_TIME)) }
            assertTrue(result.successful)
            assertEquals(SitePermissionChoice.ASK, awaitSite(ordinary).choices[SitePermissionKind.LOCATION])
            reload(ordinary)
            request()
            val fresh = awaitPrompt(origin)
            compose.runOnIdle { browser.permissions.dismiss(fresh.token); browser.selectTab(privateTab) }
            assertEquals(SitePermissionChoice.ASK, awaitSite(privateTab).choices[SitePermissionKind.LOCATION])
        }
    }

    @Test fun combinedCameraAndMicrophonePermissionProducesActualLiveTracks() {
        PermissionOrigin().use { origin ->
            val owner = page(origin, "media")
            request()
            val prompt = awaitPrompt(origin)
            assertEquals(setOf(SitePermissionKind.CAMERA, SitePermissionKind.MICROPHONE), prompt.kinds)
            traceNativeCallbackState("media-before-answer")
            compose.onNodeWithText(context.getString(R.string.site_permission_allow)).performClick()
            try { title(owner, "Permission live audio,video") }
            catch(failure: Throwable) { throw AssertionError("Actual media capture failed: ${origin.mediaFailure.get() ?: "no fixture diagnostic"}", failure) }
            assertNull(browser.permissions.prompt.value)
        }
    }

    @Test fun temporaryLocationGrantMustAskAgainForTheNextSameOriginDocument() {
        PermissionOrigin().use { origin ->
            val owner = page(origin, "location")
            request()
            val original = awaitPrompt(origin)
            traceNativeCallbackState("location-before-answer")
            compose.onNodeWithText(context.getString(R.string.site_permission_allow)).performClick()
            // An emulator may have no location fix. A provider-unavailable/timeout
            // callback still differs from permission denied; never inject a fake
            // browser permission result to make this test pass.
            compose.waitUntil(20_000) { browser.state.value.tabs.firstOrNull { it.id == owner }?.title in
                setOf("Permission located", "Permission 2", "Permission 3", "Permission denied") }
            assertNotEquals("The actual Gecko permission grant must not be rejected", "Permission denied",
                browser.state.value.tabs.first { it.id == owner }.title)
            awaitSite(owner) // Also verifies no unexpected native GEO override remains.
            compose.runOnIdle { browser.navigate("${origin.url}/location?next-document=1") }
            title(owner, "Permission ready")
            request()
            assertNotEquals("A temporary grant cannot authorize another document", original.token, awaitPrompt(origin).token)
        }
    }

    @Test fun switchingTabsRejectsTheOriginalRequestAndIgnoresItsLateAnswer() {
        PermissionOrigin().use { origin ->
            val owner = page(origin, "location")
            request()
            val old = awaitPrompt(origin)
            val replacement = page(origin, "idle")
            compose.waitUntil(10_000) { browser.permissions.prompt.value == null }
            compose.runOnIdle { browser.permissions.answer(old.token, allow = true, remember = true) }
            assertEquals(replacement, browser.state.value.selectedTabId)
            compose.runOnIdle { browser.selectTab(owner) }
            title(owner, "Permission denied")
            assertEquals("A stale answer cannot persist ALLOW", SitePermissionChoice.ASK,
                awaitSite(owner).choices[SitePermissionKind.LOCATION])
        }
    }

    @Test fun replacingTheDocumentRejectsAnOldPermissionEvenAtTheSameOrigin() {
        PermissionOrigin().use { origin ->
            val owner = page(origin, "location")
            request()
            val old = awaitPrompt(origin)
            compose.runOnIdle { browser.navigate("${origin.url}/idle?new-document=1") }
            title(owner, "Permission ready")
            compose.waitUntil(10_000) { browser.permissions.prompt.value == null }
            compose.runOnIdle { browser.permissions.answer(old.token, allow = true, remember = true) }
            assertEquals(SitePermissionChoice.ASK, awaitSite(owner).choices[SitePermissionKind.LOCATION])
            compose.runOnIdle { browser.navigate("${origin.url}/location?new-document=2") }
            title(owner, "Permission ready")
            request()
            assertNotEquals(old.token, awaitPrompt(origin).token)
        }
    }

    private fun page(origin: PermissionOrigin, mode: String, private: Boolean = false, expectedTitle: String = "Permission ready"): String {
        var id = ""
        compose.runOnIdle { id = browser.newTab("${origin.url}/$mode", isPrivate = private); tabs += id }
        // An automatic permission request may pause load completion until it is
        // answered. Its native prompt, rather than loading=false, is the signal.
        if(!mode.endsWith("-auto")) title(id, expectedTitle)
        return id
    }

    private fun reload(id: String) {
        compose.runOnIdle { browser.reload() }
        title(id, "Permission ready")
    }

    private fun title(id: String, expected: String) {
        try { compose.waitUntil(30_000) { browser.state.value.tabs.firstOrNull { it.id == id }?.let { it.title == expected && !it.loading } == true } }
        catch(failure: Throwable) { throw AssertionError("Expected $expected; current title=${browser.state.value.tabs.firstOrNull { it.id == id }?.title}", failure) }
    }

    private fun awaitPrompt(origin: PermissionOrigin): SitePermissionPrompt {
        compose.waitUntil(20_000) { browser.permissions.prompt.value != null }
        val prompt = checkNotNull(browser.permissions.prompt.value)
        assertEquals(origin.url, prompt.origin)
        compose.onNodeWithText(context.getString(R.string.site_permission_request)).assertIsDisplayed()
        return prompt
    }

    private fun awaitSite(id: String): SitePermissionState {
        compose.runOnIdle { browser.permissions.refreshSite() }
        compose.waitUntil(10_000) { browser.permissions.siteState.value?.let { it.tabId == id && !it.busy } == true }
        return checkNotNull(browser.permissions.siteState.value).also { assertFalse(it.error) }
    }

    private fun awaitChoice(id: String, choice: SitePermissionChoice) {
        compose.waitUntil(10_000) { browser.permissions.siteState.value?.let {
            it.tabId == id && !it.busy && !it.error && it.choices[SitePermissionKind.LOCATION] == choice
        } == true }
    }

    private fun traceNativeCallbackState(stage: String) {
        if(!tracePermissions) return
        compose.runOnIdle {
            // Read only fixed, pinned callback-state fields for diagnosis. No callback
            // invocation, IDs, origins or object references leave this method.
            fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
                .apply { isAccessible = true }.get(target)
            val current = field(browser.permissions, "current")
            val request = current?.let { checkNotNull(field(it, "request")) }
            if(request == null) {
                android.util.Log.i("MiruPermissionTest", "stage=$stage type=none")
                return@runOnIdle
            }
            val state = when(request.javaClass.simpleName) {
                "Content" -> "completed=${field(request, "isCompleted")} resultCount=${(field(request, "geckoResults") as List<*>).size}"
                "Media" -> "nativeCallbackPresent=${field(checkNotNull(field(request, "callback")), "mCallback") != null}"
                else -> "unrecognized"
            }
            android.util.Log.i("MiruPermissionTest", "stage=$stage type=${request.javaClass.simpleName} $state")
        }
    }

    private fun bounds(): Rect {
        var bounds: Rect? = null
        compose.runOnIdle {
            fun find(view: View): EngineView? = if(view is EngineView) view else (view as? ViewGroup)?.let {
                (0 until it.childCount).firstNotNullOfOrNull { index -> find(it.getChildAt(index)) }
            }
            val view = checkNotNull(find(compose.activity.window.decorView)).asView()
            val xy = IntArray(2); view.getLocationOnScreen(xy)
            bounds = Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
        }
        return checkNotNull(bounds)
    }

    private fun request() {
        // Require an actual Gecko frame before touching its fixture button.
        val density = context.resources.displayMetrics.density
        compose.waitUntil(8_000) {
            val bounds = bounds()
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
            try {
                val pixel = bitmap.getPixel((bounds.left + 12 * density).toInt(), (bounds.top + 12 * density).toInt())
                kotlin.math.abs(Color.red(pixel) - 0x13) < 8 && kotlin.math.abs(Color.green(pixel) - 0x57) < 8 && kotlin.math.abs(Color.blue(pixel) - 0x9b) < 8
            } finally { bitmap.recycle() }
        }
        val bounds = bounds()
        val down = SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.left + 40 * density, bounds.top + 40 * density, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            if(action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60)
        }
    }
}

private class PermissionOrigin : Closeable {
    private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    val url = "http://127.0.0.1:${server.localPort}"
    val mediaFailure = AtomicReference<String?>()
    init { thread(isDaemon = true, name = "miru-permission-origin") {
        while(running.get()) runCatching {
            val socket = server.accept()
            thread(isDaemon = true) { socket.use { client -> runCatching {
                client.soTimeout = 5_000
                val reader = client.getInputStream().bufferedReader()
                val target = reader.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                val path = target.substringBefore('?')
                while(!reader.readLine().isNullOrEmpty()) { }
                if(path == "/media-failure") {
                    val decoded = java.net.URLDecoder.decode(target.substringAfter("?data=", ""), "UTF-8")
                    mediaFailure.set(decoded.take(2048).replace(Regex("https?://[^\\s\\\"]+"), "[fixture-url]"))
                }
                val script = when(path) {
                    "/media" -> "navigator.mediaDevices.getUserMedia({audio:true,video:true}).then(s=>{document.title='Permission live '+s.getTracks().filter(t=>t.readyState==='live').map(t=>t.kind).sort().join(',');s.getTracks().forEach(t=>t.stop())}).catch(e=>{const d={name:e.name,message:String(e.message).slice(0,512),focused:document.hasFocus(),visible:document.visibilityState,secure:globalThis.isSecureContext,activated:navigator.userActivation?.isActive,camera:document.permissionsPolicy?.allowsFeature('camera')??'unknown',microphone:document.permissionsPolicy?.allowsFeature('microphone')??'unknown'};fetch('/media-failure?data='+encodeURIComponent(JSON.stringify(d)));document.title='Permission '+(e.name==='NotAllowedError'?'denied':e.name)})"
                    "/location", "/location-auto" -> "navigator.geolocation.getCurrentPosition(p=>{document.title='Permission located'},e=>{document.title='Permission '+(e.code===1?'denied':e.code)},{timeout:10000})"
                    else -> "document.title='Permission ready'"
                }
                val body = ("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>Permission ready</title>" +
                    "<style>body{margin:0}button{background:#13579b;color:white;width:220px;height:100px;border:0}</style>" +
                    "<button onclick='requestPermission()'>Request permission</button>" +
                    "<script>function requestPermission(){document.title='Permission pending';$script}</script>" +
                    if(path == "/location-auto") "<script>addEventListener('load',requestPermission,{once:true})</script>" else "").toByteArray()
                client.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body); flush()
                }
            } } }
        }
    } }
    override fun close() { running.set(false); server.close() }
}
