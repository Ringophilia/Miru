// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.miru.browser.engine.BrowserPromptKind
import app.miru.browser.engine.BrowserPromptState
import app.miru.browser.engine.BrowserUploadPhase
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
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

/** Genuine Gecko directory prompts, DocumentsUI TREE and document-owned original POST bytes. */
@RunWith(AndroidJUnit4::class)
class FolderUploadIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val driver by lazy { FolderUploadDriver(compose) }

    @Before fun preserveTabsAndObserveTheRealTreeLaunch() = driver.preserveTabsAndObserveTheRealTreeLaunch()
    @After fun closeOnlyOwnedTabsAndRestoreObservation() = driver.closeOnlyOwnedTabsAndRestoreObservation()
    @Test fun completeTreeKeepsTheRealRootRelativePathsDeepFilesAndUploadedBytes() = driver.completeTreeKeepsTheRealRootRelativePathsDeepFilesAndUploadedBytes()
    @Test fun privateTreeRetainsBytesUntilItsOwnerClosesThenDeletesItsLease() = driver.privateTreeRetainsBytesUntilItsOwnerClosesThenDeletesItsLease()
    @Test fun anEmptyTreeStillNeedsRealConfirmationAndProducesAnEmptyChange() = driver.anEmptyTreeStillNeedsRealConfirmationAndProducesAnEmptyChange()
    @Test fun decliningTheRealSecondConfirmationDoesNotGiveTheWebsiteAnyFiles() = driver.decliningTheRealSecondConfirmationDoesNotGiveTheWebsiteAnyFiles()
    @Test fun anExplicitProviderPngWithoutAnExtensionReachesTheWebsiteWithItsOriginalMetadataAndBytes() = driver.anExplicitProviderPngWithoutAnExtensionReachesTheWebsiteWithItsOriginalMetadataAndBytes()
    @Test fun systemTreeBackPreservesThePreviousRealFileListLeaseAndReadableBytes() = driver.systemTreeBackPreservesThePreviousRealFileListLeaseAndReadableBytes()
    @Test fun recreationWhileTheRealFolderConfirmationIsVisibleRetainsItsTokenAndDeliversOnce() = driver.recreationWhileTheRealFolderConfirmationIsVisibleRetainsItsTokenAndDeliversOnce()
    @Test fun closingTheTreeOwnerDiscardsItsRealLateSystemAllowBeforeTheNextOwnerUploads() = driver.closingTheTreeOwnerDiscardsItsRealLateSystemAllowBeforeTheNextOwnerUploads()
}

/** Shared real UI/HTTP fixture, composed by test classes; it contains no JUnit test methods. */
internal class FolderUploadDriver(val compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>) {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    val browser get() = (compose.activity.application as MiruApplication).container.browser
    private val tabs = BrowserTabFixture()
    private var oldAccessibilityFlags: Int? = null
    private var monitor: Instrumentation.ActivityMonitor? = null
    private val starts = AtomicInteger()
    private var primaryFailure: Throwable? = null
    private var tabsCaptured = false

