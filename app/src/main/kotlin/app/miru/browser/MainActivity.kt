package app.miru.browser

import android.app.SearchManager
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import app.miru.tabs.TabItem
import app.miru.tabs.TabsTray
import app.miru.toolbar.MiruToolbar
import app.miru.toolbar.ToolbarState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as MiruApplication).container
    private var incoming by mutableStateOf<Pair<Long, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readIntent(intent)
        setContent { MiruApp(container, incoming, onIncomingConsumed = { incoming = null }, onExit = { moveTaskToBack(true) }, onDefaultBrowser = ::requestDefaultBrowser) }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readIntent(intent) }
    override fun onStop() { container.browser.saveSession(); super.onStop() }

    private fun readIntent(value: Intent?) {
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

private enum class Route { BROWSER, HISTORY, BOOKMARKS, SETTINGS, ABOUT }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MiruApp(container: AppContainer, incoming: Pair<Long, String>?, onIncomingConsumed: () -> Unit, onExit: () -> Unit, onDefaultBrowser: () -> Unit) {
    val controller = container.browser
    val library = container.library
    val snapshot by controller.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = BrowserSettings())
    val tab = snapshot.selectedTab
    val context = LocalContext.current
    val activity = context as ComponentActivity
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
    val menuActions = setOf(MenuAction.NEW_TAB, MenuAction.NEW_PRIVATE_TAB, MenuAction.HISTORY, MenuAction.BOOKMARKS, MenuAction.SETTINGS) +
        if(webPage) {
            setOf(MenuAction.FORWARD, MenuAction.BOOKMARK, MenuAction.PAGE_INFO, MenuAction.RELOAD, MenuAction.SHARE, MenuAction.DESKTOP_SITE) +
                if(snapshot.ready) setOf(MenuAction.FIND_IN_PAGE) else emptySet()
        } else emptySet()

    fun showMessage(message: String) { scope.launch { snackbars.showMessage(message) } }
    fun navigate(value: String, newTab: Boolean = false) {
        when(val target = AddressResolver.resolve(value, settings.searchEngine)) {
            is AddressTarget.Web -> {
                if (newTab) controller.newTab(target.url, false) else controller.navigate(target.url)
                route = Route.BROWSER; editing = false; tabsVisible = false; finding = false
            }
            is AddressTarget.Internal -> when(target.route) {
                "settings" -> { route = Route.SETTINGS; editing = false }
                "history" -> { route = Route.HISTORY; editing = false }
                "bookmarks" -> { route = Route.BOOKMARKS; editing = false }
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

    LaunchedEffect(incoming) { incoming?.let { navigate(it.second, newTab = true); onIncomingConsumed() } }
    // Gecko requires the Android main thread. Let the native NTP draw before its setup runs.
    LaunchedEffect(controller) { withFrameNanos { }; withFrameNanos { }; controller.initialize() }
    LaunchedEffect(finding, findQuery, tab?.id) { if(finding) { delay(150); controller.findAll(findQuery) } else controller.clearFind() }
    DisposableEffect(isPrivate) {
        if(isPrivate) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }
    BackHandler {
        when {
            menuVisible -> menuVisible = false
            siteInfo -> siteInfo = false
            editing -> editing = false
            finding -> finding = false
            tabsVisible -> tabsVisible = false
            route == Route.ABOUT -> route = Route.SETTINGS
            route != Route.BROWSER -> route = Route.BROWSER
            controller.goBack() -> Unit
            snapshot.tabs.size > 1 && tab != null -> closeTab(tab.id)
            tab != null && tab.url != NEW_TAB_URL -> { controller.closeTab(tab.id); onExit() }
            else -> onExit()
        }
    }

    MiruTheme(themeMode = app.miru.design.ThemeMode.valueOf(settings.theme.name), dynamicColor = settings.dynamicColor, incognito = isPrivate) {
        val bottomToolbar = settings.toolbarPosition == ToolbarPosition.BOTTOM
        // Like Chrome, a bottom toolbar steps aside while the keyboard edits a web form; find-in-page keeps it.
        val imeVisible = WindowInsets.isImeVisible
        val toolbarAtBottom = bottomToolbar && !editing && (finding || !imeVisible)
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                // The browser stays composed under full-screen routes, so the GeckoView is not torn down and
                // recreated whenever the address bar is edited or settings/history/bookmarks are visited.
                Column(Modifier.fillMaxSize().then(if (route != Route.BROWSER) Modifier.clearAndSetSemantics { } else Modifier)) {
                    val toolbar: @Composable () -> Unit = {
                        Box {
                            if(finding) FindBar(findQuery, tab?.findCurrent ?: 0, tab?.findTotal ?: 0, { findQuery = it }, { controller.findNext(false) }, { controller.findNext(true) }, { finding = false })
                            else MiruToolbar(
                                state = ToolbarState(url = tab?.url.orEmpty(), title = tab?.title.orEmpty(), editing = editing, input = input, tabCount = snapshot.tabs.count { it.isPrivate == (tab?.isPrivate == true) }, loading = tab?.loading == true, progress = tab?.progress ?: 0, incognito = tab?.isPrivate == true, adblockAvailable = false, secure = tab?.secure == true),
                                onEdit = ::editAddress,
                                onTextChanged = { input = it },
                                onSubmit = { navigate(input) },
                                onCancelEdit = { editing = false },
                                onTabs = { controller.captureThumbnail(); privateGroup = tab?.isPrivate == true; tabsVisible = true },
                                onMenu = { menuVisible = true },
                                onSiteInfo = { siteInfo = true },
                                onAdblock = {},
                                progressAtTop = toolbarAtBottom,
                            )
                            Box(Modifier.align(Alignment.TopEnd)) {
                                MiruMenu(expanded = menuVisible, onDismiss = { menuVisible = false }, onAction = { action ->
                                    menuVisible = false
                                    when(action) {
                                        MenuAction.FORWARD -> controller.goForward()
                                        MenuAction.BOOKMARK -> toggleBookmark()
                                        MenuAction.PAGE_INFO -> siteInfo = true
                                        MenuAction.RELOAD -> if(tab?.loading == true) controller.stopLoading() else controller.reload()
                                        MenuAction.NEW_TAB -> { controller.newTab(); editing = false }
                                        MenuAction.NEW_PRIVATE_TAB -> { controller.newTab(isPrivate = true); editing = false }
                                        MenuAction.HISTORY -> route = Route.HISTORY
                                        MenuAction.BOOKMARKS -> route = Route.BOOKMARKS
                                        MenuAction.SHARE -> tab?.url?.takeIf { it != NEW_TAB_URL }?.let { url -> context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }, null)) }
                                        MenuAction.DESKTOP_SITE -> controller.setDesktopMode(tab?.desktopMode != true)
                                        MenuAction.FIND_IN_PAGE -> { finding = true; findQuery = "" }
                                        MenuAction.SETTINGS -> route = Route.SETTINGS
                                        else -> Unit
                                    }
                                }, enabledActions = menuActions, canGoForward = tab?.canGoForward == true, loading = tab?.loading == true, desktopMode = tab?.desktopMode == true, bookmarked = bookmarked)
                            }
                        }
                    }
                    if(!bottomToolbar || editing) toolbar()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (webPage && snapshot.ready && snapshot.error == null && tab?.crashed != true) GeckoContent(controller)
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
                    }
                    if(toolbarAtBottom) toolbar()
                }
                AnimatedContent(route, transitionSpec = { routeTransition(initialState, targetState, reducedMotion) }, label = "route") { current ->
                    when(current) {
                        Route.BROWSER -> Box(Modifier.fillMaxSize())
                        Route.HISTORY -> HistoryScreen(library, snackbars, onBack = { route = Route.BROWSER }, onOpen = { navigate(it) }, onOpenInNewTab = ::openInNewTab)
                        Route.BOOKMARKS -> BookmarksScreen(library, snackbars, onBack = { route = Route.BROWSER }, onOpen = { navigate(it) }, onOpenInNewTab = ::openInNewTab)
                        Route.SETTINGS -> SettingsScreen(settings, { route = Route.BROWSER }, { scope.launch { container.settings.setTheme(it) } }, { scope.launch { container.settings.setDynamicColor(it) } }, { scope.launch { container.settings.setToolbarPosition(it) } }, { scope.launch { container.settings.setSearchEngine(it) } }, onDefaultBrowser, { route = Route.ABOUT })
                        Route.ABOUT -> AboutScreen(BuildConfig.VERSION_NAME, { route = Route.SETTINGS }, { navigate(it, newTab = true) })
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
            if(siteInfo) ModalBottomSheet(onDismissRequest = { siteInfo = false }) {
                Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(AddressResolver.display(tab?.url.orEmpty()).ifBlank { stringResource(R.string.app_name) }, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(if(tab?.secure == true) R.string.connection_secure else R.string.connection_info), style = MaterialTheme.typography.bodyLarge)
                    Text(tab?.url.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text(stringResource(R.string.adblock_unavailable), style = MaterialTheme.typography.bodyMedium)
                }
            }
            editedBookmark?.let { bookmark ->
                BookmarkEditDialog(bookmark, onDismiss = { editedBookmark = null }) { title, url ->
                    editedBookmark = null
                    scope.launch { library.bookmarks.update(bookmark.id, title, url) }
                }
            }
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
