// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.content.Context
import android.graphics.Bitmap
import app.miru.chromium.engine.MiruChromiumRuntime
import app.miru.chromium.engine.MiruChromiumTab
import app.miru.data.BrowserSecuritySettings
import app.miru.data.BrowsingDataClearRequest
import app.miru.data.BrowsingDataClearResult
import app.miru.data.BrowsingDataRange
import app.miru.data.BrowsingDataType
import app.miru.data.BrowsingLibrary
import app.miru.media.DetectedMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * De-Googled Chromium 159 backend controller implementing [BrowserEngineController].
 * Delegates native `adblock-rust` snapshot/lists/picker management to [ChromiumAdblockManager]
 * and C++ network-layer video sniffing + isolated-world handoff to [ChromiumMediaManager].
 */
class ChromiumEngineController internal constructor(
    context: Context,
    private val applicationScope: CoroutineScope,
    private val library: BrowsingLibrary,
    private val securitySettings: Flow<BrowserSecuritySettings>,
    private val webZoomPercent: Flow<Int>,
    internal val adblockManager: ChromiumAdblockManager,
    internal val mediaManager: ChromiumMediaManager,
) : BrowserEngineController {
    constructor(
        context: Context,
        applicationScope: CoroutineScope,
        library: BrowsingLibrary,
        securitySettings: Flow<BrowserSecuritySettings>,
        webZoomPercent: Flow<Int>,
    ) : this(
        context = context,
        applicationScope = applicationScope,
        library = library,
        securitySettings = securitySettings,
        webZoomPercent = webZoomPercent,
        adblockManager = ChromiumAdblockManager(context, applicationScope),
        mediaManager = ChromiumMediaManager(),
    )

    private val applicationContext = context.applicationContext
    private val sessionStore = ChromiumSessionStore(File(applicationContext.filesDir, "chromium_session.json"))
    private val tabsById = linkedMapOf<String, ChromiumTabRecord>()
    private val tabBlockedCounts = ConcurrentHashMap<String, Int>()
    private val scrollFlow = MutableSharedFlow<Float>(replay = 1, extraBufferCapacity = 32)
    private val downloadChannel = Channel<BrowserDownloadRequest>(Channel.UNLIMITED)
    @Volatile
    var testDownloadInterceptor: ((BrowserDownloadRequest) -> Boolean)? = null
    private val activeNativeTabFlow = MutableStateFlow<MiruChromiumTab?>(null)
    private var automaticNewTabId: String? = null
    private var zoomPercentValue = 100
    private var webHoldEnabled = true
    private var webHoldSpeed = 2f
    private var initialized = false

    private val tabBridge = ChromiumTabBridge(
        object : ChromiumTabBridge.Host {
            override val applicationContext: Context get() = this@ChromiumEngineController.applicationContext
            override val applicationScope: CoroutineScope get() = this@ChromiumEngineController.applicationScope
            override val tabsById get() = this@ChromiumEngineController.tabsById
            override val selectedTabId get() = mutableState.value.selectedTabId
            override val isInitialized get() = initialized
            override val zoomPercent get() = zoomPercentValue
            override val webHoldEnabled get() = this@ChromiumEngineController.webHoldEnabled
            override val webHoldSpeed get() = this@ChromiumEngineController.webHoldSpeed
            override val adblockManager get() = this@ChromiumEngineController.adblockManager
            override val mediaManager get() = this@ChromiumEngineController.mediaManager
            override val library get() = this@ChromiumEngineController.library
            override val currentPageNeedsReload get() = adblockState.value.pageNeedsReload
            override fun openPopupTab(targetUrl: String, isPrivate: Boolean) {
                newTab(targetUrl, isPrivate)
            }
            override fun selectOrOpenSite(origin: String, isPrivate: Boolean) {
                val existing = tabsById.values.firstOrNull {
                    it.isPrivate == isPrivate && siteOrigin(it.url) == origin
                }
                if (existing != null) selectTab(existing.id) else newTab(origin, isPrivate)
            }
            override fun setActiveNativeTab(tab: MiruChromiumTab?) {
                activeNativeTabFlow.value = tab
            }
            override fun emitScroll(offsetY: Float) {
                scrollFlow.tryEmit(offsetY)
            }
            override fun recordTabAdBlocked(tabId: String) = this@ChromiumEngineController.handleAdBlocked(tabId)
            override fun resetTabBlockedCount(tabId: String) {
                tabBlockedCounts[tabId] = 0
            }
            override suspend fun refreshMediaHeaders(tabId: String, mediaId: String) =
                this@ChromiumEngineController.refreshMediaHeaders(tabId, mediaId)
            override suspend fun headersForMediaUrl(url: String, isPrivate: Boolean) =
                this@ChromiumEngineController.headersForMediaUrl(url, isPrivate)
            override fun publishState(selectedTabId: String?, pageNeedsReload: Boolean?) =
                this@ChromiumEngineController.publishState(
                    selectedTabId = selectedTabId ?: mutableState.value.selectedTabId,
                    pageNeedsReload = pageNeedsReload ?: adblockState.value.pageNeedsReload,
                )
        },
    )

    private val promptsController get() = tabBridge.prompts
    private val externalLinksController get() = tabBridge.externalLinks
    private val contextMenusController get() = tabBridge.contextMenus
    private val permissionsController get() = tabBridge.permissions
    private val notificationsController get() = tabBridge.webNotifications

    override val websiteIcons: WebsiteIconLoader = ChromiumWebsiteIcons()
    override val externalLinks: BrowserExternalLinksController = externalLinksController
    override val contextMenus: BrowserContextMenusController = contextMenusController
    override val prompts: BrowserPromptsController = promptsController
    override val uploads: BrowserFileUploadsController = ChromiumFileUploadsController()
    override val permissions: BrowserPermissionsController = permissionsController
    override val webNotifications: BrowserNotificationsController = notificationsController
    override val media: StateFlow<Map<String, List<DetectedMedia>>> = mediaManager.state
    override val mediaHints: Flow<Pair<String, Boolean>> = mediaManager.hints
    override val downloadRequests: Flow<BrowserDownloadRequest> = downloadChannel.receiveAsFlow()
    override val nativeAdblock: StateFlow<NativeAdblockState> = adblockManager.state

    private val adblockState = MutableStateFlow(
        AdblockSnapshot(
            status = AdblockStatus.STARTING,
            installed = true,
            enabled = true,
            version = "adblock-rust",
            backend = AdblockBackendKind.NATIVE_RUST,
            nativeControlsAvailable = true,
            siteBlockingEnabled = true,
        ),
    )
    override val adblock: StateFlow<AdblockSnapshot> = adblockState.asStateFlow()
    override val adblockPopup: StateFlow<BrowserPopupSession?> = MutableStateFlow<BrowserPopupSession?>(null).asStateFlow()

    private val initialId = UUID.randomUUID().toString().also { id ->
        tabsById[id] = ChromiumTabRecord(id = id, isPrivate = false, url = NEW_TAB_URL)
    }
    private val mutableState = MutableStateFlow(
        BrowserSnapshot(
            tabs = tabsById.values.map { it.toBrowserTab(null) },
            selectedTabId = initialId,
            ready = false,
        ),
    )
    override val state: StateFlow<BrowserSnapshot> = mutableState.asStateFlow()

    internal val activeNativeTab: StateFlow<MiruChromiumTab?> = activeNativeTabFlow.asStateFlow()
    internal val verticalScrollPosition: Flow<Float> = scrollFlow.asSharedFlow()

    override fun initialize() {
        if (initialized) return
        initialized = true
        applicationScope.launch(Dispatchers.Main.immediate) {
            MiruChromiumRuntime.ensureStarted(applicationContext)
            tabBridge.installRuntimeListeners()
            MiruChromiumRuntime.setDownloadListener { url, fileName, mimeType, contentLength, sourcePage, userAgent, cookieHeader, isPrivate, tempPath ->
                ChromiumDownloadBridge.buildRequest(
                    url = url,
                    fileName = fileName,
                    mimeType = mimeType,
                    contentLength = contentLength,
                    sourcePage = sourcePage,
                    userAgent = userAgent,
                    cookieHeader = cookieHeader,
                    isPrivate = isPrivate,
                    tempPath = tempPath,
                )?.let { req ->
                    if (testDownloadInterceptor?.invoke(req) != true) {
                        downloadChannel.trySend(req)
                    }
                }
            }
            // Compile/load bundled & cached adblock snapshot + restore saved session off the UI thread before navigation.
            val restoredSnapshot = withContext(Dispatchers.IO) {
                adblockManager.initializeSync()
                ChromiumDownloadBridge.cleanupStaleBlobTempDir(File(applicationContext.filesDir, "miru_chromium"))
                sessionStore.load()
            }
            if (restoredSnapshot != null &&
                tabsById.size == 1 &&
                tabsById[initialId]?.url == NEW_TAB_URL &&
                mutableState.value.selectedTabId == initialId
            ) {
                tabsById.clear()
                restoredSnapshot.tabs.forEach { saved ->
                    tabsById[saved.id] = ChromiumTabRecord(
                        id = saved.id,
                        isPrivate = false,
                        url = saved.url,
                        title = saved.title,
                        desktopMode = saved.desktopMode,
                    )
                }
                val restoredSelected = restoredSnapshot.selectedTabId?.takeIf(tabsById::containsKey)
                    ?: tabsById.keys.firstOrNull()
                val selRec = restoredSelected?.let(tabsById::get)
                if (selRec != null && !selRec.url.equals(NEW_TAB_URL, ignoreCase = true)) {
                    ensureNativeTab(selRec).apply {
                        if (selRec.desktopMode) setDesktopMode(true)
                        loadUrl(selRec.url)
                    }
                }
                activeNativeTabFlow.value = selRec?.nativeTab
                publishState(selectedTabId = restoredSelected, ready = true)
            } else {
                publishState(ready = true)
            }
            launch {
                adblockManager.state.collect { syncAdblockSnapshot(pageNeedsReload = adblockState.value.pageNeedsReload) }
            }
            val selected = mutableState.value.selectedTabId?.let(tabsById::get)
            selected?.let { rec ->
                if (rec.nativeTab == null && !rec.url.equals(NEW_TAB_URL, ignoreCase = true)) {
                    ensureNativeTab(rec).loadUrl(rec.url)
                }
                activeNativeTabFlow.value = rec.nativeTab
            }
            publishState(ready = true)
            launch {
                securitySettings.distinctUntilChanged().collect { applySecuritySettingsNow(it) }
            }
            launch {
                webZoomPercent.distinctUntilChanged().collect { zoom ->
                    zoomPercentValue = zoom
                    tabsById.values.forEach { it.nativeTab?.setZoomPercent(zoom) }
                }
            }
        }
    }

    fun applySecuritySettingsNow(settings: BrowserSecuritySettings) {
        if (initialized) ChromiumSecurityHelper.applySecuritySettings(settings)
    }

    override fun newTab(url: String, isPrivate: Boolean): String {
        val id = UUID.randomUUID().toString()
        val record = ChromiumTabRecord(id = id, isPrivate = isPrivate, url = url)
        tabsById[id] = record
        if (!url.equals(NEW_TAB_URL, ignoreCase = true)) automaticNewTabId = null
        if (initialized && url != NEW_TAB_URL) {
            ensureNativeTab(record).loadUrl(url)
        }
        publishState(selectedTabId = id, pageNeedsReload = false)
        permissionsController.syncState()
        activeNativeTabFlow.value = record.nativeTab
        return id
    }

    override fun navigate(url: String) {
        val selected = currentTab() ?: return
        val normalized = normalizeUrlInput(url)
        selected.attemptGeneration++
        promptsController.onNavigationAttempt(selected.id)
        externalLinksController.syncState()
        permissionsController.onNavigation(selected.id)
        contextMenusController.dismiss(null)
        val keepCommittedUrl = initialized && selected.nativeTab != null &&
            selected.url != NEW_TAB_URL && normalized != NEW_TAB_URL
        if (!keepCommittedUrl) {
            selected.url = normalized
        }
        selected.showToolbarAsExpanded = false
        if (selected.id == automaticNewTabId && normalized != NEW_TAB_URL) automaticNewTabId = null
        tabBlockedCounts[selected.id] = 0
        mediaManager.onNavigation(selected.id)
        if (normalized == NEW_TAB_URL) {
            selected.hardGeneration++
            promptsController.invalidateTab(selected.id)
            permissionsController.invalidateTab(selected.id)
            contextMenusController.invalidateTab(selected.id)
            selected.nativeTab?.stopLoading()
            selected.title = ""
            selected.loading = false
            selected.progress = 0
            selected.secure = false
            selected.certificate = null
            activeNativeTabFlow.value = null
            publishState(pageNeedsReload = false)
            permissionsController.syncState()
            return
        }
        if (initialized) {
            val nativeTab = ensureNativeTab(selected)
            activeNativeTabFlow.value = nativeTab
            nativeTab.loadUrl(normalized)
        }
        publishState(pageNeedsReload = false)
    }

    override fun selectTab(id: String) {
        val record = tabsById[id] ?: return
        publishState(selectedTabId = id, pageNeedsReload = false)
        permissionsController.syncState()
        activeNativeTabFlow.value = if (record.url == NEW_TAB_URL) null else record.nativeTab
    }

    override fun closeTab(id: String) {
        val index = tabsById.keys.indexOf(id).takeIf { it >= 0 } ?: return
        val wasSelected = mutableState.value.selectedTabId == id
        val removed = tabsById.remove(id) ?: return
        promptsController.invalidateTab(id)
        permissionsController.invalidateTab(id)
        contextMenusController.invalidateTab(id)
        if (!removed.isPrivate && !removed.url.equals(NEW_TAB_URL, ignoreCase = true)) {
            sessionStore.recordClosedNormalTabs(
                listOf(ChromiumSessionStore.SavedTabEntry(removed.toSavedState(), index)),
                selectedTabId = removed.id.takeIf { wasSelected },
            )
        }
        sessionStore.removeThumbnail(id)
        removed.nativeTab?.destroy()
        removed.nativeTab = null
        tabBlockedCounts.remove(id)
        if (tabsById.isEmpty()) {
            val replacementId = UUID.randomUUID().toString()
            tabsById[replacementId] = ChromiumTabRecord(id = replacementId, isPrivate = false, url = NEW_TAB_URL)
            automaticNewTabId = replacementId
        } else if (id == automaticNewTabId) {
            automaticNewTabId = null
        }
        mediaManager.retainTabs(tabsById.keys.toSet())
        val nextSelected = if (wasSelected) tabsById.keys.last() else mutableState.value.selectedTabId
        val activeRec = nextSelected?.let(tabsById::get)
        if (removed.isPrivate && tabsById.values.none { it.isPrivate }) {
            if (initialized) MiruChromiumRuntime.destroyOffTheRecordProfile()
        }
        notificationsController.onPrivateTabsStateChanged(tabsById.values.any { it.isPrivate })
        publishState(selectedTabId = nextSelected, pageNeedsReload = false)
        permissionsController.syncState()
        activeNativeTabFlow.value = if (activeRec?.url == NEW_TAB_URL) null else activeRec?.nativeTab
    }

    override fun closeAllTabs(isPrivate: Boolean) {
        if (!isPrivate) {
            val prevSelected = mutableState.value.selectedTabId
            val normalEntries = tabsById.entries.mapIndexedNotNull { index, (_, rec) ->
                rec.takeIf { !it.isPrivate && !it.url.equals(NEW_TAB_URL, ignoreCase = true) }
                    ?.let { ChromiumSessionStore.SavedTabEntry(it.toSavedState(), index) }
            }
            if (normalEntries.isNotEmpty()) {
                sessionStore.recordClosedNormalTabs(normalEntries, prevSelected?.takeIf { tabsById[it]?.isPrivate == false })
            }
        }
        val toRemove = tabsById.values.filter { it.isPrivate == isPrivate }.map { it.id }
        removeTabsAndSelectFallback(toRemove, destroyOtr = isPrivate)
    }

    override fun closePrivateTabsPermanently() {
        val toRemove = tabsById.values.filter { it.isPrivate }.map { it.id }
        removeTabsAndSelectFallback(toRemove, destroyOtr = true)
    }

    private fun removeTabsAndSelectFallback(ids: List<String>, destroyOtr: Boolean) {
        ids.forEach { id ->
            promptsController.invalidateTab(id)
            permissionsController.invalidateTab(id)
            contextMenusController.invalidateTab(id)
            tabsById.remove(id)?.let { rec ->
                sessionStore.removeThumbnail(id)
                rec.nativeTab?.destroy()
                rec.nativeTab = null
                tabBlockedCounts.remove(id)
            }
        }
        if (tabsById.isEmpty() || tabsById.values.all { it.isPrivate }) {
            val replacementId = UUID.randomUUID().toString()
            tabsById[replacementId] = ChromiumTabRecord(id = replacementId, isPrivate = false, url = NEW_TAB_URL)
            automaticNewTabId = replacementId
        }
        mediaManager.retainTabs(tabsById.keys.toSet())
        val nextSelected = mutableState.value.selectedTabId?.takeIf(tabsById::containsKey)
            ?: tabsById.values.lastOrNull { !it.isPrivate }?.id
            ?: tabsById.keys.last()
        val activeRec = tabsById[nextSelected]
        if (destroyOtr && initialized) MiruChromiumRuntime.destroyOffTheRecordProfile()
        notificationsController.onPrivateTabsStateChanged(tabsById.values.any { it.isPrivate })
        publishState(selectedTabId = nextSelected, pageNeedsReload = false)
        permissionsController.syncState()
        activeNativeTabFlow.value = if (activeRec?.url == NEW_TAB_URL) null else activeRec?.nativeTab
    }

    override fun undoClose() {
        val batch = sessionStore.popUndoBatch() ?: return
        val existing = tabsById.values.toMutableList()
        automaticNewTabId?.let { autoId ->
            existing.removeAll { it.id == autoId && it.url == NEW_TAB_URL }
            tabsById.remove(autoId)
        }
        automaticNewTabId = null
        batch.tabs.sortedBy { it.index }.forEach { entry ->
            if (existing.any { it.id == entry.state.id }) return@forEach
            val restored = ChromiumTabRecord(
                id = entry.state.id,
                isPrivate = false,
                url = entry.state.url,
                title = entry.state.title,
                desktopMode = entry.state.desktopMode,
            )
            existing.add(entry.index.coerceIn(0, existing.size), restored)
        }
        tabsById.clear()
        existing.forEach { tabsById[it.id] = it }
        val nextSelected = batch.selectedTabId?.takeIf(tabsById::containsKey)
            ?: batch.tabs.lastOrNull()?.state?.id?.takeIf(tabsById::containsKey)
            ?: mutableState.value.selectedTabId
        val selectedRec = nextSelected?.let(tabsById::get)
        if (initialized && selectedRec != null && !selectedRec.url.equals(NEW_TAB_URL, ignoreCase = true)) {
            ensureNativeTab(selectedRec).apply {
                if (selectedRec.desktopMode) setDesktopMode(true)
                loadUrl(selectedRec.url)
            }
        }
        publishState(selectedTabId = nextSelected, pageNeedsReload = false)
        permissionsController.syncState()
        activeNativeTabFlow.value = selectedRec?.nativeTab
    }

    override fun goBack(): Boolean {
        val rec = currentTab() ?: return false
        val tab = rec.nativeTab ?: return false
        if (!tab.canGoBack()) return false
        rec.attemptGeneration++
        promptsController.onNavigationAttempt(rec.id)
        externalLinksController.syncState()
        permissionsController.onNavigation(rec.id)
        contextMenusController.dismiss(null)
        tab.goBack()
        return true
    }

    override fun goForward() {
        val rec = currentTab() ?: return
        val tab = rec.nativeTab ?: return
        if (tab.canGoForward()) {
            rec.attemptGeneration++
            promptsController.onNavigationAttempt(rec.id)
            externalLinksController.syncState()
            permissionsController.onNavigation(rec.id)
            contextMenusController.dismiss(null)
            tab.goForward()
        }
    }

    override fun exitWebFullScreen(): Boolean {
        val selected = currentTab() ?: return false
        if (!selected.fullScreen) return false
        selected.nativeTab?.evaluateJavaScript("document.exitFullscreen && document.exitFullscreen()", null)
        return true
    }

    override fun configureWebMedia(holdEnabled: Boolean, holdSpeed: Float) {
        webHoldEnabled = holdEnabled
        webHoldSpeed = holdSpeed.takeIf { it.isFinite() }?.coerceIn(0.5f, 3f) ?: 2f
        if (!initialized) return
        mediaManager.configureWebMedia(tabsById.values.mapNotNull { it.nativeTab }, webHoldEnabled, webHoldSpeed)
    }

    override fun reload() {
        val rec = currentTab()
        if (rec != null) {
            rec.attemptGeneration++
            promptsController.onNavigationAttempt(rec.id)
            externalLinksController.syncState()
            permissionsController.onNavigation(rec.id)
            contextMenusController.dismiss(null)
        }
        syncAdblockSnapshot(pageNeedsReload = false)
        rec?.nativeTab?.reload()
    }

    override fun stopLoading() { currentTab()?.nativeTab?.stopLoading() }

    override fun setDesktopMode(enabled: Boolean) {
        val selected = currentTab() ?: return
        selected.desktopMode = enabled
        selected.nativeTab?.setDesktopMode(enabled)
        publishState()
    }

    override fun consumeToolbarExpansion() {
        currentTab()?.let {
            if (it.showToolbarAsExpanded) {
                it.showToolbarAsExpanded = false
                publishState()
            }
        }
    }

    override fun setAdblockEnabled(enabled: Boolean) {
        adblockManager.setEnabled(enabled)
        syncAdblockSnapshot(pageNeedsReload = true)
    }

    override fun retryAdblock() = setAdblockEnabled(adblockManager.isEnabled)
    override fun openAdblockPopup(): Boolean = false
    override fun closeAdblockPopup() = Unit
    override fun openAdblockDashboard(): Boolean = false
    override fun openAdblockExceptions(): Boolean = false
    override fun openAdblockLogger(): Boolean = false

    override fun setSiteAdblockEnabled(host: String, enabled: Boolean): Boolean {
        if (!adblockManager.setSiteAllowed(host, !enabled)) return false
        syncAdblockSnapshot(pageNeedsReload = true)
        return true
    }

    override fun startAdblockElementPicker(): Boolean {
        val nativeTab = currentTab()?.nativeTab ?: return false
        adblockManager.setPickerActive(true)
        nativeTab.evaluateIsolatedJs(ChromiumPageScripts.elementPickerScript(), false, null)
        return true
    }

    override fun toggleAdblockFilterList(id: String, enabled: Boolean) =
        adblockManager.toggleFilterList(id, enabled)

    override fun refreshAdblockLists() = adblockManager.refreshLists()
    override fun addAdblockCustomRule(rule: String): Boolean = adblockManager.addCustomRule(rule)
    override fun removeAdblockCustomRule(rule: String): Boolean = adblockManager.removeCustomRule(rule)

    fun setAdblockRules(rulesText: String) {
        if (initialized) adblockManager.setRulesOverrideForTesting(rulesText)
    }

    fun restoreDefaultAdblockRules() {
        if (initialized) {
            adblockManager.restoreDefaultRulesForTesting()
            syncAdblockSnapshot(pageNeedsReload = false)
        }
    }

    fun evaluateJavaScript(tabId: String? = null, script: String, onResult: (String) -> Unit) {
        val nativeTab = (tabId?.let(tabsById::get) ?: currentTab())?.nativeTab
        if (nativeTab == null) {
            onResult("")
            return
        }
        nativeTab.evaluateJavaScript(script) { value -> onResult(value.orEmpty()) }
    }

    override suspend fun prepareMedia(tabId: String, mediaId: String): PreparedMedia? {
        val rec = tabsById[tabId] ?: return null
        return mediaManager.prepareMedia(tabId, rec.nativeTab, rec.url, mediaId)
    }

    override suspend fun refreshMediaHeaders(tabId: String, mediaId: String): Map<String, String>? =
        mediaManager.refreshMediaHeaders(tabId, mediaId)

    override fun restoreWebMedia(tabId: String) =
        mediaManager.restoreWebMedia(tabsById[tabId]?.nativeTab)

    override suspend fun contextImagePolicy(request: BrowserContextMenu): ContextImagePolicy? =
        contextMenusController.contextImagePolicy(request)
    override suspend fun contextHeaders(request: BrowserContextMenu, url: String, imagePolicy: ContextImagePolicy?): Map<String, String>? =
        contextMenusController.contextHeaders(request, url, imagePolicy)
    override suspend fun headersForMediaUrl(url: String, isPrivate: Boolean): Map<String, String> =
        mediaManager.headersForMediaUrl(url, isPrivate)
    override suspend fun downloadHeaders(url: String, sourcePage: String?): Map<String, String>? =
        mediaManager.downloadHeaders(url, sourcePage)
    override fun downloadCurrentPage() {
        val rec = currentTab() ?: return
        if (!rec.url.startsWith("http://", ignoreCase = true) && !rec.url.startsWith("https://", ignoreCase = true)) {
            return
        }
        applicationScope.launch(Dispatchers.Main.immediate) {
            val headers = mediaManager.headersForMediaUrl(rec.url, rec.isPrivate) + mapOf("Referer" to rec.url)
            downloadChannel.trySend(
                BrowserDownloadRequest(
                    url = rec.url,
                    fileName = null,
                    mimeType = null,
                    contentLength = null,
                    headers = headers,
                    sourcePage = rec.url,
                    isPrivate = rec.isPrivate,
                    bodyStream = null,
                ),
            )
        }
    }

    override suspend fun clearBrowsingData(request: BrowsingDataClearRequest): BrowsingDataClearResult =
        ChromiumSecurityHelper.clearBrowsingData(
            request = request,
            initialized = initialized,
            hasPrivateTabs = tabsById.values.any { it.isPrivate },
            onClearMemoryCaches = {
                websiteIcons.clearMemory()
                sessionStore.clearThumbnails()
            },
            onClearSiteSettings = {
                permissionsController.clearAllSiteSettings { }
            },
        )

    override suspend fun listSavedSites(): List<SavedSiteEntry> {
        val perms = permissionsController.listSavedDefaultPermissions()
        val autoplay = permissionsController.listSavedDefaultAutoplay()
        val popups = promptsController.savedPopupOrigins()
        return (perms.keys + autoplay.keys + popups).sorted().map { origin ->
            SavedSiteEntry(
                origin = origin,
                permissions = perms[origin].orEmpty(),
                autoplay = autoplay[origin],
                allowPopups = origin in popups,
            )
        }
    }

    override suspend fun resetSavedSite(origin: String) {
        siteOrigin(origin)?.let { normalized ->
            promptsController.clearPopupOrigin(normalized)
            permissionsController.resetSavedDefaultSite(normalized)
        }
    }

    override suspend fun resetAllSavedSites(): BrowsingDataClearResult = clearBrowsingData(
        BrowsingDataClearRequest(
            types = setOf(BrowsingDataType.SITE_SETTINGS),
            range = BrowsingDataRange.ALL_TIME,
        ),
    )

    override fun findAll(query: String) {
        currentTab()?.let { rec ->
            rec.lastFindQuery = query
            rec.nativeTab?.findAll(query, true)
        }
    }

    override fun findNext(forward: Boolean) {
        val rec = currentTab() ?: return
        if (rec.lastFindQuery.isNotEmpty()) rec.nativeTab?.findAll(rec.lastFindQuery, forward)
    }

    override fun clearFind() {
        currentTab()?.let { rec ->
            rec.lastFindQuery = ""
            rec.nativeTab?.clearFind()
            rec.findCurrent = 0
            rec.findTotal = 0
            publishState()
        }
    }

    override fun captureThumbnail() {
        val rec = currentTab() ?: return
        if (!initialized || rec.isPrivate || rec.url.equals(NEW_TAB_URL, ignoreCase = true)) return
        val nativeTab = rec.nativeTab ?: return
        val capturedId = rec.id
        val capturedUrl = rec.url
        nativeTab.captureThumbnail(480) { bmp ->
            if (bmp == null) return@captureThumbnail
            applicationScope.launch(Dispatchers.Main.immediate) {
                val live = tabsById[capturedId]
                if (live != null && !live.isPrivate && live.url == capturedUrl) {
                    sessionStore.putThumbnail(capturedId, bmp)
                    publishState()
                }
            }
        }
    }

    override fun getThumbnail(id: String): Bitmap? =
        tabsById[id]?.takeUnless { it.isPrivate }?.let { sessionStore.getThumbnail(id) }

    override fun saveSession() {
        if (!initialized) return
        val normalTabs = tabsById.values.filter { !it.isPrivate }.map { it.toSavedState() }
        val selectedNormal = mutableState.value.selectedTabId?.takeIf { id -> normalTabs.any { it.id == id } }
        applicationScope.launch(Dispatchers.IO) { sessionStore.save(normalTabs, selectedNormal) }
    }

    override fun onTrimMemory(level: Int) {
        websiteIcons.clearMemory()
        sessionStore.clearThumbnails()
    }
    override fun createSurfaceHost(context: Context): BrowserSurfaceHost = ChromiumSurfaceHost(context, this, applicationScope)

    private fun ensureNativeTab(record: ChromiumTabRecord): MiruChromiumTab {
        record.nativeTab?.let { return it }
        val nativeTab = tabBridge.createNativeTab(record.id, record.isPrivate)
        record.nativeTab = nativeTab
        return nativeTab
    }

    private fun handleAdBlocked(tabId: String) {
        val total = adblockManager.recordBlocked()
        val count = (tabBlockedCounts[tabId] ?: 0) + 1
        tabBlockedCounts[tabId] = count
        if (mutableState.value.selectedTabId == tabId) {
            adblockState.value = adblockState.value.copy(
                blockedCount = count,
                totalBlockedCount = total,
                badgeText = count.toString(),
                badgeTabId = tabId,
            )
        }
    }

    private fun currentTab(): ChromiumTabRecord? = mutableState.value.selectedTabId?.let(tabsById::get)

    private fun publishState(
        selectedTabId: String? = mutableState.value.selectedTabId,
        ready: Boolean = mutableState.value.ready || initialized,
        pageNeedsReload: Boolean = adblockState.value.pageNeedsReload,
    ) {
        val resolvedSelected = selectedTabId?.takeIf(tabsById::containsKey) ?: tabsById.keys.firstOrNull()
        val tabList = tabsById.values.map { tab ->
            tab.toBrowserTab(if (tab.isPrivate) null else sessionStore.getThumbnail(tab.id))
        }
        mutableState.value = BrowserSnapshot(tabs = tabList, selectedTabId = resolvedSelected, ready = ready)
        promptsController.syncState()
        externalLinksController.syncState()
        contextMenusController.syncState()
        syncAdblockSnapshot(selectedTabId = resolvedSelected, pageNeedsReload = pageNeedsReload)
    }

    private fun syncAdblockSnapshot(
        selectedTabId: String? = mutableState.value.selectedTabId,
        pageNeedsReload: Boolean = adblockState.value.pageNeedsReload,
    ) {
        val rec = selectedTabId?.let(tabsById::get)
        val host = rec?.url?.let(::extractHost).orEmpty()
        val enabled = adblockManager.isEnabled
        val siteBlocking = host.isEmpty() || !adblockManager.isSiteAllowed(host)
        val badgeCount = selectedTabId?.let { tabBlockedCounts[it] } ?: 0
        adblockState.value = adblockState.value.copy(
            status = if (!initialized) AdblockStatus.STARTING else if (enabled) AdblockStatus.READY else AdblockStatus.DISABLED,
            installed = true,
            enabled = enabled,
            version = "adblock-rust",
            blockedCount = badgeCount,
            totalBlockedCount = adblockManager.totalBlocked,
            badgeText = if (badgeCount > 0) badgeCount.toString() else "",
            badgeTabId = selectedTabId,
            nativeControlsAvailable = true,
            siteBlockingEnabled = siteBlocking,
            backend = AdblockBackendKind.NATIVE_RUST,
            pageNeedsReload = pageNeedsReload,
        )
    }

    private fun extractHost(rawUrl: String): String =
        runCatching { URI(rawUrl).host.orEmpty() }.getOrDefault("")

    private fun normalizeUrlInput(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.equals(NEW_TAB_URL, ignoreCase = true)) return NEW_TAB_URL
        if (trimmed.contains("://") || trimmed.startsWith("about:") || trimmed.startsWith("data:") || trimmed.startsWith("javascript:")) {
            return trimmed
        }
        return "https://$trimmed"
    }

    internal fun attachWindowAndroid(window: org.chromium.ui.base.WindowAndroid?) =
        tabBridge.attachWindowAndroid(window)
}