    fun preserveTabsAndObserveTheRealTreeLaunch() = preservingPrimaryFailure {
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

    fun closeOnlyOwnedTabsAndRestoreObservation() {
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

    fun completeTreeKeepsTheRealRootRelativePathsDeepFilesAndUploadedBytes() = preservingPrimaryFailure { allowFolder(empty = false, private = false) }
    fun privateTreeRetainsBytesUntilItsOwnerClosesThenDeletesItsLease() = preservingPrimaryFailure { allowFolder(empty = false, private = true) }
    fun anEmptyTreeStillNeedsRealConfirmationAndProducesAnEmptyChange() = preservingPrimaryFailure { allowFolder(empty = true, private = false) }

    fun decliningTheRealSecondConfirmationDoesNotGiveTheWebsiteAnyFiles() = preservingPrimaryFailure {
        FolderOrigin().use { origin ->
            val baseline = leases()
            val owner = page(origin, private = false)
            chooseTree(owner, empty = false)
            awaitConfirmation(UploadTreeDocumentsProvider.ROOT_NAME, owner)
            assertEquals(0, origin.reports.size)
            assertEquals(0, origin.uploads.size)
            val selectedLeases = leases() - baseline
            assertEquals(1, selectedLeases.size)
            compose.onNodeWithTag("page_prompt_cancel").performClick()
            waitFor { browser.prompts.prompt.value == null }
            waitFor { selectedLeases.none(File::exists) }
            touchButton(145f)
            assertReport(origin, round = 1, event = "inspect", expected = emptyMap())
            assertEquals(0, origin.uploads.size)
            assertEquals(1, starts.get())
        }
    }

    fun anExplicitProviderPngWithoutAnExtensionReachesTheWebsiteWithItsOriginalMetadataAndBytes() = preservingPrimaryFailure {
        FolderOrigin().use { origin ->
            val baseline = leases()
            val grants = sourceGrants(DocumentsContract.buildTreeDocumentUri(UploadTreeDocumentsProvider.AUTHORITY, UploadTreeDocumentsProvider.MIME_ROOT_ID))
            val owner = page(origin, private = false)
            chooseTree(owner, empty = false, labelOverride = UploadTreeDocumentsProvider.MIME_ROOT_LABEL)
            awaitConfirmation("ExplicitMimeRoot", owner)
            assertNoDelivery(origin)
            val selectedLeases = leases() - baseline
            assertEquals(1, selectedLeases.size)
            compose.onNodeWithTag("page_prompt_folder_confirm").performClick()
            val expected = listOf(FolderExpectedFile("ExplicitMimeRoot/picture-without-extension", "image/png", 1_700_000_000_000L, UploadFixtureDocumentsProvider.PNG_BYTES))
            assertFileReport(origin, round = 1, event = "change", expected = expected)
            awaitPickerRetired(grants)
            touchButton(145f)
            assertFileReport(origin, round = 2, event = "inspect", expected = expected)
            assertEquals(1, origin.reportCount("change"))
            assertEquals(1, starts.get())
            compose.runOnIdle { browser.closeTab(owner) }
            waitFor { selectedLeases.none(File::exists) && leases() == baseline }
            awaitPickerRetired(grants)
        }
    }

    fun systemTreeBackPreservesThePreviousRealFileListLeaseAndReadableBytes() = preservingPrimaryFailure {
        FolderOrigin().use { origin ->
            val baseline = leases()
            val grants = sourceGrants()
            val owner = page(origin, private = false)
            val instance = documentInstance(origin)
            chooseTree(owner, empty = false)
            awaitConfirmation(UploadTreeDocumentsProvider.ROOT_NAME, owner)
            assertNoDelivery(origin)
            val originalLeases = leases() - baseline
            assertEquals(1, originalLeases.size)
            compose.onNodeWithTag("page_prompt_folder_confirm").performClick()
            assertReport(origin, round = 1, event = "change")
            awaitPickerRetired(grants)
            assertLeaseBytes(originalLeases)

            val cancelled = openTreePicker(owner)
            selectFixtureRoot(UploadTreeDocumentsProvider.ROOT_LABEL)
            assertEquals(2, starts.get())
            assertEquals(owner, cancelled.tabId)
            assertTrue("Cancellation must occur in the actual system TREE, before its grant dialog", documentsVisible() && !treeGrantDialogVisible())
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            waitFor { !documentsVisible() && browser.prompts.prompt.value == null }
            awaitPickerRetired(grants)
            assertTrue("System cancellation must retain exactly the original document lease", leases() - baseline == originalLeases)
            assertLeaseBytes(originalLeases)
            assertSameDocument(origin, instance)
            assertEquals(setOf(1), origin.reports.keys.toSet())
            assertEquals(1, origin.reportCount("change"))

            // A fresh real DOM read uploads the retained File objects after system Back.
            touchButton(145f)
            assertReport(origin, round = 2, event = "inspect")
            assertEquals(1, origin.reportCount("change"))
            assertEquals(setOf(1, 2), origin.reports.keys.toSet())
            assertEquals(2, starts.get())
            assertSameDocument(origin, instance)
            assertLeaseBytes(originalLeases)
            compose.runOnIdle { browser.closeTab(owner) }
            waitFor { originalLeases.none(File::exists) && leases() == baseline }
            awaitPickerRetired(grants)
        }
    }

    fun recreationWhileTheRealFolderConfirmationIsVisibleRetainsItsTokenAndDeliversOnce() = preservingPrimaryFailure {
        FolderOrigin().use { origin ->
            val baseline = leases()
            val grants = sourceGrants()
            val owner = page(origin, private = false)
            val instance = documentInstance(origin)
            chooseTree(owner, empty = false)
            val original = awaitConfirmation(UploadTreeDocumentsProvider.ROOT_NAME, owner)
            waitFor { !documentsVisible() && !receiverPresent() }
            compose.onNodeWithTag("page_prompt_folder_confirm").assertIsDisplayed()
            assertEquals(BrowserUploadPhase.CONFIRMING, browser.uploads.state.value.phase)
            assertNoDelivery(origin)
            assertSameDocument(origin, instance)
            assertEquals(1, starts.get())
            val selectedLeases = leases() - baseline
            assertEquals(1, selectedLeases.size)
            assertLeaseBytes(selectedLeases)
            val previousMain = compose.activity

            // Main is foreground with a genuinely visible native confirmation. The system
            // picker and its receiver have already retired before ActivityScenario resumes it.
            compose.activityRule.scenario.recreate()

            val restored = awaitConfirmation(UploadTreeDocumentsProvider.ROOT_NAME, owner)
            assertNotSame("Recreation must replace the real Main instance", previousMain, compose.activity)
            assertEquals("Recreation must keep the real native request token", original.token, restored.token)
            assertEquals(owner, restored.tabId)
            compose.onNodeWithTag("page_prompt_folder_confirm").assertIsDisplayed()
            assertSameDocument(origin, instance)
            assertEquals(1, starts.get())
            assertTrue("Recreation must preserve the same awaiting-confirmation lease", leases() - baseline == selectedLeases)
            assertLeaseBytes(selectedLeases)
            assertNoDelivery(origin)
            compose.onNodeWithTag("page_prompt_folder_confirm").performClick()
            assertReport(origin, round = 1, event = "change")
            awaitPickerRetired(grants)
            assertEquals(1, starts.get())
            assertEquals(1, origin.reportCount("change"))
            assertEquals(setOf(1), origin.reports.keys.toSet())
            assertSameDocument(origin, instance)
            assertLeaseBytes(selectedLeases)
            // Delayed File reads also detect a duplicate change/report after recreation.
            touchButton(145f)
            assertReport(origin, round = 2, event = "inspect")
            assertEquals(1, origin.reportCount("change"))
            assertEquals(setOf(1, 2), origin.reports.keys.toSet())
            assertEquals(2 * UploadTreeDocumentsProvider.expectedFiles().size, origin.uploads.size)
            compose.runOnIdle { browser.closeTab(owner) }
            waitFor { selectedLeases.none(File::exists) && leases() == baseline }
            awaitPickerRetired(grants)
        }
    }

    /** Late system TREE results are distinct from a late second native FolderUploadPrompt. */
    fun closingTheTreeOwnerDiscardsItsRealLateSystemAllowBeforeTheNextOwnerUploads() = preservingPrimaryFailure {
        FolderOrigin().use { retiredOrigin ->
            FolderOrigin().use { nextOrigin ->
                val baseline = leases()
                val grants = sourceGrants()
                // Both document-instance double-rAF beacons arrive before DocumentsUI covers Main.
                val nextOwner = page(nextOrigin, private = false)
                val nextInstance = documentInstance(nextOrigin)
                val retiredOwner = page(retiredOrigin, private = false)
                val retiredInstance = documentInstance(retiredOrigin)
                val retired = openTreePicker(retiredOwner)
                selectFixtureRoot(UploadTreeDocumentsProvider.ROOT_LABEL)
                val receiver = liveActivities().filterIsInstance<UploadPickerActivity>().single()
                assertEquals(1, starts.get())
                assertTrue("The old TREE must still be waiting for its real Use action", documentsVisible() && !treeGrantDialogVisible())
                compose.runOnIdle {
                    browser.closeTab(retiredOwner)
                    browser.selectTab(nextOwner)
                }
                waitFor {
                    browser.state.value.tabs.none { it.id == retiredOwner } && browser.state.value.selectedTabId == nextOwner &&
                        browser.prompts.prompt.value == null && documentsVisible() &&
                        liveActivities().any { it === receiver && !it.isFinishing && !it.isDestroyed } &&
                        browser.uploads.state.value.phase == BrowserUploadPhase.PICKING && browser.uploads.state.value.token == null
                }
                assertTrue("An abandoned system slot must not create a delivered folder lease", leases() == baseline)
                assertNoDelivery(retiredOrigin)
                assertNoDelivery(nextOrigin)

                // Real DocumentsUI returns its real old grant/result, with no owner left.
                confirmSystemUseAndAllow(UploadTreeDocumentsProvider.ROOT_LABEL)
                awaitPickerRetired(grants)
                waitFor { leases() == baseline }
                assertNoDelivery(retiredOrigin)
                assertNoDelivery(nextOrigin)
                assertSameDocument(retiredOrigin, retiredInstance)
                assertSameDocument(nextOrigin, nextInstance)
                touchButton(145f)
                assertReport(nextOrigin, round = 1, event = "inspect", expected = emptyMap())
                assertEquals(0, nextOrigin.uploads.size)

                val own = chooseTree(nextOwner, empty = false)
                assertNotEquals("The next owner must issue its own native request", retired.token, own.token)
                awaitConfirmation(UploadTreeDocumentsProvider.ROOT_NAME, nextOwner)
                assertEquals(0, nextOrigin.reportCount("change"))
                assertNoDelivery(retiredOrigin)
                val selectedLeases = leases() - baseline
                assertEquals(1, selectedLeases.size)
                compose.onNodeWithTag("page_prompt_folder_confirm").performClick()
                assertReport(nextOrigin, round = 2, event = "change")
                awaitPickerRetired(grants)
                assertLeaseBytes(selectedLeases)
                assertEquals(2, starts.get())
                assertEquals(1, nextOrigin.reportCount("change"))
                assertEquals(setOf(1, 2), nextOrigin.reports.keys.toSet())
                assertSameDocument(nextOrigin, nextInstance)
                assertNoDelivery(retiredOrigin)
                compose.runOnIdle { browser.closeTab(nextOwner) }
                waitFor { selectedLeases.none(File::exists) && leases() == baseline }
                awaitPickerRetired(grants)
            }
        }
    }

    private fun allowFolder(empty: Boolean, private: Boolean) {
        FolderOrigin().use { origin ->
            val grants = sourceGrants(empty)
            val baseline = leases()
            val owner = page(origin, private)
            chooseTree(owner, empty)
            awaitConfirmation(if(empty) UploadTreeDocumentsProvider.EMPTY_NAME else UploadTreeDocumentsProvider.ROOT_NAME, owner)
            assertEquals("Choosing a tree must not automatically approve its native upload", 0, origin.reports.size)
            val selectedLeases = leases() - baseline
            assertEquals(1, selectedLeases.size)
            compose.onNodeWithTag("page_prompt_folder_confirm").performClick()
            val expected = if(empty) emptyMap() else UploadTreeDocumentsProvider.expectedFiles()
            assertReport(origin, round = 1, event = "change", expected = expected)
            assertEquals(1, starts.get())
            if(private) compose.runOnIdle { assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
            awaitPickerRetired(grants)
            assertTrue("Native completion must not delete the root before Gecko reads it", selectedLeases.all(File::exists))
            compose.runOnIdle { browser.closeTab(owner) }
            waitFor { selectedLeases.none(File::exists) }
        }
    }

    fun page(origin: FolderOrigin, private: Boolean): String {
        var owner = ""
        compose.runOnIdle { owner = browser.newTab(origin.url, isPrivate = private) }
        compose.waitUntil(30_000) {
            origin.assertHealthy()
            browser.state.value.selectedTab?.let { it.id == owner && it.url == origin.url && it.title == "Folder ready" && !it.loading } == true &&
                origin.pageRequests.get() == 1 && origin.readyDocuments[1] != null
        }
        assertSameDocument(origin, documentInstance(origin))
        return owner
    }

    private fun chooseTree(owner: String, empty: Boolean, labelOverride: String? = null): BrowserPromptState {
        val request = openTreePicker(owner)
        val label = labelOverride ?: if(empty) UploadTreeDocumentsProvider.EMPTY_LABEL else UploadTreeDocumentsProvider.ROOT_LABEL
        selectFixtureRoot(label)
        confirmSystemUseAndAllow(label)
        return request
    }

    fun openTreePicker(owner: String): BrowserPromptState {
        assertEquals("Only the fixture's selected owner may open its TREE", owner, browser.state.value.selectedTabId)
        touchButton(40f)
        waitFor { browser.prompts.prompt.value?.let { it.kind == BrowserPromptKind.FOLDER && it.tabId == owner } == true }
        val request = checkNotNull(browser.prompts.prompt.value)
        assertNull(request.origin)
        compose.onNodeWithTag("page_prompt_folder_choose").performClick()
        waitFor { documentsVisible() }
        return request
    }

    fun selectFixtureRoot(label: String) {
        if(withSystemRoot { directoryTitleMatches(it, label) && !isTreeGrantDialog(it, label) } == true) return
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
        waitFor { withSystemRoot { directoryTitleMatches(it, label) && !isTreeGrantDialog(it, label) } == true }
    }

    fun confirmSystemUseAndAllow(label: String) {
        var useClicked = false
        var grantDialogObserved = false
        var allowClicked = false
        waitFor {
            // rootInActiveWindow may be absent while DocumentsUI replaces its directory
            // window with the grant dialog. Absence alone is not a completed grant.
            if(!documentsVisible()) return@waitFor useClicked && grantDialogObserved && allowClicked
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
            UploadTreeDocumentsProvider.MIME_ROOT_LABEL, PreparedFolderDocumentsProvider.ROOT_LABEL,
            PreparedFolderDocumentsProvider.REJECT_LABEL).any { isTreeGrantDialog(root, it) }
    } == true

    fun preservingPrimaryFailure(action: () -> Unit) {
        try { action() }
        catch(failure: Throwable) { if(primaryFailure == null) primaryFailure = failure; throw failure }
    }

    fun awaitConfirmation(name: String, owner: String): BrowserPromptState {
        waitFor { browser.prompts.prompt.value?.let {
            it.kind == BrowserPromptKind.FOLDER_CONFIRM && it.folderName == name && it.tabId == owner
        } == true }
        compose.onNodeWithTag("page_prompt_folder_confirm").assertIsDisplayed()
        return checkNotNull(browser.prompts.prompt.value)
    }

    private fun awaitReport(origin: FolderOrigin, round: Int): JSONObject {
        compose.waitUntil(30_000) {
            origin.assertHealthy()
            origin.reports[round]?.let { report ->
                (0 until report.getJSONArray("files").length()).all { origin.uploads[round to it] != null }
            } == true
        }
        return checkNotNull(origin.reports[round])
    }

    fun assertReport(origin: FolderOrigin, round: Int, event: String,
        expected: Map<String, ByteArray> = UploadTreeDocumentsProvider.expectedFiles()): JSONObject =
        assertFileReport(origin, round, event, expected.map { (path, bytes) ->
            val mime = when { path.endsWith(".txt") -> "text/plain"; path.endsWith(".png") -> "image/png"; else -> "application/octet-stream" }
            FolderExpectedFile(path, mime, 1_700_000_000_000L, bytes)
        })

    fun assertFileReport(origin: FolderOrigin, round: Int, event: String, expected: List<FolderExpectedFile>): JSONObject {
        val report = awaitReport(origin, round)
        val instance = documentInstance(origin)
        assertSameDocument(origin, instance)
        assertEquals(round, report.getInt("round"))
        assertEquals(event, report.getString("event"))
        assertEquals(1, report.getInt("documentGet"))
        assertTrue("The report must belong to the actual ready document instance", report.getString("documentInstance") == instance)
        val files = report.getJSONArray("files")
        assertEquals(expected.size, files.length())
        val actual = mutableListOf<FolderFileTuple>()
        val expectedTuples = expected.map { it.tuple() }
        for(index in 0 until files.length()) {
            val file = files.getJSONObject(index)
            val upload = checkNotNull(origin.uploads[round to index])
            assertEquals(1, upload.documentGet)
            assertTrue("Each original byte POST must belong to the report's real document", upload.documentInstance == instance)
            val tuple = FolderFileTuple(file.getString("path"), file.getString("name"), file.getString("type"),
                file.getLong("size"), file.getLong("lastModified"), folderByteHash(upload.bytes))
            val reference = expected.indices.firstOrNull { expectedTuples[it] == tuple }
            assertTrue("Every actual File and its original POST bytes must match a complete source metadata tuple", reference != null)
            assertArrayEquals(expected[checkNotNull(reference)].bytes, upload.bytes)
            actual += tuple
        }
        assertTrue("The complete FileList metadata and POST byte hashes must match the source multiset without dropping duplicate paths",
            expectedTuples.groupingBy { it }.eachCount() == actual.groupingBy { it }.eachCount())
        assertEquals("Each DOM report must finish exactly one round", (1..round).toSet(), origin.reports.keys.toSet())
        assertEquals((0 until files.length()).map { round to it }.toSet(), origin.uploads.keys.filter { it.first == round }.toSet())
        assertTrue("A future or duplicate DOM delivery must not start another byte round", origin.uploads.keys.all { it.first in 1..round })
        origin.assertHealthy()
        return report
    }

    fun documentInstance(origin: FolderOrigin): String = checkNotNull(origin.readyDocuments[1]) {
        "The actual first document must have completed its double-rAF ready beacon"
    }

    fun assertSameDocument(origin: FolderOrigin, instance: String) {
        origin.assertHealthy()
        assertEquals("Lifecycle changes must not fetch a replacement document", 1, origin.pageRequests.get())
        assertTrue("Only the actual first GET's ready beacon may identify this document",
            origin.readyDocuments.size == 1 && origin.readyDocuments[1] == instance)
    }

    fun assertNoDelivery(origin: FolderOrigin) {
        origin.assertHealthy()
        assertEquals(0, origin.reports.size)
        assertEquals(0, origin.uploads.size)
    }

    private fun assertLeaseBytes(selectedLeases: Set<File>) {
        assertEquals(1, selectedLeases.size)
        val root = File(selectedLeases.single(), UploadTreeDocumentsProvider.ROOT_NAME)
        assertTrue("The document's original upload lease and real root must remain readable", root.isDirectory)
        val actual = root.walkTopDown().filter(File::isFile).toMutableList()
        val expected = UploadTreeDocumentsProvider.expectedFiles()
        assertEquals("Every physical payload must remain, including duplicate basenames", expected.size, actual.size)
        // Logical paths are independently checked in the actual FileList and POSTs. Physical
        // payload directories may be private ids in the prepared-manifest implementation.
        for((path, bytes) in expected) {
            val index = actual.indexOfFirst { it.name == path.substringAfterLast('/') && it.length() == bytes.size.toLong() && it.readBytes().contentEquals(bytes) }
            assertTrue("Every original basename and byte sequence must survive in the retained lease", index >= 0)
            actual.removeAt(index)
        }
        assertTrue("No unexpected physical payload may replace a fixture entry", actual.isEmpty())
    }

    data class SourceGrants(val tree: Uri, val readWrite: List<Int>, val persisted: Set<Triple<Uri, Boolean, Boolean>>)

    private fun sourceGrants(empty: Boolean = false): SourceGrants {
        val tree = DocumentsContract.buildTreeDocumentUri(UploadTreeDocumentsProvider.AUTHORITY,
            if(empty) UploadTreeDocumentsProvider.EMPTY_ID else UploadTreeDocumentsProvider.ROOT_ID)
        return sourceGrants(tree)
    }

    fun sourceGrants(tree: Uri) = SourceGrants(tree, readWriteGrants(tree), persistedGrants(checkNotNull(tree.authority)))

    private fun readWriteGrants(tree: Uri) = listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        .map { context.checkUriPermission(tree, Process.myPid(), Process.myUid(), it) }

    private fun persistedGrants(authority: String) = context.contentResolver.persistedUriPermissions
        .filter { it.uri.authority == authority }
        .map { Triple(it.uri, it.isReadPermission, it.isWritePermission) }.toSet()

    fun awaitPickerRetired(before: SourceGrants) {
        waitFor {
            !documentsVisible() && !receiverPresent() && browser.prompts.prompt.value == null &&
                browser.uploads.state.value.phase == BrowserUploadPhase.IDLE && readWriteGrants(before.tree) == before.readWrite
        }
        assertTrue("Temporary TREE grants must return to their original baseline", readWriteGrants(before.tree) == before.readWrite)
        assertTrue("TREE selection must not add persisted grants", persistedGrants(checkNotNull(before.tree.authority)) == before.persisted)
    }

    fun leases() = File(context.cacheDir, "miru-folder-staging").listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("folder-lease-") }.toSet()

    private fun liveActivities(): List<Activity> {
        var activities = emptyList<Activity>()
        instrumentation.runOnMainSync { activities = listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
            .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.distinct() }
        return activities
    }

    private fun receiverPresent() = liveActivities().any { it is UploadPickerActivity }

    fun touchButton(yDp: Float) {
        // A retired picker and an IDLE upload do not establish that Android has returned
        // input focus to this Main window. Wait for the real touch recipient before injection.
        waitFor {
            var ready = false
            compose.runOnIdle {
                ready = compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                    compose.activity.window.decorView.hasWindowFocus()
            }
            ready
        }
        var bounds = android.graphics.Rect()
        compose.runOnIdle {
            fun find(view: View): EngineView? = if(view is EngineView) view else (view as? ViewGroup)?.let { group ->
                (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
            }
            val engine = checkNotNull(find(compose.activity.window.decorView)).asView()
            assertTrue("The actual Gecko touch recipient must be visible", engine.isShown)
            val xy = IntArray(2); engine.getLocationOnScreen(xy)
            bounds = android.graphics.Rect(xy[0], xy[1], xy[0] + engine.width, xy[1] + engine.height)
            assertTrue("The fixture touch must lie inside the actual Gecko view", engine.width > 0 &&
                engine.height > yDp * context.resources.displayMetrics.density)
            android.util.Log.i("MiruFolderPickerTest", "stage=WEB_TOUCH_READY mainFocused=${compose.activity.window.decorView.hasWindowFocus()} " +
                "mainResumed=${compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)} yDp=$yDp bounds=$bounds")
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
    fun waitFor(condition: () -> Boolean) = compose.waitUntil(20_000, condition)
    fun pickerStarts() = starts.get()
    companion object { private val DOCUMENTS_PACKAGES = setOf("com.android.documentsui", "com.google.android.documentsui") }
}

internal data class FolderExpectedFile(val path: String, val type: String, val lastModified: Long, val bytes: ByteArray) {
    fun tuple() = FolderFileTuple(path, path.substringAfterLast('/'), type, bytes.size.toLong(), lastModified, folderByteHash(bytes))
}

internal data class FolderFileTuple(val path: String, val name: String, val type: String, val size: Long, val lastModified: Long, val byteHash: String)
internal fun folderByteHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal class FolderUploadBytes(val documentGet: Int, val documentInstance: String, val bytes: ByteArray)

internal class FolderOrigin : Closeable {
    private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val pageRequests = AtomicInteger()
    val readyDocuments = ConcurrentHashMap<Int, String>()
    val reports = ConcurrentHashMap<Int, JSONObject>()
    val uploads = ConcurrentHashMap<Pair<Int, Int>, FolderUploadBytes>()
    val failure = AtomicReference<Throwable?>()
    val url = "http://127.0.0.1:${server.localPort}/"
    fun reportCount(event: String) = reports.values.count { it.getString("event") == event }
    fun assertHealthy() { failure.get()?.let { throw AssertionError("Synthetic folder server failed", it) } }
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
        val path = target.substringBefore('?')
        val content = when(path) {
            "/" -> { check(request[0] == "GET"); document(pageRequests.incrementAndGet()) }
            "/ready" -> {
                check(request[0] == "POST")
                val ready = JSONObject(body.toString(Charsets.UTF_8))
                val documentGet = ready.getInt("documentGet")
                val instance = ready.getString("documentInstance")
                check(documentGet in 1..pageRequests.get() && instance.isNotBlank())
                check(readyDocuments.putIfAbsent(documentGet, instance) == null) { "Duplicate document ready beacon" }
                "ok"
            }
            "/report" -> {
                check(request[0] == "POST")
                val report = JSONObject(body.toString(Charsets.UTF_8))
                val round = report.getInt("round")
                check(round > 0)
                check(reports.putIfAbsent(round, report) == null) { "Duplicate folder report round" }
                "ok"
            }
            "/upload" -> {
                check(request[0] == "POST")
                val query = target.substringAfter('?', "").split('&').filter(String::isNotEmpty).associate {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                }
                val round = checkNotNull(query["round"]).toInt()
                val index = checkNotNull(query["index"]).toInt()
                val documentGet = checkNotNull(query["documentGet"]).toInt()
                val instance = checkNotNull(query["documentInstance"])
                check(round > 0 && index >= 0 && documentGet in 1..pageRequests.get() && instance.isNotBlank())
                check(uploads.putIfAbsent(round to index, FolderUploadBytes(documentGet, instance, body)) == null) { "Duplicate folder byte POST" }
                "ok"
            }
            else -> "fixture"
        }.toByteArray()
        val type = if(path == "/") "text/html; charset=utf-8" else "text/plain"
        socket.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${content.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            write(content); flush()
        }
    }
    private fun document(documentGet: Int) = """
        <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Folder ready</title>
        <style>body{margin:0}button{display:block;width:220px;height:100px;background:#13579b;color:white;border:0}#inspect{margin-top:10px}</style>
        <button id="pick">Choose directory fixture</button><button id="inspect">Inspect files</button>
        <input id="files" type="file" webkitdirectory multiple hidden>
        <script>
        const input=document.getElementById('files');
        const documentGet=$documentGet;
        const documentInstance=Array.from(crypto.getRandomValues(new Uint32Array(4)),v=>v.toString(16)).join('-');
        let round=0;
        async function report(event){
          const currentRound=++round;
          const files=Array.from(input.files);
          for(let i=0;i<files.length;i++)await fetch('/upload?round='+currentRound+'&index='+i+'&documentGet='+documentGet+'&documentInstance='+encodeURIComponent(documentInstance),{method:'POST',body:await files[i].arrayBuffer()});
          await fetch('/report',{method:'POST',body:JSON.stringify({round:currentRound,event,documentGet,documentInstance,files:files.map(f=>({name:f.name,type:f.type,size:f.size,lastModified:f.lastModified,path:f.webkitRelativePath}))})});
        }
        document.getElementById('pick').onclick=()=>input.click();
        document.getElementById('inspect').onclick=()=>report('inspect');
        input.addEventListener('change',()=>report('change'));
        requestAnimationFrame(()=>requestAnimationFrame(()=>fetch('/ready',{method:'POST',body:JSON.stringify({documentGet,documentInstance})})));
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
