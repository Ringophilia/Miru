// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.bookmarks

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.miru.common.AddressResolver
import app.miru.common.AddressTarget
import app.miru.data.Bookmark
import app.miru.data.BrowsingLibrary
import app.miru.design.MiruSearchField
import app.miru.design.SiteIcon
import app.miru.design.showUndo
import app.miru.design.siteHost
import kotlinx.coroutines.launch

/** Spec 5.10 / B8: a flat, searchable list (folders are P1). */
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
    var bookmarks by remember { mutableStateOf<List<Bookmark>?>(null) }
    LaunchedEffect(query) { library.bookmarks.observe(query).collect { bookmarks = it } }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    val scope = rememberCoroutineScope()
    val deletedMessage = stringResource(R.string.bookmarks_deleted)
    val undo = stringResource(R.string.bookmarks_undo)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bookmarks_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.bookmarks_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            MiruSearchField(query, { query = it }, stringResource(R.string.bookmarks_search), stringResource(R.string.bookmarks_clear_search), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            val list = bookmarks
            when {
                list == null -> Unit
                list.isEmpty() -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Outlined.StarBorder, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(if (query.isBlank()) R.string.bookmarks_empty else R.string.bookmarks_no_results),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(list, key = { it.id }) { bookmark ->
                        BookmarkRow(
                            bookmark,
                            onOpen = { onOpen(bookmark.url) },
                            onOpenInNewTab = onOpenInNewTab,
                            onEdit = { editing = bookmark },
                            onDelete = {
                                scope.launch {
                                    library.bookmarks.remove(bookmark.url)
                                    if (snackbar.showUndo(deletedMessage, undo)) library.bookmarks.restore(bookmark)
                                }
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
    editing?.let { bookmark ->
        BookmarkEditDialog(bookmark, onDismiss = { editing = null }) { title, url ->
            editing = null
            scope.launch { library.bookmarks.update(bookmark.id, title, url) }
        }
    }
}

@Composable
private fun BookmarkRow(bookmark: Bookmark, onOpen: () -> Unit, onOpenInNewTab: (String, Boolean) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val title = bookmark.title.ifBlank { siteHost(bookmark.url) }
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(siteHost(bookmark.url), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
fun BookmarkEditDialog(bookmark: Bookmark, onDismiss: () -> Unit, onSave: (title: String, url: String) -> Unit) {
    var title by remember(bookmark.id) { mutableStateOf(bookmark.title) }
    var url by remember(bookmark.id) { mutableStateOf(bookmark.url) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmarks_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bookmarks_cancel)) } },
        confirmButton = {
            TextButton(enabled = url.isNotBlank(), onClick = {
                val normalized = normalizeUrl(url)
                if (normalized == null) invalid = true else onSave(title.trim().ifBlank { siteHost(normalized) }, normalized)
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
