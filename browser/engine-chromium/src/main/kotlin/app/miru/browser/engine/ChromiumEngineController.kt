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
import app.miru.data.BrowsingDataType
import app.miru.data.BrowsingLibrary
import app.miru.media.DetectedMedia
import app.miru.media.MediaSnifferClassifier
import app.miru.media.MediaSnifferCoordinator
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * De-Googled Chromium 159 backend controller implementing [BrowserEngineController].
 * Owns per-tab `MiruChromiumTab` (`WebContents` + C++ `MiruTab` + `URLLoaderThrottle` `adblock-rust`),
 * normal vs OffTheRecord profile isolation, and `MediaSnifferCoordinator` media propagation.
 */
class ChromiumEngineController(
    context: Context,
    private val applicationScope: CoroutineScope,
    private val library: BrowsingLibrary,
    private val securitySettings: Flow<BrowserSecuritySettings>,
    private val webZoomPercent: Flow<Int>,
) : BrowserEngineController {
    private val applicationContext = context.applicationContext
    private val tabsById = linkedMapOf<String, TabRecord>()
    private val tabBlockedCounts = ConcurrentHashMap<String, Int>()
    private val mediaCoordinator = MediaSnifferCoordinator()
    private val scrollFlow = MutableSharedFlow<Float>(replay = 1, extraBufferCapacity = 32)
    private val downloadChannel = Channel<BrowserDownloadRequest>(Channel.UNLIMITED)
    private val mediaHintsChannel = Channel<Pair<String, Boolean>>(Channel.UNLIMITED)
    private val activeNativeTabFlow = MutableStateFlow<MiruChromiumTab?>(null)
    private var zoomPercentValue = 100
    private var adblockEnabled = true
    private var totalBlocked = 0L
    private var initialized = false

    private data class TabRecord(
        val id: String,
        val isPrivate: Boolean,
        var nativeTab: MiruChromiumTab? = null,
        var url: String = NEW_TAB_URL,
        var title: String = "",
        var loading: Boolean = false,
        var progress: Int = 0,
        var canGoBack: Boolean = false,
        var canGoForward: Boolean = false,
        var desktopMode: Boolean = false,
        var fullScreen: Boolean = false,
        var findCurrent: Int = 0,
        var findTotal: Int = 0,
        var lastFindQuery: String = "",
        var showToolbarAsExpanded: Boolean = false,
        var lastRecordedUrl: String? = null,
        var lastRecordedTitle: String? = null,
    )

    override val websiteIcons: WebsiteIconLoader = ChromiumWebsiteIcons()
    override val externalLinks: BrowserExternalLinksController = ChromiumExternalLinksController()
    override val contextMenus: BrowserContextMenusController = ChromiumContextMenusController()
    override val prompts: BrowserPromptsController = ChromiumPromptsController()
    override val uploads: BrowserFileUploadsController = ChromiumFileUploadsController()
    override val permissions: BrowserPermissionsController = ChromiumPermissionsController()
    override val webNotifications: BrowserNotificationsController = ChromiumNotificationsController()
    override val media: StateFlow<Map<String, List<DetectedMedia>>> = mediaCoordinator.state
    override val mediaHints: Flow<Pair<String, Boolean>> = mediaHintsChannel.receiveAsFlow()
    override val downloadRequests: Flow<BrowserDownloadRequest> = downloadChannel.receiveAsFlow()

    private val adblockState = MutableStateFlow(
        AdblockSnapshot(
            status = AdblockStatus.STARTING,
            installed = true,
            enabled = true,
            backend = AdblockBackendKind.NATIVE_RUST,
            nativeControlsAvailable = true,
            siteBlockingEnabled = true,
        ),
    )
    override val adblock: StateFlow<AdblockSnapshot> = adblockState.asStateFlow()
    override val adblockPopup: StateFlow<BrowserPopupSession?> = MutableStateFlow<BrowserPopupSession?>(null).asStateFlow()

    private val initialId = UUID.randomUUID().toString().also { id ->
        tabsById[id] = TabRecord(id = id, isPrivate = false, url = NEW_TAB_URL)
    }
    private val mutableState = MutableStateFlow(
        BrowserSnapshot(
            tabs = tabsById.values.map { it.toBrowserTab() },
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
            MiruChromiumRuntime.setAdblockEnabled(adblockEnabled)
            adblockState.value = adblockState.value.copy(
                status = if (adblockEnabled) AdblockStatus.READY else AdblockStatus.DISABLED,
                installed = true,
                enabled = adblockEnabled,
                backend = AdblockBackendKind.NATIVE_RUST,
                nativeControlsAvailable = true,
            )
            val selected = mutableState.value.selectedTabId?.let(tabsById::get)
            selected?.let { rec ->
                if (rec.nativeTab == null && !rec.url.equals(NEW_TAB_URL, ignoreCase = true)) {
                    ensureNativeTab(rec).loadUrl(rec.url)
                }
                activeNativeTabFlow.value = rec.nativeTab
            }
            publishState(ready = true)
            launch {
                webZoomPercent.distinctUntilChanged().collect { zoom ->
                    zoomPercentValue = zoom
                    tabsById.values.forEach { it.nativeTab?.setZoomPercent(zoom) }
                }
            }
        }
    }

    override fun newTab(url: String, isPrivate: Boolean): String {
        val id = UUID.randomUUID().toString()
        val record = TabRecord(id = id, isPrivate = isPrivate, url = url)
        tabsById[id] = record
        if (initialized && url != NEW_TAB_URL) {
            ensureNativeTab(record).loadUrl(url)
        }
        activeNativeTabFlow.value = record.nativeTab
        publishState(selectedTabId = id)
        return id
    }

    override fun navigate(url: String) {
        val selected = currentTab() ?: return
        val normalized = normalizeUrlInput(url)
        selected.url = normalized
        selected.showToolbarAsExpanded = false
        tabBlockedCounts[selected.id] = 0
        mediaCoordinator.onNavigation(selected.id)
        if (normalized == NEW_TAB_URL) {
            selected.nativeTab?.stopLoading()
            selected.title = ""
            selected.loading = false
            selected.progress = 0
            activeNativeTabFlow.value = null
            publishState()
            return
        }
        if (initialized) {
            val nativeTab = ensureNativeTab(selected)
            activeNativeTabFlow.value = nativeTab
            nativeTab.loadUrl(normalized)
        }
        publishState()
    }

    override fun selectTab(id: String) {
        val record = tabsById[id] ?: return
        activeNativeTabFlow.value = if (record.url == NEW_TAB_URL) null else record.nativeTab
        publishState(selectedTabId = id)
    }

    override fun closeTab(id: String) {
        val removed = tabsById.remove(id) ?: return
        removed.nativeTab?.destroy()
        removed.nativeTab = null
        tabBlockedCounts.remove(id)
        if (tabsById.isEmpty()) {
            val replacementId = UUID.randomUUID().toString()
            tabsById[replacementId] = TabRecord(id = replacementId, isPrivate = false, url = NEW_TAB_URL)
        }
        mediaCoordinator.retainTabs(tabsById.keys.toSet())
        val nextSelected = if (mutableState.value.selectedTabId == id) tabsById.keys.last() else mutableState.value.selectedTabId
        val activeRec = nextSelected?.let(tabsById::get)
        activeNativeTabFlow.value = if (activeRec?.url == NEW_TAB_URL) null else activeRec?.nativeTab
        if (removed.isPrivate && tabsById.values.none { it.isPrivate }) {
            if (initialized) MiruChromiumRuntime.destroyOffTheRecordProfile()
        }
        publishState(selectedTabId = nextSelected)
    }

    override fun closeAllTabs(isPrivate: Boolean) {
        val toClose = tabsById.values.filter { it.isPrivate == isPrivate }.map { it.id }
        toClose.forEach { closeTab(it) }
    }

    override fun closePrivateTabsPermanently() {
        val privateIds = tabsById.values.filter { it.isPrivate }.map { it.id }
        privateIds.forEach { id ->
            tabsById.remove(id)?.let { rec ->
                rec.nativeTab?.destroy()
                rec.nativeTab = null
                tabBlockedCounts.remove(id)
            }
        }
        if (tabsById.isEmpty()) {
            val replacementId = UUID.randomUUID().toString()
            tabsById[replacementId] = TabRecord(id = replacementId, isPrivate = false, url = NEW_TAB_URL)
        }
        mediaCoordinator.retainTabs(tabsById.keys.toSet())
        val nextSelected = mutableState.value.selectedTabId?.takeIf(tabsById::containsKey) ?: tabsById.keys.last()
        val activeRec = tabsById[nextSelected]
        activeNativeTabFlow.value = if (activeRec?.url == NEW_TAB_URL) null else activeRec?.nativeTab
        if (initialized) MiruChromiumRuntime.destroyOffTheRecordProfile()
        publishState(selectedTabId = nextSelected)
    }

    override fun undoClose() = Unit

    override fun goBack(): Boolean {
        val tab = currentTab()?.nativeTab ?: return false
        if (!tab.canGoBack()) return false
        tab.goBack()
        return true
    }

    override fun goForward() {
        val tab = currentTab()?.nativeTab ?: return
        if (tab.canGoForward()) tab.goForward()
    }

    override fun exitWebFullScreen(): Boolean {
        val selected = currentTab() ?: return false
        if (!selected.fullScreen) return false
        selected.nativeTab?.evaluateJavaScript("document.exitFullscreen && document.exitFullscreen()", null)
        return true
    }

    override fun configureWebMedia(holdEnabled: Boolean, holdSpeed: Float) = Unit
    override fun reload() { currentTab()?.nativeTab?.reload() }
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
        adblockEnabled = enabled
        if (initialized) MiruChromiumRuntime.setAdblockEnabled(enabled)
        adblockState.value = adblockState.value.copy(
            enabled = enabled,
            status = if (enabled) AdblockStatus.READY else AdblockStatus.DISABLED,
        )
    }

    override fun retryAdblock() = setAdblockEnabled(adblockEnabled)
    override fun openAdblockPopup(): Boolean = false
    override fun closeAdblockPopup() = Unit
    override fun openAdblockDashboard(): Boolean = false
    override fun openAdblockExceptions(): Boolean = false
    override fun openAdblockLogger(): Boolean = false

    override fun setSiteAdblockEnabled(host: String, enabled: Boolean): Boolean {
        if (!initialized || host.isBlank()) return false
        MiruChromiumRuntime.setSiteAdblockAllowed(host, !enabled)
        adblockState.value = adblockState.value.copy(siteBlockingEnabled = enabled, pageNeedsReload = true)
        return true
    }

    fun setAdblockRules(rulesText: String) {
        if (!initialized) return
        MiruChromiumRuntime.setAdblockRules(rulesText)
        MiruChromiumRuntime.setAdblockEnabled(adblockEnabled)
    }

    fun evaluateJavaScript(tabId: String? = null, script: String, onResult: (String) -> Unit) {
        val target = tabId?.let(tabsById::get) ?: currentTab()
        val nativeTab = target?.nativeTab
        if (nativeTab == null) {
            onResult("")
            return
        }
        nativeTab.evaluateJavaScript(script) { value -> onResult(value.orEmpty()) }
    }

    override suspend fun prepareMedia(tabId: String, mediaId: String): PreparedMedia? {
        val found = mediaCoordinator.state.value[tabId]?.firstOrNull { it.id == mediaId } ?: return null
        return PreparedMedia(media = found, headers = found.headers, positionMs = (found.position * 1000.0).toLong())
    }

    override suspend fun refreshMediaHeaders(tabId: String, mediaId: String): Map<String, String>? =
        mediaCoordinator.state.value[tabId]?.firstOrNull { it.id == mediaId }?.headers

    override fun restoreWebMedia(tabId: String) = Unit
    override suspend fun contextImagePolicy(request: BrowserContextMenu): ContextImagePolicy? = null
    override suspend fun contextHeaders(request: BrowserContextMenu, url: String, imagePolicy: ContextImagePolicy?): Map<String, String>? = null
    override suspend fun headersForMediaUrl(url: String, isPrivate: Boolean): Map<String, String> = emptyMap()
    override suspend fun downloadHeaders(url: String, sourcePage: String?): Map<String, String>? = null
    override fun downloadCurrentPage() = Unit

    override suspend fun clearBrowsingData(request: BrowsingDataClearRequest): BrowsingDataClearResult = withContext(Dispatchers.Main.immediate) {
        if (initialized && (BrowsingDataType.COOKIES in request.types || BrowsingDataType.SITE_DATA in request.types)) {
            MiruChromiumRuntime.destroyOffTheRecordProfile()
        }
        val cleared = request.types.intersect(BrowserEngineDataTypes.ENGINE_TYPES)
        BrowsingDataClearResult(completed = cleared, failed = emptySet())
    }

    override suspend fun listSavedSites(): List<SavedSiteEntry> = emptyList()
    override suspend fun resetSavedSite(origin: String) = Unit
    override suspend fun resetAllSavedSites(): BrowsingDataClearResult = BrowsingDataClearResult(emptySet(), emptySet())

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

    override fun captureThumbnail() = Unit
    override fun getThumbnail(id: String): Bitmap? = null
    override fun saveSession() = Unit
    override fun onTrimMemory(level: Int) { websiteIcons.clearMemory() }
    override fun createSurfaceHost(context: Context): BrowserSurfaceHost = ChromiumSurfaceHost(context, this, applicationScope)

    private fun ensureNativeTab(record: TabRecord): MiruChromiumTab {
        record.nativeTab?.let { return it }
        val nativeTab = MiruChromiumTab(applicationContext, record.isPrivate, object : MiruChromiumTab.Listener {
            override fun onNavigationChanged(url: String, title: String, canGoBack: Boolean, canGoForward: Boolean, isLoading: Boolean) {
                val previousUrl = record.url
                if (url.isNotBlank()) record.url = url
                if (title.isNotBlank()) record.title = title
                record.canGoBack = canGoBack
                record.canGoForward = canGoForward
                record.loading = isLoading
                if (!isLoading) record.progress = 100
                if (previousUrl != record.url && record.url.startsWith("http")) {
                    tabBlockedCounts[record.id] = 0
                }
                if (!record.isPrivate && !isLoading && record.url.startsWith("http")) {
                    recordHistoryVisit(record)
                }
                publishState()
            }

            override fun onProgressChanged(progress: Double) {
                record.progress = (progress * 100.0).toInt().coerceIn(0, 100)
                publishState()
            }

            override fun onScrollChanged(scrollOffsetY: Int) {
                if (mutableState.value.selectedTabId == record.id) {
                    scrollFlow.tryEmit(scrollOffsetY.toFloat())
                }
            }

            override fun onFullscreenChanged(fullscreen: Boolean) {
                record.fullScreen = fullscreen
                publishState()
            }

            override fun onAdBlocked(blockedUrl: String) {
                totalBlocked += 1L
                val count = (tabBlockedCounts[record.id] ?: 0) + 1
                tabBlockedCounts[record.id] = count
                if (mutableState.value.selectedTabId == record.id) {
                    adblockState.value = adblockState.value.copy(
                        blockedCount = count,
                        totalBlockedCount = totalBlocked,
                        badgeText = count.toString(),
                        badgeTabId = record.id,
                    )
                }
            }

            override fun onMediaDetected(url: String, kind: String, mimeType: String, contentLength: Long) {
                if (MediaSnifferClassifier.classify(url, mimeType.ifEmpty { null }, contentLength.takeIf { it >= 0 }) != null ||
                    kind.isNotEmpty()
                ) {
                    mediaCoordinator.onNetworkMedia(
                        tabId = record.id,
                        pageUrl = record.url,
                        title = record.title,
                        isPrivate = record.isPrivate,
                        mediaUrl = url,
                        contentType = mimeType.ifEmpty { null },
                        contentLength = contentLength.takeIf { it >= 0 },
                    )
                }
            }

            override fun onFindResult(activeOrdinal: Int, matchCount: Int, finalUpdate: Boolean) {
                record.findCurrent = activeOrdinal
                record.findTotal = matchCount
                publishState()
            }
        })
        nativeTab.setZoomPercent(zoomPercentValue)
        record.nativeTab = nativeTab
        return nativeTab
    }

    private fun recordHistoryVisit(record: TabRecord) {
        val url = record.url
        val title = record.title
        if (url != record.lastRecordedUrl) {
            record.lastRecordedUrl = url
            record.lastRecordedTitle = title
            applicationScope.launch(Dispatchers.IO) {
                library.history.recordVisit(url)
                if (title.isNotBlank()) library.history.updateTitle(url, title)
            }
        } else if (title.isNotBlank() && title != record.lastRecordedTitle) {
            record.lastRecordedTitle = title
            applicationScope.launch(Dispatchers.IO) {
                library.history.updateTitle(url, title)
            }
        }
    }

    private fun currentTab(): TabRecord? = mutableState.value.selectedTabId?.let(tabsById::get)

    private fun publishState(
        selectedTabId: String? = mutableState.value.selectedTabId,
        ready: Boolean = mutableState.value.ready || initialized,
    ) {
        val resolvedSelected = selectedTabId?.takeIf(tabsById::containsKey) ?: tabsById.keys.firstOrNull()
        val tabList = tabsById.values.map { it.toBrowserTab() }
        mutableState.value = BrowserSnapshot(tabs = tabList, selectedTabId = resolvedSelected, ready = ready)
        val badgeCount = resolvedSelected?.let { tabBlockedCounts[it] } ?: 0
        adblockState.value = adblockState.value.copy(
            blockedCount = badgeCount,
            totalBlockedCount = totalBlocked,
            badgeText = if (badgeCount > 0) badgeCount.toString() else "",
            badgeTabId = resolvedSelected,
        )
    }

    private fun TabRecord.toBrowserTab(): BrowserTab = BrowserTab(
        id = id,
        url = url,
        title = title,
        isPrivate = isPrivate,
        loading = loading,
        progress = progress,
        canGoBack = canGoBack,
        canGoForward = canGoForward,
        desktopMode = desktopMode,
        secure = url.startsWith("https://", ignoreCase = true),
        findCurrent = findCurrent,
        findTotal = findTotal,
        fullScreen = fullScreen,
        showToolbarAsExpanded = showToolbarAsExpanded,
    )

    private fun normalizeUrlInput(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.equals(NEW_TAB_URL, ignoreCase = true)) return NEW_TAB_URL
        if (trimmed.contains("://") || trimmed.startsWith("about:") || trimmed.startsWith("data:") || trimmed.startsWith("javascript:")) {
            return trimmed
        }
        return "https://$trimmed"
    }
}
