// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.bookmarks

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.miru.common.AddressResolver
import app.miru.common.AddressTarget
import app.miru.data.Bookmark
import app.miru.data.BookmarkFolder
import app.miru.data.BrowsingLibrary
import app.miru.data.MOBILE_BOOKMARKS_FOLDER_ID
import app.miru.design.MiruMotion
import app.miru.design.MiruSearchField
import app.miru.design.SiteIcon
import app.miru.design.animationsReduced
import app.miru.design.showUndo
import app.miru.design.siteHost
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    library: BrowsingLibrary,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (url: String, private: Boolean) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedFolderId by rememberSaveable { mutableLongStateOf(MOBILE_BOOKMARKS_FOLDER_ID) }
    val folders by remember(library) { library.bookmarks.observeFolders() }.collectAsState(emptyList())
    val foldersById = remember(folders) { folders.associateBy { it.id } }
    val current = foldersById[selectedFolderId]
    val currentId = current?.id ?: MOBILE_BOOKMARKS_FOLDER_ID
    val parentId = current?.parentId
    val searching = query.isNotBlank()
    val children = if (searching) emptyList() else folders.filter { it.parentId == currentId }
    val queryFolder = currentId.takeUnless { searching }
    val bookmarks by produceState<List<Bookmark>?>(null, library, queryFolder, query) {
        value = null
        library.bookmarks.observe(query, queryFolder).collect { value = it }
    }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var namingFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<BookmarkFolder?>(null) }
    var moving by remember { mutableStateOf<BookmarkFolder?>(null) }
    var removing by remember { mutableStateOf<BookmarkFolder?>(null) }
    val scope = rememberCoroutineScope()
    val reducedMotion = animationsReduced()
    val fadeSpec = tween<Float>(if (reducedMotion) MiruMotion.short2 else MiruMotion.medium1, easing = MiruMotion.standard)
    val placeSpec = if (reducedMotion) null else tween<IntOffset>(MiruMotion.medium1, easing = MiruMotion.standard)
    val focus = LocalFocusManager.current
    val deletedMessage = stringResource(R.string.bookmarks_deleted)
    val undo = stringResource(R.string.bookmarks_undo)
    val mobileFolder = stringResource(R.string.bookmarks_mobile_folder)
    val goBack = {
        focus.clearFocus()
        when {
            searching -> query = ""
            parentId != null -> selectedFolderId = parentId
            else -> onBack()
        }
    }
    BackHandler(enabled = searching || parentId != null, onBack = goBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.bookmarks_title))
                        if (!searching) Text(
                            current?.title.orEmpty().ifEmpty { mobileFolder },
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = goBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.bookmarks_back))
                    }
                },
                actions = {
                    if (!searching) IconButton(onClick = { renaming = null; namingFolder = true }) {
                        Icon(Icons.Outlined.CreateNewFolder, stringResource(R.string.bookmarks_new_folder))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            MiruSearchField(query, { query = it }, stringResource(R.string.bookmarks_search), stringResource(R.string.bookmarks_clear_search), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            val list = bookmarks
            when {
                list == null -> Unit
                list.isEmpty() && children.isEmpty() -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Outlined.StarBorder, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(when {
                            searching -> R.string.bookmarks_no_results
                            currentId == MOBILE_BOOKMARKS_FOLDER_ID -> R.string.bookmarks_empty
                            else -> R.string.bookmarks_empty_folder
                        }),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(children, key = { "folder-${it.id}" }) { folder ->
                        BookmarkFolderRow(
                            folder,
                            onOpen = { selectedFolderId = folder.id; focus.clearFocus() },
                            onRename = { renaming = folder; namingFolder = true },
                            onMove = { moving = folder },
                            onRemove = { removing = folder },
                            modifier = Modifier.animateItem(fadeInSpec = fadeSpec, placementSpec = placeSpec, fadeOutSpec = fadeSpec),
                        )
                    }
                    items(list, key = { "bookmark-${it.id}" }) { bookmark ->
                        BookmarkRow(
                            bookmark,
                            folder = if (searching) folderLabel(bookmark.folderId, foldersById, mobileFolder) else null,
                            onOpen = { onOpen(bookmark.url) },
                            onOpenInNewTab = onOpenInNewTab,
                            onEdit = { editing = bookmark },
                            onDelete = {
                                scope.launch {
                                    library.bookmarks.remove(bookmark.url)
                                    if (snackbar.showUndo(deletedMessage, undo)) library.bookmarks.restore(bookmark)
                                }
                            },
                            modifier = Modifier.animateItem(fadeInSpec = fadeSpec, placementSpec = placeSpec, fadeOutSpec = fadeSpec),
                        )
                    }
                }
            }
        }
    }
    editing?.let { bookmark ->
        BookmarkEditDialog(bookmark, library, onDismiss = { editing = null }) { title, url, folderId ->
            editing = null
            scope.launch { library.bookmarks.update(bookmark.id, title, url, folderId) }
        }
    }
    if (namingFolder) {
        val folder = renaming
        FolderNameDialog(folder, onDismiss = { namingFolder = false }) { name ->
            namingFolder = false
            scope.launch {
                if (folder == null) library.bookmarks.createFolder(name, currentId)
                else library.bookmarks.renameFolder(folder.id, name)
            }
        }
    }
    moving?.let { folder ->
        ChooseBookmarkFolderDialog(foldersById, folder.parentId ?: MOBILE_BOOKMARKS_FOLDER_ID, folder.id,
            onDismiss = { moving = null },
        ) { destination ->
            moving = null
            scope.launch { library.bookmarks.moveFolder(folder.id, destination) }
        }
    }
    removing?.let { folder ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.bookmarks_delete_folder)) },
            text = { Text(stringResource(R.string.bookmarks_delete_folder_detail, folder.title)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch { library.bookmarks.removeFolder(folder.id) }
                }) { Text(stringResource(R.string.bookmarks_delete)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.bookmarks_cancel)) } },
        )
    }
}

