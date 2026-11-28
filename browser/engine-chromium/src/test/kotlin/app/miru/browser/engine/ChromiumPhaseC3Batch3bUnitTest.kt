// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.PrivateKey

class ChromiumPhaseC3Batch3bUnitTest {
    private fun controllerHarness(
        tabUrl: String = "https://example.com/form",
        isPrivate: Boolean = false,
        onClientCertResolve: (Long, Array<ByteArray>?, PrivateKey?) -> Unit = { _, _, _ -> },
        onDateTimeResolve: (Long, Boolean, String) -> Unit = { _, _, _ -> },
        onColorResolve: (Long, Boolean, Int) -> Unit = { _, _, _ -> },
        onFileCancel: (Long) -> Unit = {},
        onFolderResolve: (Long, PreparedUploadFolder) -> Unit = { _, _ -> },
    ): Triple<CoroutineScope, ChromiumPromptsController, MutableMap<String, ChromiumTabRecord>> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tabs = linkedMapOf(
            "t1" to ChromiumTabRecord(id = "t1", isPrivate = isPrivate, url = tabUrl),
        )
        var selected: String? = "t1"
        val prompts = ChromiumPromptsController(
            scope = scope,
            selectedTabId = { selected },
            lookupTab = { id ->
                tabs[id]?.let {
                    ChromiumPromptsController.TabMeta(
                        tabId = it.id,
                        pageUrl = it.url,
                        isPrivate = it.isPrivate,
                        hardGeneration = it.hardGeneration,
                        locationGeneration = it.locationGeneration,
                        attemptGeneration = it.attemptGeneration,
                        documentGeneration = it.documentGeneration,
                    )
                }
            },
            lookupTabByNativePtr = { ptr ->
                if (ptr == 101L) {
                    tabs["t1"]?.let {
                        ChromiumPromptsController.TabMeta(
                            tabId = it.id,
                            pageUrl = it.url,
                            isPrivate = it.isPrivate,
                            hardGeneration = it.hardGeneration,
                            locationGeneration = it.locationGeneration,
                            attemptGeneration = it.attemptGeneration,
                            documentGeneration = it.documentGeneration,
                        )
                    }
                } else null
            },
            hasPrivateTabs = { tabs.values.any { it.isPrivate } },
            popupPermissions = PopupPermissions({ false }, { _, _ -> }, {}, { emptySet() }),
            resolveJsDialogNative = { _, _, _, _ -> },
            resolveHttpAuthNative = { _, _, _, _ -> },
            resolveRepostNative = { _, _ -> },
            resolveClientCertNative = onClientCertResolve,
            resolveFileChooserFolderNative = onFolderResolve,
            cancelFileChooserNative = onFileCancel,
            resolveDateTimeNative = onDateTimeResolve,
            resolveColorNative = onColorResolve,
            openPopupTarget = { _, _, _ -> },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
        )
        return Triple(scope, prompts, tabs)
    }

    @Test
    fun selectPopupBuildsHierarchyAndLiveUpdateRetiresOldToken() {
        var resolved: IntArray? = null
        val (scope, prompts, _) = controllerHarness()
        try {
            prompts.onSelectPopup(
                tabId = "t1",
                requestId = 10L,
                labels = arrayOf("Group one", "Alpha", "Beta", "Gamma", "Blocked"),
                itemTypes = intArrayOf(0, 2, 2, 2, 1),
                multiple = false,
                selectedIndices = intArrayOf(1),
                callback = { resolved = it },
            )
            val first = prompts.prompt.value!!
            assertEquals(BrowserPromptKind.SINGLE_CHOICE, first.kind)
            assertTrue(first.choices[0].group)
            assertEquals("Alpha", first.choices[1].label)
            assertTrue(first.choices[1].selected)
            assertFalse(first.choices[4].enabled)

            var updatedResolved: IntArray? = null
            prompts.onSelectPopup(
                tabId = "t1",
                requestId = 11L,
                labels = arrayOf("Group one", "Alpha", "Beta", "Updated"),
                itemTypes = intArrayOf(0, 2, 1, 2),
                multiple = false,
                selectedIndices = intArrayOf(1),
                callback = { updatedResolved = it },
            )
            val second = prompts.prompt.value!!
            assertTrue(second.token != first.token)
            assertNull("Retiring old SelectPopup during live update must not cancel native", resolved)
            prompts.answerChoice(first.token, setOf(first.choices[2].id))
            assertNull(resolved)

            prompts.answerChoice(second.token, setOf(second.choices[3].id))
            assertArrayEquals(intArrayOf(3), updatedResolved)
            assertNull(prompts.prompt.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun dateTimeAndColorPromptsValidateAndClearOrCancelCleanly() {
        val dateCalls = mutableListOf<Triple<Long, Boolean, String>>()
        val colorCalls = mutableListOf<Triple<Long, Boolean, Int>>()
        val (scope, prompts, _) = controllerHarness(
            onDateTimeResolve = { id, acc, v -> dateCalls += Triple(id, acc, v) },
            onColorResolve = { id, acc, c -> colorCalls += Triple(id, acc, c) },
        )
        try {
            prompts.onDateTimePrompt("t1", 21L, 0, "2026-04-10", "2026-04-06", "2026-04-30", "2")
            val dt = prompts.prompt.value!!
            assertEquals(BrowserPromptKind.DATE_TIME, dt.kind)
            assertEquals("2026-04-06", dt.dateInput?.minimum)
            prompts.answerDate(dt.token, "2026-04-07") // invalid step=2
            assertTrue(dateCalls.isEmpty())
            assertNotNull(prompts.prompt.value)
            prompts.clearDate(dt.token)
            assertEquals(listOf(Triple(21L, true, "")), dateCalls)
            assertNull(prompts.prompt.value)

            prompts.onColorPrompt("t1", 22L, "#112233")
            val col = prompts.prompt.value!!
            assertEquals(BrowserPromptKind.COLOR, col.kind)
            prompts.answerColor(col.token, "#bad")
            assertTrue(colorCalls.isEmpty())
            prompts.answerColor(col.token, "#12AB80")
            assertEquals(listOf(Triple(22L, true, 0xFF12AB80.toInt())), colorCalls)
            assertNull(prompts.prompt.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun sameDocumentCommitRetainsFilePromptWhileNewDocumentCancelsIt() {
        val cancelled = mutableListOf<Long>()
        val (scope, prompts, tabs) = controllerHarness(onFileCancel = { cancelled += it })
        try {
            prompts.onFileChooserRequest("t1", 31L, 0, arrayOf("text/plain"), 0)
            val filePrompt = prompts.prompt.value!!
            assertEquals(BrowserPromptKind.FILE, filePrompt.kind)
            assertNull(filePrompt.origin)

            tabs["t1"]!!.attemptGeneration++
            prompts.onNavigationAttempt("t1")
            prompts.onMainFrameDocumentCommitted("t1", isSameDocument = true)
            assertEquals(filePrompt.token, prompts.prompt.value?.token)
            assertTrue(cancelled.isEmpty())

            tabs["t1"]!!.documentGeneration++
            prompts.onMainFrameDocumentCommitted("t1", isSameDocument = false)
            assertNull(prompts.prompt.value)
            assertEquals(listOf(31L), cancelled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clientCertPickerGatePreventsReentryAndCancelsOnClose() {
        val certCalls = mutableListOf<Triple<Long, Array<ByteArray>?, PrivateKey?>>()
        val (scope, prompts, tabs) = controllerHarness(
            isPrivate = true,
            onClientCertResolve = { id, chain, key -> certCalls += Triple(id, chain, key) },
        )
        try {
            val emitted = mutableListOf<ClientCertificateResult>()
            val job = scope.launch { prompts.clientCertificateResults.collect { emitted += it } }
            prompts.onClientCertificateRequest(41L, 101L, "client.badssl.com", emptyArray())
            val state = prompts.prompt.value!!
            assertEquals(BrowserPromptKind.CERTIFICATE, state.kind)
            assertEquals("client.badssl.com", state.certificateHost)
            val sel = prompts.prepareClientCertificate(state.token)
            assertNotNull(sel)
            assertNull(prompts.prepareClientCertificate(state.token))
            assertTrue(prompts.prompt.value?.certificateChoosing == true)

            prompts.completeClientCertificate(state.token, null, unavailable = false)
            assertEquals(1, certCalls.size)
            assertNull(certCalls[0].second)
            assertEquals(listOf(ClientCertificateResult(state.token, "t1", selected = false, unavailable = false)), emitted)
            assertNull(prompts.prompt.value)
            job.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun folderConfirmationDeliversAndDisposesOnDecline() {
        val delivered = mutableListOf<Pair<Long, PreparedUploadFolder>>()
        val cancelled = mutableListOf<Long>()
        val (scope, prompts, _) = controllerHarness(
            onFolderResolve = { id, folder -> delivered += id to folder },
            onFileCancel = { cancelled += it },
        )
        val tmpRoot = Files.createTempDirectory("miru-test-folder-root").toFile()
        try {
            val rootDir = File(tmpRoot, "MyFolder").apply { mkdirs() }
            val folder = PreparedUploadFolder.validate(tmpRoot.toPath(), rootDir.toPath(), emptyList(), 0, 0L)
            prompts.onFileChooserRequest("t1", 51L, 2, emptyArray(), 0)
            val folderTicket = prompts.folderSelection(prompts.prompt.value!!.token)!!
            var resolvedAllowed: Boolean? = null
            assertTrue(folderTicket.confirm(folder) { resolvedAllowed = it })
            val confirmState = prompts.prompt.value!!
            assertEquals(BrowserPromptKind.FOLDER_CONFIRM, confirmState.kind)
            assertEquals("MyFolder", confirmState.folderName)

            prompts.answerFolderUpload(confirmState.token, true)
            assertEquals(true, resolvedAllowed)
            assertEquals(1, delivered.size)
            assertEquals(51L, delivered.single().first)
            assertNull(prompts.prompt.value)
            assertTrue(cancelled.isEmpty())
        } finally {
            tmpRoot.deleteRecursively()
            scope.cancel()
        }
    }
}
