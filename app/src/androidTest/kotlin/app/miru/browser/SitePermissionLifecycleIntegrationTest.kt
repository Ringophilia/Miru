// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.Manifest
import android.content.pm.PackageManager
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
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.engine.media.RecordingDevice
import mozilla.components.concept.engine.permission.PermissionRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual ordinary-document requests, Activity recreation, and native capture events. */
@RunWith(AndroidJUnit4::class)
class SitePermissionLifecycleIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val browser get() = (compose.activity.application as MiruApplication).container.browser
    private val tabs = mutableListOf<String>()
    private val recordingObservers = mutableListOf<Pair<EngineSession, LifecycleRecordingObserver>>()

    @Before fun allowAndroidForWebsiteLifecycleTests() {
        // This class tests website/request lifecycle. RuntimeSitePermissionIntegrationTest
        // separately clicks the Android dialogs on a fresh AVD.
        for(permission in MEDIA_PERMISSIONS) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        assertAndroidMediaGranted()
    }

    @After fun clearWebsiteChoicesAndCloseFixtures() {
        compose.runOnIdle {
            recordingObservers.forEach { (session, observer) -> session.unregister(observer) }
            recordingObservers.clear()
        }
        val result = runBlocking {
            browser.clearBrowsingData(BrowsingDataClearRequest(setOf(BrowsingDataType.SITE_SETTINGS), BrowsingDataRange.ALL_TIME))
        }
        assertTrue("Both website and native site settings must finish clearing", result.successful)
        compose.runOnIdle { tabs.forEach(browser::closeTab) }
    }

    @Test fun pendingMediaRequestSurvivesActivityRecreationAndKeepsItsOwner() {
        LifecyclePermissionOrigin().use { origin ->
            val owner = page(origin)
            val generation = origin.documentCount.get()
            requestFromWebButton()
            val original = awaitPrompt(origin)
            assertEquals(MEDIA_KINDS, original.kinds)
            title(owner, "Lifecycle pending $generation")
            val retiredActivity = compose.activity

            // Recreate the real host without replacing the Gecko document or
            // resolving its pending PermissionRequest through a test callback.
            compose.activityRule.scenario.recreate()
            compose.runOnIdle { assertNotSame("The Activity must actually be recreated", retiredActivity, compose.activity) }
            val recreated = awaitPrompt(origin)
            assertEquals("Recreation must retain the same Gecko request", original.token, recreated.token)
            assertEquals(original.origin, recreated.origin)
            assertEquals(original.topLevelOrigin, recreated.topLevelOrigin)
            assertEquals(MEDIA_KINDS, recreated.kinds)
            assertEquals("The original tab must still own the request", owner, browser.state.value.selectedTabId)
            assertEquals("Recreation must not replace the waiting website document", generation, origin.documentCount.get())
            title(owner, "Lifecycle pending $generation")

            compose.onNodeWithText(context.getString(R.string.site_permission_block)).performClick()
            denied(owner, generation, origin)
            assertNull(browser.permissions.prompt.value)
            val blocked = awaitSite(owner)
            for(kind in MEDIA_KINDS) assertEquals("The original document must receive the temporary website BLOCK",
                SitePermissionChoice.BLOCK, blocked.choices[kind])
            assertAndroidMediaGranted()

            // The unchecked Remember box made this a per-document choice.
            compose.runOnIdle { browser.reload() }
            title(owner, "Lifecycle ready ${generation + 1}")
            val replacement = awaitSite(owner)
            for(kind in MEDIA_KINDS) assertEquals("Temporary BLOCK must not leak into the next document",
                SitePermissionChoice.ASK, replacement.choices[kind])
        }
    }

    @Test fun cameraBlockReloadsTheDocumentAndStopsActualAudioVideoCapture() {
        LifecyclePermissionOrigin().use { origin ->
            val owner = page(origin)
            val generation = origin.documentCount.get()
            val recording = observeNativeRecording(owner)
            requestFromWebButton()
            assertEquals(MEDIA_KINDS, awaitPrompt(origin).kinds)
            compose.onNode(isToggleable()).performClick().assertIsOn()
            compose.onNodeWithText(context.getString(R.string.site_permission_allow)).performClick()
            try { title(owner, "Lifecycle live audio,video $generation") }
            catch(failure: Throwable) {
                throw AssertionError("Actual audio/video capture failed: ${origin.mediaFailure.get() ?: "no fixture diagnostic"}", failure)
            }
            compose.waitUntil(20_000) { origin.liveHeartbeatCount(generation) >= 2 }
            compose.waitUntil(20_000) { recording.events.any(::bothDevicesRecording) }
            assertTrue("Native Gecko must report both actual recording devices before revocation",
                bothDevicesRecording(recording.events.last()))
            val allowed = awaitSite(owner)
            for(kind in MEDIA_KINDS) assertEquals(SitePermissionChoice.ALLOW, allowed.choices[kind])
            assertAndroidMediaGranted()

            // Use the actual per-site controls. The fixture intentionally retains
            // its live stream and never calls track.stop(); production must revoke it.
            compose.onNodeWithContentDescription(context.getString(app.miru.toolbar.R.string.toolbar_site_info)).performClick()
            compose.onNodeWithContentDescription(context.getString(
                R.string.site_permission_change, context.getString(R.string.site_permission_camera),
            )).performScrollTo().performClick()
            assertTrue("Both native devices must still be recording when camera is revoked",
                bothDevicesRecording(recording.events.last()))
            val revokeEventStart = recording.events.size
            compose.onNodeWithText(context.getString(R.string.site_permission_block)).performClick()
            compose.waitUntil(20_000) { origin.documentCount.get() > generation }
            val replacementGeneration = generation + 1
            title(owner, "Lifecycle ready $replacementGeneration")
            compose.waitUntil(10_000) { origin.departedGenerations.contains(generation) }
            compose.waitUntil(20_000) {
                recording.events.drop(revokeEventStart).any { devices -> devices.none { it.status == RecordingDevice.Status.RECORDING } }
            }
            assertTrue("The old ordinary document must send its real pagehide beacon", origin.departedGenerations.contains(generation))
            assertTrue("A later native Gecko event must confirm capture is idle after revocation",
                recording.events.last().none { it.status == RecordingDevice.Status.RECORDING })
            assertEquals("The replacement document must not automatically request media", 0,
                origin.requestCount(replacementGeneration))
            val blocked = awaitSite(owner)
            assertEquals(SitePermissionChoice.BLOCK, blocked.choices[SitePermissionKind.CAMERA])
            assertEquals("Changing camera must preserve the remembered microphone choice",
                SitePermissionChoice.ALLOW, blocked.choices[SitePermissionKind.MICROPHONE])
            assertAndroidMediaGranted()

            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            requestFromWebButton()
            denied(owner, replacementGeneration, origin)
            compose.waitUntil(10_000) { origin.requestCount(replacementGeneration) == 1 }
            assertNull("Remembered camera BLOCK must reject without reopening the website prompt", browser.permissions.prompt.value)
            assertTrue("The rejected replacement request must not restart native capture",
                recording.events.last().none { it.status == RecordingDevice.Status.RECORDING })
            val afterRetry = awaitSite(owner)
            assertEquals(SitePermissionChoice.BLOCK, afterRetry.choices[SitePermissionKind.CAMERA])
            assertEquals(SitePermissionChoice.ALLOW, afterRetry.choices[SitePermissionKind.MICROPHONE])
            assertAndroidMediaGranted()
        }
    }

    private fun assertAndroidMediaGranted() {
        for(permission in MEDIA_PERMISSIONS) assertEquals("Website policy changes must not revoke Android permission",
            PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
    }

    /** Read-only session lookup, used only by the active-stream test. */
    private fun observeNativeRecording(owner: String): LifecycleRecordingObserver {
        val observer = LifecycleRecordingObserver()
        compose.runOnIdle {
            // BrowserSnapshot intentionally omits native recording state. Read the
            // real store solely to register a passive observer on its real session.
            // No state, callbacks, device IDs, or permission policy are replaced.
            val store = browser.javaClass.getDeclaredField("store").apply { isAccessible = true }.get(browser)
            val state = store.javaClass.getMethod("getState").invoke(store)
            assertEquals(owner, state.javaClass.getMethod("getSelectedTabId").invoke(state))
            val nativeTabs = state.javaClass.getMethod("getTabs").invoke(state) as List<*>
            val nativeTab = checkNotNull(nativeTabs.firstOrNull { tab ->
                tab != null && tab.javaClass.getMethod("getId").invoke(tab) == owner
            })
            val engineState = nativeTab.javaClass.getMethod("getEngineState").invoke(nativeTab)
            val session = engineState.javaClass.getMethod("getEngineSession").invoke(engineState) as EngineSession
            session.register(observer)
            recordingObservers += session to observer
        }
        return observer
    }

    private fun bothDevicesRecording(devices: List<RecordingDevice>) =
        devices.any { it.type == RecordingDevice.Type.CAMERA && it.status == RecordingDevice.Status.RECORDING } &&
            devices.any { it.type == RecordingDevice.Type.MICROPHONE && it.status == RecordingDevice.Status.RECORDING }

    private fun page(origin: LifecyclePermissionOrigin): String {
        var id = ""
        compose.runOnIdle { id = browser.newTab("${origin.url}/media", isPrivate = false); tabs += id }
        title(id, "Lifecycle ready 1")
        return id
    }

    private fun title(id: String, expected: String) {
        try {
            compose.waitUntil(30_000) {
                browser.state.value.tabs.firstOrNull { it.id == id }?.let { it.title == expected && !it.loading } == true
            }
        } catch(failure: Throwable) {
            throw AssertionError("Expected $expected; current title=${browser.state.value.tabs.firstOrNull { it.id == id }?.title}", failure)
        }
    }

    private fun denied(id: String, generation: Int, origin: LifecyclePermissionOrigin) {
        try { title(id, "Lifecycle denied $generation") }
        catch(failure: Throwable) {
            throw AssertionError("Actual media rejection failed: ${origin.mediaFailure.get() ?: "no fixture diagnostic"}", failure)
        }
    }

    private fun awaitPrompt(origin: LifecyclePermissionOrigin): SitePermissionPrompt {
        compose.waitUntil(20_000) { browser.permissions.prompt.value != null }
        return checkNotNull(browser.permissions.prompt.value).also {
            assertEquals(origin.url, it.origin)
            assertFalse(it.isPrivate)
            compose.onNodeWithText(context.getString(R.string.site_permission_request)).assertIsDisplayed()
        }
    }

    private fun awaitSite(id: String): SitePermissionState {
        compose.runOnIdle { browser.permissions.refreshSite() }
        compose.waitUntil(10_000) { browser.permissions.siteState.value?.let { it.tabId == id && !it.busy } == true }
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
        // Both fixtures are ordinary tabs; require Gecko's real painted button.
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
        private val MEDIA_PERMISSIONS = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        private val MEDIA_KINDS = setOf(SitePermissionKind.CAMERA, SitePermissionKind.MICROPHONE)
    }
}

