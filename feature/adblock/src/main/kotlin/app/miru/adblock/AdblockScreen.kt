// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.adblock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

enum class AdblockPhase { INSTALLING, READY, DISABLED, FAILED }
data class AdblockUiState(val phase: AdblockPhase, val version: String = "", val busy: Boolean = false, val pageNeedsReload: Boolean = false) {
    val installed: Boolean get() = phase == AdblockPhase.READY || phase == AdblockPhase.DISABLED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdblockSheet(
    host: String,
    count: Int,
    state: AdblockUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenSiteControls: () -> Unit,
    onOpenDashboard: () -> Unit,
    onReload: () -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(host, style = MaterialTheme.typography.titleLarge)
            Status(state)
            if(state.pageNeedsReload) {
                Text(stringResource(R.string.reload_needed), color = MaterialTheme.colorScheme.error)
                Button(onClick = onReload) { Text(stringResource(R.string.reload)) }
            }
            if (state.phase == AdblockPhase.READY) {
                if(!state.pageNeedsReload) Text(stringResource(R.string.blocked_on_page, count), style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
                Button(onClick = onOpenSiteControls, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
                    Icon(Icons.Outlined.Shield, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.site_controls))
                }
                Text(stringResource(R.string.site_controls_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.installed) TextButton(onClick = onOpenDashboard, enabled = state.phase == AdblockPhase.READY && !state.busy) {
                Icon(Icons.Outlined.Settings, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.advanced))
            }
            if (state.phase == AdblockPhase.FAILED) Button(onClick = onRetry, enabled = !state.busy) { Text(stringResource(R.string.retry)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdblockScreen(
    state: AdblockUiState,
    onBack: () -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onOpenExceptions: () -> Unit,
    onOpenDashboard: () -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            ListItem(headlineContent = { Text(stringResource(R.string.global_switch)) }, supportingContent = { Status(state) }, trailingContent = {
                Switch(checked = state.phase == AdblockPhase.READY, enabled = state.installed && !state.busy, onCheckedChange = onEnabledChanged)
            }, leadingContent = { Icon(Icons.Outlined.Shield, null) })
            if (state.phase == AdblockPhase.INSTALLING) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            if (state.phase == AdblockPhase.FAILED) TextButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 16.dp), enabled = !state.busy) { Text(stringResource(R.string.retry)) }
            ListItem(headlineContent = { Text(stringResource(R.string.exceptions)) }, supportingContent = { Text(stringResource(R.string.exceptions_detail)) }, leadingContent = { Icon(Icons.Outlined.Language, null) }, modifier = Modifier.clickable(enabled = state.phase == AdblockPhase.READY && !state.busy, onClick = onOpenExceptions))
            ListItem(headlineContent = { Text(stringResource(R.string.advanced)) }, supportingContent = { Text(stringResource(R.string.advanced_detail)) }, leadingContent = { Icon(Icons.Outlined.Tune, null) }, modifier = Modifier.clickable(enabled = state.phase == AdblockPhase.READY && !state.busy, onClick = onOpenDashboard))
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(stringResource(R.string.privacy), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** uBO's own action popup supplies the site toggle, so Miru never duplicates its whitelist state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdblockPopupSheet(
    host: String,
    count: Int,
    onDismiss: () -> Unit,
    onOpenDashboard: () -> Unit,
    pageNeedsReload: Boolean = false,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.78f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(host, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(if(pageNeedsReload) stringResource(R.string.protection_unconfirmed) else stringResource(R.string.blocked_on_page, count), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
                }
                IconButton(onClick = onOpenDashboard) { Icon(Icons.Outlined.Settings, stringResource(R.string.advanced)) }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.close_and_reload)) }
            }
            Text(stringResource(R.string.popup_hint), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.fillMaxWidth().weight(1f)) { content() }
        }
    }
}

@Composable private fun Status(state: AdblockUiState) {
    Text(stringResource(when(state.phase) {
        AdblockPhase.INSTALLING -> R.string.installing
        AdblockPhase.READY -> R.string.enabled
        AdblockPhase.DISABLED -> R.string.disabled
        AdblockPhase.FAILED -> R.string.failed
    }, state.version), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
