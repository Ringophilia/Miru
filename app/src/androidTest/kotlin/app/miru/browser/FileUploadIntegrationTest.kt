// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.os.Process
import android.provider.DocumentsContract
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.miru.browser.engine.BrowserPromptKind
import app.miru.browser.engine.BrowserPromptState
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
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

/**
 * Real system upload acceptance. Run alone on a controlled AVD without existing private tabs.
 * Real Gecko input, real DocumentsUI/provider results, and website POST bytes; no injected results,
 * PromptRequest, FileList, or native selection callbacks. Provider content is synthetic and static.
 */
@RunWith(AndroidJUnit4::class)
class FileUploadIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val browser get() = (compose.activity.application as MiruApplication).container.browser
    private val fixtureTabs = mutableListOf<String>()
    private var originalTabs = emptySet<String>()
    private var originalSelection: String? = null
    private var originalAccessibilityFlags: Int? = null
    private var pickerMonitor: Instrumentation.ActivityMonitor? = null
    private val pickerLaunchCount = AtomicInteger()
    private val pickerMultipleFlag = AtomicReference<Boolean?>()

    @Before fun preserveExistingTabsAndExposeActualSystemNodes() {
        compose.waitUntil(30_000) { browser.state.value.ready }
        // Navigation is allowed after the browser's bounded extension startup window.
        // A valid file request must also work while add-on readiness is still unconfirmed.
        android.util.Log.i("MiruUploadAcceptance", "setup adblockStatus=${browser.adblock.value.status}")
        compose.runOnIdle {
            assertTrue("Run without pre-existing private tabs; never screenshot somebody else's private state",
                browser.state.value.tabs.none { it.isPrivate })
            originalTabs = browser.state.value.tabs.mapTo(mutableSetOf()) { it.id }
            originalSelection = browser.state.value.selectedTabId
        }
        val automation = instrumentation.uiAutomation
        originalAccessibilityFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        // The callback observes the real start before IntentFilter MIME/category matching.
        // Returning null always lets Android launch it and never supplies an ActivityResult.
        pickerLaunchCount.set(0)
        pickerMultipleFlag.set(null)
        pickerMonitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) {
                    pickerMultipleFlag.set(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
                    pickerLaunchCount.incrementAndGet()
                }
                return null
            }
        }.also(instrumentation::addMonitor)
    }

    @After fun closeOnlyFixtureStateAndRestoreSystemObservation() {
        var failure: Throwable? = null
        fun rememberFailure(problem: Throwable) {
            val first = failure
            if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
        }
        fun cleanup(action: () -> Unit) {
            try { action() } catch (problem: Throwable) { rememberFailure(problem) }
        }
        try {
            if (documentsUiVisible()) {
                systemBack()
                compose.waitUntil(10_000) { !documentsUiVisible() }
            }
        } catch (problem: Throwable) {
            rememberFailure(problem)
        } finally {
            try {
                cleanup {
                    compose.runOnIdle {
                        cleanup {
                            browser.prompts.prompt.value?.takeIf { it.tabId in fixtureTabs }?.let { browser.prompts.dismiss(it.token) }
                        }
                        fixtureTabs.filter { id -> browser.state.value.tabs.any { it.id == id } }.forEach { id ->
                            cleanup { browser.closeTab(id) }
                        }
                        cleanup {
                            originalSelection?.takeIf { id -> browser.state.value.tabs.any { it.id == id } }?.let(browser::selectTab)
                        }
                        cleanup {
                            assertTrue("Pre-existing tabs must survive fixture cleanup",
                                browser.state.value.tabs.map { it.id }.containsAll(originalTabs))
                        }
                    }
                }
            } finally {
                try { cleanup { pickerMonitor?.let(instrumentation::removeMonitor) } }
                finally {
                    cleanup {
                        originalAccessibilityFlags?.let { saved ->
                            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply { flags = saved }
                        }
                    }
                }
            }
        }
        failure?.let { throw it }
    }

    @Test fun singleFileKeepsTheWebsiteNameMimeSizeAndUploadedBytes() {
        UploadOrigin().use { origin ->
            val owner = page(origin, multiple = false)
            beginSelection(owner)
            openFixtureRoot()
            selectSingle()
            val report = awaitReport(origin, 1)
            assertEquals("change", report.label)
            assertEquals(1, report.files.size)
            assertFile(report.files.single(), UploadFixtureDocumentsProvider.SINGLE_NAME,
                UploadFixtureDocumentsProvider.SINGLE_BYTES, origin.upload(1, 0))
            assertEquals(1, pickerStarts())
            assertNull(browser.prompts.prompt.value)
            awaitReceiverFinishedAndTemporaryGrantReleased()
        }
    }

    @Test fun multipleDocumentsWithTheSameDisplayNameKeepBothDistinctBodies() {
        UploadOrigin().use { origin ->
            val owner = page(origin, multiple = true)
            val request = beginSelection(owner)
            assertTrue("The actual native File request must allow multiple files", request.fileMultiple)
            assertEquals("The actual Android OPEN_DOCUMENT launch must allow multiple files", true, pickerMultipleFlag.get())
            openFixtureRoot()
            waitFor("Both same.txt rows must come from the real provider") { exactDocumentRows(UploadFixtureDocumentsProvider.SAME_NAME) == 2 }
            performDocumentRowAction(UploadFixtureDocumentsProvider.SAME_NAME, index = 0,
                action = AccessibilityNodeInfo.ACTION_LONG_CLICK)
            awaitActualDocumentSelection(listOf(true, false))
            performDocumentRowAction(UploadFixtureDocumentsProvider.SAME_NAME, index = 1,
                action = AccessibilityNodeInfo.ACTION_CLICK)
            awaitActualDocumentSelection(listOf(true, true))
            finishPickerSelection(required = true)
            val report = awaitReport(origin, 1)
            assertEquals(2, report.files.size)
            assertTrue(report.files.all { it.name == UploadFixtureDocumentsProvider.SAME_NAME && it.mime == "text/plain" })
            val actual = report.files.indices.map { index ->
                val body = checkNotNull(origin.upload(1, index))
                assertEquals(report.files[index].size, body.size.toLong())
                uploadHash(body)
            }.toSet()
            assertEquals(setOf(uploadHash(UploadFixtureDocumentsProvider.SAME_A_BYTES),
                uploadHash(UploadFixtureDocumentsProvider.SAME_B_BYTES)), actual)
            assertEquals(1, pickerStarts())
            assertNull(browser.prompts.prompt.value)
        }
    }

    @Test fun systemBackCancelsTheNewPickerWithoutChangingThePreviousWebsiteSelection() {
        UploadOrigin().use { origin ->
            val owner = page(origin, multiple = false)
            beginSelection(owner)
            openFixtureRoot()
            selectSingle()
            awaitReport(origin, 1)

            beginSelection(owner)
            openFixtureRoot()
            systemBack()
            waitFor("Android Back must close DocumentsUI") { !documentsUiVisible() }
            waitFor("Cancelled system selection must complete its native request") { browser.prompts.prompt.value == null }
            touchWebButton(yDp = 145f) // A second real DOM button inspects the retained FileList.
            val report = awaitReport(origin, 2)
            assertEquals("inspect", report.label)
            assertEquals("Cancelling must not generate a replacement change result", 1, origin.reportCount("change"))
            assertEquals(1, report.files.size)
            assertFile(report.files.single(), UploadFixtureDocumentsProvider.SINGLE_NAME,
                UploadFixtureDocumentsProvider.SINGLE_BYTES, origin.upload(2, 0))
            assertEquals(2, pickerStarts())

            // Cancelling Miru's own native-request sheet must preserve that selection too,
            // and must not start a third external picker.
            openFilePrompt(owner)
            compose.onNodeWithTag("page_prompt_cancel").performClick()
            waitFor("The real application cancel action must finish the native File request") { browser.prompts.prompt.value == null }
            assertFalse(documentsUiVisible())
            assertEquals(2, pickerStarts())
            touchWebButton(yDp = 145f)
            val afterAppCancel = awaitReport(origin, 3)
            assertEquals("inspect", afterAppCancel.label)
            assertEquals(1, afterAppCancel.files.size)
            assertFile(afterAppCancel.files.single(), UploadFixtureDocumentsProvider.SINGLE_NAME,
                UploadFixtureDocumentsProvider.SINGLE_BYTES, origin.upload(3, 0))
            assertEquals(1, origin.reportCount("change"))
        }
    }

    @Test fun recreationWhileDocumentsUiIsOpenRetainsTheOriginalTokenAndLaunchesOnce() {
        UploadOrigin().use { origin ->
            val owner = page(origin, multiple = false)
            val original = beginSelection(owner)
            openFixtureRoot()
            assertEquals(1, pickerStarts())
            recreateStoppedUploadHosts()
            val retained = checkNotNull(browser.prompts.prompt.value)
            assertEquals(original.token, retained.token)
            assertEquals(owner, retained.tabId)
            assertEquals("Recreation must not launch a second picker", 1, pickerStarts())
            selectSingle()
            val report = awaitReport(origin, 1)
            assertFile(report.files.single(), UploadFixtureDocumentsProvider.SINGLE_NAME,
                UploadFixtureDocumentsProvider.SINGLE_BYTES, origin.upload(1, 0))
            assertEquals("The original document must not be fetched again", 1, origin.pageRequests.get())
            assertEquals(1, pickerStarts())
            assertNull(browser.prompts.prompt.value)
        }
    }

    @Test fun sameDocumentHashNavigationKeepsTheOpenPickerAndItsOriginalNativeRequest() {
        UploadOrigin().use { origin ->
            val owner = page(origin, multiple = false)
            val originalUrl = checkNotNull(browser.state.value.tabs.firstOrNull { it.id == owner }?.url)
            val original = beginSelection(owner)
            openFixtureRoot()

            // This is a real Gecko navigation while the real external picker is open.
            // The native same-document flag must preserve this request, without a new page GET.
            val destination = "$originalUrl#same-document"
            compose.runOnIdle { browser.navigate(destination) }
            waitFor("Gecko must report the actual same-document hash location") {
                browser.state.value.tabs.firstOrNull { it.id == owner }?.url == destination
            }
            val retained = checkNotNull(browser.prompts.prompt.value)
            assertEquals(original.token, retained.token)
            assertEquals(owner, retained.tabId)
            assertEquals(BrowserPromptKind.FILE, retained.kind)
            assertEquals("A same-document navigation must not fetch a replacement document", 1, origin.pageRequests.get())
            assertTrue("The original DocumentsUI picker must remain open", documentsUiVisible())
            assertEquals("A hash change must not launch another picker", 1, pickerStarts())

            selectSingle()
            val report = awaitReport(origin, 1)
            assertEquals("change", report.label)
            assertEquals(1, report.files.size)
            assertFile(report.files.single(), UploadFixtureDocumentsProvider.SINGLE_NAME,
                UploadFixtureDocumentsProvider.SINGLE_BYTES, origin.upload(1, 0))
            assertEquals(1, origin.pageRequests.get())
            assertEquals(1, pickerStarts())
            assertNull(browser.prompts.prompt.value)
        }
    }

    @Test fun privateUploadKeepsItsFileUntilTheLastOwnerClosesThenDeletesTheLease() {
        UploadOrigin().use { origin ->
            val baseline = uploadLeaseDirectories()
            val owner = page(origin, multiple = false, isPrivate = true)
            beginSelection(owner)
            openFixtureRoot()
            assertSecureWindow()
            val receivers = liveActivities().filterIsInstance<UploadPickerActivity>()
            assertEquals("Exactly one real receiver must own the private system selection", 1, receivers.size)
            instrumentation.runOnMainSync {
                assertTrue("The private receiving activity must retain FLAG_SECURE",
                    receivers.single().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            selectSingle()
            val report = awaitReport(origin, 1)
            assertEquals(1, report.files.size)
            assertFile(report.files.single(), UploadFixtureDocumentsProvider.SINGLE_NAME,
                UploadFixtureDocumentsProvider.SINGLE_BYTES, origin.upload(1, 0))
            assertEquals(1, pickerStarts())
            assertSecureWindow()
            awaitReceiverFinishedAndTemporaryGrantReleased()
            val leases = awaitNewUploadLeases(baseline)
            assertSingleFixtureRemainsReadable(leases)

            compose.runOnIdle {
                assertEquals("This fixture must own the last private tab", listOf(owner),
                    browser.state.value.tabs.filter { it.isPrivate }.map { it.id })
                browser.closeTab(owner)
            }
            waitFor("Closing the last private owner must retire its page and native request") {
                browser.state.value.tabs.none { it.isPrivate } && browser.prompts.prompt.value == null
            }
            awaitUploadLeasesDeleted(leases)
            awaitReceiverFinishedAndTemporaryGrantReleased()
            assertEquals("Closing a private page must not send another website upload", 1, origin.reportCount("change"))
        }
    }

    @Test fun aNewSameOriginDocumentDeletesThePreviousDocumentsDeliveredLease() {
        UploadOrigin().use { origin ->
            val baseline = uploadLeaseDirectories()
            val owner = page(origin, multiple = false)
            beginSelection(owner)
            openFixtureRoot()
            selectSingle()
            val report = awaitReport(origin, 1)
            assertFile(report.files.single(), UploadFixtureDocumentsProvider.SINGLE_NAME,
                UploadFixtureDocumentsProvider.SINGLE_BYTES, origin.upload(1, 0))
            awaitReceiverFinishedAndTemporaryGrantReleased()
            val leases = awaitNewUploadLeases(baseline)
            assertSingleFixtureRemainsReadable(leases)

            val destination = "${origin.url}/single?new-document=2"
            compose.runOnIdle { browser.navigate(destination) }
            waitFor("A new document on the same origin must complete its own GET and real animation-frame beacon", 30_000) {
                origin.pageRequests.get() == 2 && origin.ready.get() == 2 &&
                    browser.state.value.tabs.firstOrNull { it.id == owner }?.let {
                        it.url == destination && it.title == "Upload ready" && !it.loading
                    } == true
            }
            awaitUploadLeasesDeleted(leases)
            assertNull(browser.prompts.prompt.value)
            assertEquals("Replacing the document must not relaunch a picker", 1, pickerStarts())
            assertEquals("Only the original document selected a file", 1, origin.reportCount("change"))
        }
    }

    @Test fun closingThePickerOwnerDiscardsItsRealLateResultAndTheNextOwnerCanUpload() {
        UploadOrigin().use { retiredOrigin ->
            UploadOrigin().use { nextOrigin ->
                val baseline = uploadLeaseDirectories()
                // Prepare the replacement page before DocumentsUI covers the application so its
                // readiness uses actual rAFs, rather than requiring a hidden document to animate.
                val nextOwner = page(nextOrigin, multiple = false)
                val retiredOwner = page(retiredOrigin, multiple = false)
                beginSelection(retiredOwner)
                openFixtureRoot()
                compose.runOnIdle {
                    browser.closeTab(retiredOwner)
                    browser.selectTab(nextOwner)
                }
                waitFor("Closing the original owner must retire its native request") {
                    browser.state.value.tabs.none { it.id == retiredOwner } && browser.prompts.prompt.value == null
                }
                assertTrue("The old real DocumentsUI must still be visible before returning its result", documentsUiVisible())
                selectSingle()
                awaitReceiverFinishedAndTemporaryGrantReleased()
                assertEquals("The closed page must receive no file-change delivery", 0, retiredOrigin.reportCount("change"))
                assertEquals("The replacement page must not receive the old system result", 0, nextOrigin.reportCount("change"))
                assertTrue("An abandoned result must not leave a new upload lease", uploadLeaseDirectories() - baseline == emptySet<File>())
                assertEquals(1, pickerStarts())

                beginSelection(nextOwner)
                openFixtureRoot()
                selectSingle()
                val current = awaitReport(nextOrigin, 1)
                assertEquals(1, current.files.size)
                assertFile(current.files.single(), UploadFixtureDocumentsProvider.SINGLE_NAME,
                    UploadFixtureDocumentsProvider.SINGLE_BYTES, nextOrigin.upload(1, 0))
                awaitReceiverFinishedAndTemporaryGrantReleased()
                assertEquals("Only the next owner's own picker may deliver its file", 2, pickerStarts())
                assertEquals(0, retiredOrigin.reportCount("change"))
                assertEquals(1, nextOrigin.reportCount("change"))
                assertEquals("Returning an old result must not reload the replacement document", 1, nextOrigin.pageRequests.get())
            }
        }
    }

    private fun page(origin: UploadOrigin, multiple: Boolean, isPrivate: Boolean = false): String {
        var owner = ""
        compose.runOnIdle {
            owner = browser.newTab("${origin.url}/${if (multiple) "multiple" else "single"}", isPrivate = isPrivate)
            fixtureTabs += owner
        }
        try {
            waitFor("The actual loopback document must load", 30_000) {
                browser.state.value.tabs.firstOrNull { it.id == owner }?.let { it.title == "Upload ready" && !it.loading } == true &&
                    origin.ready.get() > 0
            }
        } catch (failure: AssertionError) {
            val state = browser.state.value
            val tab = state.tabs.firstOrNull { it.id == owner }
            // Report only controlled counters and booleans, never private origins, titles or URIs.
            throw AssertionError("Upload fixture readiness: GET=${origin.pageRequests.get()}, " +
                "DOM=${origin.domReady.get()}, RAF=${origin.ready.get()}, ownerPresent=${tab != null}, " +
                "ownerSelected=${state.selectedTabId == owner}, loading=${tab?.loading}, " +
                "titleReady=${tab?.title == "Upload ready"}, crashed=${tab?.crashed}, " +
                "browserReady=${state.ready}, adblock=${browser.adblock.value.status}", failure)
        }
        if (isPrivate) assertSecureWindow()
        return owner
    }

    private fun beginSelection(owner: String): BrowserPromptState {
        val request = openFilePrompt(owner)
        compose.onNodeWithTag("page_prompt_file_choose").assertIsDisplayed().performClick()
        waitFor("The application must open the real Android document picker") { documentsUiVisible() }
        return request
    }

    private fun openFilePrompt(owner: String): BrowserPromptState {
        touchWebButton()
        waitFor("A real native File request must reach its original tab") {
            browser.prompts.prompt.value?.let { it.kind == BrowserPromptKind.FILE && it.tabId == owner } == true
        }
        val request = checkNotNull(browser.prompts.prompt.value)
        if (browser.state.value.tabs.first { it.id == owner }.isPrivate) assertTrue(request.isPrivate)
        else assertFalse(request.isPrivate)
        assertNull("The engine has no verified requesting-frame origin for File", request.origin)
        return request
    }

    private fun awaitReport(origin: UploadOrigin, round: Int): WebsiteFileReport {
        waitFor("Real website metadata and binary POST bodies must reach the fixture server", 30_000) {
            origin.failure.get()?.let { throw AssertionError("Synthetic loopback server failed", it) }
            origin.report(round)?.let { report -> report.files.indices.all { origin.upload(round, it) != null } } == true
        }
        return checkNotNull(origin.report(round))
    }

    private fun assertFile(file: WebsiteFile, name: String, bytes: ByteArray, uploaded: ByteArray?) {
        assertEquals(name, file.name)
        assertEquals("text/plain", file.mime)
        assertEquals(bytes.size.toLong(), file.size)
        assertEquals(uploadHash(bytes), uploadHash(checkNotNull(uploaded)))
    }

    private fun pickerStarts(): Int {
        checkNotNull(pickerMonitor)
        return pickerLaunchCount.get()
    }

    private fun liveActivities(): List<Activity> {
        var activities = emptyList<Activity>()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            activities = listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
                .flatMap { monitor.getActivitiesInStage(it) }.distinct()
        }
        return activities
    }

    private fun recreateStoppedUploadHosts() {
        var previousMain: MainActivity? = null
        var previousReceiver: UploadPickerActivity? = null
        waitFor("Both real upload host activities must exist behind DocumentsUI") {
            val activities = liveActivities()
            previousMain = activities.filterIsInstance<MainActivity>().singleOrNull()
            previousReceiver = activities.filterIsInstance<UploadPickerActivity>().singleOrNull()
            previousMain != null && previousReceiver != null
        }
        val oldMain = checkNotNull(previousMain)
        val oldReceiver = checkNotNull(previousReceiver)
        // ActivityScenario.recreate forces its own activity to RESUMED. These real activities
        // intentionally remain STOPPED under DocumentsUI, so call their platform API on Main.
        instrumentation.runOnMainSync {
            oldMain.recreate()
            oldReceiver.recreate()
        }
        waitFor("Both replacement activities must be created while the original DocumentsUI stays foreground") {
            val activities = liveActivities()
            activities.any { it is MainActivity && it !== oldMain && !it.isFinishing && !it.isDestroyed } &&
                activities.any { it is UploadPickerActivity && it !== oldReceiver && !it.isFinishing && !it.isDestroyed } &&
                documentsUiVisible()
        }
    }

    private fun awaitReceiverFinishedAndTemporaryGrantReleased() {
        val uri = DocumentsContract.buildDocumentUri(UploadFixtureDocumentsProvider.AUTHORITY, "single")
        val context = instrumentation.targetContext
        fun permission(flags: Int) = context.checkUriPermission(uri, Process.myPid(), Process.myUid(), flags)
        waitFor("The real receiving activity must finish and Android must release its temporary read and write grants") {
            liveActivities().none { it is UploadPickerActivity } &&
                permission(Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_DENIED &&
                permission(Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_DENIED
        }
        assertTrue("No real upload receiver may retain the selection grant", liveActivities().none { it is UploadPickerActivity })
        assertEquals("The selected source URI must no longer be readable through a temporary grant",
            PackageManager.PERMISSION_DENIED, permission(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        assertEquals("The selected source URI must not retain a temporary write grant",
            PackageManager.PERMISSION_DENIED, permission(Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
    }

    private fun uploadLeaseDirectories(): Set<File> =
        File(instrumentation.targetContext.cacheDir, "miru-upload-staging").listFiles()
            .orEmpty().filter { it.isDirectory && it.name.startsWith("upload-lease-") }.toSet()

    private fun awaitNewUploadLeases(baseline: Set<File>): Set<File> {
        var leases = emptySet<File>()
        waitFor("A successful native file answer must retain its document-owned staging lease") {
            leases = uploadLeaseDirectories() - baseline
            leases.isNotEmpty()
        }
        assertEquals("One system selection must create one document lease", 1, leases.size)
        return leases
    }

    private fun assertSingleFixtureRemainsReadable(leases: Set<File>) {
        val files = leases.flatMap { it.walkTopDown().filter(File::isFile).toList() }
        assertEquals("The delivered document lease must retain exactly one complete file", 1, files.size)
        assertTrue("The staged file must remain readable with the exact selected fixture bytes",
            files.single().readBytes().contentEquals(UploadFixtureDocumentsProvider.SINGLE_BYTES))
    }

    private fun awaitUploadLeasesDeleted(leases: Set<File>) {
        waitFor("Ending the owning document must delete its full staging lease directories") {
            leases.none(File::exists)
        }
        assertTrue("No document-owned staging directory may remain", leases.none(File::exists))
    }

    private fun assertSecureWindow() {
        compose.runOnIdle {
            assertTrue("Private upload fixtures must keep FLAG_SECURE throughout the test",
                compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
    }

    private fun openFixtureRoot() {
        if (exactDocumentRows(UploadFixtureDocumentsProvider.SINGLE_NAME) > 0) return
        if (!clickSystemText(UploadFixtureDocumentsProvider.ROOT_LABEL)) {
            waitFor("DocumentsUI must expose its actual roots navigation control") {
                withDocumentsRoot(::openRootsDrawer) == true
            }
            waitFor("DocumentsUI must list the synthetic provider root") {
                clickSystemText(UploadFixtureDocumentsProvider.ROOT_LABEL)
            }
        }
        waitFor("The selected synthetic root must show its real file rows") {
            exactDocumentRows(UploadFixtureDocumentsProvider.SINGLE_NAME) > 0
        }
    }

    private fun openRootsDrawer(root: AccessibilityNodeInfo): Boolean {
        val home = root.findAccessibilityNodeInfosByViewId("android:id/home")
        try { if (home.any { it.isVisibleToUser && clickAncestor(it) }) return true }
        finally { home.forEach { it.recycle() } }
        for (namespace in DOCUMENTS_UI_PACKAGES) {
            val toolbars = root.findAccessibilityNodeInfosByViewId("$namespace:id/toolbar")
            try {
                for (toolbar in toolbars) if (clickNavigationImage(toolbar)) return true
            } finally { toolbars.forEach { it.recycle() } }
        }
        return false
    }

    private fun clickNavigationImage(node: AccessibilityNodeInfo): Boolean {
        val description = node.contentDescription?.toString().orEmpty()
        if (node.className?.toString()?.endsWith("ImageButton") == true && node.isVisibleToUser && node.isEnabled &&
            description.isNotBlank() && description.lowercase(Locale.ROOT) !in setOf("more options", "更多选项")) {
            if (clickAncestor(node)) return true
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (clickNavigationImage(child)) return true }
            finally { child.recycle() }
        }
        return false
    }

    private fun selectSingle() {
        clickDocument(UploadFixtureDocumentsProvider.SINGLE_NAME)
        finishPickerSelection(required = false)
    }

    private fun clickDocument(name: String, index: Int = 0, longClick: Boolean = false) {
        var touchBounds: Rect? = null
        waitFor("Missing selectable fixture document row: $name") {
            withDocumentsRoot { root ->
                val nodes = root.findAccessibilityNodeInfosByText(name)
                try {
                    val matches = nodes.filter { it.text?.toString() == name && it.isVisibleToUser }
                    matches.getOrNull(index)?.let { node ->
                        if (longClick) {
                            val bounds = Rect()
                            node.getBoundsInScreen(bounds)
                            if (bounds.width() <= 0 || bounds.height() <= 0) false
                            else { touchBounds = bounds; true }
                        } else clickAncestor(node)
                    } == true
                } finally { nodes.forEach { it.recycle() } }
            } ?: false
        }
        if (longClick) {
            val bounds = checkNotNull(touchBounds)
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true)) }
            finally { down.recycle() }
            SystemClock.sleep(650)
            val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true)) }
            finally { up.recycle() }
        }
    }

    private fun exactDocumentRows(name: String): Int = withDocumentsRoot { root ->
        val nodes = root.findAccessibilityNodeInfosByText(name)
        try { nodes.count { it.text?.toString() == name && it.isVisibleToUser } }
        finally { nodes.forEach { it.recycle() } }
    } ?: 0

    /** DocumentsUI adds this action through its RecyclerView delegate without setting the
     * View's isLongClickable flag. Invoke only actions advertised by the actual system row.
     * The following selection-state assertions prove that this selected instead of opening it.
     */
    private fun performDocumentRowAction(name: String, index: Int, action: Int) {
        var dispatched = false
        waitFor("The actual DocumentsUI row must advertise and accept action=$action") {
            dispatched = withDocumentsRoot { root ->
                withDocumentRow(root, name, index) { row ->
                    row.actionList.any { it.id == action } && row.isEnabled && row.isVisibleToUser && row.performAction(action)
                } ?: false
            } == true
            dispatched
        }
        assertTrue("The actual system row action must be dispatched", dispatched)
    }

    private fun awaitActualDocumentSelection(expected: List<Boolean>) {
        var observed: List<DocumentRowSelection> = emptyList()
        try {
            waitFor("The actual DocumentsUI selection state must match the requested rows") {
                observed = withDocumentsRoot { root ->
                    expected.indices.mapNotNull { index ->
                        withDocumentRow(root, UploadFixtureDocumentsProvider.SAME_NAME, index) { row ->
                            DocumentRowSelection(row.isSelected, row.isCheckable && row.isChecked,
                                row.collectionItemInfo?.isSelected == true,
                                row.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK })
                        }
                    }
                }.orEmpty()
                observed.map { it.selected || it.checked || it.collectionSelected } == expected
            }
        } catch (failure: AssertionError) {
            // Only synthetic-row booleans are included; never dump unrelated system contents.
            throw AssertionError("DocumentsUI selection: visible=${documentsUiVisible()}, rows=$observed, expected=$expected", failure)
        }
        assertTrue("Actual multi-selection must keep the external picker open", documentsUiVisible())
    }

    private data class DocumentRowSelection(val selected: Boolean, val checked: Boolean,
        val collectionSelected: Boolean, val longClickAction: Boolean)

    private fun <T> withDocumentRow(root: AccessibilityNodeInfo, name: String, index: Int,
        block: (AccessibilityNodeInfo) -> T): T? {
        val nodes = root.findAccessibilityNodeInfosByText(name)
        try {
            val text = nodes.filter { it.text?.toString() == name && it.isVisibleToUser }.getOrNull(index) ?: return null
            var candidate: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(text)
            try {
                repeat(8) {
                    val row = candidate ?: return null
                    if (row.collectionItemInfo != null ||
                        row.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }) return block(row)
                    candidate = row.parent
                    row.recycle()
                }
                return null
            } finally { candidate?.recycle() }
        } finally { nodes.forEach { it.recycle() } }
    }

    private fun finishPickerSelection(required: Boolean) {
        waitFor(if (required) "DocumentsUI must finish the real multiple selection" else "DocumentsUI must finish the real single selection") {
            // Some DocumentsUI versions finish immediately after a file-row action. The actual
            // website's exact file count, original names and byte hashes decide success below.
            if (!documentsUiVisible()) return@waitFor true
            val result = withDocumentsRoot { root ->
                for (namespace in DOCUMENTS_UI_PACKAGES + "android") {
                    for (id in listOf("action_menu_select", "button1")) {
                        val nodes = root.findAccessibilityNodeInfosByViewId("$namespace:id/$id")
                        try { if (nodes.any { it.isVisibleToUser && it.isEnabled && clickAncestor(it) }) return@withDocumentsRoot true }
                        finally { nodes.forEach { it.recycle() } }
                    }
                }
                for (text in listOf("Open", "OPEN", "Select", "SELECT", "打开", "选择")) {
                    val nodes = root.findAccessibilityNodeInfosByText(text)
                    try { if (nodes.any { it.text?.toString() == text && it.isVisibleToUser && it.isEnabled && clickAncestor(it) }) return@withDocumentsRoot true }
                    finally { nodes.forEach { it.recycle() } }
                }
                false
            } == true
            result
        }
    }

    private fun clickSystemText(text: String): Boolean = withDocumentsRoot { root ->
        val nodes = root.findAccessibilityNodeInfosByText(text)
        try { nodes.any { it.text?.toString() == text && it.isVisibleToUser && clickAncestor(it) } }
        finally { nodes.forEach { it.recycle() } }
    } ?: false

    private fun clickAncestor(node: AccessibilityNodeInfo, longClick: Boolean = false): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        try {
            repeat(8) {
                val candidate = current ?: return false
                val action = if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
                if (candidate.isVisibleToUser && candidate.isEnabled &&
                    (if (longClick) candidate.isLongClickable else candidate.isClickable) && candidate.performAction(action)) return true
                val parent = candidate.parent
                candidate.recycle()
                current = parent
            }
            return false
        } finally { current?.recycle() }
    }

    private fun <T> withDocumentsRoot(block: (AccessibilityNodeInfo) -> T): T? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        try {
            if (root.packageName?.toString() !in DOCUMENTS_UI_PACKAGES) return null
            return block(root)
        } finally { root.recycle() }
    }

    private fun documentsUiVisible(): Boolean = withDocumentsRoot { true } == true

    private fun systemBack() {
        assertTrue("Android must accept its real global Back action",
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
    }

    private fun waitFor(message: String, timeout: Long = 20_000, condition: () -> Boolean) {
        try { compose.waitUntil(timeout) { condition() } }
        catch (failure: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError(message, failure) }
    }

    private fun engineBounds(): Rect {
        var bounds: Rect? = null
        compose.runOnIdle {
            fun find(view: View): EngineView? = if (view is EngineView) view else (view as? ViewGroup)?.let { group ->
                (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
            }
            val view = checkNotNull(find(compose.activity.window.decorView)).asView()
            val xy = IntArray(2)
            view.getLocationOnScreen(xy)
            bounds = Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
        }
        return checkNotNull(bounds)
    }

    private fun touchWebButton(yDp: Float = 40f) {
        val density = compose.activity.resources.displayMetrics.density
        if (browser.state.value.selectedTab?.isPrivate == true) {
            // page() already received the private document's two real rAFs over HTTP.
            // Keep FLAG_SECURE intact and never inspect a private screenshot.
            assertSecureWindow()
        } else waitFor("The ordinary fixture's real Gecko frame must be painted", 8_000) {
            val bounds = engineBounds()
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return@waitFor false
            try {
                val x = (bounds.left + 12 * density).toInt()
                val y = (bounds.top + 12 * density).toInt()
                if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return@waitFor false
                val pixel = bitmap.getPixel(x, y)
                kotlin.math.abs(Color.red(pixel) - 0x13) < 8 && kotlin.math.abs(Color.green(pixel) - 0x57) < 8 &&
                    kotlin.math.abs(Color.blue(pixel) - 0x9b) < 8
            } finally { bitmap.recycle() }
        }
        val bounds = engineBounds()
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.left + 40 * density, bounds.top + yDp * density, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60)
        }
    }

    companion object {
        private val DOCUMENTS_UI_PACKAGES = setOf("com.android.documentsui", "com.google.android.documentsui")
    }
}

