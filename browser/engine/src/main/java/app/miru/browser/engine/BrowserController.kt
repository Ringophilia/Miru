/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * BrowserStore/EngineMiddleware/SessionFeature wiring follows Mozilla reference-browser.
 * See docs/UPSTREAM.md for source provenance and MPL-2.0 attribution.
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.state.action.ContentAction
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
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.engine.history.HistoryTrackingDelegate
import mozilla.components.feature.session.SessionFeature
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.session.middleware.undo.UndoMiddleware
import mozilla.components.feature.tabs.TabsUseCases
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
                val runtimeSettings = GeckoRuntimeSettings.Builder()
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
                    ),
                    runtime = existingRuntime,
                ).also { engine = it }
                // No telemetry SDK, crash uploader, Firefox account, experiments, or sync is initialized.
                createdEngine.notifyTelemetryPrefChanged(false)
                engine = createdEngine
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
        interactedBeforeRestore = true
        automaticNewTabId = null
        return tabs.addTab(url = url, private = isPrivate, startLoading = url != NEW_TAB_URL)
    }

    fun navigate(url: String) {
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
        interactedBeforeRestore = true
        automaticNewTabId = null
        tabs.selectTab(id)
    }

    fun closeTab(id: String) {
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
    fun reload() {
        val selected = store.state.selectedTab
        if (selected?.engineState?.crashed == true) sessions.crashRecovery.invoke(listOf(selected.id))
        else sessions.reload()
    }
    fun stopLoading() = sessions.stopLoading()
    fun setDesktopMode(enabled: Boolean) = sessions.requestDesktopSite(enabled)

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
            )
        },
        selectedTabId = state.selectedTabId,
        ready = isReady,
        error = initializationError,
    )
}
