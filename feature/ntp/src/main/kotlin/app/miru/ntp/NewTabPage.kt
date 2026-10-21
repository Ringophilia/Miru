package app.miru.ntp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import java.util.UUID
import app.miru.design.SiteIcon
import app.miru.design.showMessage
import app.miru.design.showUndo
import kotlinx.coroutines.launch

@Composable
fun NewTabPage(
    incognito: Boolean,
    onSearch: () -> Unit,
    onOpenUrl: (String) -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context.applicationContext) { ShortcutsRepository(context.applicationContext) }
    val shortcuts by repository.shortcuts.collectAsState(initial = repository.defaults)
    val scope = rememberCoroutineScope()
    var editorVisible by remember { mutableStateOf(false) }
    var editedShortcut by remember { mutableStateOf<Shortcut?>(null) }
    val removedMessage = stringResource(R.string.ntp_shortcut_removed)
    val undoLabel = stringResource(R.string.ntp_undo)
    val saveFailedMessage = stringResource(R.string.ntp_shortcut_save_failed)
    if (editorVisible) {
        ShortcutEditor(
            shortcut = editedShortcut,
            onDismiss = { editorVisible = false },
            onSave = { title, url ->
                val item = Shortcut(editedShortcut?.id ?: UUID.randomUUID().toString(), title, url)
                scope.launch {
                    try {
                        repository.update { items ->
                            if (items.any { it.id == item.id }) items.map { if (it.id == item.id) item else it }
                            else items + item
                        }
                    } catch (_: java.io.IOException) { snackbar.showMessage(saveFailedMessage) }
                }
                editorVisible = false
            },
        )
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val topSpace = maxHeight * 0.16f
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(topSpace.coerceAtLeast(32.dp)))
                if (incognito) {
                    Icon(Icons.Outlined.VisibilityOff, stringResource(R.string.ntp_incognito_title), Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.ntp_incognito_title), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.ntp_incognito_description),
                        modifier = Modifier.widthIn(max = 360.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Image(
                        painterResource(app.miru.design.R.drawable.miru_wordmark),
                        stringResource(R.string.ntp_miru_logo),
                        Modifier.height(40.dp).width(132.dp),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Surface(
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().height(52.dp).clickable(onClick = onSearch),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(50),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.ntp_search_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!incognito) {
                    Spacer(Modifier.height(28.dp))
                    Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        val entries = shortcuts.map { it as Shortcut? } + if (shortcuts.size < 8) listOf(null) else emptyList()
                        entries.chunked(4).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                row.forEach { shortcut ->
                                    if (shortcut == null) {
                                        ShortcutTile(null, onClick = { snackbar.currentSnackbarData?.dismiss(); editedShortcut = null; editorVisible = true })
                                    } else {
                                        ShortcutTile(
                                            shortcut,
                                            onClick = { onOpenUrl(shortcut.url) },
                                            onEdit = { editedShortcut = shortcut; editorVisible = true },
                                            onRemove = {
                                                val previousIndex = shortcuts.indexOfFirst { it.id == shortcut.id }
                                                scope.launch {
                                                    try {
                                                        repository.update { it.filterNot { entry -> entry.id == shortcut.id } }
                                                    } catch (_: java.io.IOException) {
                                                        snackbar.showMessage(saveFailedMessage)
                                                        return@launch
                                                    }
                                                    if (snackbar.showUndo(removedMessage, undoLabel)) {
                                                        try {
                                                            repository.update { entries ->
                                                                if (entries.none { it.id == shortcut.id }) entries.toMutableList().apply { add(previousIndex.coerceIn(0, size), shortcut) }
                                                                else entries
                                                            }
                                                        } catch (_: java.io.IOException) { snackbar.showMessage(saveFailedMessage) }
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                                repeat(4 - row.size) { Spacer(Modifier.width(64.dp)) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun ShortcutTile(shortcut: Shortcut?, onClick: () -> Unit, onEdit: () -> Unit = {}, onRemove: () -> Unit = {}) {
    var menuOpen by remember { mutableStateOf(false) }
    val editLabel = stringResource(R.string.ntp_shortcut_edit)
    Box {
        Column(
            Modifier.width(64.dp).heightIn(min = 80.dp)
                .combinedClickable(onClick = onClick, role = Role.Button, onLongClickLabel = editLabel,
                    onLongClick = if (shortcut == null) null else { { menuOpen = true } })
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (shortcut == null) {
                Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary)
                }
            } else {
                // Color per domain keeps Google and GitHub ("G") apart until favicons are available.
                SiteIcon(shortcut.url, shortcut.title, size = 48.dp)
            }
            Spacer(Modifier.height(8.dp))
            Text(shortcut?.title ?: stringResource(R.string.ntp_shortcut_add), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.ntp_shortcut_edit)) }, onClick = { menuOpen = false; onEdit() })
            DropdownMenuItem(text = { Text(stringResource(R.string.ntp_shortcut_remove)) }, onClick = { menuOpen = false; onRemove() })
        }
    }
}

@Composable
private fun ShortcutEditor(shortcut: Shortcut?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember(shortcut?.id) { mutableStateOf(shortcut?.title.orEmpty()) }
    var url by remember(shortcut?.id) { mutableStateOf(shortcut?.url.orEmpty()) }
    var invalidUrl by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (shortcut == null) R.string.ntp_shortcut_add_title else R.string.ntp_shortcut_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.ntp_shortcut_name)) }, singleLine = true)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; invalidUrl = false },
                    label = { Text(stringResource(R.string.ntp_shortcut_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = invalidUrl,
                    supportingText = if (invalidUrl) { { Text(stringResource(R.string.ntp_shortcut_invalid_url)) } } else null,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ntp_cancel)) } },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && url.isNotBlank(),
                onClick = {
                    val normalized = normalizeShortcutUrl(url)
                    if (normalized == null) invalidUrl = true else onSave(title.trim(), normalized)
                },
            ) { Text(stringResource(R.string.ntp_save)) }
        },
    )
}
