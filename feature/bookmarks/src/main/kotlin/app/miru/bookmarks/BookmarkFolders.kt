// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.bookmarks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.miru.data.BookmarkFolder
import app.miru.data.MOBILE_BOOKMARKS_FOLDER_ID

internal fun folderPath(id: Long, folders: Map<Long, BookmarkFolder>): List<BookmarkFolder> =
    generateSequence(folders[id]) { it.parentId?.let(folders::get) }.toList().asReversed()

internal fun folderLabel(id: Long, folders: Map<Long, BookmarkFolder>, mobileFolder: String): String =
    folderPath(id, folders).joinToString(" › ") {
        if (it.id == MOBILE_BOOKMARKS_FOLDER_ID) mobileFolder else it.title
    }.ifEmpty { mobileFolder }

@Composable
internal fun BookmarkFolderRow(
    folder: BookmarkFolder,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(folder.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(Icons.Outlined.Folder, null) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.bookmarks_more, folder.title))
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bookmarks_rename_folder)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bookmarks_move_folder)) },
                        leadingIcon = { Icon(Icons.Outlined.DriveFileMove, null) },
                        onClick = { menu = false; onMove() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bookmarks_delete_folder)) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = { menu = false; onRemove() },
                    )
                }
            }
        },
        modifier = modifier.clickable(onClick = onOpen),
    )
}

@Composable
internal fun FolderNameDialog(folder: BookmarkFolder?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable(folder?.id) { mutableStateOf(folder?.title.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (folder == null) R.string.bookmarks_new_folder else R.string.bookmarks_rename_folder)) },
        text = {
            OutlinedTextField(
                name, { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.bookmarks_name)) },
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bookmarks_cancel)) } },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) {
                Text(stringResource(R.string.bookmarks_save))
            }
        },
    )
}

@Composable
internal fun ChooseBookmarkFolderDialog(
    folders: Map<Long, BookmarkFolder>,
    selectedId: Long,
    movingFolderId: Long? = null,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
) {
    val mobileFolder = stringResource(R.string.bookmarks_mobile_folder)
    val destinations = remember(folders, movingFolderId, mobileFolder) {
        folders.values.filter { folder ->
            movingFolderId == null || folderPath(folder.id, folders).none { it.id == movingFolderId }
        }.map { it.id to folderLabel(it.id, folders, mobileFolder) }.sortedBy { it.second }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmarks_choose_folder)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp).selectableGroup()) {
                items(destinations, key = { it.first }) { (id, path) ->
                    ListItem(
                        headlineContent = { Text(path) },
                        leadingContent = { RadioButton(selected = id == selectedId, onClick = null) },
                        modifier = Modifier.selectable(selected = id == selectedId, role = Role.RadioButton) { onSelect(id) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bookmarks_cancel)) } },
    )
}
