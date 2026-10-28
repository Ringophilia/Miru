// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser

import android.app.SearchManager
import android.Manifest
import android.content.pm.PackageManager
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.miru.bookmarks.BookmarkEditDialog
import app.miru.bookmarks.BookmarksScreen
import app.miru.browser.engine.BrowserController
import app.miru.browser.engine.BrowserTab
import app.miru.browser.engine.NEW_TAB_URL
import app.miru.adblock.AdblockPhase
import app.miru.adblock.AdblockUiState
import app.miru.adblock.AdblockScreen
import app.miru.adblock.AdblockSheet
import app.miru.adblock.AdblockPopupSheet
import app.miru.media.MediaSheet
import app.miru.media.DetectedMedia
import app.miru.player.PlaybackRequests
import app.miru.player.PlaybackRequest
import app.miru.player.PlayerSettingsScreen
import app.miru.downloads.DownloadConfirmSheet
import app.miru.downloads.DownloadRequest
import app.miru.downloads.DownloadsScreen
import app.miru.downloads.DownloadSettingsScreen
import app.miru.downloads.OfflineConfirmSheet
import app.miru.offline.OfflineSelection
import app.miru.offline.OfflineSource
import app.miru.common.AddressResolver
import app.miru.common.AddressTarget
import app.miru.common.SearchEngine
import app.miru.data.Bookmark
import app.miru.data.BrowserSettings
import app.miru.data.BrowsingLibrary
import app.miru.data.HistoryEntry
import app.miru.data.ToolbarPosition
import app.miru.design.MiruMotion
import app.miru.design.MiruTheme
import app.miru.design.animationsReduced
import app.miru.design.showMessage
import app.miru.design.showUndo
import app.miru.design.showWithAction
import app.miru.history.HistoryScreen
import app.miru.menu.MenuAction
import app.miru.menu.MiruMenu
import app.miru.ntp.NewTabPage
import app.miru.settings.AboutScreen
import app.miru.settings.SettingsScreen
import app.miru.settings.ClearBrowsingDataScreen
import app.miru.tabs.TabItem
import app.miru.tabs.TabsTray
import app.miru.toolbar.MiruToolbar
import app.miru.toolbar.ToolbarState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.EngineSession

class MainActivity : ComponentActivity() {
    private val container get() = (application as MiruApplication).container
    private var incoming by mutableStateOf<Pair<Long, String>?>(null)
    private var incomingOffline by mutableStateOf<String?>(null)
    internal var sitePermissionSettingsRequired by mutableStateOf(false)
        private set
    private val sitePermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val relevant = container.browser.permissions.onRuntimePermissionsResult(results)
        val locationGranted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if(relevant) sitePermissionSettingsRequired = results.any { (permission, granted) ->
            val location = permission == Manifest.permission.ACCESS_COARSE_LOCATION || permission == Manifest.permission.ACCESS_FINE_LOCATION
            !granted && !(location && locationGranted) && !shouldShowRequestPermissionRationale(permission)
        }
    }

    internal fun requestSiteRuntimePermissions(token: Long, permissions: List<String>) {
        if(container.browser.permissions.markRuntimeRequestLaunched(token)) sitePermissionsLauncher.launch(permissions.toTypedArray())
    }

    internal fun dismissSitePermissionSettings() { sitePermissionSettingsRequired = false }

    internal fun openSitePermissionSettings() {
        sitePermissionSettingsRequired = false
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        container.browser.permissions.attachPermissionHost(this)
        container.browser.uploads.attachHost(this, UploadPickerActivity::class.java)
        enableEdgeToEdge()
        if (savedInstanceState == null) readIntent(intent)
        incomingOffline = savedInstanceState?.getString("miru.pendingOffline") ?: incomingOffline
        setContent { MiruApp(container, incoming, incomingOffline,
            onIncomingOfflineConsumed = { incomingOffline = null; intent.removeExtra("miru.offline.id") },
            onIncomingConsumed = { incoming = null }, onExit = { moveTaskToBack(true) }, onDefaultBrowser = ::requestDefaultBrowser) }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readIntent(intent) }
    override fun onStart() { super.onStart(); container.downloads.onAppForeground(); container.offline.onAppForeground() }
    override fun onResume() { super.onResume(); container.browser.permissions.refreshSite() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        container.browser.permissions.onHostWindowFocusChanged(this, hasFocus)
    }
    override fun onDestroy() {
        container.browser.permissions.detachPermissionHost(this)
        container.browser.uploads.detachHost(this)
        super.onDestroy()
    }
    override fun onStop() { container.browser.saveSession(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        incomingOffline?.let { outState.putString("miru.pendingOffline", it) }
        super.onSaveInstanceState(outState)
    }

    private fun readIntent(value: Intent?) {
        value?.getStringExtra("miru.offline.id")?.takeIf { it.length == 36 && it.matches(Regex("[0-9a-fA-F-]{36}")) }?.let { incomingOffline = it }
        val input = when(value?.action) {
            Intent.ACTION_VIEW -> value.dataString
            Intent.ACTION_SEND -> value.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_WEB_SEARCH -> value.getStringExtra(SearchManager.QUERY)
            else -> null
        }
        if (!input.isNullOrBlank()) incoming = System.nanoTime() to input
    }

    private fun requestDefaultBrowser() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val role = getSystemService(RoleManager::class.java)
            if (role.isRoleAvailable(RoleManager.ROLE_BROWSER) && !role.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                startActivity(role.createRequestRoleIntent(RoleManager.ROLE_BROWSER)); return
            }
        }
        startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }
}

