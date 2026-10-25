// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
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
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import mozilla.components.concept.engine.EngineView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Destructive-to-test-state, ordered system-dialog suite. Run the entire class
 * alone on a fresh API 35 AVD; see docs/B13-RUNTIME-PERMISSIONS-TEST.md.
 *
 * These cases prove Android gating independently of successful media capture.
 * No runtime grants, permission callbacks, or media devices are synthesized.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RuntimeSitePermissionIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val browser get() = (compose.activity.application as MiruApplication).container.browser
    private val tabs = mutableListOf<String>()
    private var originalAccessibilityFlags: Int? = null

    @Before fun exposePermissionControllerViewIds() {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        originalAccessibilityFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
    }

    @After fun clearOnlyWebsiteStateAndCloseFixtures() {
        try {
            val result = runBlocking {
                browser.clearBrowsingData(BrowsingDataClearRequest(
                    setOf(BrowsingDataType.SITE_SETTINGS), BrowsingDataRange.ALL_TIME,
                ))
            }
            assertTrue("Website and Gecko settings cleanup must complete", result.successful)
            compose.runOnIdle { tabs.forEach(browser::closeTab) }
        } finally {
            originalAccessibilityFlags?.let { flags ->
                val automation = instrumentation.uiAutomation
                automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
            }
        }
    }

    @Test fun a_firstCameraAndroidDenialLeavesWebsiteChoiceAsk() {
        requireStep(0)
        assertAndroidPermissions(emptySet())
        RuntimePermissionOrigin().use { origin ->
            val owner = page(origin, "camera")
            requestFromWebButton()
            awaitRuntimeRequest(setOf(Manifest.permission.CAMERA))
            clickSystemPermissionNode("permission_deny_button")

            denied(owner, origin)
            awaitRuntimeFinished()
            assertAndroidPermissions(emptySet())
            assertNull("Android denial must not open a website consent prompt", browser.permissions.prompt.value)
            assertEquals("An Android denial must not become a website BLOCK",
                SitePermissionChoice.ASK, awaitSite(owner).choices[SitePermissionKind.CAMERA])
            assertNoSystemSettingsRequired()
        }
        completedStep = 1
    }

    @Test fun b_realCameraAndMicrophoneGrantsStillRequireWebsiteConsent() {
        requireStep(1)
        // Camera has exactly one real denial from a_; microphone has never been requested.
        assertAndroidPermissions(emptySet())
        RuntimePermissionOrigin().use { origin ->
            val owner = page(origin, "media")
            requestFromWebButton()
            val mediaPermissions = setOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            var clickedDialogs = 0
            var previousDialogMessage: String? = null
            while(mediaPermissions.any { !granted(it) }) {
                assertTrue("API 35 must grant camera and microphone through at most two dialogs", clickedDialogs < 2)
                awaitRuntimeRequestAllowedSubset(mediaPermissions)
                val before = mediaPermissions.filterTo(mutableSetOf(), ::granted)
                previousDialogMessage = clickSystemPermissionNode(
                    "permission_allow_foreground_only_button", differentMessageFrom = previousDialogMessage,
                ).also { assertTrue("Each media dialog must expose its actual permission message", it.isNotBlank()) }
                clickedDialogs++
                waitFor("A real Android allow click must grant a requested media permission") {
                    mediaPermissions.any { it !in before && granted(it) }
                }
            }
            assertEquals("Camera and microphone must each have a real PermissionController dialog", 2, clickedDialogs)
            awaitRuntimeFinished()
            assertAndroidPermissions(mediaPermissions)

            val sitePrompt = awaitPrompt(origin)
            assertEquals(setOf(SitePermissionKind.CAMERA, SitePermissionKind.MICROPHONE), sitePrompt.kinds)
            compose.onNodeWithText(context.getString(R.string.site_permission_block)).performClick()
            denied(owner, origin)
            assertNull(browser.permissions.prompt.value)
            val site = awaitSite(owner)
            for(kind in setOf(SitePermissionKind.CAMERA, SitePermissionKind.MICROPHONE)) {
                assertEquals("Android consent cannot bypass the website's effective BLOCK", SitePermissionChoice.BLOCK, site.choices[kind])
                assertEquals(true, site.systemGranted[kind])
            }
            assertAndroidPermissions(mediaPermissions)
            assertNoSystemSettingsRequired()
        }
        completedStep = 2
    }

    @Test fun c_approximateAndroidLocationSatisfiesAnAllowedWebsite() {
        requireStep(2)
        val mediaPermissions = setOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        assertAndroidPermissions(mediaPermissions)
        RuntimePermissionOrigin().use { origin ->
            val owner = page(origin, "location")
            // Set the website's real stored choice through the site-info UI before
            // invoking Gecko. This isolates Android's coarse/fine result handling.
            compose.onNodeWithContentDescription(context.getString(app.miru.toolbar.R.string.toolbar_site_info)).performClick()
            awaitSite(owner)
            compose.onNodeWithContentDescription(context.getString(
                R.string.site_permission_change, context.getString(R.string.site_permission_location),
            )).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.site_permission_allow)).performClick()
            compose.waitUntil(10_000) {
                browser.permissions.siteState.value?.let {
                    it.tabId == owner && !it.busy && !it.error &&
                        it.choices[SitePermissionKind.LOCATION] == SitePermissionChoice.ALLOW
                } == true
            }
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            title(owner, "Runtime ready")

            requestFromWebButton()
            awaitRuntimeRequest(setOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            clickSystemPermissionNode("permission_location_accuracy_radio_coarse")
            clickSystemPermissionNode("permission_allow_foreground_only_button")
            waitFor("Approximate location must grant coarse permission") { granted(Manifest.permission.ACCESS_COARSE_LOCATION) }
            awaitRuntimeFinished()
            assertAndroidPermissions(mediaPermissions + Manifest.permission.ACCESS_COARSE_LOCATION)

            waitFor("Gecko must deliver a real location result (including provider unavailable or timeout)", 25_000) {
                currentTitle(owner) in setOf("Runtime located", "Runtime unavailable", "Runtime timeout", "Runtime denied")
            }
            assertNotEquals("Coarse-only Android consent must not be rejected as permission denied",
                "Runtime denied", currentTitle(owner))
            assertNull("A pre-allowed website must not ask again", browser.permissions.prompt.value)
            val site = awaitSite(owner)
            assertEquals(SitePermissionChoice.ALLOW, site.choices[SitePermissionKind.LOCATION])
            assertEquals(true, site.systemGranted[SitePermissionKind.LOCATION])
            assertNoSystemSettingsRequired()
        }
        completedStep = 3
    }

    private fun requireStep(expected: Int) {
        assertEquals("Run the entire class alone, in name order, on a fresh AVD; earlier cases must pass", expected, completedStep)
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun assertAndroidPermissions(expectedGranted: Set<String>) {
        for(permission in SYSTEM_PERMISSIONS) assertEquals("Unexpected Android state for $permission; use a fresh isolated AVD",
            permission in expectedGranted, granted(permission))
    }

    private fun assertNoSystemSettingsRequired() {
        compose.runOnIdle {
            assertFalse("A first denial or valid approximate grant must not require Android settings",
                compose.activity.sitePermissionSettingsRequired)
        }
        compose.onNodeWithText(context.getString(R.string.site_permission_settings_title)).assertDoesNotExist()
    }

    private fun awaitRuntimeRequest(expected: Set<String>) {
        waitFor("A real Android runtime permission request must be launched") {
            browser.permissions.runtimeRequest.value?.launched == true
        }
        val request = checkNotNull(browser.permissions.runtimeRequest.value)
        assertEquals(expected, request.permissions.toSet())
        assertFalse(request.isPrivate)
    }

    private fun awaitRuntimeRequestAllowedSubset(allowed: Set<String>) {
        waitFor("A real Android media runtime permission request must be launched") {
            browser.permissions.runtimeRequest.value?.launched == true
        }
        val request = checkNotNull(browser.permissions.runtimeRequest.value)
        assertTrue("The Android media request must contain only camera/microphone", request.permissions.isNotEmpty() &&
            request.permissions.all { it in allowed })
        assertFalse(request.isPrivate)
    }

    private fun awaitRuntimeFinished() {
        waitFor("The actual ActivityResult callback must finish the Android permission gate") {
            browser.permissions.runtimeRequest.value == null
        }
    }

    /** Clicks only an accessible node owned by Android's PermissionController. */
    private fun clickSystemPermissionNode(resourceName: String, differentMessageFrom: String? = null): String {
        var clickedMessage: String? = null
        waitFor("Missing or unclickable Android PermissionController node: $resourceName") {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@waitFor false
            try {
                if(root.packageName?.toString() !in PERMISSION_CONTROLLERS) return@waitFor false
                // Google's installed package can retain the AOSP resource namespace.
                // Validate the actual owner separately from the resource ID prefix.
                for(namespace in PERMISSION_CONTROLLERS) {
                    val messages = root.findAccessibilityNodeInfosByViewId("$namespace:id/permission_message")
                    val message = try { messages.joinToString("\n") { it.text?.toString().orEmpty() }.trim() }
                        finally { messages.forEach { it.recycle() } }
                    // Camera and microphone share the allow-button resource ID.
                    // Require the second group's real message before clicking it.
                    if(differentMessageFrom != null && message == differentMessageFrom) continue
                    val nodes = root.findAccessibilityNodeInfosByViewId("$namespace:id/$resourceName")
                    try {
                        for(node in nodes) {
                            if(node.packageName?.toString() in PERMISSION_CONTROLLERS && node.isVisibleToUser && node.isEnabled &&
                                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                clickedMessage = message
                                return@waitFor true
                            }
                        }
                    } finally { nodes.forEach { it.recycle() } }
                }
                false
            } finally { root.recycle() }
        }
        return checkNotNull(clickedMessage)
    }

    private fun waitFor(message: String, timeout: Long = 20_000, condition: () -> Boolean) {
        try {
            // Compose's test clock must advance so the production runtime-request
            // LaunchedEffect can launch Android's dialog. A plain sleep loop stalls
            // that effect even while the coordinator's StateFlow is already ready.
            compose.waitUntil(timeout) { condition() }
        } catch(failure: ComposeTimeoutException) {
            throw AssertionError(message, failure)
        }
    }

    private fun page(origin: RuntimePermissionOrigin, mode: String): String {
        var id = ""
        compose.runOnIdle { id = browser.newTab("${origin.url}/$mode"); tabs += id }
        title(id, "Runtime ready")
        return id
    }

    private fun currentTitle(id: String) = browser.state.value.tabs.firstOrNull { it.id == id }?.title

    private fun title(id: String, expected: String) {
        try {
            compose.waitUntil(30_000) {
                browser.state.value.tabs.firstOrNull { it.id == id }?.let { it.title == expected && !it.loading } == true
            }
        } catch(failure: Throwable) {
            throw AssertionError("Expected $expected; current title=${currentTitle(id)}", failure)
        }
    }

    private fun denied(id: String, origin: RuntimePermissionOrigin) {
        try { title(id, "Runtime denied") }
        catch(failure: Throwable) {
            throw AssertionError("Actual getUserMedia denial failed: ${origin.mediaResult.get() ?: "no fixture diagnostic"}", failure)
        }
    }

    private fun awaitPrompt(origin: RuntimePermissionOrigin): SitePermissionPrompt {
        compose.waitUntil(20_000) { browser.permissions.prompt.value != null }
        return checkNotNull(browser.permissions.prompt.value).also {
            assertEquals(origin.url, it.origin)
            assertFalse(it.isPrivate)
            compose.onNodeWithText(context.getString(R.string.site_permission_request)).assertIsDisplayed()
        }
    }

    private fun awaitSite(id: String): SitePermissionState {
        compose.runOnIdle { browser.permissions.refreshSite() }
        compose.waitUntil(10_000) {
            browser.permissions.siteState.value?.let { it.tabId == id && !it.busy } == true
        }
        return checkNotNull(browser.permissions.siteState.value).also { assertFalse(it.error) }
    }

    private fun engineBounds(): Rect {
        var result: Rect? = null
        compose.runOnIdle {
            fun find(view: View): EngineView? = if(view is EngineView) view else (view as? ViewGroup)?.let {
                (0 until it.childCount).firstNotNullOfOrNull { index -> find(it.getChildAt(index)) }
            }
            val view = checkNotNull(find(compose.activity.window.decorView)).asView()
            val xy = IntArray(2)
            view.getLocationOnScreen(xy)
            result = Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
        }
        return checkNotNull(result)
    }

    private fun requestFromWebButton() {
        // The fixture is ordinary browsing content. Require a real painted Gecko
        // frame before injecting a trusted touchscreen gesture into its button.
        val density = context.resources.displayMetrics.density
        compose.waitUntil(8_000) {
            val bounds = engineBounds()
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
            try {
                val x = (bounds.left + 12 * density).toInt()
                val y = (bounds.top + 12 * density).toInt()
                if(x !in 0 until bitmap.width || y !in 0 until bitmap.height) return@waitUntil false
                val pixel = bitmap.getPixel(x, y)
                abs(Color.red(pixel) - 0x13) < 8 && abs(Color.green(pixel) - 0x57) < 8 && abs(Color.blue(pixel) - 0x9b) < 8
            } finally { bitmap.recycle() }
        }
        val bounds = engineBounds()
        val down = SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.left + 40 * density, bounds.top + 40 * density, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if(action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60)
        }
    }

    companion object {
        private val SYSTEM_PERMISSIONS = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        private val PERMISSION_CONTROLLERS = listOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
        private var completedStep = 0

        @BeforeClass @JvmStatic fun requireFreshApi35AndroidPermissions() {
            assertEquals("This ordered system-dialog suite is pinned to a fresh API 35 AVD", 35, Build.VERSION.SDK_INT)
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            for(permission in SYSTEM_PERMISSIONS) assertEquals("$permission must start ungranted; exclude this class from ordinary regression runs",
                PackageManager.PERMISSION_DENIED, target.checkSelfPermission(permission))
            completedStep = 0
        }
    }
}