/** Observe actual events while leaving the coordinator's permission callbacks alone. */
private class LifecycleRecordingObserver : EngineSession.Observer {
    val events = CopyOnWriteArrayList<List<RecordingDevice>>()
    override fun onAppPermissionRequest(permissionRequest: PermissionRequest) = Unit
    override fun onContentPermissionRequest(permissionRequest: PermissionRequest) = Unit
    override fun onRecordingStateChanged(devices: List<RecordingDevice>) { events += devices.toList() }
}

/** No automatic media request and no track.stop(): revocation must stop the stream. */
private class LifecyclePermissionOrigin : Closeable {
    private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    val url = "http://127.0.0.1:${server.localPort}"
    val documentCount = AtomicInteger()
    val departedGenerations = ConcurrentHashMap.newKeySet<Int>()
    val mediaFailure = AtomicReference<String?>()
    private val requests = ConcurrentHashMap<Int, AtomicInteger>()
    private val liveHeartbeats = ConcurrentHashMap<Int, AtomicInteger>()

    fun requestCount(generation: Int) = requests[generation]?.get() ?: 0
    fun liveHeartbeatCount(generation: Int) = liveHeartbeats[generation]?.get() ?: 0

    init {
        thread(isDaemon = true, name = "miru-permission-lifecycle-origin") {
            while(running.get()) runCatching {
                val socket = server.accept()
                thread(isDaemon = true) {
                    socket.use { client -> runCatching {
                        client.soTimeout = 5_000
                        val reader = client.getInputStream().bufferedReader()
                        val target = reader.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                        val path = target.substringBefore('?')
                        while(!reader.readLine().isNullOrEmpty()) { }
                        val query = target.substringAfter('?', "").split('&').associate { part ->
                            part.substringBefore('=') to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                        }
                        val generation = query["generation"]?.toIntOrNull()
                        when(path) {
                            "/requested" -> generation?.let { requests.computeIfAbsent(it) { AtomicInteger() }.incrementAndGet() }
                            "/heartbeat" -> if(generation != null && query["live"] == "audio,video") {
                                liveHeartbeats.computeIfAbsent(generation) { AtomicInteger() }.incrementAndGet()
                            }
                            "/gone" -> generation?.let(departedGenerations::add)
                            "/failure" -> mediaFailure.set(query["data"]?.take(2_048)
                                ?.replace(Regex("https?://[^\\s\\\"]+"), "[fixture-url]"))
                        }
                        val body = if(path == "/media") document(documentCount.incrementAndGet()).toByteArray() else ByteArray(0)
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

    private fun document(generation: Int) = """
        <!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>
        <title>Lifecycle ready $generation</title>
        <style>body{margin:0}button{background:#13579b;color:white;width:220px;height:100px;border:0}</style>
        <button onclick='requestPermission()'>Request audio and video</button>
        <script>
        let activeStream=null,departing=false,lastHeartbeat=0;
        function heartbeat(now){
          if(departing)return;
          if(activeStream&&now-lastHeartbeat>=250){
            lastHeartbeat=now;
            const live=activeStream.getTracks().filter(t=>t.readyState==='live').map(t=>t.kind).sort().join(',');
            fetch('/heartbeat?generation=$generation&live='+encodeURIComponent(live),{cache:'no-store'}).catch(()=>{});
          }
          requestAnimationFrame(heartbeat);
        }
        function requestPermission(){
          document.title='Lifecycle pending $generation';
          fetch('/requested?generation=$generation',{cache:'no-store'}).catch(()=>{});
          navigator.mediaDevices.getUserMedia({audio:true,video:true}).then(s=>{
            activeStream=s;
            const live=s.getTracks().filter(t=>t.readyState==='live').map(t=>t.kind).sort().join(',');
            document.title='Lifecycle live '+live+' $generation';
            requestAnimationFrame(heartbeat);
          }).catch(e=>{
            const diagnostic={name:e.name,message:String(e.message).slice(0,512),secure:globalThis.isSecureContext,focused:document.hasFocus()};
            fetch('/failure?data='+encodeURIComponent(JSON.stringify(diagnostic))).catch(()=>{});
            document.title='Lifecycle '+(e.name==='NotAllowedError'?'denied':e.name)+' $generation';
          });
        }
        addEventListener('pagehide',()=>{departing=true;navigator.sendBeacon('/gone?generation=$generation','gone')},{once:true});
        </script>
    """.trimIndent()

    override fun close() {
        running.set(false)
        server.close()
    }
}
