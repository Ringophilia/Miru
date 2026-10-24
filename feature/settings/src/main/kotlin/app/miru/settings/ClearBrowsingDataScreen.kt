// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.miru.data.BrowsingDataClearRequest
import app.miru.data.BrowsingDataRange
import app.miru.data.BrowsingDataType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClearBrowsingDataScreen(controller: ClearBrowsingDataController, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    var selectedRangeName by rememberSaveable { mutableStateOf(BrowsingDataRange.ALL_TIME.name) }
    var selectedNames by rememberSaveable { mutableStateOf(listOf(BrowsingDataType.HISTORY.name, BrowsingDataType.COOKIES.name, BrowsingDataType.WEB_CACHE.name)) }
    val range = BrowsingDataRange.valueOf(selectedRangeName)
    val selected = selectedNames.map(BrowsingDataType::valueOf).toSet()
    val available = BrowsingDataType.entries.filter { range == BrowsingDataRange.ALL_TIME || it.supportsTimeRange }.toSet()
    var showRanges by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    BackHandler(state.running) { }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.clear_browsing_data)) }, navigationIcon = {
            IconButton(onClick = onBack, enabled = !state.running) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
        })
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.clear_keeps_files), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.fillMaxWidth()) {
                TextButton(onClick = { showRanges = true }, enabled = !state.running, modifier = Modifier.testTag("clear_range")) {
                    Text(stringResource(R.string.clear_time_range, rangeName(range)))
                }
                DropdownMenu(expanded = showRanges, onDismissRequest = { showRanges = false }) {
                    BrowsingDataRange.entries.forEach { choice ->
                        DropdownMenuItem(text = { Text(rangeName(choice)) }, modifier = Modifier.testTag("clear_range_${choice.name}"), onClick = {
                            selectedRangeName = choice.name
                            if(choice != BrowsingDataRange.ALL_TIME) selectedNames = selected.filter { it.supportsTimeRange }.map { it.name }
                            showRanges = false
                        })
                    }
                }
            }
            if(range != BrowsingDataRange.ALL_TIME) Text(stringResource(R.string.clear_range_support), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { selectedNames = if(selected.containsAll(available)) emptyList() else available.map { it.name } },
                enabled = !state.running, modifier = Modifier.testTag("clear_select_all")) {
                Text(stringResource(if(selected.containsAll(available)) R.string.clear_select_none else R.string.clear_select_all))
            }
            BrowsingDataType.entries.forEach { type ->
                val enabled = !state.running && type in available
                Row(Modifier.fillMaxWidth().testTag("clear_type_${type.name}")
                    .toggleable(type in selected, enabled = enabled, role = Role.Checkbox) {
                        selectedNames = (if(type in selected) selected - type else selected + type).map { it.name }
                    }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = type in selected, onCheckedChange = null, enabled = enabled)
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Text(typeName(type), color = if(enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(if(type !in available) R.string.clear_all_time_only else typeDescription(type)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if(state.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp).testTag("clear_running"))
                Text(stringResource(R.string.clear_in_progress), Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite })
            }
            state.result?.let { result ->
                Column(Modifier.padding(16.dp).testTag(if(result.successful) "clear_success" else "clear_failure").semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(stringResource(if(result.successful) R.string.clear_finished else R.string.clear_incomplete), color = if(result.successful) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    if(result.completed.isNotEmpty()) Text(stringResource(R.string.clear_completed_types, result.completed.map { typeName(it) }.joinToString("、")), style = MaterialTheme.typography.bodySmall)
                    if(result.failed.isNotEmpty()) Text(stringResource(R.string.clear_failed_types, result.failed.map { typeName(it) }.joinToString("、")), style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onClick = { confirm = true }, enabled = !state.running && selected.isNotEmpty(), modifier = Modifier.padding(16.dp).fillMaxWidth().testTag("clear_execute")) {
                Text(stringResource(R.string.clear_selected_data))
            }
        }
    }
    if(confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.clear_confirm_title)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.clear_time_range, rangeName(range)))
            Text(selected.map { typeName(it) }.joinToString("、"))
            Text(stringResource(R.string.clear_confirm_irreversible))
            if(BrowsingDataType.FORM_DATA in selected) Text(stringResource(R.string.clear_forms_warning))
            if(BrowsingDataType.COOKIES in selected || BrowsingDataType.AUTH_SESSIONS in selected) Text(stringResource(R.string.clear_login_warning))
        }
    }, confirmButton = { TextButton(onClick = { confirm = false; controller.execute(BrowsingDataClearRequest(selected.toSet(), range)) }, modifier = Modifier.testTag("clear_confirm")) {
        Text(stringResource(R.string.clear_confirm_action))
    } }, dismissButton = { TextButton(onClick = { confirm = false }, modifier = Modifier.testTag("clear_confirm_cancel")) { Text(stringResource(R.string.clear_cancel)) } })
}

@Composable private fun typeName(type: BrowsingDataType): String = stringResource(when(type) {
    BrowsingDataType.HISTORY -> R.string.clear_history
    BrowsingDataType.COOKIES -> R.string.clear_cookies
    BrowsingDataType.SITE_DATA -> R.string.clear_site_data
    BrowsingDataType.WEB_CACHE -> R.string.clear_web_cache
    BrowsingDataType.SITE_SETTINGS -> R.string.clear_site_settings
    BrowsingDataType.AUTH_SESSIONS -> R.string.clear_auth_sessions
    BrowsingDataType.FORM_DATA -> R.string.clear_form_data
    BrowsingDataType.PLAYBACK_PROGRESS -> R.string.clear_playback_progress
    BrowsingDataType.PLAYBACK_CACHE -> R.string.clear_playback_cache
})

private fun typeDescription(type: BrowsingDataType): Int = when(type) {
    BrowsingDataType.HISTORY -> R.string.clear_history_detail
    BrowsingDataType.COOKIES -> R.string.clear_cookies_detail
    BrowsingDataType.SITE_DATA -> R.string.clear_site_data_detail
    BrowsingDataType.WEB_CACHE -> R.string.clear_web_cache_detail
    BrowsingDataType.SITE_SETTINGS -> R.string.clear_site_settings_detail
    BrowsingDataType.AUTH_SESSIONS -> R.string.clear_auth_sessions_detail
    BrowsingDataType.FORM_DATA -> R.string.clear_forms_warning
    BrowsingDataType.PLAYBACK_PROGRESS -> R.string.clear_playback_progress_detail
    BrowsingDataType.PLAYBACK_CACHE -> R.string.clear_playback_cache_detail
}

@Composable private fun rangeName(range: BrowsingDataRange): String = stringResource(when(range) {
    BrowsingDataRange.LAST_HOUR -> R.string.clear_last_hour
    BrowsingDataRange.LAST_DAY -> R.string.clear_last_day
    BrowsingDataRange.LAST_WEEK -> R.string.clear_last_week
    BrowsingDataRange.LAST_FOUR_WEEKS -> R.string.clear_last_four_weeks
    BrowsingDataRange.ALL_TIME -> R.string.clear_all_time
})