@Composable
private fun BookmarkRow(bookmark: Bookmark, folder: String?, onOpen: () -> Unit, onOpenInNewTab: (String, Boolean) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val title = bookmark.title.ifBlank { siteHost(bookmark.url) }
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(siteHost(bookmark.url), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (folder != null) Text(folder, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        leadingContent = { SiteIcon(bookmark.url, bookmark.title) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.bookmarks_more, title)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_open_new_tab)) }, leadingIcon = { Icon(Icons.Outlined.Add, null) }, onClick = { menu = false; onOpenInNewTab(bookmark.url, false) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_open_private)) }, leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) }, onClick = { menu = false; onOpenInNewTab(bookmark.url, true) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_edit)) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_copy)) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = {
                        menu = false
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(title, bookmark.url))
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks_delete)) }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        },
        modifier = modifier.clickable(onClick = onOpen),
    )
}

/** Also used by the browser after starring a page ("Edit" on the confirmation snackbar). */
@Composable
fun BookmarkEditDialog(bookmark: Bookmark, library: BrowsingLibrary, onDismiss: () -> Unit, onSave: (title: String, url: String, folderId: Long) -> Unit) {
    var title by rememberSaveable(bookmark.id) { mutableStateOf(bookmark.title) }
    var url by rememberSaveable(bookmark.id) { mutableStateOf(bookmark.url) }
    var folderId by rememberSaveable(bookmark.id) { mutableLongStateOf(bookmark.folderId) }
    var choosingFolder by rememberSaveable { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    val folders by remember(library) { library.bookmarks.observeFolders() }.collectAsState(emptyList())
    val foldersById = remember(folders) { folders.associateBy { it.id } }
    val mobileFolder = stringResource(R.string.bookmarks_mobile_folder)
    if (choosingFolder) {
        ChooseBookmarkFolderDialog(foldersById, folderId, onDismiss = { choosingFolder = false }) {
            folderId = it
            choosingFolder = false
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmarks_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.bookmarks_name)) }, singleLine = true)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; invalid = false },
                    label = { Text(stringResource(R.string.bookmarks_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = invalid,
                    supportingText = if (invalid) { { Text(stringResource(R.string.bookmarks_invalid_url)) } } else null,
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.bookmarks_folder)) },
                    supportingContent = { Text(folderLabel(folderId, foldersById, mobileFolder), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(Icons.Outlined.Folder, null) },
                    modifier = Modifier.clickable(enabled = folders.isNotEmpty()) { choosingFolder = true },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bookmarks_cancel)) } },
        confirmButton = {
            TextButton(enabled = url.isNotBlank(), onClick = {
                val normalized = normalizeUrl(url)
                if (normalized == null) invalid = true else onSave(title.trim().ifBlank { siteHost(normalized) }, normalized, folderId)
            }) { Text(stringResource(R.string.bookmarks_save)) }
        },
    )
}

/** An address, never a search: text that would become a search query is rejected. */
private fun normalizeUrl(value: String): String? {
    val input = value.trim()
    val target = AddressResolver.resolve(input) as? AddressTarget.Web ?: return null
    return target.url.takeIf { it != app.miru.common.SearchEngine.GOOGLE.search(input) }
}