private enum class Route { BROWSER, HISTORY, BOOKMARKS, DOWNLOADS, SETTINGS, DOWNLOAD_SETTINGS, PLAYER_SETTINGS, CLEAR_DATA, ADBLOCK, ABOUT }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MiruApp(container: AppContainer, incoming: Pair<Long, String>?, incomingOffline: String?, onIncomingOfflineConsumed: () -> Unit, onIncomingConsumed: () -> Unit, onExit: () -> Unit, onDefaultBrowser: () -> Unit) {
    val controller = container.browser
    val library = container.library
    val downloads = container.downloads
    val snapshot by controller.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = BrowserSettings())
    val downloadSettings by downloads.settings.collectAsStateWithLifecycle()
    val downloadItems by downloads.downloads.collectAsStateWithLifecycle()
    val offlineItems by container.offline.items.collectAsStateWithLifecycle()
    val adblock by controller.adblock.collectAsStateWithLifecycle()
    val adblockPopup by controller.adblockPopup.collectAsStateWithLifecycle()
    val mediaByTab by controller.media.collectAsStateWithLifecycle()
    val contextMenu by controller.contextMenus.state.collectAsStateWithLifecycle()
    val sitePermissionPrompt by controller.permissions.prompt.collectAsStateWithLifecycle()
    val runtimePermissionRequest by controller.permissions.runtimeRequest.collectAsStateWithLifecycle()
    val sitePermissions by controller.permissions.siteState.collectAsStateWithLifecycle()
    val pagePrompt by controller.prompts.prompt.collectAsStateWithLifecycle()
    val playerOptions by container.playerPreferences.settings.collectAsStateWithLifecycle(initialValue = app.miru.player.PlayerPreferences())
    val tab = snapshot.selectedTab
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val permissionActivity = activity as MainActivity
    LaunchedEffect(runtimePermissionRequest?.token) {
        runtimePermissionRequest?.takeIf { !it.launched }?.let { permissionActivity.requestSiteRuntimePermissions(it.token, it.permissions) }
    }
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    var route by rememberSaveable { mutableStateOf(Route.BROWSER) }
    var tabsVisible by rememberSaveable { mutableStateOf(false) }
    var privateGroup by rememberSaveable { mutableStateOf(false) }
    var menuVisible by remember { mutableStateOf(false) }
    // Editing text can contain a private address; keep it out of Android's saved-state bundle.
    var editing by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var siteInfo by remember { mutableStateOf(false) }
    LaunchedEffect(siteInfo, tab?.id) {
        if(siteInfo) controller.permissions.refreshSite()
    }
    var adblockPanel by remember { mutableStateOf(false) }
    var mediaPanel by remember { mutableStateOf(false) }
    var mediaBusy by remember { mutableStateOf(false) }
    var mediaHint by remember { mutableStateOf(false) }
    var mediaAddress by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var offlineSelection by remember { mutableStateOf<OfflineSelection?>(null) }
    var offlineOwner by remember { mutableStateOf<Pair<String, String>?>(null) }
    var offlineBusy by remember { mutableStateOf(false) }
    var offlineGeneration by remember { mutableIntStateOf(0) }
    var offlinePreparation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val hintedPages = remember { mutableMapOf<String, String>() }
    val automaticPages = container.automaticMediaPages
    val pageMedia = mediaByTab[tab?.id].orEmpty()
    val webFullScreen = tab?.fullScreen == true && route == Route.BROWSER && !tabsVisible && !editing
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var downloadQueue by remember { mutableStateOf(emptyList<DownloadRequest>()) }
    val pendingDownload = downloadQueue.firstOrNull()
    var editedBookmark by remember { mutableStateOf<Bookmark?>(null) }
    val isPrivate = if (tabsVisible) privateGroup else tab?.isPrivate == true
    val webPage = tab != null && tab.url != NEW_TAB_URL
    val pageUrl = tab?.url.orEmpty()
    val bookmarked by remember(pageUrl, webPage) { if (webPage) library.bookmarks.isBookmarked(pageUrl) else flowOf(false) }.collectAsState(initial = false)
    val reducedMotion = animationsReduced()
    val unsupported = stringResource(R.string.unsupported_address)
    val unavailableRoute = stringResource(R.string.unavailable_route)
    val closedMessage = stringResource(R.string.closed_tab)
    val closedNamedMessage = stringResource(R.string.closed_tab_named)
    val closedAllMessage = stringResource(R.string.closed_all_tabs)
    val bookmarkAdded = stringResource(R.string.bookmark_added)
    val bookmarkRemoved = stringResource(R.string.bookmark_removed)
    val editLabel = stringResource(R.string.edit)
    val undoLabel = stringResource(R.string.undo)
    val downloadStarted = stringResource(R.string.download_started)
    val viewLabel = stringResource(R.string.view_downloads)
    val downloadFailed = stringResource(R.string.download_failed)
    val mediaFailed = stringResource(R.string.media_handoff_failed)
    val offlineFailed = stringResource(R.string.offline_failed)
    val offlineUnavailable = stringResource(R.string.offline_unavailable)
    val activeDownloads = downloadItems.count { it.status in setOf("queued", "running", "waiting_network") } +
        offlineItems.count { it.status in setOf("queued", "running", "waiting_network") }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val adblockUi = AdblockUiState(
        phase = if(adblock.status.name == "STARTING") AdblockPhase.INSTALLING else AdblockPhase.valueOf(adblock.status.name),
        version = adblock.version.orEmpty(),
        pageNeedsReload = adblock.pageNeedsReload,
    )
    val menuActions = setOf(MenuAction.NEW_TAB, MenuAction.NEW_PRIVATE_TAB, MenuAction.HISTORY, MenuAction.BOOKMARKS, MenuAction.DOWNLOADS, MenuAction.ADBLOCK, MenuAction.SETTINGS) +
        if(webPage) {
            setOf(MenuAction.FORWARD, MenuAction.BOOKMARK, MenuAction.DOWNLOAD, MenuAction.PAGE_INFO, MenuAction.RELOAD, MenuAction.SHARE, MenuAction.DESKTOP_SITE) +
                if(snapshot.ready) setOf(MenuAction.FIND_IN_PAGE) else emptySet()
        } else emptySet()

    fun showMessage(message: String) { scope.launch { snackbars.showMessage(message) } }
    fun playMedia(item: DetectedMedia) {
        if(mediaBusy) return
        mediaBusy = true
        scope.launch {
            try {
                val prepared = controller.prepareMedia(item.tabId, item.id)
                if(prepared == null) { showMessage(mediaFailed); return@launch }
                val selected = controller.state.value.selectedTab
                if(selected?.id != item.tabId || selected.url != item.pageUrl || route != Route.BROWSER || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    controller.restoreWebMedia(item.tabId)
                    return@launch
                }
                controller.exitWebFullScreen()
                val request = PlaybackRequest(item.url, item.title, item.pageUrl,
                    mimeType = when(item.kind) { "HLS" -> "application/x-mpegURL"; "DASH" -> "application/dash+xml"; else -> item.mimeType },
                    headers = prepared.headers, positionMs = prepared.positionMs, fromWeb = true, isPrivate = item.isPrivate,
                    refreshHeaders = { controller.refreshMediaHeaders(item.tabId, item.id) },
                    onReturn = { failed ->
                        val key = "${item.pageUrl}|${item.id}"
                        if(failed) {
                            if(controller.state.value.tabs.any { it.id == item.tabId && it.url == item.pageUrl }) automaticPages[item.tabId] = key
                            controller.restoreWebMedia(item.tabId)
                        } else automaticPages.remove(item.tabId, key)
                    },
                    onVideoSize = { width, height -> container.recordMediaSize(item, width, height) })
                mediaPanel = false; mediaHint = false
                context.startActivity(PlaybackRequests.intent(context, request))
            } catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch(_: Exception) { controller.restoreWebMedia(item.tabId); showMessage(mediaFailed) }
            finally { mediaBusy = false }
        }
    }
    LaunchedEffect(controller) { controller.mediaHints.collect { (id, fullscreen) ->
        val selected = controller.state.value.selectedTab ?: return@collect
        if(id == selected.id && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && (fullscreen || hintedPages[id] != selected.url)) {
            hintedPages[id] = selected.url
            mediaHint = true; kotlinx.coroutines.delay(if(fullscreen) 3_000 else 5_000); mediaHint = false
        }
    } }
    LaunchedEffect(pageMedia, playerOptions.autoTakeover, route, tab?.loading, mediaBusy, editing, tabsVisible, mediaPanel, menuVisible, pendingDownload != null, mediaAddress != null, offlineBusy, offlineSelection != null) {
        val selected = tab ?: return@LaunchedEffect
        if(playerOptions.autoTakeover && route == Route.BROWSER && !mediaBusy && !editing && !tabsVisible && !mediaPanel && !menuVisible && pendingDownload == null && mediaAddress == null && !offlineBusy && offlineSelection == null && !selected.loading &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pageMedia.firstOrNull { it.canAutoTakeover && automaticPages[selected.id] != "${selected.url}|${it.id}" }?.let { item ->
                automaticPages[selected.id] = "${selected.url}|${item.id}"; playMedia(item)
            }
        }
    }
    LaunchedEffect(snapshot.tabs.map { it.id }) {
        val ids = snapshot.tabs.map { it.id }.toSet()
        hintedPages.keys.retainAll(ids); automaticPages.keys.retainAll(ids)
    }
    LaunchedEffect(tab?.id, tab?.url, tab?.loading) {
        if(tab?.loading == true) { hintedPages.remove(tab.id); automaticPages.remove(tab.id) }
    }
    DisposableEffect(activity, webFullScreen) {
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if(webFullScreen) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else bars.show(WindowInsetsCompat.Type.systemBars())
        onDispose { bars.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(tab?.id, tab?.url) { mediaPanel = false; mediaHint = false }
    fun navigate(value: String, newTab: Boolean = false) {
        when(val target = AddressResolver.resolve(value, settings.searchEngine)) {
            is AddressTarget.Web -> {
                val path = android.net.Uri.parse(target.url).path.orEmpty().lowercase()
                if(listOf(".m3u8", ".mp4", ".mpd").any(path::endsWith)) {
                    mediaAddress = target.url to newTab; editing = false
                    return
                }
                if (newTab) controller.newTab(target.url, false) else controller.navigate(target.url)
                route = Route.BROWSER; editing = false; tabsVisible = false; finding = false
            }
            is AddressTarget.Internal -> when(target.route) {
                "settings" -> { route = Route.SETTINGS; editing = false }
                "history" -> { route = Route.HISTORY; editing = false }
                "bookmarks" -> { route = Route.BOOKMARKS; editing = false }
                "downloads" -> { route = Route.DOWNLOADS; editing = false }
                "adblock" -> { route = Route.ADBLOCK; editing = false }
                "newtab" -> { controller.newTab(isPrivate = tab?.isPrivate == true); editing = false }
                "tabs" -> { controller.captureThumbnail(); privateGroup = tab?.isPrivate == true; tabsVisible = true; editing = false }
                else -> showMessage(unavailableRoute)
            }
            AddressTarget.Empty -> Unit
            AddressTarget.Unsupported -> showMessage(unsupported)
        }
    }
    fun openInNewTab(url: String, private: Boolean) {
        controller.newTab(url, private)
        route = Route.BROWSER; tabsVisible = false
    }
    fun editAddress() { input = if(tab?.url == NEW_TAB_URL) "" else tab?.url.orEmpty(); editing = true }
    fun closeTab(id: String) {
        val title = snapshot.tabs.firstOrNull { it.id == id }?.title.orEmpty().trim()
        controller.closeTab(id)
        val message = if (title.isEmpty()) closedMessage else closedNamedMessage.format(if (title.length > 24) title.take(23) + "…" else title)
        scope.launch { if (snackbars.showUndo(message, undoLabel)) controller.undoClose() }
    }
    fun toggleBookmark() {
        val page = tab?.takeIf { it.url != NEW_TAB_URL } ?: return
        scope.launch {
            val existing = library.bookmarks.find(page.url)
            if (existing != null) {
                library.bookmarks.remove(page.url)
                if (snackbars.showUndo(bookmarkRemoved, undoLabel)) library.bookmarks.restore(existing)
            } else {
                library.bookmarks.add(page.url, page.title.ifBlank { AddressResolver.display(page.url) })
                if (snackbars.showWithAction(bookmarkAdded, editLabel)) editedBookmark = library.bookmarks.find(page.url)
            }
        }
    }
    fun enqueueDownload(request: DownloadRequest) {
        try {
            downloads.enqueue(request)
            if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            scope.launch { if (snackbars.showWithAction(downloadStarted, viewLabel)) route = Route.DOWNLOADS }
        } catch (failure: Exception) { runCatching { request.bodyStream?.close() }; showMessage(downloadFailed) }
    }
    fun requestDownload(request: DownloadRequest) {
        if (downloadSettings.askBeforeDownload) {
            if(downloadQueue.size < 16) downloadQueue = downloadQueue + request
            else { runCatching { request.bodyStream?.close() }; showMessage(downloadFailed) }
        } else enqueueDownload(request)
    }
    fun cancelOfflinePreparation() {
        offlineGeneration++
        offlinePreparation?.cancel(); offlinePreparation = null
        offlineBusy = false; offlineSelection = null; offlineOwner = null
    }
    fun openOffline(id: String) {
        scope.launch {
            val request = container.offlinePlaybackRequest(id)
            if(request == null) showMessage(offlineUnavailable)
            else if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) context.startActivity(PlaybackRequests.intent(context, request))
        }
    }
    fun downloadMedia(item: DetectedMedia) {
        if(!item.playable || offlineBusy || offlineSelection != null) return
        mediaPanel = false
        val generation = ++offlineGeneration
        offlineBusy = true; offlineOwner = item.tabId to item.pageUrl
        offlinePreparation = scope.launch {
            try {
                val headers = controller.refreshMediaHeaders(item.tabId, item.id) ?: item.headers
                val selected = controller.state.value.selectedTab
                if(selected?.id != item.tabId || selected.url != item.pageUrl || route != Route.BROWSER) return@launch
                if(item.kind !in setOf("HLS", "DASH")) {
                    // Direct files keep the existing segmented download / MediaStore path.
                    requestDownload(DownloadRequest(item.url, fileName = item.title.takeIf(String::isNotBlank), mimeType = item.mimeType,
                        headers = headers, sourcePage = item.pageUrl, isPrivate = item.isPrivate))
                } else {
                    val selection = container.offline.prepare(OfflineSource(item.url, item.title, item.pageUrl,
                        mimeType = if(item.kind == "HLS") "application/vnd.apple.mpegurl" else "application/dash+xml",
                        headers = headers, isPrivate = item.isPrivate, drm = item.drm))
                    val current = controller.state.value.selectedTab
                    if(generation == offlineGeneration && current?.id == item.tabId && current.url == item.pageUrl && route == Route.BROWSER && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        offlineSelection = selection
                    }
                }
            } catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch(_: Exception) { if(generation == offlineGeneration) showMessage(offlineFailed) }
            finally { if(generation == offlineGeneration) { offlineBusy = false; offlinePreparation = null } }
        }
    }
    LaunchedEffect(tab?.id, tab?.url, route) {
        val owner = offlineOwner
        if(owner != null && (owner.first != tab?.id || owner.second != tab?.url || route != Route.BROWSER)) cancelOfflinePreparation()
    }
    fun openAdblockDashboard(exceptions: Boolean = false) {
        adblockPanel = false
        controller.closeAdblockPopup()
        if (if (exceptions) controller.openAdblockExceptions() else controller.openAdblockDashboard()) route = Route.BROWSER
    }
    val handleDownload by rememberUpdatedState<(DownloadRequest) -> Unit>(::requestDownload)

    LaunchedEffect(incoming) { incoming?.let { navigate(it.second, newTab = true); onIncomingConsumed() } }
    LaunchedEffect(incomingOffline) { incomingOffline?.let { id ->
        val request = container.offlinePlaybackRequest(id)
        if(request == null) showMessage(offlineUnavailable)
        else if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) context.startActivity(PlaybackRequests.intent(context, request))
        onIncomingOfflineConsumed()
    } }
    // Gecko requires the Android main thread. Let the native NTP draw before its setup runs.
    LaunchedEffect(controller) { withFrameNanos { }; withFrameNanos { }; controller.initialize() }
    LaunchedEffect(controller) { controller.downloadRequests.collect { request ->
        handleDownload(DownloadRequest(url = request.url, fileName = request.fileName, mimeType = request.mimeType, contentLength = request.contentLength ?: -1, headers = request.headers, sourcePage = request.sourcePage, isPrivate = request.isPrivate, bodyStream = request.bodyStream))
    } }
    val uploadMessages by rememberUpdatedState(mapOf(
        app.miru.browser.engine.BrowserUploadError.UNAVAILABLE to stringResource(R.string.upload_unavailable),
        app.miru.browser.engine.BrowserUploadError.PERMISSION_DENIED to stringResource(R.string.upload_permission_denied),
        app.miru.browser.engine.BrowserUploadError.TOO_MANY_FILES to stringResource(R.string.upload_too_many),
        app.miru.browser.engine.BrowserUploadError.BUSY to stringResource(R.string.upload_busy),
        app.miru.browser.engine.BrowserUploadError.UNREADABLE to stringResource(R.string.upload_unreadable),
        app.miru.browser.engine.BrowserUploadError.FOLDER_UNAVAILABLE to stringResource(R.string.upload_folder_unavailable),
    ))
    LaunchedEffect(controller) { controller.uploads.errors.collect { error ->
        snackbars.showMessage(uploadMessages.getValue(error))
    } }
    DisposableEffect(Unit) { onDispose { downloadQueue.forEach { runCatching { it.bodyStream?.close() } } } }
    LaunchedEffect(finding, findQuery, tab?.id) { if(finding) { delay(150); controller.findAll(findQuery) } else controller.clearFind() }
    val privateDownloadDialog = offlineSelection?.source?.isPrivate == true || pendingDownload?.isPrivate == true
    DisposableEffect(isPrivate, privateDownloadDialog) {
        if(isPrivate || privateDownloadDialog) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }
    BackHandler {
        when {
            sitePermissionPrompt != null -> controller.permissions.dismiss(sitePermissionPrompt!!.token)
            pagePrompt != null -> controller.prompts.dismiss(pagePrompt!!.token)
            webFullScreen -> controller.exitWebFullScreen()
            menuVisible -> menuVisible = false
            adblockPopup != null -> { controller.closeAdblockPopup(); controller.reload() }
            adblockPanel -> adblockPanel = false
            offlineBusy || offlineSelection != null -> cancelOfflinePreparation()
            pendingDownload != null -> { runCatching { pendingDownload.bodyStream?.close() }; downloadQueue = downloadQueue.drop(1) }
            siteInfo -> siteInfo = false
            editing -> editing = false
            finding -> finding = false
            tabsVisible -> tabsVisible = false
            route in setOf(Route.ABOUT, Route.DOWNLOAD_SETTINGS, Route.PLAYER_SETTINGS, Route.CLEAR_DATA, Route.ADBLOCK) -> route = Route.SETTINGS
            route != Route.BROWSER -> route = Route.BROWSER
            controller.goBack() -> Unit
            snapshot.tabs.size > 1 && tab != null -> closeTab(tab.id)
            tab != null && tab.url != NEW_TAB_URL -> { controller.closeTab(tab.id); onExit() }
            else -> onExit()
        }
    }

    MiruTheme(themeMode = if(webFullScreen) app.miru.design.ThemeMode.DARK else app.miru.design.ThemeMode.valueOf(settings.theme.name), dynamicColor = settings.dynamicColor, incognito = isPrivate) {
        val bottomToolbar = settings.toolbarPosition == ToolbarPosition.BOTTOM
        // Like Chrome, a bottom toolbar steps aside while the keyboard edits a web form; find-in-page keeps it.
        val imeVisible = WindowInsets.isImeVisible
        val toolbarAtBottom = bottomToolbar && !editing && (finding || !imeVisible)
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().then(if(webFullScreen) Modifier else Modifier.safeDrawingPadding().imePadding())) {
                // The browser stays composed under full-screen routes, so the GeckoView is not torn down and
                // recreated whenever the address bar is edited or settings/history/bookmarks are visited.
                Box(Modifier.fillMaxSize().then(if (route != Route.BROWSER) Modifier.clearAndSetSemantics { } else Modifier)) {
                    val toolbar: @Composable () -> Unit = {
                        Box {
                            if(finding) FindBar(findQuery, tab?.findCurrent ?: 0, tab?.findTotal ?: 0, { findQuery = it }, { controller.findNext(false) }, { controller.findNext(true) }, { finding = false })
                            else MiruToolbar(
                                state = ToolbarState(url = tab?.url.orEmpty(), title = tab?.title.orEmpty(), editing = editing, input = input, tabCount = snapshot.tabs.count { it.isPrivate == (tab?.isPrivate == true) }, loading = tab?.loading == true, progress = tab?.progress ?: 0, incognito = tab?.isPrivate == true, adblockAvailable = adblock.installed, blockedCount = adblock.blockedCount, secure = tab?.secure == true, downloading = activeDownloads > 0, adblockNeedsReload = adblock.pageNeedsReload, mediaCount = if(webPage) pageMedia.size else 0),
                                onEdit = ::editAddress,
                                onTextChanged = { input = it },
                                onSubmit = { navigate(input) },
                                onCancelEdit = { editing = false },
                                onTabs = { controller.captureThumbnail(); privateGroup = tab?.isPrivate == true; tabsVisible = true },
                                onMenu = { menuVisible = true },
                                onSiteInfo = { siteInfo = true },
                                onAdblock = { adblockPanel = true },
                                progressAtTop = toolbarAtBottom,
                                onMedia = { mediaPanel = true },
                            )
                            Box(Modifier.align(Alignment.TopEnd)) {
                                MiruMenu(expanded = menuVisible, onDismiss = { menuVisible = false }, onAction = { action ->
                                    menuVisible = false
                                    when(action) {
                                        MenuAction.FORWARD -> controller.goForward()
                                        MenuAction.BOOKMARK -> toggleBookmark()
                                        MenuAction.DOWNLOAD -> controller.downloadCurrentPage()
                                        MenuAction.PAGE_INFO -> siteInfo = true
                                        MenuAction.RELOAD -> if(tab?.loading == true) controller.stopLoading() else controller.reload()
                                        MenuAction.NEW_TAB -> { controller.newTab(); editing = false }
                                        MenuAction.NEW_PRIVATE_TAB -> { controller.newTab(isPrivate = true); editing = false }
                                        MenuAction.HISTORY -> route = Route.HISTORY
                                        MenuAction.BOOKMARKS -> route = Route.BOOKMARKS
                                        MenuAction.DOWNLOADS -> route = Route.DOWNLOADS
                                        MenuAction.ADBLOCK -> if(webPage) { adblockPanel = true } else { route = Route.ADBLOCK }
                                        MenuAction.SHARE -> tab?.url?.takeIf { it != NEW_TAB_URL }?.let { url -> context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }, null)) }
                                        MenuAction.DESKTOP_SITE -> controller.setDesktopMode(tab?.desktopMode != true)
                                        MenuAction.FIND_IN_PAGE -> { finding = true; findQuery = "" }
                                        MenuAction.SETTINGS -> route = Route.SETTINGS
                                        else -> Unit
                                    }
                                }, enabledActions = menuActions, canGoForward = tab?.canGoForward == true, loading = tab?.loading == true, desktopMode = tab?.desktopMode == true, bookmarked = bookmarked, activeDownloads = activeDownloads, blockedCount = adblock.blockedCount)
                            }
                        }
                    }
                    BrowserViewport(controller = controller,
                        webContent = webPage && snapshot.error == null && tab?.crashed != true,
                        toolbarAtBottom = toolbarAtBottom,
                        toolbarVisible = !webFullScreen && (!bottomToolbar || editing || toolbarAtBottom),
                        forceToolbarVisible = editing || finding || menuVisible || tabsVisible || route != Route.BROWSER ||
                            siteInfo || adblockPanel || mediaPanel || contextMenu != null || pagePrompt != null,
                        refreshEnabled = !webFullScreen && route == Route.BROWSER && !tabsVisible && !editing,
                        reducedMotion = reducedMotion, toolbar = toolbar) {
                        when {
                            tab == null || tab.url == NEW_TAB_URL -> NewTabPage(incognito = tab?.isPrivate == true, onSearch = ::editAddress, onOpenUrl = { navigate(it) }, snackbar = snackbars)
                            snapshot.error != null -> ErrorContent(stringResource(R.string.engine_error), { controller.initialize() })
                            !snapshot.ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            tab.crashed -> ErrorContent(stringResource(R.string.page_crashed), controller::reload)
                        }
                        if (editing) Surface(Modifier.fillMaxSize()) {
                            Suggestions(
                                input = input,
                                engine = settings.searchEngine,
                                library = library,
                                tabs = snapshot.tabs.filter { it.url != NEW_TAB_URL && it.id != tab?.id && it.isPrivate == (tab?.isPrivate == true) },
                                onNavigate = { navigate(it) },
                                onSwitchTab = { controller.selectTab(it); editing = false },
                                onFill = { input = it },
                            )
                        }
                        if(mediaHint && pageMedia.any { it.playable } && !editing && route == Route.BROWSER) FilledTonalButton(
                            onClick = { mediaPanel = true; mediaHint = false }, modifier = Modifier.align(if(webFullScreen) Alignment.TopEnd else Alignment.BottomCenter).then(if(webFullScreen) Modifier.safeDrawingPadding() else Modifier).padding(16.dp)
                        ) { Text(stringResource(app.miru.media.R.string.media_play)) }
                    }
                }
                AnimatedContent(route, transitionSpec = { routeTransition(initialState, targetState, reducedMotion) }, label = "route") { current ->
                    when(current) {
                        Route.BROWSER -> Box(Modifier.fillMaxSize())
                        Route.HISTORY -> HistoryScreen(library, snackbars, onBack = { route = Route.BROWSER }, onOpen = { navigate(it) }, onOpenInNewTab = ::openInNewTab)
                        Route.BOOKMARKS -> BookmarksScreen(library, snackbars, onBack = { route = Route.BROWSER }, onOpen = { navigate(it) }, onOpenInNewTab = ::openInNewTab)
                        Route.DOWNLOADS -> DownloadsScreen(downloads, onBack = { route = Route.BROWSER }, snackbarHostState = snackbars,
                            offline = container.offline, onOpenOffline = ::openOffline)
                        Route.DOWNLOAD_SETTINGS -> DownloadSettingsScreen(downloads, onBack = { route = Route.SETTINGS })
                        Route.ADBLOCK -> AdblockScreen(adblockUi, { route = Route.SETTINGS }, controller::setAdblockEnabled, controller::retryAdblock, { openAdblockDashboard(true) }, { openAdblockDashboard() })
                        Route.SETTINGS -> SettingsScreen(settings, { route = Route.BROWSER }, { scope.launch { container.settings.setTheme(it) } }, { scope.launch { container.settings.setDynamicColor(it) } }, { scope.launch { container.settings.setToolbarPosition(it) } }, { scope.launch { container.settings.setSearchEngine(it) } }, onDefaultBrowser, { route = Route.ABOUT }, { route = Route.ADBLOCK }, { route = Route.DOWNLOAD_SETTINGS }, onPlayer = { route = Route.PLAYER_SETTINGS }, onClearBrowsingData = { route = Route.CLEAR_DATA })
                        Route.CLEAR_DATA -> ClearBrowsingDataScreen(container.browsingDataClearer, onBack = { route = Route.SETTINGS })
                        Route.PLAYER_SETTINGS -> PlayerSettingsScreen(container.playerPreferences, snackbars, onBack = { route = Route.SETTINGS })
                        Route.ABOUT -> AboutScreen(BuildConfig.VERSION_NAME, { route = Route.SETTINGS }, { navigate(it, newTab = true) }, adblockVersion = adblock.version.takeIf { adblock.installed })
                    }
                }
                if(tabsVisible) {
                    TabsTray(tabs = snapshot.tabs.map { TabItem(it.id, it.title, it.url, it.isPrivate, thumbnail = it.thumbnail) }, selectedTabId = snapshot.selectedTabId, incognito = privateGroup, onIncognitoChanged = { privateGroup = it }, onSelect = { controller.selectTab(it); tabsVisible = false }, onClose = ::closeTab, onNewTab = { controller.newTab(isPrivate = privateGroup); tabsVisible = false }, onCloseAll = {
                        controller.closeAllTabs(privateGroup)
                        scope.launch { if (snackbars.showUndo(closedAllMessage, undoLabel)) controller.undoClose() }
                    }, onDismiss = { tabsVisible = false })
                }
                // Spec 6.6: floating 8 dp above a bottom toolbar, otherwise 8 dp above the screen edge.
                val overToolbar = route == Route.BROWSER && !tabsVisible && toolbarAtBottom
                SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).padding(bottom = if (overToolbar) 56.dp else 0.dp).padding(8.dp))
            }
            if(route == Route.BROWSER && !tabsVisible && !editing) contextMenu?.let { owner ->
                BrowserContextMenuSheet(owner, onDismiss = { controller.contextMenus.dismiss(owner.token) },
                    onOpenTab = ::openInNewTab,
                    onDownload = { request, url ->
                        val media = controller.media.value[request.tabId].orEmpty().firstOrNull { it.url == url && it.pageUrl == request.pageUrl && it.playable }
                        if(media != null) downloadMedia(media) else scope.launch {
                            val headers = controller.contextHeaders(request, url)
                            if(headers == null || !url.startsWith("http", true)) showMessage(downloadFailed)
                            else requestDownload(DownloadRequest(url, headers = headers, sourcePage = request.pageUrl, isPrivate = request.isPrivate))
                        }
                    },
                    onPlayVideo = { request ->
                        val media = controller.media.value[request.tabId].orEmpty().firstOrNull { it.url == request.videoUrl && it.pageUrl == request.pageUrl && it.playable }
                        if(media != null) playMedia(media) else scope.launch {
                            val url = request.videoUrl.orEmpty()
                            val headers = controller.contextHeaders(request, url)
                            if(headers == null || !(url.startsWith("https://") || url.startsWith("http://"))) showMessage(mediaFailed)
                            else context.startActivity(PlaybackRequests.intent(context, PlaybackRequest(url, request.title.orEmpty(), pageUrl = request.pageUrl,
                                headers = headers, isPrivate = request.isPrivate)))
                        }
                    }, loadImage = { loadContextImage(controller, it) }, isCurrent = controller.contextMenus::isCurrent, onMessage = ::showMessage)
            }
            mediaAddress?.let { (url, newTab) -> AlertDialog(
                onDismissRequest = { mediaAddress = null }, title = { Text(stringResource(R.string.media_open_question)) },
                confirmButton = { TextButton(onClick = {
                    mediaAddress = null
                    scope.launch {
                        val private = !newTab && tab?.isPrivate == true
                        val headers = controller.headersForMediaUrl(url, private)
                        context.startActivity(PlaybackRequests.intent(context, PlaybackRequest(url, android.net.Uri.parse(url).lastPathSegment.orEmpty(), headers = headers, isPrivate = private)))
                    }
                }) { Text(stringResource(app.miru.media.R.string.media_play)) } },
                dismissButton = { TextButton(onClick = { mediaAddress = null; if(newTab) controller.newTab(url) else controller.navigate(url); route = Route.BROWSER; tabsVisible = false }) { Text(stringResource(R.string.media_open_web)) } },
            ) }
            if(mediaPanel) MediaSheet(pageMedia, onDismiss = { mediaPanel = false }, onPlay = ::playMedia,
                resolve = { item -> container.mediaProbe.probe(item)?.let { "${it.label} · ${it.width} × ${it.height}" } }, onDownload = ::downloadMedia)
            if(offlineBusy) AlertDialog(onDismissRequest = ::cancelOfflinePreparation,
                title = { Text(stringResource(R.string.offline_preparing)) },
                text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.offline_preparing_detail))
                } },
                confirmButton = { TextButton(onClick = ::cancelOfflinePreparation) { Text(stringResource(android.R.string.cancel)) } })
            offlineSelection?.let { selection -> OfflineConfirmSheet(selection, onDismiss = ::cancelOfflinePreparation,
                onConfirm = { quality, title ->
                    try {
                        container.offline.enqueue(selection, quality, title)
                        cancelOfflinePreparation()
                        if(Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        scope.launch { if(snackbars.showWithAction(downloadStarted, viewLabel)) route = Route.DOWNLOADS }
                    } catch(_: Exception) { cancelOfflinePreparation(); showMessage(offlineFailed) }
                }) }
            if(siteInfo && sitePermissionPrompt == null && pagePrompt == null) ModalBottomSheet(onDismissRequest = { siteInfo = false }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(AddressResolver.display(tab?.url.orEmpty()).ifBlank { stringResource(R.string.app_name) }, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(if(tab?.secure == true) R.string.connection_secure else R.string.connection_info), style = MaterialTheme.typography.bodyLarge)
                    Text(tab?.url.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    sitePermissions?.takeIf { it.tabId == tab?.id }?.let { state ->
                        HorizontalDivider()
                        SitePermissionControls(state,
                            onChange = { kind, choice -> controller.permissions.setForSite(state.token, kind, choice) },
                            onReset = { controller.permissions.resetSite(state.token) },
                            onSystemSettings = permissionActivity::openSitePermissionSettings)
                    }
                    HorizontalDivider()
                    TextButton(onClick = { siteInfo = false; adblockPanel = true }) { Text(stringResource(app.miru.adblock.R.string.title)) }
                }
            }
            sitePermissionPrompt?.let { prompt ->
                SitePermissionSheet(prompt, onDismiss = { controller.permissions.dismiss(prompt.token) },
                    onAnswer = { allow, remember ->
                        android.util.Log.d("MiruPermissionsUi", "answer allow=$allow hostFocused=${activity.hasWindowFocus()}")
                        controller.permissions.answer(prompt.token, allow, remember)
                    })
            }
            if(sitePermissionPrompt == null) pagePrompt?.let { prompt ->
                key(prompt.token) { BrowserPromptDialog(prompt, controller.prompts, controller.uploads) }
            }
            if(permissionActivity.sitePermissionSettingsRequired && sitePermissionPrompt == null && pagePrompt == null) AlertDialog(
                onDismissRequest = permissionActivity::dismissSitePermissionSettings,
                title = { Text(stringResource(R.string.site_permission_settings_title)) },
                text = { Text(stringResource(R.string.site_permission_settings_detail)) },
                confirmButton = { TextButton(onClick = permissionActivity::openSitePermissionSettings) { Text(stringResource(R.string.site_permission_settings)) } },
                dismissButton = { TextButton(onClick = permissionActivity::dismissSitePermissionSettings) { Text(stringResource(android.R.string.cancel)) } },
            )
            editedBookmark?.let { bookmark ->
                BookmarkEditDialog(bookmark, onDismiss = { editedBookmark = null }) { title, url ->
                    editedBookmark = null
                    scope.launch { library.bookmarks.update(bookmark.id, title, url) }
                }
            }
            if (adblockPanel && adblockPopup == null) AdblockSheet(
                host = AddressResolver.display(tab?.url.orEmpty()), count = adblock.blockedCount, state = adblockUi,
                onDismiss = { adblockPanel = false }, onRetry = controller::retryAdblock,
                onOpenSiteControls = { if(controller.openAdblockPopup()) adblockPanel = false },
                onOpenDashboard = { openAdblockDashboard() },
                onReload = { adblockPanel = false; controller.reload() },
            )
            adblockPopup?.let { popup ->
                AdblockPopupSheet(AddressResolver.display(tab?.url.orEmpty()), adblock.blockedCount, { controller.closeAdblockPopup(); controller.reload() }, { openAdblockDashboard() }, pageNeedsReload = adblock.pageNeedsReload) {
                    AdblockPopupContent(controller, popup)
                }
            }
            pendingDownload?.let { request -> DownloadConfirmSheet(request, onDismiss = { runCatching { request.bodyStream?.close() }; downloadQueue = downloadQueue.drop(1) }, onConfirm = { downloadQueue = downloadQueue.drop(1); enqueueDownload(it) }) }
        }
    }
}

