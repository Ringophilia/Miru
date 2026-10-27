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
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
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
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.miru.browser.engine.BrowserPromptKind
import app.miru.browser.engine.BrowserPromptState
import app.miru.browser.engine.BrowserFileCapture
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.ByteBuffer
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
    private val captureLaunchCount = AtomicInteger()
    private val captureLaunch = AtomicReference<CaptureLaunch?>()
    private val capturePermissionPrompts = mutableSetOf<Pair<Int, Int>>()
    private var captureRequested = false

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
        captureLaunchCount.set(0)
        captureLaunch.set(null)
        capturePermissionPrompts.clear()
        captureRequested = false
        pickerMonitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) {
                    pickerMultipleFlag.set(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
                    pickerLaunchCount.incrementAndGet()
                }
                if(intent.action in setOf(MediaStore.ACTION_IMAGE_CAPTURE, MediaStore.ACTION_VIDEO_CAPTURE)) {
                    @Suppress("DEPRECATION")
                    val output = intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT)
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    captureLaunch.set(CaptureLaunch(checkNotNull(intent.action), output,
                        intent.flags and flags == flags, intent.clipData?.let {
                            it.itemCount == 1 && it.getItemAt(0).uri == output
                        } == true, intent.getIntExtra("android.intent.extras.CAMERA_FACING", -1) == 0))
                    captureLaunchCount.incrementAndGet()
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
            if(captureRequested) {
                if(permissionControllerVisible()) {
                    systemBack()
                    compose.waitUntil(10_000) { !permissionControllerVisible() }
                }
                cleanupActualCameraIfVisible()
            }
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

    @Test fun absentAcceptAllowsRealTextPngAndOpaqueBinaryFiles() = runMimeCase(
        acceptedTypes = null, multiple = true,
        expected = listOf(textFixture(), pngFixture(), opaqueFixture()), blocked = emptyList(),
    )

    @Test fun textPlainAcceptFiltersPngAndOpaqueBinaryBeforeRealTextUpload() = runMimeCase(
        acceptedTypes = "text/plain", multiple = false,
        expected = listOf(textFixture()), blocked = listOf(pngFixture(), opaqueFixture()),
    )

    @Test fun mixedTextAndPngAcceptSelectsBothRealFilesAndExcludesOpaqueBinary() = runMimeCase(
        acceptedTypes = "text/plain,image/png", multiple = true,
        expected = listOf(textFixture(), pngFixture()), blocked = listOf(opaqueFixture()),
    )

    @Test fun imageWildcardAcceptFiltersTextAndOpaqueBinaryBeforeRealPngUpload() = runMimeCase(
        acceptedTypes = "image/*", multiple = false,
        expected = listOf(pngFixture()), blocked = listOf(textFixture(), opaqueFixture()),
    )

    @Test fun pngExtensionAcceptFiltersTextAndOpaqueBinaryBeforeRealPngUpload() = runMimeCase(
        acceptedTypes = ".png", multiple = false,
        expected = listOf(pngFixture()), blocked = listOf(textFixture(), opaqueFixture()),
    )

    @Test fun realCameraPhotoReachesTheWebsiteWithItsOriginalJpegBytes() = runCaptureCase(video = false)

    @Test fun realCameraVideoReachesTheWebsiteWithItsOriginalRecordedBytes() = runCaptureCase(video = true)

    /** Only process-memory evidence from the actual system launch. Never log the output URI. */
    private class CaptureLaunch(val action: String, val output: Uri?, val readWriteFlags: Boolean,
        val outputClip: Boolean, val backFacingHint: Boolean) {
        override fun toString() = "CaptureLaunch(outputPresent=${output != null}, readWrite=$readWriteFlags, clip=$outputClip)"
    }

    private fun runCaptureCase(video: Boolean) {
        val acceptedTypes = if(video) "video/*" else "image/*"
        val expectedName = if(video) "capture.mp4" else "capture.jpg"
        val expectedMime = if(video) "video/mp4" else "image/jpeg"
        val expectedAction = if(video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE
        val outputBaseline = captureOutputFiles()
        val leaseBaseline = uploadLeaseDirectories()
        UploadOrigin(acceptedTypes, captureMode = "environment").use { origin ->
            val owner = page(origin, multiple = false)
            val request = openFilePrompt(owner)
            assertEquals("The real HTML capture=environment request must retain its facing hint",
                BrowserFileCapture.BACK_CAMERA, request.fileCapture)
            captureRequested = true
            compose.onNodeWithTag(if(video) "page_prompt_capture_video" else "page_prompt_capture_photo")
                .assertIsDisplayed().performClick()
            awaitActualCameraControl("shutter_button")
            assertEquals("Exactly one real external capture action must be launched", 1, captureLaunchCount.get())
            assertEquals("Capture must not be replaced by choosing a static document", 0, pickerStarts())
            val launch = checkNotNull(captureLaunch.get())
            assertEquals(expectedAction, launch.action)
            assertTrue("The actual output grant must include read and write flags", launch.readWriteFlags)
            assertTrue("The actual launch must carry its same output URI in ClipData", launch.outputClip)
            assertTrue("The back-facing camera extra must be an actual launch hint", launch.backFacingHint)
            val outputUri = checkNotNull(launch.output)
            val context = instrumentation.targetContext
            assertEquals("content", outputUri.scheme)
            assertEquals("${context.packageName}.uploads", outputUri.authority)
            var outputFile: File? = null
            waitFor("The actual capture action must own one new restricted output file") {
                outputFile = (captureOutputFiles() - outputBaseline).singleOrNull { it.name == expectedName }
                outputFile != null
            }
            val originalFile = checkNotNull(outputFile)
            assertTrue("The new capture file must be the actual EXTRA_OUTPUT resource",
                FileProvider.getUriForFile(context, "${context.packageName}.uploads", originalFile) == outputUri)
            val cameraUid = actualCameraUid()
            assertNotEquals("Capture grants must be checked for the external camera, not our own provider UID", Process.myUid(), cameraUid)
            assertCaptureGrant(outputUri, cameraUid, granted = true)

            // Camera2 PhotoModule writes EXTRA_OUTPUT only when Done is clicked. Open the actual
            // output read-only BEFORE that click and retain its original inode across cleanup.
            // This descriptor is never a staging copy and never writes or delays production IO.
            val descriptor = checkNotNull(context.contentResolver.openFileDescriptor(outputUri, "r"))
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { source ->
                clickActualCameraControl("shutter_button")
                if(video) {
                    waitFor("The actual Camera2 recording clock must advance to at least two seconds", 20_000) {
                        allowCapturePermissionIfShown()
                        actualCameraRecordingSeconds()?.let { it >= 2 } == true
                    }
                    clickActualCameraControl("shutter_button")
                }
                awaitActualCameraControl("done_button")
                assertEquals("A real review must not upload before the user accepts it", 0, origin.reportCount("change"))
                assertEquals(request.token, browser.prompts.prompt.value?.token)
                assertTrue("The original camera output must still exist during review", originalFile.isFile)
                assertCaptureGrant(outputUri, cameraUid, granted = true)
                if(video) assertRecordedVideoFormat(source)
                else android.util.Log.i("MiruUploadAcceptance", "photoReview originalOutputNonEmpty=${source.channel.size() > 0}")

                clickActualCameraControl("done_button")
                val report = awaitReport(origin, 1)
                // Success and native staging may unlink the original pathname. The retained
                // read-only descriptor still supplies exactly the camera's original bytes.
                val originalBytes = readOriginalCaptureBytes(source)
                if(video) assertRecordedVideoFormat(source) else assertCapturedJpegFormat(originalBytes)
                assertEquals("change", report.label)
                assertEquals(1, report.files.size)
                assertFile(report.files.single(), expectedName, originalBytes, origin.upload(1, 0), expectedMime)
                assertEquals("Exactly one native change may reach the actual website", 1, origin.reportCount("change"))
                assertEquals(1, captureLaunchCount.get())
                assertNull(browser.prompts.prompt.value)
                waitFor("Capture completion must retire the actual output file, receiver and camera grants") {
                    !originalFile.exists() && liveActivities().none { it is UploadPickerActivity } &&
                        captureGrantDenied(outputUri, cameraUid)
                }
                val leases = awaitNewUploadLeases(leaseBaseline)
                val retained = leases.flatMap { it.walkTopDown().filter(File::isFile).toList() }
                assertEquals("The delivered capture must retain one document-owned file", 1, retained.size)
                assertEquals(expectedName, retained.single().name)
                assertEquals("Document retention must preserve the separately verified original capture bytes",
                    uploadHash(originalBytes), uploadHash(retained.single().readBytes()))
                compose.runOnIdle { browser.closeTab(owner) }
                awaitUploadLeasesDeleted(leases)
                assertCaptureGrant(outputUri, cameraUid, granted = false)
            } // Finally closes the original read-only descriptor even after a failed assertion.
        }
    }

    private fun captureOutputFiles(): Set<File> =
        File(instrumentation.targetContext.cacheDir, "miru-upload-capture").takeIf { it.isDirectory }
            ?.walkTopDown()?.filter { it.isFile }?.toSet().orEmpty()

    private fun actualCameraUid(): Int {
        try { return instrumentation.targetContext.packageManager.getApplicationInfo(CAMERA_PACKAGE, 0).uid }
        catch(_: PackageManager.NameNotFoundException) {
            // Resolve only the already observed handler's UID through read-only package inventory.
            // No permission identity is adopted and no camera/microphone permission is pregranted.
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd package list packages -U $CAMERA_PACKAGE")
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                val exact = Regex("^package:com\\.android\\.camera2\\s+uid:(\\d+)\\s*$")
                val matches = reader.lineSequence().take(32).mapNotNull {
                    exact.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull()
                }.toList()
                assertEquals("Read-only inventory must identify exactly the actual Camera2 package UID", 1, matches.size)
                matches.single()
            }
        }
    }

    private fun assertCaptureGrant(uri: Uri, uid: Int, granted: Boolean) {
        val expected = if(granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        for(flag in listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            assertEquals("The actual Camera2 output grant must match its capture lifecycle", expected,
                instrumentation.targetContext.checkUriPermission(uri, -1, uid, flag))
    }

    private fun captureGrantDenied(uri: Uri, uid: Int): Boolean =
        listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION).all {
            instrumentation.targetContext.checkUriPermission(uri, -1, uid, it) == PackageManager.PERMISSION_DENIED
        }

    private fun readOriginalCaptureBytes(source: FileInputStream): ByteArray {
        val length = source.channel.size()
        assertTrue("A real bounded capture output must contain nonempty bytes", length in 1L..CAPTURE_BODY_LIMIT.toLong())
        source.channel.position(0)
        val bytes = ByteArray(length.toInt())
        var offset = 0
        while(offset < bytes.size) {
            val count = source.read(bytes, offset, bytes.size - offset)
            assertTrue("The original camera output must remain readable in full", count > 0)
            offset += count
        }
        assertEquals("The finalized camera output must have exactly its observed length", -1, source.read())
        source.channel.position(0)
        return bytes
    }

    private fun assertCapturedJpegFormat(bytes: ByteArray) {
        assertTrue("The actual captured image must contain JPEG framing", bytes.size > 4 &&
            bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte())
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        assertEquals("image/jpeg", options.outMimeType)
        assertTrue("The real JPEG decoder must report positive captured dimensions", options.outWidth > 0 && options.outHeight > 0)
    }

    private fun assertRecordedVideoFormat(source: FileInputStream) {
        assertTrue("A completed recording must have actual bytes before parsing", source.channel.size() > 0)
        val extractor = MediaExtractor()
        try {
            source.channel.position(0)
            extractor.setDataSource(source.fd)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            assertNotNull("The actual recorder output must contain a video track", track)
            val selectedTrack = checkNotNull(track)
            val format = extractor.getTrackFormat(selectedTrack)
            assertTrue("The actual recorded video must have positive dimensions",
                format.getInteger(MediaFormat.KEY_WIDTH) > 0 && format.getInteger(MediaFormat.KEY_HEIGHT) > 0)
            assertTrue("The actual recorded video must have nonzero duration",
                format.containsKey(MediaFormat.KEY_DURATION) && format.getLong(MediaFormat.KEY_DURATION) > 0)
            extractor.selectTrack(selectedTrack)
            assertTrue("The actual video track must contain a real encoded sample",
                extractor.readSampleData(ByteBuffer.allocate(1024 * 1024), 0) > 0)
        } finally {
            extractor.release()
            source.channel.position(0)
        }
    }

    private fun awaitActualCameraControl(resource: String) {
        try {
            waitFor("The actual Camera2 control must become available: $resource", 30_000) {
                allowCapturePermissionIfShown()
                actualCameraControlAvailable(resource)
            }
        } catch(failure: AssertionError) {
            throw AssertionError("Camera2 control=$resource; ${captureUiDiagnostics()}", failure)
        }
    }

    private fun clickActualCameraControl(resource: String) {
        awaitActualCameraControl(resource)
        waitFor("The actual Camera2 control must accept the user's action: $resource") {
            withCameraRoot { root ->
                val nodes = root.findAccessibilityNodeInfosByViewId("$CAMERA_PACKAGE:id/$resource")
                try { nodes.any { it.isVisibleToUser && it.isEnabled &&
                    it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK } &&
                    it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } }
                finally { nodes.forEach { it.recycle() } }
            } == true
        }
    }

    private fun actualCameraControlAvailable(resource: String): Boolean = withCameraRoot { root ->
        val nodes = root.findAccessibilityNodeInfosByViewId("$CAMERA_PACKAGE:id/$resource")
        try { nodes.any { it.isVisibleToUser && it.isEnabled &&
            it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK } } }
        finally { nodes.forEach { it.recycle() } }
    } == true

    private fun actualCameraRecordingSeconds(): Int? = withCameraRoot { root ->
        val nodes = root.findAccessibilityNodeInfosByViewId("$CAMERA_PACKAGE:id/recording_time")
        try {
            nodes.firstNotNullOfOrNull { node ->
                if(!node.isVisibleToUser) return@firstNotNullOfOrNull null
                val time = node.text?.toString()?.substringBefore('.')?.filter { it.isDigit() || it == ':' }.orEmpty()
                val parts = time.split(':').map { it.toIntOrNull() }
                if(parts.any { it == null }) null else when(parts.size) {
                    2 -> checkNotNull(parts[0]) * 60 + checkNotNull(parts[1])
                    3 -> checkNotNull(parts[0]) * 3600 + checkNotNull(parts[1]) * 60 + checkNotNull(parts[2])
                    else -> null
                }
            }
        } finally { nodes.forEach { it.recycle() } }
    }

    /** Real system buttons only. Ordinary capture may need CAMERA for Miru and CAMERA/MIC for
     * the external recorder. An unknown permission, including location, is never auto-allowed.
     * The tested AVD's dialog is English; the explicit Chinese capability phrases are also safe.
     */
    private fun allowCapturePermissionIfShown(): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        try {
            if(root.packageName?.toString() !in PERMISSION_CONTROLLER_PACKAGES) return false
            for(namespace in PERMISSION_CONTROLLER_PACKAGES) {
                val nodes = root.findAccessibilityNodeInfosByViewId("$namespace:id/permission_message")
                val message = try { nodes.joinToString(" ") { it.text?.toString().orEmpty() }.lowercase(Locale.ROOT) }
                    finally { nodes.forEach { it.recycle() } }
                if(message.isBlank()) continue
                val location = listOf("location", "位置信息", "位置").any(message::contains)
                val camera = listOf("take pictures", "record video", "拍摄", "录制视频").any(message::contains)
                val microphone = listOf("record audio", "microphone", "麦克风", "录音", "录制音频").any(message::contains)
                assertTrue("Capture may only authorize an actual camera/microphone dialog; knownCamera=$camera knownMicrophone=$microphone location=$location",
                    !location && (camera || microphone))
                val seen = root.windowId to message.hashCode()
                if(seen in capturePermissionPrompts) return false
                for(resource in listOf("permission_allow_foreground_only_button", "permission_allow_one_time_button", "permission_allow_button")) {
                    val buttons = root.findAccessibilityNodeInfosByViewId("$namespace:id/$resource")
                    try {
                        if(buttons.any { it.isVisibleToUser && it.isEnabled && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }) {
                            capturePermissionPrompts += seen
                            return true
                        }
                    } finally { buttons.forEach { it.recycle() } }
                }
            }
            return false
        } finally { root.recycle() }
    }

    private fun permissionControllerVisible(): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        try { return root.packageName?.toString() in PERMISSION_CONTROLLER_PACKAGES }
        finally { root.recycle() }
    }

    private fun <T> withCameraRoot(block: (AccessibilityNodeInfo) -> T): T? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        try { return if(root.packageName?.toString() == CAMERA_PACKAGE) block(root) else null }
        finally { root.recycle() }
    }

    private fun captureUiDiagnostics(): String =
        "cameraVisible=${withCameraRoot { true } == true}, permissionVisible=${permissionControllerVisible()}, " +
            listOf("shutter_button", "done_button", "cancel_button", "retake_button").joinToString { resource ->
                "$resource=${actualCameraControlAvailable(resource)}"
            } + ", realRecordingClockObserved=${actualCameraRecordingSeconds() != null}, " +
            "captureLaunches=${captureLaunchCount.get()}, outputPresent=${captureLaunch.get()?.output != null}"

    private fun cleanupActualCameraIfVisible() {
        repeat(3) {
            if(withCameraRoot { true } != true) return
            val reviewBefore = actualCameraControlAvailable("cancel_button")
            if(reviewBefore) clickActualCameraControl("cancel_button") else systemBack()
            waitFor("The real Camera2 cancel/back action must finish or change its capture state", 10_000) {
                withCameraRoot { true } != true || actualCameraControlAvailable("cancel_button") != reviewBefore
            }
        }
        assertFalse("Fixture cleanup must not leave the actual external camera open", withCameraRoot { true } == true)
    }

    private data class ExpectedUpload(val documentId: String, val name: String, val mime: String, val bytes: ByteArray)
    private fun textFixture() = ExpectedUpload("single", UploadFixtureDocumentsProvider.SINGLE_NAME,
        "text/plain", UploadFixtureDocumentsProvider.SINGLE_BYTES)
    private fun pngFixture() = ExpectedUpload("image", UploadFixtureDocumentsProvider.PNG_NAME,
        "image/png", UploadFixtureDocumentsProvider.PNG_BYTES)
    private fun opaqueFixture() = ExpectedUpload("opaque", UploadFixtureDocumentsProvider.OPAQUE_NAME,
        "application/octet-stream", UploadFixtureDocumentsProvider.OPAQUE_BYTES)

    private fun runMimeCase(acceptedTypes: String?, multiple: Boolean,
        expected: List<ExpectedUpload>, blocked: List<ExpectedUpload>) {
        UploadOrigin(acceptedTypes).use { origin ->
            val owner = page(origin, multiple = multiple)
            val request = beginSelection(owner)
            assertEquals("The actual native request must retain the HTML multiple choice", multiple, request.fileMultiple)
            assertEquals("The real DocumentsUI launch must retain the HTML multiple choice", multiple, pickerMultipleFlag.get())
            openFixtureRoot(expected.first().name)
            assertActualPickerFilter(origin, request, expected.map { it.name }, blocked.map { it.name })
            if(multiple) selectNamedMultipleFiles(expected.map { it.name })
            else {
                clickDocument(expected.single().name)
                finishPickerSelection(required = false)
            }
            val report = awaitReport(origin, 1)
            assertEquals("Only the real accepted file selection may generate a change", "change", report.label)
            assertEquals(expected.size, report.files.size)
            assertEquals("Every actual selected name must match the precise accepted set",
                expected.map { it.name }.toSet(), report.files.map { it.name }.toSet())
            report.files.forEachIndexed { index, file ->
                val wanted = expected.single { it.name == file.name }
                assertFile(file, wanted.name, wanted.bytes, origin.upload(1, index), expectedMime = wanted.mime)
            }
            assertEquals("MIME filtering must not relaunch the picker", 1, pickerStarts())
            assertEquals(1, origin.reportCount("change"))
            assertNull(browser.prompts.prompt.value)
            awaitReceiverFinishedAndTemporaryGrantReleased(expected.map { it.documentId })
        }
    }

    private fun selectNamedMultipleFiles(names: List<String>) {
        require(names.size > 1 && names.distinct().size == names.size)
        performDocumentRowAction(names.first(), index = 0, action = AccessibilityNodeInfo.ACTION_LONG_CLICK)
        awaitNamedDocumentSelection(names.associateWith { it == names.first() })
        names.drop(1).forEachIndexed { index, name ->
            performDocumentRowAction(name, index = 0, action = AccessibilityNodeInfo.ACTION_CLICK)
            val selected = names.take(index + 2).toSet()
            awaitNamedDocumentSelection(names.associateWith { it in selected })
        }
        finishPickerSelection(required = true)
    }

    private fun awaitNamedDocumentSelection(expected: Map<String, Boolean>) {
        var observed = emptyMap<String, DocumentRowSelection?>()
        try {
            waitFor("The actual DocumentsUI selection must match every named MIME fixture") {
                observed = withDocumentsRoot { root -> expected.mapValues { (name, _) ->
                    withDocumentRow(root, name, index = 0) { row ->
                        DocumentRowSelection(row.isSelected, row.isCheckable && row.isChecked,
                            row.collectionItemInfo?.isSelected == true,
                            row.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK })
                    }
                } }.orEmpty()
                observed.keys == expected.keys && expected.all { (name, selected) ->
                    val state = observed[name]
                    state != null && (state.selected || state.checked || state.collectionSelected) == selected
                }
            }
        } catch(failure: AssertionError) {
            throw AssertionError("Named fixture selection: pickerVisible=${documentsUiVisible()}, states=$observed", failure)
        }
        assertTrue("Real multiple selection must keep DocumentsUI open until confirmation", documentsUiVisible())
    }

    private data class PickerRowAvailability(val enabled: Boolean, val clickAction: Boolean,
        val longClickAction: Boolean, val selected: Boolean) {
        val selectable get() = enabled && (clickAction || longClickAction)
    }
    private data class PickerInventory(val rows: Map<String, List<PickerRowAvailability>>, val unresolved: Set<String>)

    private fun assertActualPickerFilter(origin: UploadOrigin, request: BrowserPromptState,
        allowed: List<String>, blocked: List<String>) {
        val inventory = enumerateActualFixtureDirectory()
        assertTrue("Visible fixture rows must resolve to real DocumentsUI document ancestors: ${inventory.unresolved}",
            inventory.unresolved.isEmpty())
        allowed.forEach { name ->
            val rows = inventory.rows[name].orEmpty()
            assertTrue("Matching synthetic document must be truly visible and selectable: $name rows=$rows",
                rows.any { it.selectable })
            android.util.Log.i("MiruUploadAcceptance", "mimeFilter file=$name visible=true selectable=true")
        }
        assertTrue("A new real picker must start with no fixture already selected",
            inventory.rows.values.flatten().none { it.selected })
        blocked.forEach { name ->
            val rows = inventory.rows[name].orEmpty()
            if(rows.isEmpty()) {
                // Enumeration scrolled the actual directory to its end, then back to its start.
                // This is an absent entry, not a name that happened to be below the viewport.
                android.util.Log.i("MiruUploadAcceptance", "mimeFilter file=$name visible=false selectable=false")
            } else {
                assertTrue("A displayed nonmatching synthetic document must be disabled or have no activation action: $name rows=$rows",
                    rows.none { it.selectable })
                touchRejectedFixture(name)
                awaitDocumentsPickerIdle()
                assertTrue("A real tap on a nonmatching file must leave DocumentsUI open: $name", documentsUiVisible())
                assertEquals("A nonmatching file must not finish or replace the original native request",
                    request.token, browser.prompts.prompt.value?.token)
                assertEquals("A nonmatching file must generate no website file-change report", 0, origin.reportCount("change"))
                val afterTap = enumerateActualFixtureDirectory()
                assertTrue("A rejected row tap must not select another fixture", afterTap.rows.values.flatten().none { it.selected })
                android.util.Log.i("MiruUploadAcceptance", "mimeFilter file=$name visible=true selectable=false tapKeptPicker=true selectionUnchanged=true received=false")
            }
        }
    }

    /** Enumerate the real list to its actual forward boundary. Bounded scrolling fails closed
     * rather than treating offscreen rows or an unknown list widget as a hidden MIME negative.
     */
    private fun enumerateActualFixtureDirectory(): PickerInventory {
        awaitDocumentsPickerIdle()
        scrollFixtureDirectoryToStart()
        val observed = mutableMapOf<String, MutableList<PickerRowAvailability>>()
        val unresolved = mutableSetOf<String>()
        var reachedEnd = false
        for(page in 0 until 8) {
            withDocumentsRoot { root ->
                for(name in SYNTHETIC_DOCUMENT_NAMES) {
                    val rows = actualDocumentRows(root, name)
                    try {
                        for(node in rows) {
                            val row = PickerRowAvailability(node.isEnabled,
                                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK },
                                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK },
                                node.isSelected || (node.isCheckable && node.isChecked) || node.collectionItemInfo?.isSelected == true)
                            observed.getOrPut(name) { mutableListOf() } += row
                        }
                        if(rows.isEmpty() && withActualFileLists(root) { lists -> lists.any {
                            fixtureLabelEvidence(it, name, visibleOnly = true).matches
                        } }) unresolved += name
                    } finally { rows.forEach { it.recycle() } }
                }
            } ?: throw AssertionError("DocumentsUI must remain the actual foreground directory while enumerating fixtures")
            if(!scrollActualFixtureDirectory(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                reachedEnd = true
                break
            }
        }
        assertTrue("The complete synthetic directory must reach a real scroll boundary within eight pages", reachedEnd)
        scrollFixtureDirectoryToStart()
        return PickerInventory(observed.mapValues { it.value.toList() }, unresolved.toSet())
    }

    private fun scrollFixtureDirectoryToStart() {
        for(page in 0 until 8) {
            if(!scrollActualFixtureDirectory(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return
        }
        throw AssertionError("The synthetic directory must reach its actual beginning within eight pages")
    }

    private fun scrollActualFixtureDirectory(action: Int): Boolean {
        val moved = withDocumentsRoot { root ->
            for(namespace in DOCUMENTS_UI_PACKAGES) {
                // AOSP DocumentsUI DirectoryFragment's actual RecyclerView, not its roots drawer.
                val nodes = root.findAccessibilityNodeInfosByViewId("$namespace:id/dir_list")
                try {
                    val list = nodes.firstOrNull { it.isVisibleToUser } ?: continue
                    if(list.actionList.none { it.id == action }) return@withDocumentsRoot false
                    assertTrue("The actual file list advertised a scroll action but rejected it", list.performAction(action))
                    return@withDocumentsRoot true
                } finally { nodes.forEach { it.recycle() } }
            }
            throw AssertionError("The actual DocumentsUI file-list widget must be observable")
        } ?: throw AssertionError("The actual DocumentsUI directory must remain open while scrolling")
        if(moved) awaitDocumentsPickerIdle()
        return moved
    }

    private fun awaitDocumentsPickerIdle() {
        try { instrumentation.uiAutomation.waitForIdle(200, 5_000) }
        catch(failure: java.util.concurrent.TimeoutException) {
            throw AssertionError("The actual synthetic DocumentsUI directory did not become idle", failure)
        }
    }

    private fun touchRejectedFixture(name: String) {
        scrollFixtureDirectoryToStart()
        var bounds: Rect? = null
        for(page in 0 until 8) {
            bounds = withDocumentsRoot { root -> withDocumentRow(root, name, 0) { row ->
                Rect().also(row::getBoundsInScreen).takeIf { !it.isEmpty }
            } }
            if(bounds != null) break
            if(!scrollActualFixtureDirectory(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
        }
        val target = bounds ?: throw AssertionError("The displayed synthetic negative must remain physically observable: $name")
        val down = SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                target.exactCenterX(), target.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if(action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60)
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

    private fun assertFile(file: WebsiteFile, name: String, bytes: ByteArray, uploaded: ByteArray?, expectedMime: String = "text/plain") {
        assertEquals(name, file.name)
        assertEquals(expectedMime, file.mime)
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

    private fun awaitReceiverFinishedAndTemporaryGrantReleased(documentIds: List<String> = listOf("single")) {
        val uris = documentIds.map { DocumentsContract.buildDocumentUri(UploadFixtureDocumentsProvider.AUTHORITY, it) }
        val context = instrumentation.targetContext
        fun permission(uri: android.net.Uri, flags: Int) = context.checkUriPermission(uri, Process.myPid(), Process.myUid(), flags)
        fun allDenied(flags: Int) = uris.all { permission(it, flags) == PackageManager.PERMISSION_DENIED }
        waitFor("The real receiving activity must finish and Android must release its temporary read and write grants") {
            liveActivities().none { it is UploadPickerActivity } &&
                allDenied(Intent.FLAG_GRANT_READ_URI_PERMISSION) && allDenied(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        assertTrue("No real upload receiver may retain the selection grant", liveActivities().none { it is UploadPickerActivity })
        uris.forEach { uri ->
            assertEquals("The selected source URI must no longer be readable through a temporary grant",
                PackageManager.PERMISSION_DENIED, permission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION))
            assertEquals("The selected source URI must not retain a temporary write grant",
                PackageManager.PERMISSION_DENIED, permission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        }
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

    private fun openFixtureRoot(expectedName: String = UploadFixtureDocumentsProvider.SINGLE_NAME) {
        try {
            if (exactDocumentRows(expectedName) > 0) return
            if (!clickSystemText(UploadFixtureDocumentsProvider.ROOT_LABEL)) {
                waitFor("DocumentsUI must expose its actual roots navigation control") {
                    withDocumentsRoot(::openRootsDrawer) == true
                }
                waitFor("DocumentsUI must list the synthetic provider root") {
                    clickSystemText(UploadFixtureDocumentsProvider.ROOT_LABEL)
                }
            }
            waitFor("The selected synthetic root must show its real file rows") {
                exactDocumentRows(expectedName) > 0
            }
        } catch(failure: AssertionError) {
            throw AssertionError("Synthetic picker root expected=$expectedName; ${pickerFixtureDiagnostics()}", failure)
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
                withDocumentRow(root, name, index) { node ->
                    if (longClick) {
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        if (bounds.width() <= 0 || bounds.height() <= 0) false
                        else { touchBounds = bounds; true }
                    } else node.isEnabled && node.isVisibleToUser &&
                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } &&
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } == true
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
        val rows = actualDocumentRows(root, name)
        try { rows.size } finally { rows.forEach { it.recycle() } }
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

    private data class FixtureLabelEvidence(val textExact: Boolean, val descriptionContains: Boolean) {
        val matches get() = textExact || descriptionContains
    }

    /** GridPhotoHolder exposes filename/size/date as the real row's contentDescription rather
     * than a visible title TextView. Match only known synthetic labels inside dir_list, and
     * keep a single actual row when both its own description and a child title match.
     */
    private fun actualDocumentRows(root: AccessibilityNodeInfo, name: String): List<AccessibilityNodeInfo> {
        require(name in SYNTHETIC_DOCUMENT_NAMES) { "Only fixed synthetic document labels may be matched" }
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if(depth > 24 || !node.isVisibleToUser) return
            val documentRow = node.collectionItemInfo != null ||
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }
            if(documentRow) {
                if(fixtureLabelEvidence(node, name).matches && result.none { it == node })
                    result += AccessibilityNodeInfo.obtain(node)
                // Child title/preview nodes are labels or actions of this same row, not files.
                return
            }
            for(index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
            }
        }
        withActualFileLists(root) { lists -> lists.forEach { list ->
            for(index in 0 until list.childCount) {
                val child = list.getChild(index) ?: continue
                try { visit(child, 0) } finally { child.recycle() }
            }
        } }
        return result
    }

    private fun fixtureLabelEvidence(node: AccessibilityNodeInfo, name: String,
        visibleOnly: Boolean = false): FixtureLabelEvidence {
        var textExact = false
        var descriptionContains = false
        fun visit(candidate: AccessibilityNodeInfo, depth: Int) {
            if(depth > 24 || (visibleOnly && !candidate.isVisibleToUser)) return
            textExact = textExact || candidate.text?.toString() == name
            descriptionContains = descriptionContains || candidate.contentDescription?.toString()?.contains(name) == true
            for(index in 0 until candidate.childCount) {
                val child = candidate.getChild(index) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
            }
        }
        visit(node, 0)
        return FixtureLabelEvidence(textExact, descriptionContains)
    }

    private fun <T> withActualFileLists(root: AccessibilityNodeInfo,
        block: (List<AccessibilityNodeInfo>) -> T): T {
        val nodes = DOCUMENTS_UI_PACKAGES.flatMap { root.findAccessibilityNodeInfosByViewId("$it:id/dir_list") }
        try { return block(nodes.filter { it.isVisibleToUser }) }
        finally { nodes.forEach { it.recycle() } }
    }

    private fun pickerFixtureDiagnostics(): String = withDocumentsRoot { root ->
        val labels = withActualFileLists(root) { lists ->
            "fileListVisible=${lists.isNotEmpty()}, " + SYNTHETIC_DOCUMENT_NAMES.joinToString { name ->
                val text = lists.any { fixtureLabelEvidence(it, name).textExact }
                val description = lists.any { fixtureLabelEvidence(it, name).descriptionContains }
                "$name(textExact=$text,descriptionContains=$description)"
            }
        }
        val rows = SYNTHETIC_DOCUMENT_NAMES.joinToString { name ->
            val matches = actualDocumentRows(root, name)
            try { "$name=" + matches.joinToString(prefix = "[", postfix = "]") { row ->
                val label = fixtureLabelEvidence(row, name)
                val bounds = Rect().also(row::getBoundsInScreen)
                "(textExact=${label.textExact},descriptionContains=${label.descriptionContains}," +
                    "enabled=${row.isEnabled},click=${row.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }}," +
                    "longClick=${row.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }},bounds=$bounds)"
            } } finally { matches.forEach { it.recycle() } }
        }
        // Never dump arbitrary node text, descriptions, URLs, source URIs or private page state.
        "pickerVisible=true, $labels, rows={$rows}"
    } ?: "pickerVisible=false"

    private fun <T> withDocumentRow(root: AccessibilityNodeInfo, name: String, index: Int,
        block: (AccessibilityNodeInfo) -> T): T? {
        val rows = actualDocumentRows(root, name)
        try {
            return rows.getOrNull(index)?.let(block)
        } finally { rows.forEach { it.recycle() } }
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
        private const val CAMERA_PACKAGE = "com.android.camera2"
        private val PERMISSION_CONTROLLER_PACKAGES = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
        private const val CAPTURE_BODY_LIMIT = 32 * 1024 * 1024
        private val DOCUMENTS_UI_PACKAGES = setOf("com.android.documentsui", "com.google.android.documentsui")
        private val SYNTHETIC_DOCUMENT_NAMES = listOf(UploadFixtureDocumentsProvider.SINGLE_NAME,
            UploadFixtureDocumentsProvider.SAME_NAME, UploadFixtureDocumentsProvider.PNG_NAME,
            UploadFixtureDocumentsProvider.OPAQUE_NAME)
    }
}

private data class WebsiteFile(val name: String, val mime: String, val size: Long)
private data class WebsiteFileReport(val round: Int, val label: String, val files: List<WebsiteFile>)
private fun uploadHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Reads actual HTTP bodies as bytes, without a Reader consuming ahead into the binary payload. */
private class UploadOrigin(private val acceptedTypes: String? = "text/plain", private val captureMode: String? = null) : Closeable {
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
        require(acceptedTypes in setOf(null, "text/plain", "text/plain,image/png", "image/*", ".png", "video/*")) {
            "Only the fixed synthetic accept matrix may be rendered"
        }
        require(captureMode in setOf(null, "environment") &&
            (captureMode == null || acceptedTypes in setOf("image/*", "video/*"))) {
            "Only the fixed environment photo/video capture fixtures may be rendered"
        }
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
        val bodyLimit = if(captureMode == null) 1_048_576 else 32 * 1024 * 1024
        check(length in 0..bodyLimit)
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
        <input id="files" type="file" ${acceptedTypes?.let { "accept=\"$it\"" }.orEmpty()} ${captureMode?.let { "capture=\"$it\"" }.orEmpty()} ${if (multiple) "multiple" else ""} hidden>
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
