package app.miru.history

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.miru.data.BrowsingLibrary
import app.miru.data.HistoryEntry
import app.miru.design.MiruSearchField
import app.miru.design.SiteIcon
import app.miru.design.showUndo
import app.miru.design.siteHost
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

private enum class ClearRange(val label: Int) { HOUR(R.string.history_range_hour), TODAY(R.string.history_range_today), ALL(R.string.history_range_all) }

/** Spec 5.10: search, grouped by date, swipe or menu delete with undo, and clearing by time range. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    library: BrowsingLibrary,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (url: String, private: Boolean) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // Keep the previous results while a new query runs, so typing does not blank the list.
    var entries by remember { mutableStateOf<List<HistoryEntry>?>(null) }
    LaunchedEffect(query) { library.history.observe(query).collect { entries = it } }
    var clearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val deletedMessage = stringResource(R.string.history_deleted)
    val clearedMessage = stringResource(R.string.history_cleared)
    val undo = stringResource(R.string.history_undo)

    fun delete(entry: HistoryEntry) = scope.launch {
        library.history.delete(entry.url)
        if (snackbar.showUndo(deletedMessage, undo)) library.history.restore(listOf(entry))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.history_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            MiruSearchField(query, { query = it }, stringResource(R.string.history_search), stringResource(R.string.history_clear_search), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            val list = entries
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(stringResource(if (query.isBlank()) R.string.history_empty else R.string.history_no_results))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (query.isBlank()) item(key = "clear") {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.history_clear), color = MaterialTheme.colorScheme.primary) },
                            leadingContent = { Icon(Icons.Outlined.DeleteSweep, null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable { clearing = true },
                        )
                    }
                    groupByDay(list).forEach { (day, items) ->
                        item(key = "day-$day") { DayHeader(day) }
                        items(items, key = { it.url }) { entry ->
                            HistoryRow(entry, { onOpen(entry.url) }, onOpenInNewTab, { delete(entry) }, Modifier.animateItem())
                        }
                    }
                }
            }
        }
    }
    if (clearing) ClearDialog(onDismiss = { clearing = false }) { range ->
        clearing = false
        val now = System.currentTimeMillis()
        val since = when (range) {
            ClearRange.HOUR -> now - 60 * 60 * 1000L
            ClearRange.TODAY -> LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            ClearRange.ALL -> 0L
        }
        scope.launch {
            val removed = library.history.clear(since)
            if (removed.isNotEmpty() && snackbar.showUndo(clearedMessage, undo)) library.history.restore(removed)
        }
    }
}

private fun groupByDay(entries: List<HistoryEntry>): List<Pair<LocalDate, List<HistoryEntry>>> {
    val zone = ZoneId.systemDefault()
    return entries.groupBy { Instant.ofEpochMilli(it.lastVisited).atZone(zone).toLocalDate() }.toList()
}

@Composable
private fun DayHeader(day: LocalDate) {
    val today = LocalDate.now()
    val label = when (day) {
        today -> stringResource(R.string.history_today)
        today.minusDays(1) -> stringResource(R.string.history_yesterday)
        else -> remember(day) { day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
    }
    Text(label, Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryRow(entry: HistoryEntry, onOpen: () -> Unit, onOpenInNewTab: (String, Boolean) -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dismiss = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismiss.currentValue) { if (dismiss.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete() }
    val title = entry.title.ifBlank { siteHost(entry.url) }
    val time = remember(entry.lastVisited) { DateFormat.getTimeFormat(context).format(entry.lastVisited) }
    var menu by remember { mutableStateOf(false) }
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        modifier = modifier,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        ListItem(
            headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text("${siteHost(entry.url)} · $time", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = { SiteIcon(entry.url, entry.title) },
            trailingContent = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.history_more, title)) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.history_open_new_tab)) }, leadingIcon = { Icon(Icons.Outlined.Add, null) }, onClick = { menu = false; onOpenInNewTab(entry.url, false) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.history_open_private)) }, leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) }, onClick = { menu = false; onOpenInNewTab(entry.url, true) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.history_copy)) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = {
                            menu = false
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(title, entry.url))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.history_delete)) }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; onDelete() })
                    }
                }
            },
            modifier = Modifier.clickable(onClick = onOpen),
        )
    }
}

@Composable
private fun ClearDialog(onDismiss: () -> Unit, onClear: (ClearRange) -> Unit) {
    var range by remember { mutableStateOf(ClearRange.HOUR) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeleteSweep, null) },
        title = { Text(stringResource(R.string.history_clear_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                ClearRange.entries.forEach { value ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(range == value, role = Role.RadioButton) { range = value },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(range == value, onClick = null)
                        Text(stringResource(value.label), Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.history_cancel)) } },
        confirmButton = { TextButton(onClick = { onClear(range) }) { Text(stringResource(R.string.history_clear_confirm)) } },
    )
}

@Composable
private fun EmptyState(message: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.History, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