/** Spec 7.3 shared axis X for full-screen routes; a cross-fade when system animations are off. */
private fun routeTransition(from: Route, to: Route, reduced: Boolean): ContentTransform {
    if (reduced) return fadeIn(tween(MiruMotion.short2)) togetherWith fadeOut(tween(MiruMotion.short2))
    val forward = to.ordinal > from.ordinal
    val enter = fadeIn(tween(MiruMotion.medium2, easing = MiruMotion.emphasizedDecelerate)) +
        slideInHorizontally(tween(MiruMotion.medium2, easing = MiruMotion.emphasizedDecelerate)) { (if (forward) 1 else -1) * it / 12 }
    val exit = fadeOut(tween(MiruMotion.short3, easing = MiruMotion.emphasizedAccelerate)) +
        slideOutHorizontally(tween(MiruMotion.short3, easing = MiruMotion.emphasizedAccelerate)) { (if (forward) -1 else 1) * it / 12 }
    return enter togetherWith exit
}

@Composable private fun AdblockPopupContent(controller: BrowserController, session: EngineSession) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember(controller, session) { controller.createView(context).also { it.render(session) } }
    DisposableEffect(view, lifecycle) {
        var started = false
        fun start() { if(!started) { view.onStart(); view.onResume(); started = true } }
        fun stop() { if(started) { view.onPause(); view.onStop(); started = false } }
        val observer = LifecycleEventObserver { _, event -> when(event) { Lifecycle.Event.ON_START -> start(); Lifecycle.Event.ON_STOP -> stop(); else -> Unit } }
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    AndroidView(factory = { view.asView() }, modifier = Modifier.fillMaxSize())
}

