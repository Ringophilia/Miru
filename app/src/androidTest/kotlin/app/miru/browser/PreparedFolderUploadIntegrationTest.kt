// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.provider.DocumentsContract
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real source metadata, TREE grants, second Gecko confirmation and full duplicate FileList POSTs. */
@RunWith(AndroidJUnit4::class)
class PreparedFolderUploadIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val driver by lazy { FolderUploadDriver(compose) }

    @Before fun preserveTabsAndObserveTheRealTreeLaunch() = driver.preserveTabsAndObserveTheRealTreeLaunch()
    @After fun closeOnlyOwnedTabsAndRestoreObservation() = driver.closeOnlyOwnedTabsAndRestoreObservation()

    @Test fun explicitMetadataDuplicatePathsAndBothSameNamedDirectoryBranchesReachTheRealWebsite() = driver.preservingPrimaryFailure {
        FolderOrigin().use { origin ->
            val baseline = driver.leases()
            val grants = driver.sourceGrants(DocumentsContract.buildTreeDocumentUri(
                PreparedFolderDocumentsProvider.AUTHORITY, PreparedFolderDocumentsProvider.ROOT_ID))
            val owner = driver.page(origin, private = false)
            val instance = driver.documentInstance(origin)
            val source = PreparedFolderDocumentsProvider.expectedFiles()
            assertEquals("The fixture must preserve every separate source occurrence", 12, source.size)
            assertEquals(2, source.count { it.path == "${PreparedFolderDocumentsProvider.ROOT_NAME}/same.txt" })
            assertEquals(2, source.count { it.path == "${PreparedFolderDocumentsProvider.ROOT_NAME}/same-dir/inside.txt" })
            assertTrue(source.any { it.lastModified == 0L })
            assertTrue(source.any { (it.lastModified ?: 0L) < 0L })
            assertEquals(1, source.count { it.lastModified == null })
            val beforeCopy = System.currentTimeMillis()
            driver.openTreePicker(owner)
            driver.selectFixtureRoot(PreparedFolderDocumentsProvider.ROOT_LABEL)
            driver.confirmSystemUseAndAllow(PreparedFolderDocumentsProvider.ROOT_LABEL)
            driver.awaitConfirmation(PreparedFolderDocumentsProvider.ROOT_NAME, owner)
            val afterCopy = System.currentTimeMillis()
            driver.assertNoDelivery(origin)
            val selectedLeases = driver.leases() - baseline
            assertEquals(1, selectedLeases.size)
            val physical = physicalPayloads(selectedLeases)
            assertPhysicalBytes(source, physical)
            val unknown = source.single { it.lastModified == null }
            val unknownPayload = physical.single { it.name == unknown.name && it.readBytes().contentEquals(unknown.bytes) }
            val copiedTime = unknownPayload.lastModified()
            assertTrue("Unknown source time must use the real payload stat within this copy's wall-clock interval",
                copiedTime in beforeCopy..afterCopy)
            val expected = source.map { entry ->
                FolderExpectedFile(entry.path, entry.mime, entry.lastModified ?: copiedTime, entry.bytes)
            }

            // Only this real Gecko FolderUploadPrompt's actual visible ALLOW authorizes delivery.
            compose.onNodeWithTag("page_prompt_folder_confirm").performClick()
            driver.assertFileReport(origin, round = 1, event = "change", expected = expected)
            driver.awaitPickerRetired(grants)
            driver.assertSameDocument(origin, instance)
            assertEquals(1, driver.pickerStarts())
            assertPhysicalBytes(source, physicalPayloads(selectedLeases))
            assertEquals("A completed native answer must not rewrite the unknown source fallback", copiedTime, unknownPayload.lastModified())

            // A second actual File.arrayBuffer read proves that all duplicate objects remain usable.
            driver.touchButton(145f)
            driver.assertFileReport(origin, round = 2, event = "inspect", expected = expected)
            assertEquals(1, origin.reportCount("change"))
            assertEquals(24, origin.uploads.size)
            assertEquals(1, driver.pickerStarts())
            driver.assertSameDocument(origin, instance)
            compose.runOnIdle { driver.browser.closeTab(owner) }
            driver.waitFor { selectedLeases.none(File::exists) && driver.leases() == baseline }
            driver.awaitPickerRetired(grants)
        }
    }

    @Test fun mixingANormalFileWithAnUnconvertibleVirtualDocumentRejectsTheWholeTreeAndReleasesItsGrant() = driver.preservingPrimaryFailure {
        FolderOrigin().use { origin ->
            val baseline = driver.leases()
            val grants = driver.sourceGrants(DocumentsContract.buildTreeDocumentUri(
                PreparedFolderDocumentsProvider.AUTHORITY, PreparedFolderDocumentsProvider.REJECT_ID))
            val owner = driver.page(origin, private = false)
            val instance = driver.documentInstance(origin)
            driver.openTreePicker(owner)
            driver.selectFixtureRoot(PreparedFolderDocumentsProvider.REJECT_LABEL)
            driver.confirmSystemUseAndAllow(PreparedFolderDocumentsProvider.REJECT_LABEL)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            driver.waitFor { compose.onAllNodesWithText(context.getString(R.string.upload_folder_unavailable)).fetchSemanticsNodes().isNotEmpty() }
            driver.awaitPickerRetired(grants)
            driver.waitFor { driver.leases() == baseline }
            driver.assertNoDelivery(origin)
            driver.touchButton(145f)
            driver.assertFileReport(origin, round = 1, event = "inspect", expected = emptyList())
            assertEquals(0, origin.reportCount("change"))
            assertEquals(0, origin.uploads.size)
            assertEquals(1, driver.pickerStarts())
            driver.assertSameDocument(origin, instance)
            compose.runOnIdle { driver.browser.closeTab(owner) }
            driver.waitFor { driver.leases() == baseline }
            driver.awaitPickerRetired(grants)
        }
    }

    private fun physicalPayloads(selectedLeases: Set<File>): List<File> {
        assertEquals(1, selectedLeases.size)
        val root = File(selectedLeases.single(), PreparedFolderDocumentsProvider.ROOT_NAME)
        assertTrue("The selected lease must retain the actual authorized physical root", root.isDirectory)
        return root.walkTopDown().filter(File::isFile).toList()
    }

    private fun assertPhysicalBytes(source: List<PreparedFolderDocumentsProvider.ExpectedFile>, physical: List<File>) {
        assertEquals("Every source occurrence must keep an independent physical payload", source.size, physical.size)
        val remaining = physical.toMutableList()
        for(entry in source) {
            val index = remaining.indexOfFirst { it.name == entry.name && it.length() == entry.bytes.size.toLong() && it.readBytes().contentEquals(entry.bytes) }
            assertTrue("Physical source basenames and bytes must match a multiset, including both duplicate branches", index >= 0)
            remaining.removeAt(index)
        }
        assertTrue(remaining.isEmpty())
    }
}
