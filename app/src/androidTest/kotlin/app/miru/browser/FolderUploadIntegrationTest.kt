// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.miru.browser.engine.BrowserPromptKind
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import mozilla.components.concept.engine.EngineView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pending first build/runtime: genuine Gecko directory prompts, DocumentsUI TREE and POST bytes. */
@RunWith(AndroidJUnit4::class)
class FolderUploadIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val browser get() = (compose.activity.application as MiruApplication).container.browser
    private val tabs = BrowserTabFixture()
    private var oldAccessibilityFlags: Int? = null
    private var monitor: Instrumentation.ActivityMonitor? = null
    private val starts = AtomicInteger()
    private var primaryFailure: Throwable? = null
    private var tabsCaptured = false

    @Before fun preserveTabsAndObserveTheRealTreeLaunch() = preservingPrimaryFailure {
        compose.waitUntil(30_000) { browser.state.value.ready }
        compose.runOnIdle {
            assertTrue("Use a controlled fixture without pre-existing private tabs", browser.state.value.tabs.none { it.isPrivate })
            tabs.capture(browser)
            tabsCaptured = true
        }
        oldAccessibilityFlags = instrumentation.uiAutomation.serviceInfo.flags
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        starts.set(0)
        monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if(intent.action == Intent.ACTION_OPEN_DOCUMENT_TREE) starts.incrementAndGet()
                return null // Never inject a result or intercept the real system picker.
            }
        }.also(instrumentation::addMonitor)
    }

    @After fun closeOnlyOwnedTabsAndRestoreObservation() {
        val failures = mutableListOf<Throwable>()
        fun cleanup(action: () -> Unit) { try { action() } catch(failure: Throwable) { failures += failure } }
        cleanup {
            if(treeGrantDialogVisible()) {
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                waitFor { !treeGrantDialogVisible() }
            }
        }
        cleanup {
            if(documentsVisible()) {
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                waitFor { !documentsVisible() }
            }
        }
        // A failure in either system stage must not prevent app/native ownership cleanup.
        cleanup { if(tabsCaptured) compose.runOnIdle { tabs.closeCreatedTabs(browser) } }
        cleanup { monitor?.let(instrumentation::removeMonitor) }
        cleanup { oldAccessibilityFlags?.let { flags -> instrumentation.uiAutomation.serviceInfo =
            instrumentation.uiAutomation.serviceInfo.apply { this.flags = flags } } }
        if(failures.isNotEmpty()) {
            val original = primaryFailure
            if(original != null) failures.filter { it !== original }.forEach(original::addSuppressed)
            else {
                val first = failures.first()
                failures.drop(1).filter { it !== first }.forEach(first::addSuppressed)
                throw first
            }
        }
    }

    @Test fun completeTreeKeepsTheRealRootRelativePathsDeepFilesAndUploadedBytes() = preservingPrimaryFailure { allowFolder(empty = false, private = false) }
    @Test fun privateTreeRetainsBytesUntilItsOwnerClosesThenDeletesItsLease() = preservingPrimaryFailure { allowFolder(empty = false, private = true) }
    @Test fun anEmptyTreeStillNeedsRealConfirmationAndProducesAnEmptyChange() = preservingPrimaryFailure { allowFolder(empty = true, private = false) }

    @Test fun decliningTheRealSecondConfirmationDoesNotGiveTheWebsiteAnyFiles() = preservingPrimaryFailure {
        FolderOrigin().use { origin ->
            val baseline = leases()
            page(origin, private = false)
            chooseTree(empty = false)
            awaitConfirmation(UploadTreeDocumentsProvider.ROOT_NAME)
            assertEquals(0, origin.reports.size)
            assertEquals(0, origin.uploads.size)
            val selectedLeases = leases() - baseline
            assertEquals(1, selectedLeases.size)
            compose.onNodeWithTag("page_prompt_cancel").performClick()
            waitFor { browser.prompts.prompt.value == null }
            waitFor { selectedLeases.none(File::exists) }
            touchButton(145f)
            val report = awaitReport(origin)
            assertEquals("inspect", report.getString("event"))
            assertEquals(0, report.getJSONArray("files").length())
            assertEquals(0, origin.uploads.size)
            assertEquals(1, starts.get())
        }
    }

    @Test fun anExplicitMimeThatTheLocalRootRouteCannotPreserveFailsWithoutAPartialUpload() = preservingPrimaryFailure {
        FolderOrigin().use { origin ->
            val baseline = leases()
            page(origin, private = false)
            chooseTree(empty = false, labelOverride = UploadTreeDocumentsProvider.MIME_ROOT_LABEL)
            waitFor { compose.onAllNodesWithText(context.getString(R.string.upload_folder_unavailable)).fetchSemanticsNodes().isNotEmpty() }
            waitFor { browser.prompts.prompt.value == null && leases() == baseline && !receiverPresent() }
            assertEquals(0, origin.reports.size)
            assertEquals(0, origin.uploads.size)
            touchButton(145f)
            val report = awaitReport(origin)
            assertEquals("inspect", report.getString("event"))
            assertEquals(0, report.getJSONArray("files").length())
            assertEquals(0, origin.uploads.size)
        }
    }

    private fun allowFolder(empty: Boolean, private: Boolean) {
        FolderOrigin().use { origin ->
            val id = if(empty) UploadTreeDocumentsProvider.EMPTY_ID else UploadTreeDocumentsProvider.ROOT_ID
            val tree = DocumentsContract.buildTreeDocumentUri(UploadTreeDocumentsProvider.AUTHORITY, id)
            val grantBefore = listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .map { context.checkUriPermission(tree, Process.myPid(), Process.myUid(), it) }
            val persistedBefore = context.contentResolver.persistedUriPermissions.filter { it.uri.authority == UploadTreeDocumentsProvider.AUTHORITY }
                .map { Triple(it.uri, it.isReadPermission, it.isWritePermission) }.toSet()
            val baseline = leases()
            val owner = page(origin, private)
            chooseTree(empty)
            awaitConfirmation(if(empty) UploadTreeDocumentsProvider.EMPTY_NAME else UploadTreeDocumentsProvider.ROOT_NAME)
            assertEquals("Choosing a tree must not automatically approve its native upload", 0, origin.reports.size)
            val selectedLeases = leases() - baseline
            assertEquals(1, selectedLeases.size)
            compose.onNodeWithTag("page_prompt_folder_confirm").performClick()
            val report = awaitReport(origin)
            assertEquals("change", report.getString("event"))
            val expected = if(empty) emptyMap() else UploadTreeDocumentsProvider.expectedFiles()
            val files = report.getJSONArray("files")
            assertEquals(expected.size, files.length())
            val actualPaths = mutableSetOf<String>()
            for(index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                val path = file.getString("path")
                assertTrue("The real directory FileList must not duplicate or invent a path", actualPaths.add(path))
                val bytes = checkNotNull(expected[path]) { "Unexpected synthetic relative path" }
                assertEquals(path.substringAfterLast('/'), file.getString("name"))
                assertEquals(bytes.size.toLong(), file.getLong("size"))
                val mime = when { path.endsWith(".txt") -> "text/plain"; path.endsWith(".png") -> "image/png"; else -> "application/octet-stream" }
                assertEquals(mime, file.getString("type"))
                assertArrayEquals(bytes, origin.uploads[index])
            }
            assertEquals(expected.keys, actualPaths)
            assertEquals(1, starts.get())
            if(private) compose.runOnIdle { assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
            waitFor {
                val after = listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    .map { context.checkUriPermission(tree, Process.myPid(), Process.myUid(), it) }
                after == grantBefore && !receiverPresent()
            }
            assertEquals(persistedBefore, context.contentResolver.persistedUriPermissions
                .filter { it.uri.authority == UploadTreeDocumentsProvider.AUTHORITY }
                .map { Triple(it.uri, it.isReadPermission, it.isWritePermission) }.toSet())
            assertTrue("Native completion must not delete the root before Gecko reads it", selectedLeases.all(File::exists))
            compose.runOnIdle { browser.closeTab(owner) }
            waitFor { selectedLeases.none(File::exists) }
        }
    }

    private fun page(origin: FolderOrigin, private: Boolean): String {
        var owner = ""
        compose.runOnIdle { owner = browser.newTab(origin.url, isPrivate = private) }
        compose.waitUntil(30_000) {
            browser.state.value.selectedTab?.let { it.id == owner && it.title == "Folder ready" && !it.loading } == true && origin.ready.get() > 0
        }
        return owner
    }

    private fun chooseTree(empty: Boolean, labelOverride: String? = null) {
        touchButton(40f)
        waitFor { browser.prompts.prompt.value?.kind == BrowserPromptKind.FOLDER }
        assertNull(browser.prompts.prompt.value!!.origin)
        compose.onNodeWithTag("page_prompt_folder_choose").performClick()
        waitFor { documentsVisible() }
        val label = labelOverride ?: if(empty) UploadTreeDocumentsProvider.EMPTY_LABEL else UploadTreeDocumentsProvider.ROOT_LABEL
        if(!clickText(label)) {
            waitFor {
                withSystemRoot { root ->
                    for(namespace in DOCUMENTS_PACKAGES + "android") {
                        val nodes = root.findAccessibilityNodeInfosByViewId("$namespace:id/home")
                        try { if(nodes.any(::clickAncestor)) return@withSystemRoot true }
                        finally { nodes.forEach { it.recycle() } }
                    }
                    // The appcompat toolbar exposes its navigation button as an ImageButton.
                    fun navigation(node: AccessibilityNodeInfo): Boolean {
                        if(node.className?.toString() == "android.widget.ImageButton" && clickAncestor(node)) return true
                        for(index in 0 until node.childCount) {
                            val child = node.getChild(index) ?: continue
                            try { if(navigation(child)) return true } finally { child.recycle() }
                        }
                        return false
                    }
                    navigation(root)
                } == true
            }
            waitFor { clickText(label) }
        }
        var useClicked = false
        var grantDialogObserved = false
        var allowClicked = false
        waitFor {
            if(!documentsVisible()) return@waitFor true
            withSystemRoot { root ->
                // Saved real UI: both stages use android:id/button1. Identify the
                // directory action and the grant dialog by their actual labels/title.
                if(isTreeGrantDialog(root, label)) {
                    assertTrue("System grant confirmation must follow the real use-folder action", useClicked)
                    grantDialogObserved = true
                    if(!allowClicked && clickExactButton(root, "android:id/button1", "ALLOW")) {
                        allowClicked = true
                        android.util.Log.i("MiruFolderPickerTest", "stage=SYSTEM_ALLOW_CLICKED")
                    }
                } else if(!useClicked && directoryTitleMatches(root, label) &&
                    clickExactButton(root, "android:id/button1", "USE THIS FOLDER")) {
                    useClicked = true
                    android.util.Log.i("MiruFolderPickerTest", "stage=USE_FOLDER_CLICKED")
                }
            }
            false
        }
        assertTrue("A real TREE use-folder action must be observed", useClicked)
        assertTrue("The actual system TREE grant dialog must be observed", grantDialogObserved)
        assertTrue("The actual system TREE grant must be explicitly allowed", allowClicked)
    }

    private fun clickExactButton(root: AccessibilityNodeInfo, id: String, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(id)
        try { return nodes.any { it.text?.toString() == text && it.isVisibleToUser && it.isEnabled && clickAncestor(it) } }
        finally { nodes.forEach { it.recycle() } }
    }

    private fun directoryTitleMatches(root: AccessibilityNodeInfo, label: String): Boolean {
        for(namespace in DOCUMENTS_PACKAGES) {
            val nodes = root.findAccessibilityNodeInfosByViewId("$namespace:id/breadcrumb_text")
            try { if(nodes.any { it.isVisibleToUser && it.text?.toString() == label }) return true }
            finally { nodes.forEach { it.recycle() } }
        }
        return false
    }

    private fun isTreeGrantDialog(root: AccessibilityNodeInfo, label: String): Boolean {
        val expected = "Allow Miru to access files in $label?"
        for(namespace in DOCUMENTS_PACKAGES) {
            val titles = root.findAccessibilityNodeInfosByViewId("$namespace:id/alertTitle")
            try { if(titles.any { it.isVisibleToUser && it.text?.toString() == expected }) return true }
            finally { titles.forEach { it.recycle() } }
        }
        return false
    }

    private fun treeGrantDialogVisible(): Boolean = withSystemRoot { root ->
        listOf(UploadTreeDocumentsProvider.ROOT_LABEL, UploadTreeDocumentsProvider.EMPTY_LABEL,
            UploadTreeDocumentsProvider.MIME_ROOT_LABEL).any { isTreeGrantDialog(root, it) }
    } == true

    private inline fun preservingPrimaryFailure(action: () -> Unit) {
        try { action() }
        catch(failure: Throwable) { if(primaryFailure == null) primaryFailure = failure; throw failure }
    }

    private fun awaitConfirmation(name: String) {
        waitFor { browser.prompts.prompt.value?.let { it.kind == BrowserPromptKind.FOLDER_CONFIRM && it.folderName == name } == true }
    }

    private fun awaitReport(origin: FolderOrigin): JSONObject {
        compose.waitUntil(30_000) {
            origin.failure.get()?.let { throw AssertionError("Synthetic folder server failed", it) }
            origin.reports.singleOrNull()?.let { report ->
                (0 until report.getJSONArray("files").length()).all { origin.uploads[it] != null }
            } == true
        }
        return origin.reports.single()
    }

    private fun leases() = File(context.cacheDir, "miru-folder-staging").listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("folder-lease-") }.toSet()

    private fun receiverPresent(): Boolean {
        var present = false
        instrumentation.runOnMainSync { present = listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
            .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.any { it is UploadPickerActivity } }
        return present
    }

    private fun touchButton(yDp: Float) {
        var bounds = android.graphics.Rect()
        compose.runOnIdle {
            fun find(view: View): EngineView? = if(view is EngineView) view else (view as? ViewGroup)?.let { group ->
                (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
            }
            val engine = checkNotNull(find(compose.activity.window.decorView)).asView()
            val xy = IntArray(2); engine.getLocationOnScreen(xy)
            bounds = android.graphics.Rect(xy[0], xy[1], xy[0] + engine.width, xy[1] + engine.height)
        }
        val density = context.resources.displayMetrics.density
        val down = SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.left + 40 * density, bounds.top + yDp * density, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }

    private fun clickText(text: String): Boolean = withSystemRoot { root ->
        val nodes = root.findAccessibilityNodeInfosByText(text)
        try { nodes.any { it.text?.toString() == text && clickAncestor(it) } }
        finally { nodes.forEach { it.recycle() } }
    } == true

    private fun clickAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        try { repeat(8) {
            val item = current ?: return false
            if(item.isVisibleToUser && item.isEnabled && item.isClickable && item.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            val next = item.parent; item.recycle(); current = next
        }; return false } finally { current?.recycle() }
    }

    private fun <T> withSystemRoot(block: (AccessibilityNodeInfo) -> T): T? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        try { return if(root.packageName?.toString() in DOCUMENTS_PACKAGES) block(root) else null }
        finally { root.recycle() }
    }
    private fun documentsVisible() = withSystemRoot { true } == true
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(20_000, condition)
    companion object { private val DOCUMENTS_PACKAGES = setOf("com.android.documentsui", "com.google.android.documentsui") }
}