@Composable private fun Suggestions(
    input: String,
    engine: SearchEngine,
    library: BrowsingLibrary,
    tabs: List<BrowserTab>,
    onNavigate: (String) -> Unit,
    onSwitchTab: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    if (input.isBlank()) return
    val bookmarks by produceState(emptyList<Bookmark>(), input) { value = library.bookmarks.search(input, limit = 3) }
    val history by produceState(emptyList<HistoryEntry>(), input) { value = library.history.suggest(input, limit = 4) }
    val target = remember(input, engine) { AddressResolver.resolve(input, engine) }
    val isSearch = target is AddressTarget.Web && target.url == engine.search(input.trim())
    val fillLabel = stringResource(R.string.fill_address)
    Column(Modifier.verticalScroll(rememberScrollState())) {
        ListItem(
            headlineContent = { Text(if (target is AddressTarget.Web && !isSearch) target.url else input, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(stringResource(if (isSearch) R.string.suggest_search else R.string.suggest_open)) },
            leadingContent = { Icon(if (isSearch) Icons.Rounded.Search else Icons.Outlined.Public, null) },
            modifier = Modifier.clickable { onNavigate(input) },
        )
        // Spec 5.3 order: history, bookmarks, then open tabs. A bookmarked page is listed once, as a bookmark.
        val bookmarkUrls = bookmarks.map { it.url }.toSet()
        history.filter { it.url !in bookmarkUrls }.take(3).forEach { entry ->
            SuggestionRow(entry.title, entry.url, Icons.Outlined.History, fillLabel, { onNavigate(entry.url) }, { onFill(entry.url) })
        }
        bookmarks.forEach { bookmark ->
            SuggestionRow(bookmark.title, bookmark.url, Icons.Outlined.StarBorder, fillLabel, { onNavigate(bookmark.url) }, { onFill(bookmark.url) })
        }
        tabs.filter { it.url.contains(input, true) || it.title.contains(input, true) }.take(5).forEach { suggestion ->
            ListItem(headlineContent = { Text(suggestion.title.ifBlank { AddressResolver.display(suggestion.url) }, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(stringResource(R.string.switch_tab)) }, leadingContent = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) }, modifier = Modifier.clickable { onSwitchTab(suggestion.id) })
        }
    }
}

