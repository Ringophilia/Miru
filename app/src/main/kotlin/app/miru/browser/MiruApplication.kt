// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser

import android.app.Application
import android.app.ActivityManager
import android.os.Build
import android.os.Process
import app.miru.browser.engine.BrowserController
import app.miru.browser.engine.LibraryHistoryDelegate
import app.miru.data.BrowsingLibrary
import app.miru.data.SettingsRepository
import app.miru.data.BrowsingDataType
import app.miru.data.BrowsingDataClearResult
import app.miru.settings.ClearBrowsingDataController
import app.miru.player.PlaybackData
import kotlinx.coroutines.CancellationException
import app.miru.downloads.DownloadCoordinator
import app.miru.player.PlaybackRequests
import app.miru.player.PlaybackRequest
import app.miru.player.PlayerPreferencesRepository
import app.miru.player.PlaybackCache
import app.miru.media.DetectedMedia
import app.miru.media.MediaResolutionProbe
import app.miru.offline.OfflineCoordinator
import app.miru.common.DownloadConcurrency
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class AppContainer(application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsRepository(application)
    val library = BrowsingLibrary(application)
    val downloads = DownloadCoordinator.get(application)
    val browser = BrowserController(application, scope, LibraryHistoryDelegate(library))
    val playerPreferences = PlayerPreferencesRepository(application)
    val mediaProbe = MediaResolutionProbe(application)
    val offline = OfflineCoordinator.get(application)
    val browsingDataClearer = ClearBrowsingDataController { request ->
        val engineResult = browser.clearBrowsingData(request)
        val completed = engineResult.completed.toMutableSet()
        val failed = engineResult.failed.toMutableSet()
        for(type in request.types - app.miru.browser.engine.BrowserDataCleaner.ENGINE_TYPES) {
            try {
                when(type) {
                    BrowsingDataType.HISTORY -> library.history.clear(request.since)
                    BrowsingDataType.PLAYBACK_PROGRESS -> PlaybackData.clearProgress(application, request.since)
                    BrowsingDataType.PLAYBACK_CACHE -> withContext(Dispatchers.IO) { PlaybackCache.clear(application) }
                    else -> error("UNSUPPORTED_CLEAR_TYPE")
                }
                completed.add(type)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { failed.add(type) }
        }
        BrowsingDataClearResult(completed, failed)
    }
    // Process-only handoff guard survives browser Activity recreation; never save private URLs.
    val automaticMediaPages = mutableMapOf<String, String>()
    suspend fun offlinePlaybackRequest(id: String): PlaybackRequest? {
        val item = withTimeoutOrNull(5_000) { offline.items.first { rows -> rows.any { it.id == id } }.first { it.id == id } }
            ?.takeIf { it.status == "completed" } ?: return null
        return PlaybackRequest(item.url, item.title, pageUrl = item.sourcePage.orEmpty(), mimeType = item.mimeType,
            isPrivate = item.isPrivate, offlineId = item.id)
    }
    fun recordMediaSize(media: DetectedMedia, width: Int, height: Int) {
        scope.launch(Dispatchers.IO) {
            if(!media.isPrivate || browser.state.value.tabs.any { it.id == media.tabId && it.isPrivate }) {
                mediaProbe.record(media, width, height)
            }
        }
    }
    init {
        scope.launch { downloads.settings.map { it.maxConcurrent }.distinctUntilChanged().collect { DownloadConcurrency.transfers.setLimit(it) } }
        scope.launch { browser.state.map { state -> state.tabs.filter { it.isPrivate }.map { it.id }.toSet() }.distinctUntilChanged().collect {
            mediaProbe.clearPrivate()
        } }
        scope.launch { browser.state.collect { state ->
            automaticMediaPages.entries.removeAll { (id, key) -> state.tabs.none { it.id == id && key.startsWith("${it.url}|") } }
        } }
        scope.launch { playerPreferences.settings.map { it.webHoldSpeed to it.holdSpeed }.distinctUntilChanged().collect { (enabled, speed) ->
            browser.configureWebMedia(enabled, speed)
        } }
        // One application-scoped writer applies cache budgets in preference order, including while PiP is active.
        scope.launch { playerPreferences.settings.map { it.cacheMb }.distinctUntilChanged().collect { limit ->
            withContext(Dispatchers.IO) { runCatching { PlaybackCache.setLimit(limit * 1024L * 1024) } }
        } }
        downloads.headerResolver = { item -> if (item.isPrivate) null else browser.downloadHeaders(item.url, item.sourcePage) }
        offline.headerResolver = { item ->
            if(item.isPrivate) null else {
                val captured = browser.media.value.values.flatten().firstOrNull { !it.isPrivate && it.url == item.url && (item.sourcePage == null || it.pageUrl == item.sourcePage) }
                if(captured != null) browser.refreshMediaHeaders(captured.tabId, captured.id)
                else browser.downloadHeaders(item.url, item.sourcePage)
            }
        }
        downloads.openMedia = { item ->
            item.destUri?.let { uri ->
                application.startActivity(PlaybackRequests.intent(application, PlaybackRequest(uri, item.fileName, mimeType = item.mimeType, isPrivate = item.isPrivate)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
            } ?: false
        }
    }
}

class MiruApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Gecko's sandboxed/helper processes also instantiate Application.
        // They must not construct another browser store or recursively launch a runtime.
        val processName = if (Build.VERSION.SDK_INT >= 28) getProcessName() else {
            getSystemService(ActivityManager::class.java).runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        if (processName != packageName) return
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::container.isInitialized) container.browser.onTrimMemory(level)
    }
}
