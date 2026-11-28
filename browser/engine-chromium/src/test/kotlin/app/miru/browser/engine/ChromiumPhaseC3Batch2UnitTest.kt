// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromiumPhaseC3Batch2UnitTest {

    private fun memoryPopups(): PopupPermissions {
        val backing = linkedMapOf<String, Boolean>()
        return PopupPermissions(
            { backing[it] == true },
            { k, v -> if (v) backing[k] = true else backing.remove(k) },
            { backing.clear() },
            { backing.filterValues { it }.keys.toSet() },
        )
    }

    @Test
    fun jsDialogsTrackSuppressionAndResetOnDocumentReady() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val tabs = linkedMapOf(
                "tab-1" to ChromiumPromptsController.TabMeta("tab-1", "https://example.com/a", false, 0L, 1L, 1L),
            )
            val resolvedJs = mutableListOf<Triple<Long, Boolean, String>>()
            val controller = ChromiumPromptsController(
                scope = scope,
                selectedTabId = { "tab-1" },
                lookupTab = { tabs[it] },
                hasPrivateTabs = { false },
                popupPermissions = memoryPopups(),
                resolveJsDialogNative = { _, id, ok, text -> resolvedJs += Triple(id, ok, text) },
                resolveHttpAuthNative = { _, _, _, _ -> },
                resolveRepostNative = { _, _ -> },
                openPopupTarget = { _, _, _ -> },
            )

            controller.onJavaScriptDialog("tab-1", 10L, 0, "Alert body", "")
            val alert = controller.prompt.value!!
            assertEquals(BrowserPromptKind.ALERT, alert.kind)
            assertNull(alert.origin)
            assertEquals("https://example.com", alert.topOrigin)
            assertFalse(alert.hasShownManyDialogs)
            controller.acknowledgeAlert(alert.token, allowMoreDialogs = true)
            assertEquals(listOf(Triple(10L, true, "")), resolvedJs)

            controller.onJavaScriptDialog("tab-1", 11L, 2, "Enter name", "Miru")
            val text = controller.prompt.value!!
            assertEquals(BrowserPromptKind.TEXT, text.kind)
            assertEquals("Enter name", text.inputLabel)
            assertEquals("Miru", text.inputValue)
            assertTrue(text.hasShownManyDialogs)
            controller.answerText(text.token, "User", allowMoreDialogs = false)
            assertEquals(Triple(11L, true, "User"), resolvedJs.last())

            // Suppression immediately rejects next dialog without showing UI.
            controller.onJavaScriptDialog("tab-1", 12L, 1, "Suppressed?", "")
            assertNull(controller.prompt.value)
            assertEquals(Triple(12L, false, ""), resolvedJs.last())

            // Reload/document ready resets suppression.
            controller.onMainFrameDocumentReady("tab-1")
            controller.onJavaScriptDialog("tab-1", 13L, 1, "Confirm?", "")
            val confirm = controller.prompt.value!!
            assertEquals(BrowserPromptKind.CONFIRM, confirm.kind)
            assertFalse(confirm.hasShownManyDialogs)
            controller.answerConfirm(confirm.token, BrowserPromptButton.NEGATIVE, allowMoreDialogs = true)
            assertEquals(Triple(13L, false, ""), resolvedJs.last())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun beforeUnloadRepostAndHttpAuthSurviveLoadingAttempt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val tabs = linkedMapOf(
                "tab-1" to ChromiumPromptsController.TabMeta("tab-1", "https://site.example/page", false, 0L, 1L, 1L),
            )
            var lastJs = Triple(0L, false, "")
            var lastRepost: Boolean? = null
            val authReplies = mutableListOf<Triple<Long, Boolean, String>>()
            val controller = ChromiumPromptsController(
                scope = scope,
                selectedTabId = { "tab-1" },
                lookupTab = { tabs[it] },
                hasPrivateTabs = { false },
                popupPermissions = memoryPopups(),
                resolveJsDialogNative = { _, id, ok, text -> lastJs = Triple(id, ok, text) },
                resolveHttpAuthNative = { id, ok, u, _ -> authReplies += Triple(id, ok, u) },
                resolveRepostNative = { _, resubmit -> lastRepost = resubmit },
                openPopupTarget = { _, _, _ -> },
            )

            controller.onBeforeUnloadDialog("tab-1", 20L, isReload = false)
            val beforeUnload = controller.prompt.value!!
            assertEquals(BrowserPromptKind.BEFORE_UNLOAD, beforeUnload.kind)
            tabs["tab-1"] = tabs.getValue("tab-1").copy(attemptGeneration = 2L)
            controller.onNavigationAttempt("tab-1")
            assertNotNull(controller.prompt.value)
            controller.answerBeforeUnload(beforeUnload.token, leave = true)
            assertEquals(Triple(20L, true, ""), lastJs)

            controller.onRepostDialog("tab-1")
            val repost = controller.prompt.value!!
            assertEquals(BrowserPromptKind.REPOST, repost.kind)
            controller.onNavigationAttempt("tab-1")
            assertNotNull(controller.prompt.value)
            controller.answerRepost(repost.token, resubmit = true)
            assertEquals(true, lastRepost)

            controller.onHttpAuthRequest(
                tabId = "tab-1",
                authId = 99L,
                url = "https://auth.example/login",
                challenger = "auth.example",
                realm = "Members",
                scheme = "Basic",
                isProxy = true,
                previousFailed = true,
            )
            val auth = controller.prompt.value!!
            assertEquals(BrowserPromptKind.AUTHENTICATION, auth.kind)
            assertEquals("https://auth.example", auth.origin)
            assertEquals("https://site.example", auth.topOrigin)
            assertTrue(auth.isCrossOrigin)
            assertTrue(auth.previousFailed)
            assertEquals(BrowserAuthMethod.PROXY, auth.authMethod)
            assertEquals(BrowserAuthLevel.SECURED, auth.authLevel)
            controller.onLocationChange("tab-1", preserveAuth = true)
            assertNotNull(controller.prompt.value)
            controller.authenticate(auth.token, "alice", "secret")
            assertEquals(listOf(Triple(99L, true, "alice")), authReplies)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun popupPermissionsIsolateNormalAndPrivateAndClearOnSiteSettings() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var selected = "normal"
            val tabs = linkedMapOf(
                "normal" to ChromiumPromptsController.TabMeta("normal", "https://example.org/p", false, 0L, 1L, 1L),
                "priv" to ChromiumPromptsController.TabMeta("priv", "https://example.org/p", true, 0L, 1L, 1L),
            )
            val openedTargets = mutableListOf<Pair<String, Boolean>>()
            val controller = ChromiumPromptsController(
                scope = scope,
                selectedTabId = { selected },
                lookupTab = { tabs[it] },
                hasPrivateTabs = { tabs.values.any { it.isPrivate } },
                popupPermissions = memoryPopups(),
                resolveJsDialogNative = { _, _, _, _ -> },
                resolveHttpAuthNative = { _, _, _, _ -> },
                resolveRepostNative = { _, _ -> },
                openPopupTarget = { _, target, priv -> openedTargets += target to priv },
                mainDispatcher = Dispatchers.Unconfined,
            )

            assertFalse(controller.shouldAllowPopupWindow("normal", "https://example.org/child", userGesture = false, openerSuppressed = false))
            val banner = controller.blockedPopup.value!!
            controller.allowPopup(banner.token)
            assertEquals(listOf("https://example.org/child" to false), openedTargets)
            assertTrue(controller.shouldAllowPopupWindow("normal", "https://example.org/c2", userGesture = false, openerSuppressed = false))

            // Normal allow must NOT grant private tab.
            selected = "priv"
            assertFalse(controller.shouldAllowPopupWindow("priv", "https://example.org/child", userGesture = false, openerSuppressed = false))
            controller.allowPopup(controller.blockedPopup.value!!.token)
            assertTrue(controller.shouldAllowPopupWindow("priv", "https://example.org/c2", userGesture = false, openerSuppressed = false))

            // Closing last private tab clears ephemeral private grant, leaving normal intact.
            tabs.remove("priv")
            selected = "normal"
            controller.syncState()
            tabs["priv2"] = ChromiumPromptsController.TabMeta("priv2", "https://example.org/p", true, 0L, 1L, 1L)
            selected = "priv2"
            assertFalse(controller.shouldAllowPopupWindow("priv2", "https://example.org/c3", userGesture = false, openerSuppressed = false))

            selected = "normal"
            assertEquals(setOf("https://example.org"), controller.savedPopupOrigins())
            controller.clearPopupOrigin("https://example.org")
            assertTrue(controller.savedPopupOrigins().isEmpty())
            assertFalse(controller.shouldAllowPopupWindow("normal", "https://example.org/c4", userGesture = false, openerSuppressed = false))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun contextMenusTrackOwnerGenerationAndNotifyNativeClose() {
        var selected = "t1"
        val tabs = linkedMapOf(
            "t1" to ChromiumContextMenusController.TabMeta("t1", false, hardGeneration = 0L, locationGeneration = 1L),
            "t2" to ChromiumContextMenusController.TabMeta("t2", false, hardGeneration = 0L, locationGeneration = 1L),
        )
        val closedCounts = mutableMapOf<String, Int>()
        val controller = ChromiumContextMenusController(
            lookupTab = { tabs[it] },
            selectedTabId = { selected },
            evaluateScript = { _, _, cb ->
                cb("""[{"imgPolicy":"no-referrer","docPolicy":"","docUrl":"https://a.test/page"}]""")
                true
            },
            lookupCapturedHeaders = { _, _, _ -> mapOf("Referer" to "https://a.test/page") },
            fallbackHeaders = { _, _ -> emptyMap() },
            notifyNativeClosed = { closedCounts[it] = (closedCounts[it] ?: 0) + 1 },
            mainDispatcher = Dispatchers.Unconfined,
        )

        controller.onContextMenu("t1", 1, "https://a.test/link", "Go", "https://a.test/img.png", "Alt", "https://a.test/page", "", "")
        val imgMenu = controller.state.value!!
        assertEquals(BrowserContextMenuKind.IMAGE, imgMenu.kind)
        assertEquals("https://a.test/img.png", imgMenu.imageUrl)
        assertEquals("https://a.test/link", imgMenu.linkUrl)
        assertTrue(controller.isOwnerCurrent(imgMenu))
        runBlocking {
            val policy = controller.contextImagePolicy(imgMenu)
            assertNotNull(policy)
            assertEquals("no-referrer", policy!!.policy)
            val headers = controller.contextHeaders(imgMenu, "https://a.test/img.png", policy)
            assertNotNull(headers)
            assertFalse(headers!!.containsKey("Referer"))
        }

        // Dismiss twice only notifies native once.
        controller.dismiss(imgMenu.token)
        controller.dismiss(imgMenu.token)
        assertNull(controller.state.value)
        assertEquals(1, closedCounts["t1"])

        // Navigation invalidates ownership and fails closed for contextHeaders.
        controller.onContextMenu("t1", 0, "https://a.test/next", "Next", "", "", "https://a.test/page", "", "")
        val linkMenu = controller.state.value!!
        assertEquals(BrowserContextMenuKind.LINK, linkMenu.kind)
        tabs["t1"] = tabs.getValue("t1").copy(locationGeneration = 2L)
        assertFalse(controller.isOwnerCurrent(linkMenu))
        controller.syncState()
        assertNull(controller.state.value)
        assertEquals(2, closedCounts["t1"])
        runBlocking {
            assertNull(controller.contextHeaders(linkMenu, "https://a.test/next", null))
        }
    }

    @Test
    fun externalLinksEnforcesSchemeSanitizationAndFallback() {
        var selected = "t1"
        val tabs = linkedMapOf(
            "t1" to ChromiumExternalLinksController.TabMeta("t1", false, 1L, 1L, "https://app.test/home"),
        )
        val loadedFallbacks = mutableListOf<String>()
        val controller = ChromiumExternalLinksController(
            lookupTab = { tabs[it] },
            selectedTabId = { selected },
            loadUrlInTab = { _, url -> loadedFallbacks += url },
            resolvePlan = { uri ->
                when {
                    uri.startsWith("missing://") -> ChromiumExternalLinksController.ExternalPlan(
                        intent = null,
                        url = uri,
                        label = null,
                        fallback = "https://app.test/web-fallback",
                    )
                    else -> ChromiumExternalLinksController.ExternalPlan(
                        intent = Intent(Intent.ACTION_VIEW),
                        url = uri,
                        label = "Demo App",
                        fallback = null,
                    )
                }
            },
        )

        // Browser schemes never prompt.
        controller.onExternalProtocol("t1", "https://app.test/next")
        assertNull(controller.prompt.value)

        // Missing app exposes fallback without canOpenApp.
        controller.onExternalProtocol("t1", "missing://open")
        val missingPrompt = controller.prompt.value!!
        assertFalse(missingPrompt.canOpenApp)
        assertTrue(missingPrompt.canOpenWeb)
        assertEquals("https://app.test/web-fallback", missingPrompt.webUrl)

        // Navigating retires stale token.
        val staleToken = missingPrompt.token
        tabs["t1"] = tabs.getValue("t1").copy(locationGeneration = 2L)
        controller.onNavigation("t1")
        assertNull(controller.prompt.value)

        // Re-offer after navigation and trigger web fallback cleanly.
        controller.onExternalProtocol("t1", "missing://open")
        val livePrompt = controller.prompt.value!!
        assertTrue(controller.open(livePrompt.token, Activity()))
        assertFalse(controller.open(staleToken, Activity()))
        assertEquals(listOf("https://app.test/web-fallback"), loadedFallbacks)
    }
}