@Composable private fun SuggestionRow(title: String, url: String, icon: ImageVector, fillLabel: String, onClick: () -> Unit, onFill: () -> Unit) {
    ListItem(
        headlineContent = { Text(title.ifBlank { AddressResolver.display(url) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(url.removePrefix("https://").removePrefix("http://"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { IconButton(onClick = onFill) { Icon(Icons.Rounded.NorthWest, fillLabel) } },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable private fun FindBar(query: String, current: Int, total: Int, onQuery: (String) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val hint = stringResource(R.string.find_in_page)
    // Opening find-in-page from the menu should bring up the keyboard immediately.
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = hint },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onNext() }),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                inner()
                            }
                        },
                    )
                    if (query.isNotEmpty()) {
                        Text(stringResource(R.string.find_count, current, total), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconButton(onClick = onPrevious, enabled = total > 0) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.previous_match)) }
            IconButton(onClick = onNext, enabled = total > 0) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.next_match)) }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.close_find)) }
        }
    }
}

@Composable private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message); Spacer(Modifier.height(16.dp)); Button(onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable private fun GeckoContent(controller: BrowserController) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember(controller) { controller.createView(context) }
    DisposableEffect(controller, lifecycle, view) {
        val binding = controller.bind(view)
        val observer = LifecycleEventObserver { _, event ->
            when(event) { Lifecycle.Event.ON_START -> binding.start(); Lifecycle.Event.ON_STOP -> binding.stop(); else -> Unit }
        }
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) binding.start()
        onDispose { lifecycle.removeObserver(observer); binding.release() }
    }
    AndroidView(factory = { view.asView() }, modifier = Modifier.fillMaxSize())
}