/** A dedicated loopback origin; all results come from the real Gecko web APIs. */
private class RuntimePermissionOrigin : Closeable {
    private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    val url = "http://127.0.0.1:${server.localPort}"
    val mediaResult = AtomicReference<String?>()

    init {
        thread(isDaemon = true, name = "miru-runtime-permission-origin") {
            while(running.get()) runCatching {
                val socket = server.accept()
                thread(isDaemon = true) {
                    socket.use { client -> runCatching {
                        client.soTimeout = 5_000
                        val reader = client.getInputStream().bufferedReader()
                        val target = reader.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                        val path = target.substringBefore('?')
                        while(!reader.readLine().isNullOrEmpty()) { }
                        if(path == "/media-result") {
                            mediaResult.set(URLDecoder.decode(target.substringAfter("?data=", ""), "UTF-8")
                                .take(2_048).replace(Regex("https?://[^\\s\\\"]+"), "[fixture-url]"))
                        }
                        val script = when(path) {
                            "/camera" -> mediaScript("{audio:false,video:true}")
                            "/media" -> mediaScript("{audio:true,video:true}")
                            "/location" -> "navigator.geolocation.getCurrentPosition(p=>{document.title='Runtime located'},e=>{document.title='Runtime '+(e.code===1?'denied':e.code===2?'unavailable':e.code===3?'timeout':'unexpected')},{timeout:10000})"
                            else -> "document.title='Runtime ready'"
                        }
                        val body = ("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>Runtime ready</title>" +
                            "<style>body{margin:0}button{background:#13579b;color:white;width:220px;height:100px;border:0}</style>" +
                            "<button onclick='requestPermission()'>Request permission</button>" +
                            "<script>function requestPermission(){document.title='Runtime pending';$script}</script>").toByteArray()
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body)
                            flush()
                        }
                    } }
                }
            }
        }
    }

    private fun mediaScript(constraints: String) =
        "navigator.mediaDevices.getUserMedia($constraints).then(s=>{document.title='Runtime live '+s.getTracks().filter(t=>t.readyState==='live').map(t=>t.kind).sort().join(',');s.getTracks().forEach(t=>t.stop())}).catch(e=>{const d={name:e.name,message:String(e.message).slice(0,512),focused:document.hasFocus(),visible:document.visibilityState,secure:globalThis.isSecureContext,activated:navigator.userActivation?.isActive};fetch('/media-result?data='+encodeURIComponent(JSON.stringify(d)));document.title='Runtime '+(e.name==='NotAllowedError'?'denied':e.name)})"

    override fun close() {
        running.set(false)
        server.close()
    }
}