private class FolderOrigin : Closeable {
    private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val ready = AtomicInteger()
    val reports = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
    val uploads = ConcurrentHashMap<Int, ByteArray>()
    val failure = AtomicReference<Throwable?>()
    val url = "http://127.0.0.1:${server.localPort}/"
    init { thread(isDaemon = true, name = "miru-folder-origin") {
        while(running.get()) {
            val socket = try { server.accept() } catch(_: Exception) { break }
            sockets += socket
            thread(isDaemon = true) { try { socket.use(::respond) }
                catch(problem: Exception) { if(running.get()) failure.compareAndSet(null, problem) }
                finally { sockets -= socket } }
        }
    } }
    private fun respond(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream().buffered()
        val request = (line(input) ?: return).split(' ')
        val target = request[1]
        val headers = mutableMapOf<String, String>()
        while(true) {
            val value = checkNotNull(line(input)); if(value.isEmpty()) break
            check(headers.size < 64); headers[value.substringBefore(':').lowercase()] = value.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        check(length in 0..1_048_576)
        val body = ByteArray(length)
        var position = 0
        while(position < length) { val count = input.read(body, position, length - position); check(count > 0); position += count }
        val content = when(target.substringBefore('?')) {
            "/ready" -> { ready.incrementAndGet(); "ok" }
            "/report" -> { check(request[0] == "POST"); reports += JSONObject(body.toString(Charsets.UTF_8)); "ok" }
            "/upload" -> { check(request[0] == "POST"); val index = target.substringAfter("index=").toInt();
                check(uploads.putIfAbsent(index, body) == null); "ok" }
            else -> document()
        }.toByteArray()
        val type = if(target == "/") "text/html; charset=utf-8" else "text/plain"
        socket.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${content.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            write(content); flush()
        }
    }
    private fun document() = """
        <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Folder ready</title>
        <style>body{margin:0}button{display:block;width:220px;height:100px;background:#13579b;color:white;border:0}#inspect{margin-top:10px}</style>
        <button id="pick">Choose directory fixture</button><button id="inspect">Inspect files</button>
        <input id="files" type="file" webkitdirectory multiple hidden>
        <script>
        const input=document.getElementById('files');
        async function report(event){
          const files=Array.from(input.files);
          for(let i=0;i<files.length;i++)await fetch('/upload?index='+i,{method:'POST',body:await files[i].arrayBuffer()});
          await fetch('/report',{method:'POST',body:JSON.stringify({event,files:files.map(f=>({name:f.name,type:f.type,size:f.size,path:f.webkitRelativePath}))})});
        }
        document.getElementById('pick').onclick=()=>input.click();
        document.getElementById('inspect').onclick=()=>report('inspect');
        input.addEventListener('change',()=>report('change'));
        requestAnimationFrame(()=>requestAnimationFrame(()=>fetch('/ready')));
        </script>
    """.trimIndent()
    private fun line(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while(bytes.size() <= 8192) {
            val byte = input.read()
            if(byte < 0) return if(bytes.size() == 0) null else error("Incomplete fixture line")
            if(byte == 10) return bytes.toString("ISO-8859-1").removeSuffix("\r")
            bytes.write(byte)
        }; error("Oversize fixture line")
    }
    override fun close() { running.set(false); server.close(); sockets.toList().forEach { runCatching { it.close() } } }
}