private data class WebsiteFile(val name: String, val mime: String, val size: Long)
private data class WebsiteFileReport(val round: Int, val label: String, val files: List<WebsiteFile>)
private fun uploadHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Reads actual HTTP bodies as bytes, without a Reader consuming ahead into the binary payload. */
private class UploadOrigin : Closeable {
    private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val reports = ConcurrentHashMap<Int, WebsiteFileReport>()
    private val uploads = ConcurrentHashMap<Pair<Int, Int>, ByteArray>()
    val ready = AtomicInteger()
    val domReady = AtomicInteger()
    val pageRequests = AtomicInteger()
    val failure = AtomicReference<Throwable?>()
    val url = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true, name = "miru-file-upload-origin") {
            while (running.get()) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets += socket
                thread(isDaemon = true, name = "miru-file-upload-response") {
                    try { socket.use(::respond) }
                    catch (problem: Exception) { if (running.get()) failure.compareAndSet(null, problem) }
                    finally { sockets -= socket }
                }
            }
        }
    }

    fun report(round: Int) = reports[round]
    fun upload(round: Int, index: Int) = uploads[round to index]
    fun reportCount(label: String) = reports.values.count { it.label == label }

    private fun respond(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream().buffered()
        val firstLine = try { line(input) } catch (_: java.net.SocketTimeoutException) { return }
        val request = (firstLine ?: return).split(' ')
        check(request.size >= 2)
        val method = request[0]
        val target = request[1]
        val path = target.substringBefore('?')
        val headers = mutableMapOf<String, String>()
        var headerCount = 0
        while (true) {
            val value = checkNotNull(line(input))
            if (value.isEmpty()) break
            check(++headerCount <= 64)
            headers[value.substringBefore(':').lowercase(Locale.ROOT)] = value.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        check(length in 0..1_048_576)
        val body = ByteArray(length)
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            check(count > 0)
            offset += count
        }
        val response = when (path) {
            "/report" -> {
                check(method == "POST")
                val value = JSONObject(body.toString(Charsets.UTF_8))
                val array = value.getJSONArray("files")
                val report = WebsiteFileReport(value.getInt("round"), value.getString("label"),
                    (0 until array.length()).map { index -> array.getJSONObject(index).let {
                        WebsiteFile(it.getString("name"), it.getString("mime"), it.getLong("size"))
                    } })
                check(reports.putIfAbsent(report.round, report) == null)
                "ok".toByteArray()
            }
            "/upload" -> {
                check(method == "POST")
                val query = target.substringAfter('?', "").split('&').associate {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                }
                val key = checkNotNull(query["round"]).toInt() to checkNotNull(query["index"]).toInt()
                check(uploads.putIfAbsent(key, body) == null)
                "ok".toByteArray()
            }
            "/ready" -> { ready.incrementAndGet(); "ok".toByteArray() }
            "/dom-ready" -> { domReady.incrementAndGet(); "ok".toByteArray() }
            "/single", "/multiple" -> { pageRequests.incrementAndGet(); document(path == "/multiple").toByteArray() }
            else -> "ok".toByteArray()
        }
        socket.getOutputStream().apply {
            val type = if (path == "/single" || path == "/multiple") "text/html; charset=utf-8" else "text/plain"
            write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${response.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            write(response)
            flush()
        }
    }

    private fun document(multiple: Boolean) = """
        <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Upload ready</title>
        <style>body{margin:0}button{display:block;width:220px;height:100px;border:0;color:white;background:#13579b}#inspect{margin-top:10px;background:#246824}</style>
        <button id="pick">Choose fixture</button><button id="inspect">Inspect selected files</button>
        <input id="files" type="file" accept="text/plain" ${if (multiple) "multiple" else ""} hidden>
        <script>
        const files=document.getElementById('files');let round=0;
        async function report(label){
          const id=++round;const selected=Array.from(files.files);
          const values=selected.map(f=>({name:f.name,mime:f.type,size:f.size}));
          await fetch('/report',{method:'POST',body:JSON.stringify({round:id,label,files:values})});
          for(let i=0;i<selected.length;i++)await fetch('/upload?round='+id+'&index='+i,{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:await selected[i].arrayBuffer()});
          document.title='Upload report '+id;
        }
        document.getElementById('pick').onclick=()=>files.click();
        document.getElementById('inspect').onclick=()=>report('inspect');
        files.addEventListener('change',()=>report('change'));
        files.addEventListener('cancel',()=>{document.title='Upload cancelled '+files.files.length});
        fetch('/dom-ready');
        requestAnimationFrame(()=>requestAnimationFrame(()=>fetch('/ready')));
        </script>
    """.trimIndent()

    private fun line(input: InputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= 8192) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else error("Incomplete fixture HTTP line")
            if (value == 10) return bytes.toByteArray().toString(Charsets.ISO_8859_1).removeSuffix("\r")
            bytes += value.toByte()
        }
        error("Oversized fixture HTTP line")
    }

    override fun close() {
        running.set(false)
        server.close()
        sockets.toList().forEach { runCatching { it.close() } }
    }
}
