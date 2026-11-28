// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.content.Context
import app.miru.chromium.engine.MiruChromiumRuntime
import app.miru.chromium.engine.MiruChromiumTab
import app.miru.data.BrowsingLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.chromium.ui.base.WindowAndroid
import org.json.JSONObject
import java.util.UUID

/**
 * Owns the Chromium C3.2 prompt / external-link / context-menu controllers and translates
 * [MiruChromiumTab.Listener] callbacks into [ChromiumTabRecord] updates so
 * [ChromiumEngineController] remains concise and well under file-size limits.
 */
internal class ChromiumTabBridge(
    private val host: Host,
) {
    internal interface Host {
        val applicationContext: Context
        val applicationScope: CoroutineScope
        val tabsById: LinkedHashMap<String, ChromiumTabRecord>
        val selectedTabId: String?
        val isInitialized: Boolean
        val zoomPercent: Int
        val webHoldEnabled: Boolean
        val webHoldSpeed: Float
        val adblockManager: ChromiumAdblockManager
        val mediaManager: ChromiumMediaManager
        val library: BrowsingLibrary
        val currentPageNeedsReload: Boolean
        fun openPopupTab(targetUrl: String, isPrivate: Boolean)
        fun selectOrOpenSite(origin: String, isPrivate: Boolean)
        fun setActiveNativeTab(tab: MiruChromiumTab?)
        fun emitScroll(offsetY: Float)
        fun recordTabAdBlocked(tabId: String)
        fun resetTabBlockedCount(tabId: String)
        suspend fun refreshMediaHeaders(tabId: String, mediaId: String): Map<String, String>?
        suspend fun headersForMediaUrl(url: String, isPrivate: Boolean): Map<String, String>
        fun publishState(selectedTabId: String? = null, pageNeedsReload: Boolean? = null)
    }

    val prompts = ChromiumPromptsController(
        context = host.applicationContext,
        scope = host.applicationScope,
        selectedTabId = { host.selectedTabId },
        lookupTab = { id ->
            host.tabsById[id]?.let {
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
        lookupTabByNativePtr = { nativePtr ->
            if (nativePtr == 0L) null
            else host.tabsById.values.firstOrNull { it.nativeTab?.nativeTabPtr == nativePtr }?.let {
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
        hasPrivateTabs = { host.tabsById.values.any { it.isPrivate } },
        resolveJsDialogNative = { tabId, dialogId, accept, input ->
            host.tabsById[tabId]?.nativeTab?.resolveJsDialog(dialogId, accept, input)
        },
        resolveHttpAuthNative = { authId, submit, user, pass ->
            MiruChromiumTab.resolveHttpAuth(authId, submit, user, pass)
        },
        resolveRepostNative = { tabId, resubmit ->
            host.tabsById[tabId]?.nativeTab?.resolveRepostDialog(resubmit)
        },
        resolveClientCertNative = { requestId, encodedChain, privateKey ->
            MiruChromiumRuntime.resolveClientCertificate(requestId, encodedChain, privateKey)
        },
        resolveFileChooserFilesNative = { requestId, staged ->
            MiruChromiumRuntime.resolveFileChooser(
                requestId,
                true,
                staged.map { it.file.absolutePath }.toTypedArray(),
                staged.map { it.displayName }.toTypedArray(),
                Array(staged.size) { "" },
                staged.map { it.mimeType }.toTypedArray(),
                LongArray(staged.size),
                BooleanArray(staged.size),
                "",
            )
        },
        resolveFileChooserFolderNative = { requestId, folder ->
            val payloads = folder.payloads
            MiruChromiumRuntime.resolveFileChooser(
                requestId,
                true,
                payloads.map { it.filePath }.toTypedArray(),
                payloads.map { it.logicalRelativePath.substringAfterLast('/') }.toTypedArray(),
                payloads.map { payload ->
                    payload.logicalRelativePath.removePrefix("/").substringBeforeLast('/', "")
                }.toTypedArray(),
                payloads.map { it.mimeType }.toTypedArray(),
                payloads.map { it.lastModified ?: 0L }.toLongArray(),
                payloads.map { it.lastModified != null }.toBooleanArray(),
                folder.rootPath,
            )
        },
        cancelFileChooserNative = { requestId ->
            MiruChromiumRuntime.resolveFileChooser(
                requestId,
                false,
                emptyArray(),
                emptyArray(),
                emptyArray(),
                emptyArray(),
                LongArray(0),
                BooleanArray(0),
                "",
            )
        },
        resolveDateTimeNative = { requestId, accepted, value ->
            MiruChromiumRuntime.resolveDateTimePrompt(requestId, accepted, value)
        },
        resolveColorNative = { requestId, accepted, argb ->
            MiruChromiumRuntime.resolveColorPrompt(requestId, accepted, argb)
        },
        openPopupTarget = { _, targetUrl, isPrivate ->
            if (host.isInitialized && targetUrl.isNotBlank()) host.openPopupTab(targetUrl, isPrivate)
        },
    )

    val uploads = ChromiumFileUploadsController(host.applicationContext, host.applicationScope, prompts)

    val externalLinks = ChromiumExternalLinksController(
        context = host.applicationContext,
        lookupTab = { id ->
            host.tabsById[id]?.let {
                ChromiumExternalLinksController.TabMeta(
                    tabId = it.id,
                    isPrivate = it.isPrivate,
                    locationGeneration = it.locationGeneration,
                    attemptGeneration = it.attemptGeneration,
                    currentUrl = it.url,
                )
            }
        },
        selectedTabId = { host.selectedTabId },
        loadUrlInTab = { tabId, url -> host.tabsById[tabId]?.nativeTab?.loadUrl(url) },
    )

    val contextMenus = ChromiumContextMenusController(
        lookupTab = { id ->
            host.tabsById[id]?.let {
                ChromiumContextMenusController.TabMeta(
                    tabId = it.id,
                    isPrivate = it.isPrivate,
                    hardGeneration = it.hardGeneration,
                    locationGeneration = it.locationGeneration,
                )
            }
        },
        selectedTabId = { host.selectedTabId },
        evaluateScript = { tabId, script, cb ->
            val tab = host.tabsById[tabId]?.nativeTab
            if (tab != null) {
                tab.evaluateIsolatedJs(script, true, cb)
                true
            } else {
                false
            }
        },
        lookupCapturedHeaders = { tabId, pageUrl, url ->
            val captured = host.mediaManager.state.value[tabId].orEmpty()
                .firstOrNull { it.url == url && it.pageUrl == pageUrl }
            captured?.let { host.refreshMediaHeaders(tabId, it.id) }
        },
        fallbackHeaders = { url, isPrivate -> host.headersForMediaUrl(url, isPrivate) },
        notifyNativeClosed = { tabId -> host.tabsById[tabId]?.nativeTab?.notifyContextMenuClosed() },
    )

    val permissions = ChromiumPermissionsController(
        context = host.applicationContext,
        scope = host.applicationScope,
        selectedTabId = { host.selectedTabId },
        lookupTab = { id ->
            host.tabsById[id]?.let {
                ChromiumPermissionsController.TabMeta(
                    tabId = it.id,
                    pageUrl = it.url,
                    isPrivate = it.isPrivate,
                    hardGeneration = it.hardGeneration,
                    locationGeneration = it.locationGeneration,
                )
            }
        },
        lookupTabByNativePtr = { nativePtr ->
            if (nativePtr == 0L) {
                null
            } else {
                host.tabsById.values.firstOrNull { it.nativeTab?.nativeTabPtr == nativePtr }?.let {
                    ChromiumPermissionsController.TabMeta(
                        tabId = it.id,
                        pageUrl = it.url,
                        isPrivate = it.isPrivate,
                        hardGeneration = it.hardGeneration,
                        locationGeneration = it.locationGeneration,
                    )
                }
            }
        },
        hasPrivateTabs = { host.tabsById.values.any { it.isPrivate } },
        resolveSitePermissionsNative = { requestId, allowed ->
            MiruChromiumRuntime.resolveSitePermissions(requestId, allowed)
        },
        resolveMediaAccessNative = { requestId, allowed ->
            MiruChromiumRuntime.resolveMediaAccess(requestId, allowed)
        },
        onSiteChanged = { origin, isPrivate -> handleSiteChanged(origin, isPrivate) },
        onAllSiteSettingsCleared = { handleAllSiteSettingsCleared() },
    )

    val webNotifications = ChromiumNotificationsController(
        context = host.applicationContext,
        isNotificationAllowed = { origin, isPrivate -> permissions.notificationAllowed(origin, isPrivate) },
        openSite = { origin, isPrivate -> host.selectOrOpenSite(origin, isPrivate) },
        dispatchNativeClick = { notificationId, origin, isPrivate, isPersistent, actionIndex ->
            MiruChromiumRuntime.dispatchNotificationClick(notificationId, origin, isPrivate, isPersistent, actionIndex)
        },
        dispatchNativeClose = { notificationId, origin, isPrivate, isPersistent ->
            MiruChromiumRuntime.dispatchNotificationClose(notificationId, origin, isPrivate, isPersistent)
        },
    )

    private var attachedWindow: WindowAndroid? = null

    fun attachWindowAndroid(window: WindowAndroid?) {
        attachedWindow = window
        val activeTab = host.selectedTabId?.let(host.tabsById::get)?.nativeTab
        host.tabsById.values.mapNotNull { it.nativeTab }.forEach { tab ->
            if (window == null || tab !== activeTab) {
                tab.attachWindowOnly(window)
            }
        }
    }

    fun installRuntimeListeners() {
        MiruChromiumRuntime.setPermissionListener(
            object : MiruChromiumRuntime.PermissionListener {
                override fun querySitePermission(origin: String, offTheRecord: Boolean, kind: Int, tabPtr: Long): Int =
                    permissions.querySitePermission(origin, offTheRecord, kind, tabPtr)

                override fun requestSitePermissions(
                    requestId: Long,
                    tabPtr: Long,
                    origin: String,
                    topOrigin: String,
                    offTheRecord: Boolean,
                    kindsMask: Int,
                ) = permissions.onRequestSitePermissions(requestId, tabPtr, origin, topOrigin, offTheRecord, kindsMask)

                override fun requestMediaAccess(
                    requestId: Long,
                    tabPtr: Long,
                    origin: String,
                    topOrigin: String,
                    offTheRecord: Boolean,
                    kindsMask: Int,
                ) = permissions.onRequestMediaAccess(requestId, tabPtr, origin, topOrigin, offTheRecord, kindsMask)

                override fun queryAutoplayPolicy(url: String, offTheRecord: Boolean): Int =
                    permissions.queryAutoplayPolicy(url, offTheRecord)
            },
        )
        MiruChromiumRuntime.setNotificationListener(
            object : MiruChromiumRuntime.NotificationListener {
                override fun onShowNotification(
                    notificationId: String,
                    origin: String,
                    isPrivate: Boolean,
                    isPersistent: Boolean,
                    title: String,
                    body: String,
                    tag: String,
                    silent: Boolean,
                    actionsJson: String,
                ) = webNotifications.onShowNotification(
                    notificationId = notificationId,
                    originUrl = origin,
                    isPrivate = isPrivate,
                    isPersistent = isPersistent,
                    title = title,
                    body = body,
                    tag = tag,
                    silent = silent,
                    actionsJson = actionsJson,
                )

                override fun onCloseNotification(notificationId: String) =
                    webNotifications.onNativeCloseNotification(notificationId)
            },
        )
        MiruChromiumRuntime.setClientCertListener(
            object : MiruChromiumRuntime.ClientCertListener {
                override fun onSelectClientCertificate(
                    requestId: Long,
                    tabPtr: Long,
                    host: String,
                    encodedPrincipals: Array<ByteArray>,
                ) = prompts.onClientCertificateRequest(requestId, tabPtr, host, encodedPrincipals)

                override fun onCancelClientCertificate(requestId: Long) =
                    prompts.onClientCertificateCancelled(requestId)
            },
        )
    }

    private fun handleSiteChanged(origin: String, isPrivate: Boolean) {
        webNotifications.cancelSite(origin, isPrivate)
        host.tabsById.values.filter { tab ->
            tab.isPrivate == isPrivate && siteOrigin(tab.url) == origin && tab.nativeTab != null
        }.forEach { tab ->
            tab.attemptGeneration++
            prompts.onNavigationAttempt(tab.id)
            tab.nativeTab?.reload()
        }
    }

    private fun handleAllSiteSettingsCleared() {
        webNotifications.cancelAll()
        prompts.clearPopupPermissions()
        host.tabsById.values.filter { tab ->
            tab.nativeTab != null && siteOrigin(tab.url) != null
        }.forEach { tab ->
            tab.attemptGeneration++
            prompts.onNavigationAttempt(tab.id)
            tab.nativeTab?.reload()
        }
    }

    fun createNativeTab(
        tabId: String,
        isPrivate: Boolean,
        adoptId: Long = 0L,
    ): MiruChromiumTab {
        lateinit var tabRef: MiruChromiumTab
        val listener = object : MiruChromiumTab.Listener {
            override fun onNavigationChanged(
                url: String,
                title: String,
                canGoBack: Boolean,
                canGoForward: Boolean,
                isLoading: Boolean,
            ) = handleNavigationState(tabId, url, title, canGoBack, canGoForward, isLoading)

            override fun onProgressChanged(progress: Double) {
                val record = host.tabsById[tabId] ?: return
                record.progress = (progress * 100.0).toInt().coerceIn(0, 100)
                host.publishState()
            }

            override fun onScrollChanged(scrollOffsetY: Int) {
                if (host.selectedTabId == tabId) host.emitScroll(scrollOffsetY.toFloat())
            }

            override fun onFullscreenChanged(fullscreen: Boolean) {
                val record = host.tabsById[tabId] ?: return
                record.fullScreen = fullscreen
                host.publishState()
            }

            override fun onAdBlocked(blockedUrl: String) = host.recordTabAdBlocked(tabId)

            override fun onMediaDetected(
                url: String,
                kind: String,
                mimeType: String,
                contentLength: Long,
                headersJson: String,
            ) = handleMediaDetected(tabId, url, kind, mimeType, contentLength, headersJson)

            override fun onFrameDomReady(isMainFrame: Boolean, frameUrl: String) {
                tabRef.evaluateIsolatedJs(
                    ChromiumPageScripts.pageAgentScript(host.webHoldEnabled, host.webHoldSpeed),
                    !isMainFrame,
                    null,
                )
                if (isMainFrame) {
                    prompts.onMainFrameDocumentReady(tabId)
                    if (host.adblockManager.state.value.pickerActive) {
                        host.adblockManager.setPickerActive(false)
                    }
                }
            }

            override fun onBridgeEvent(eventJson: String) = handleBridgePayload(tabId, eventJson)

            override fun onFindResult(activeOrdinal: Int, matchCount: Int, finalUpdate: Boolean) {
                val record = host.tabsById[tabId] ?: return
                record.findCurrent = activeOrdinal
                record.findTotal = matchCount
                host.publishState()
            }

            override fun onSecurityStateChanged(isSecure: Boolean, certDer: ByteArray?) =
                handleSecurityState(tabId, isSecure, certDer)

            override fun onJavaScriptDialog(
                dialogId: Long,
                dialogType: Int,
                origin: String,
                message: String,
                defaultPrompt: String,
            ) = prompts.onJavaScriptDialog(tabId, dialogId, dialogType, message, defaultPrompt)

            override fun onBeforeUnloadDialog(
                dialogId: Long,
                origin: String,
                isReload: Boolean,
            ) = prompts.onBeforeUnloadDialog(tabId, dialogId, isReload)

            override fun onDialogsCancelled(dialogId: Long, resetState: Boolean) =
                prompts.onDialogsCancelled(tabId, resetState)

            override fun onHttpAuthRequest(
                authId: Long,
                url: String,
                challenger: String,
                realm: String,
                scheme: String,
                isProxy: Boolean,
                previousFailed: Boolean,
            ) = prompts.onHttpAuthRequest(tabId, authId, url, challenger, realm, scheme, isProxy, previousFailed)

            override fun onHttpAuthCancelled(authId: Long) = prompts.onHttpAuthCancelled(authId)

            override fun onRepostDialog() = prompts.onRepostDialog(tabId)

            override fun onCheckPopupAllowed(
                openerUrl: String,
                targetUrl: String,
                userGesture: Boolean,
                openerSuppressed: Boolean,
            ): Boolean = prompts.shouldAllowPopupWindow(tabId, targetUrl, userGesture, openerSuppressed)

            override fun onAdoptPopupTab(adoptId: Long, targetUrl: String) =
                adoptPopupTab(tabId, isPrivate, adoptId, targetUrl)

            override fun onExternalProtocol(url: String, hasUserGesture: Boolean) {
                if (host.selectedTabId == tabId) {
                    externalLinks.onExternalProtocol(tabId, url, hasUserGesture)
                }
            }

            override fun onContextMenu(
                mediaType: Int,
                linkUrl: String,
                linkText: String,
                srcUrl: String,
                titleText: String,
                pageUrl: String,
                frameUrl: String,
                referrerPolicy: String,
                x: Int,
                y: Int,
            ) = contextMenus.onContextMenu(
                tabId = tabId,
                mediaType = mediaType,
                linkUrl = linkUrl,
                linkText = linkText,
                srcUrl = srcUrl,
                titleText = titleText,
                pageUrl = pageUrl,
                frameUrl = frameUrl,
                referrerPolicy = referrerPolicy,
                x = x,
                y = y,
            )

            override fun onMainFrameNavigationCommitted(url: String, isSameDocument: Boolean) =
                handleMainFrameCommitted(tabId, url, isSameDocument)

            override fun onFileChooserRequest(
                requestId: Long,
                mode: Int,
                acceptTypes: Array<String>,
                captureMode: Int,
            ) = prompts.onFileChooserRequest(tabId, requestId, mode, acceptTypes, captureMode)

            override fun onSelectPopup(
                requestId: Long,
                labels: Array<String>,
                itemTypes: IntArray,
                multiple: Boolean,
                selectedIndices: IntArray,
                callback: java.util.function.Consumer<IntArray?>,
            ) = prompts.onSelectPopup(tabId, requestId, labels, itemTypes, multiple, selectedIndices) { callback.accept(it) }

            override fun onSelectPopupClosed(requestId: Long) =
                prompts.onSelectPopupClosed(tabId, requestId)

            override fun onDateTimePrompt(
                requestId: Long,
                type: Int,
                initialValue: String,
                minValue: String,
                maxValue: String,
                stepValue: String,
            ) = prompts.onDateTimePrompt(tabId, requestId, type, initialValue, minValue, maxValue, stepValue)

            override fun onDateTimePromptClosed(requestId: Long) =
                prompts.onDateTimePromptClosed(tabId, requestId)

            override fun onColorPrompt(requestId: Long, initialColor: String) =
                prompts.onColorPrompt(tabId, requestId, initialColor)

            override fun onColorPromptClosed(requestId: Long) =
                prompts.onColorPromptClosed(tabId, requestId)
        }
        tabRef = if (adoptId > 0L) {
            MiruChromiumTab(host.applicationContext, isPrivate, adoptId, listener)
        } else {
            MiruChromiumTab(host.applicationContext, isPrivate, listener)
        }
        tabRef.setZoomPercent(host.zoomPercent)
        attachedWindow?.let { tabRef.attachWindowOnly(it) }
        return tabRef
    }

    private fun adoptPopupTab(openerTabId: String, isPrivate: Boolean, adoptId: Long, targetUrl: String) {
        val newId = UUID.randomUUID().toString()
        val initialUrl = targetUrl.takeIf { it.isNotBlank() } ?: NEW_TAB_URL
        val record = ChromiumTabRecord(id = newId, isPrivate = isPrivate, url = initialUrl)
        val nativeTab = createNativeTab(newId, isPrivate, adoptId)
        record.nativeTab = nativeTab
        val reordered = linkedMapOf<String, ChromiumTabRecord>()
        var inserted = false
        host.tabsById.forEach { (id, tab) ->
            reordered[id] = tab
            if (id == openerTabId) {
                reordered[newId] = record
                inserted = true
            }
        }
        if (!inserted) reordered[newId] = record
        host.tabsById.clear()
        host.tabsById.putAll(reordered)
        host.publishState(selectedTabId = newId, pageNeedsReload = false)
        permissions.syncState()
        host.setActiveNativeTab(nativeTab)
    }

    private fun handleNavigationState(
        tabId: String,
        url: String,
        title: String,
        canGoBack: Boolean,
        canGoForward: Boolean,
        isLoading: Boolean,
    ) {
        val record = host.tabsById[tabId] ?: return
        val previousUrl = record.url
        val committedUrl = record.nativeTab?.webContents?.lastCommittedUrl?.spec?.takeIf { it.isNotBlank() }
        val resolvedUrl = when {
            committedUrl != null -> committedUrl
            record.url == NEW_TAB_URL && url.isNotBlank() -> url
            else -> record.url
        }
        if (resolvedUrl.isNotBlank()) record.url = resolvedUrl
        if (title.isNotBlank()) record.title = title
        record.canGoBack = canGoBack
        record.canGoForward = canGoForward
        record.loading = isLoading
        if (!isLoading) record.progress = 100
        val navigated = previousUrl.substringBefore('#') != record.url.substringBefore('#')
        if (navigated) {
            record.locationGeneration++
            prompts.onLocationChange(tabId, preserveAuth = isLoading)
            externalLinks.onNavigation(tabId)
            permissions.onNavigation(tabId)
            contextMenus.syncState()
        }
        if (navigated && record.url.startsWith("http")) {
            host.resetTabBlockedCount(record.id)
            host.mediaManager.onNavigation(record.id)
        }
        if (!record.isPrivate && !isLoading && record.url.startsWith("http")) {
            recordHistoryVisit(record)
        }
        host.publishState(pageNeedsReload = if (navigated) false else host.currentPageNeedsReload)
    }

    private fun handleMainFrameCommitted(tabId: String, url: String, isSameDocument: Boolean) {
        val record = host.tabsById[tabId] ?: return
        if (url.isNotBlank()) record.url = url
        if (!isSameDocument) {
            record.documentGeneration++
            record.locationGeneration++
        }
        prompts.onMainFrameDocumentCommitted(tabId, isSameDocument)
        host.publishState()
    }

    private fun handleMediaDetected(
        tabId: String,
        url: String,
        kind: String,
        mimeType: String,
        contentLength: Long,
        headersJson: String,
    ) {
        val record = host.tabsById[tabId] ?: return
        host.mediaManager.onNetworkMedia(
            tabId = record.id,
            pageUrl = record.url,
            title = record.title,
            isPrivate = record.isPrivate,
            url = url,
            kind = kind,
            mimeType = mimeType,
            contentLength = contentLength,
            headersJson = headersJson,
        )
    }

    private fun handleBridgePayload(tabId: String, eventJson: String) {
        if (eventJson.isBlank()) return
        val record = host.tabsById[tabId] ?: return
        val obj = runCatching { JSONObject(eventJson) }.getOrNull() ?: return
        when (obj.optString("kind")) {
            "dom_media" -> host.mediaManager.onDomMediaEvent(record.id, record.url, record.title, record.isPrivate, obj)
            "picker_confirm" -> {
                host.adblockManager.setPickerActive(false)
                val hostName = obj.optString("host").trim()
                val selector = obj.optString("selector").trim()
                if (selector.isNotEmpty()) {
                    val rule = if (hostName.isNotEmpty()) "$hostName##$selector" else "##$selector"
                    host.adblockManager.addCustomRule(rule)
                }
            }
            "picker_cancel" -> host.adblockManager.setPickerActive(false)
        }
    }

    private fun handleSecurityState(tabId: String, isSecure: Boolean, certDer: ByteArray?) {
        val record = host.tabsById[tabId] ?: return
        val currentUrl = record.nativeTab?.url?.takeIf { it.isNotBlank() } ?: record.url
        record.secure = isSecure
        record.certificate = ChromiumSecurityHelper.parseCertificateDetails(currentUrl, isSecure, certDer)
        host.publishState()
    }

    private fun recordHistoryVisit(record: ChromiumTabRecord) {
        val url = record.url
        val title = record.title
        if (url != record.lastRecordedUrl) {
            record.lastRecordedUrl = url
            record.lastRecordedTitle = title
            host.applicationScope.launch(Dispatchers.IO) {
                host.library.history.recordVisit(url)
                if (title.isNotBlank()) host.library.history.updateTitle(url, title)
            }
        } else if (title.isNotBlank() && title != record.lastRecordedTitle) {
            record.lastRecordedTitle = title
            host.applicationScope.launch(Dispatchers.IO) {
                host.library.history.updateTitle(url, title)
            }
        }
    }
}
