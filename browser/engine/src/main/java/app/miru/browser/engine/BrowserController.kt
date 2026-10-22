/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * BrowserStore/EngineMiddleware/SessionFeature wiring follows Mozilla reference-browser.
 * See docs/UPSTREAM.md for source provenance and MPL-2.0 attribution.
 *
 * Modifications Copyright (c) 2026 Ringophilia and xiaomao
 * SPDX-License-Identifier: MPL-2.0
 */
package app.miru.browser.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.RestoreCompleteAction
import mozilla.components.browser.state.action.SystemAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.action.UndoAction
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.DownloadDelegate
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.engine.history.HistoryTrackingDelegate
import mozilla.components.feature.session.SessionFeature
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.session.middleware.undo.UndoMiddleware
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.browser.engine.gecko.util.EngineDownloadDelegate
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * Application-scoped browser core. Create it in Application, then call initialize after the first native frame.
 * Gecko is initialized on Main (its API requirement); disk restoration and saving run on IO.
 */
class BrowserController(
    context: Context,
    private val applicationScope: CoroutineScope,
    private val historyDelegate: HistoryTrackingDelegate? = null,
) {
    private val applicationContext = context.applicationContext
    private val deferredEngine = DeferredEngineMiddleware()
    private val initialTab = createTab(NEW_TAB_URL)
    private val store = BrowserStore(
        initialState = BrowserState(tabs = listOf(initialTab), selectedTabId = initialTab.id),
        middleware = listOf(UndoMiddleware(clearAfterMillis = 6_000), deferredEngine),
    )
    private val tabs = TabsUseCases(store)
    private val sessions = SessionUseCases(store)
    private val cookieBridge = CookieBridge(applicationScope, store)
    val media = cookieBridge.media.state
    val mediaHints = cookieBridge.hints
    private val downloadChannel = Channel<BrowserDownloadRequest>(Channel.UNLIMITED)
    val downloadRequests = downloadChannel.receiveAsFlow()
    private val observedDownloads = mutableSetOf<String>()
    private val ublock = UblockController(applicationContext, applicationScope, store,
        onNewTab = { session, url, selected, isPrivate ->
            val id = tabs.addTab(url = url, selectTab = selected, private = isPrivate, engineSession = session)
            if (selected) tabs.selectTab(id)
        },
        onCloseTab = ::closeTab,
        onSelectTab = ::selectTab,
        onOpenUrl = { newTab(it, isPrivate = store.state.selectedTab?.content?.private == true) },
    )
    val adblock = ublock.state
    val adblockPopup = ublock.popup
    private val thumbnails = linkedMapOf<String, Bitmap>()
    private val mutableState = MutableStateFlow(snapshot(store.state))
    val state: StateFlow<BrowserSnapshot> = mutableState.asStateFlow()

    private var engine: GeckoEngine? = null
    private var runtime: GeckoRuntime? = null
    private var activeView: WeakReference<EngineView>? = null
    private var sessionStorage: SessionStorage? = null
    private var initialization: Job? = null
    private var interactedBeforeRestore = false
    private var isReady = false
    private var initializationError: String? = null
    private var automaticNewTabId: String? = null

    init {
        applicationScope.launch(Dispatchers.Main.immediate) {
            store.stateFlow.collect {
                thumbnails.keys.retainAll(it.tabs.filterNot { tab -> tab.content.private }.map { tab -> tab.id }.toSet())
                it.tabs.forEach { tab ->
                    tab.content.download?.takeIf { download -> observedDownloads.add(download.id) }?.let { download ->
                        store.dispatch(ContentAction.ConsumeDownloadAction(tab.id, download.id))
                        applicationScope.launch {
                            val headers = buildMap {
                                (download.userAgent ?: engine?.settings?.userAgentString)?.let { put("User-Agent", it) }
                                (download.referrerUrl ?: tab.content.url).takeIf { url -> url.startsWith("http://") || url.startsWith("https://") }?.let { put("Referer", it) }
                                cookieBridge.cookies(download.url, download.private)?.takeIf(String::isNotEmpty)?.let { put("Cookie", it) }
                            }
                            val isHttp = download.url.startsWith("http://") || download.url.startsWith("https://")
                            // HTTP downloads are re-requested by the Range engine using transient Gecko cookies.
                            // Blob/data responses cannot be fetched outside Gecko, so preserve their original bytes.
                            val stream = if(isHttp) { download.response?.close(); null }
                                else download.response?.let { response -> GeckoResponseStream(response, applicationScope) }
                            downloadChannel.send(BrowserDownloadRequest(download.url, download.fileName, download.contentType, download.contentLength, headers, tab.content.url, download.private, stream))
                        }
                    }
                }
                mutableState.value = snapshot(it)
            }
        }
    }

    /** Idempotent and non-blocking for the caller. Native NTP remains available during initialization. */
    fun initialize() {
        if (isReady || initialization?.isActive == true) return
        initializationError = null
        initialization = applicationScope.launch(Dispatchers.Main) {
            yield()
            try {
                val runtimeConfiguration = withContext(Dispatchers.IO) { RuntimeConfiguration.prepare(applicationContext) }
                val runtimeSettings = GeckoRuntimeSettings.Builder()
                    .configFilePath(runtimeConfiguration.absolutePath)
                    .remoteDebuggingEnabled(false)
                    .consoleOutput(false)
                    .crashHandler(null)
                    .build()
                val existingRuntime = runtime ?: GeckoRuntime.create(applicationContext, runtimeSettings).also { runtime = it }
                val createdEngine = engine ?: GeckoEngine(
                    applicationContext,
                    defaultSettings = DefaultSettings(
                        remoteDebuggingEnabled = false,
                        javaScriptCanOpenWindowsAutomatically = false,
                        mediaPlaybackRequiresUserGesture = true,
                        trackingProtectionPolicy = EngineSession.TrackingProtectionPolicy.recommended(),
                        historyTrackingDelegate = historyDelegate,
                        downloadDelegate = EngineDownloadDelegate(applicationContext) { android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path + "/Miru" },
                    ),
                    runtime = existingRuntime,
                ).also { engine = it }
                // No telemetry SDK, crash uploader, Firefox account, experiments, or sync is initialized.
                createdEngine.notifyTelemetryPrefChanged(false)
                engine = createdEngine
                // Install only Miru's own extension with ensureBuiltIn. The signed uBO XPI uses install(file://).
                ublock.start(createdEngine)
                cookieBridge.start(createdEngine)
                val storage = SessionStorage(applicationContext, createdEngine, applicationScope = applicationScope)
                sessionStorage = storage
                val restored = withContext(Dispatchers.IO) {
                    try {
                        storage.restore { !it.state.private }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        // A corrupt/unreadable session must not disable the whole browser or create another runtime.
                        Log.e("MiruEngine", "Unable to restore previous session; starting with native NTP", failure)
                        null
                    }
                }
                if (restored != null && restored.tabs.isNotEmpty()) {
                    if (!interactedBeforeRestore) {
                        store.dispatch(TabListAction.RemoveAllTabsAction(recoverable = false))
                        tabs.restore(restored)
                    } else {
                        // Do not replace a URL the user typed while disk restoration was running.
                        tabs.restore(restored.tabs, selectTabId = null, restoreLocation = TabListAction.RestoreAction.RestoreLocation.BEGINNING)
                    }
                }
                store.dispatch(RestoreCompleteAction)
                // NTP stays interactive while restoring. Page commands remain queued until ready, at most 1.5 s.
                ublock.awaitStartup()
                deferredEngine.connect(EngineMiddleware.create(createdEngine, applicationScope), store)
                isReady = true
                mutableState.value = snapshot(store.state)
                // SessionStorage serializes Gecko engineSessionState, including the navigation stack,
                // and its writer deliberately omits all private tabs.
                storage.autoSave(store)
                    .whenSessionsChange(applicationScope)
                    .whenGoingToBackground()
                    .periodicallyInForeground()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e("MiruEngine", "Unable to initialize browser engine", failure)
                initializationError = failure.javaClass.simpleName
                mutableState.value = snapshot(store.state)
            }
        }
    }

    fun newTab(url: String = NEW_TAB_URL, isPrivate: Boolean = false): String {
        exitWebFullScreen()
        interactedBeforeRestore = true
        automaticNewTabId = null
        return tabs.addTab(url = url, private = isPrivate, startLoading = url != NEW_TAB_URL)
    }

    fun navigate(url: String) {
        exitWebFullScreen()
        interactedBeforeRestore = true
        automaticNewTabId = null
        if (store.state.selectedTab == null) {
            newTab(url)
        } else if (url == NEW_TAB_URL) {
            // Native NTP never navigates Gecko to about:newtab.
            store.dispatch(ContentAction.UpdateUrlAction(store.state.selectedTabId!!, NEW_TAB_URL))
        } else {
            // Main.immediate middleware may create/link a session inline. Publish its destination first,
            // otherwise the linking step loads about:newtab and adds a spurious entry to the back stack.
            store.state.selectedTabId?.let { store.dispatch(ContentAction.UpdateUrlAction(it, url)) }
            sessions.loadUrl(url)
        }
    }

    fun selectTab(id: String) {
        if(id != store.state.selectedTabId) exitWebFullScreen()
        interactedBeforeRestore = true
        automaticNewTabId = null
        tabs.selectTab(id)
    }

    fun closeTab(id: String) {
        if(id == store.state.selectedTabId) exitWebFullScreen()
        interactedBeforeRestore = true
        tabs.removeTab(id)
        ensureTab()
    }

    fun closeAllTabs(isPrivate: Boolean = false) {
        interactedBeforeRestore = true
        if (isPrivate) tabs.removePrivateTabs() else tabs.removeNormalTabs()
        ensureTab()
    }

    private fun ensureTab() {
        if (store.state.tabs.isEmpty()) {
            automaticNewTabId = tabs.addTab(url = NEW_TAB_URL, startLoading = false)
        }
    }

    fun undoClose() {
        val undo = store.state.undoHistory
        if (undo.tabs.isEmpty()) return
        // Restore synchronously, so removing the temporary NTP cannot race UndoMiddleware's async use case.
        tabs.restore(undo.tabs, undo.selectedTabId, TabListAction.RestoreAction.RestoreLocation.AT_INDEX)
        automaticNewTabId?.let { id ->
            if (store.state.tabs.any { it.id == id }) tabs.removeTab(id)
        }
        undo.selectedTabId?.let(tabs.selectTab::invoke)
        store.dispatch(UndoAction.ClearRecoverableTabs(store.state.undoHistory.tag))
        automaticNewTabId = null
    }

    fun goBack(): Boolean {
        if (store.state.selectedTab?.content?.canGoBack != true) return false
        sessions.goBack()
        return true
    }

    fun goForward() = sessions.goForward()
    fun exitWebFullScreen(): Boolean {
        val tab = store.state.selectedTab?.takeIf { it.content.fullScreen } ?: return false
        sessions.exitFullscreen(tab.id)
        return true
    }
    fun configureWebMedia(holdEnabled: Boolean, holdSpeed: Float) = cookieBridge.configureWebMedia(holdEnabled, holdSpeed)
    fun reload() {
        val selected = store.state.selectedTab
        if (selected?.engineState?.crashed == true) sessions.crashRecovery.invoke(listOf(selected.id))
        else sessions.reload()
    }
    fun stopLoading() = sessions.stopLoading()
    fun setDesktopMode(enabled: Boolean) = sessions.requestDesktopSite(enabled)
    fun setAdblockEnabled(enabled: Boolean) { ublock.setEnabled(enabled, ::reload) }
    fun retryAdblock() = ublock.retry()
    fun openAdblockPopup(): Boolean = ublock.openPopup()
    fun closeAdblockPopup() = ublock.closePopup()
    fun openAdblockDashboard(): Boolean = ublock.openDashboard()
    fun openAdblockExceptions(): Boolean = ublock.openExceptions()
    fun openAdblockLogger(): Boolean = ublock.openLogger()

    suspend fun prepareMedia(tabId: String, mediaId: String): PreparedMedia? {
        val item = cookieBridge.media.find(tabId, mediaId)?.takeIf { it.playable } ?: return null
        val result = cookieBridge.prepare(item) ?: return null
        if(result.optBoolean("drm")) return null
        if(store.state.tabs.firstOrNull { it.id == tabId }?.content?.url != item.pageUrl) return null
        // Do not persist headers in Intent extras, Room, SavedState or logs.
        val headers = freshMediaHeaders(item)
        val position = result.optDouble("position", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0
        return PreparedMedia(item, headers, (position * 1000).toLong())
    }

    suspend fun refreshMediaHeaders(tabId: String, mediaId: String): Map<String, String>? {
        val item = cookieBridge.media.find(tabId, mediaId) ?: return null
        return freshMediaHeaders(item)
    }

    private suspend fun freshMediaHeaders(item: app.miru.media.DetectedMedia): Map<String, String> {
        val headers = item.headers.toMutableMap()
        cookieBridge.cookies(item.url, item.isPrivate)?.let { cookie ->
            headers.keys.filter { it.equals("cookie", true) }.toList().forEach(headers::remove)
            if(cookie.isNotBlank()) headers["Cookie"] = cookie
        }
        if(headers.keys.none { it.equals("user-agent", true) }) engine?.settings?.userAgentString?.let { headers["User-Agent"] = it }
        if(headers.keys.none { it.equals("referer", true) }) headers["Referer"] = item.pageUrl
        return headers
    }

    fun restoreWebMedia(tabId: String) = cookieBridge.restore(tabId)

    suspend fun headersForMediaUrl(url: String, isPrivate: Boolean): Map<String, String> = withContext(Dispatchers.Main.immediate) {
        buildMap {
            engine?.settings?.userAgentString?.let { put("User-Agent", it) }
            cookieBridge.cookies(url, isPrivate)?.takeIf(String::isNotBlank)?.let { put("Cookie", it) }
        }
    }

    /** Used for process-restart continuation of ordinary downloads. Never reads the private jar implicitly. */
    suspend fun downloadHeaders(url: String, sourcePage: String? = null): Map<String, String>? = withContext(Dispatchers.Main.immediate) {
        if (!state.value.ready) {
            val initialized = withTimeoutOrNull(15_000) { state.first { it.ready || it.error != null } }
            if (initialized?.ready != true) return@withContext null
        }
        val cookie = cookieBridge.cookies(url, false) ?: return@withContext null
        buildMap {
            engine?.settings?.userAgentString?.let { put("User-Agent", it) }
            sourcePage?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { put("Referer", it) }
            if(cookie.isNotEmpty()) put("Cookie", cookie)
        }
    }

    fun downloadCurrentPage() {
        val tab = store.state.selectedTab ?: return
        val url = tab.content.url.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return
        applicationScope.launch {
            val headers = buildMap {
                engine?.settings?.userAgentString?.let { put("User-Agent", it) }
                put("Referer", url)
                cookieBridge.cookies(url, tab.content.private)?.takeIf(String::isNotEmpty)?.let { put("Cookie", it) }
            }
            downloadChannel.send(BrowserDownloadRequest(url, null, null, null, headers, url, tab.content.private))
        }
    }

    fun findAll(query: String) {
        store.state.selectedTab?.let { tab ->
            store.dispatch(ContentAction.ClearFindResultsAction(tab.id))
            tab.engineState.engineSession?.findAll(query)
        }
    }

    fun findNext(forward: Boolean = true) {
        store.state.selectedTab?.engineState?.engineSession?.findNext(forward)
    }

    fun clearFind() {
        store.state.selectedTab?.let { tab ->
            tab.engineState.engineSession?.clearFindMatches()
            store.dispatch(ContentAction.ClearFindResultsAction(tab.id))
        }
    }

    /** Regular-tab screenshots are bounded in memory. Private tabs are never captured or written to disk. */
    fun captureThumbnail() { activeView?.get()?.let(::captureThumbnail) }

    fun captureThumbnail(view: EngineView) {
        val tab = store.state.selectedTab ?: return
        if (tab.content.url == NEW_TAB_URL || tab.content.private) return
        val id = tab.id
        view.captureThumbnail { bitmap ->
            if (bitmap != null) applicationScope.launch(Dispatchers.Main) {
                if (store.state.tabs.none { it.id == id && !it.content.private }) return@launch
                val width = minOf(bitmap.width, 360)
                val height = (bitmap.height.toLong() * width / bitmap.width).toInt().coerceAtLeast(1)
                thumbnails[id] = Bitmap.createScaledBitmap(bitmap, width, height, true)
                while (thumbnails.size > 12) thumbnails.remove(thumbnails.keys.first())
                mutableState.value = snapshot(store.state)
            }
        }
    }

    fun getThumbnail(id: String): Bitmap? = thumbnails[id]

    fun saveSession() {
        if (!isReady) return
        val snapshot = store.state
        applicationScope.launch(Dispatchers.IO) { sessionStorage?.save(snapshot) }
    }

    fun onTrimMemory(level: Int) {
        store.dispatch(SystemAction.LowMemoryAction(level))
    }

    fun createView(context: Context): EngineView = checkNotNull(engine) { "Gecko is not ready" }.createView(context)

    fun bind(view: EngineView): EngineViewBinding {
        activeView = WeakReference(view)
        return EngineViewBinding(SessionFeature(store, sessions.goBack, sessions.goForward, view), view)
    }

    private fun snapshot(state: BrowserState): BrowserSnapshot = BrowserSnapshot(
        tabs = state.tabs.map { tab ->
            BrowserTab(
                id = tab.id,
                url = tab.content.url,
                title = tab.content.title,
                isPrivate = tab.content.private,
                loading = tab.content.loading,
                progress = tab.content.progress,
                canGoBack = tab.content.canGoBack,
                canGoForward = tab.content.canGoForward,
                desktopMode = tab.content.desktopMode,
                secure = tab.content.securityInfo.isSecure,
                crashed = tab.engineState.crashed,
                thumbnail = if (tab.content.private) null else thumbnails[tab.id],
                findCurrent = tab.content.findResults.lastOrNull()?.let { if (it.numberOfMatches > 0) it.activeMatchOrdinal + 1 else 0 } ?: 0,
                findTotal = tab.content.findResults.lastOrNull()?.numberOfMatches ?: 0,
                fullScreen = tab.content.fullScreen,
            )
        },
        selectedTabId = state.selectedTabId,
        ready = isReady,
        error = initializationError,
    )
}
