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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.miru.design.MiruBottomSheet

/** UI state for the v2.0 native adblock-rust shield sheet and rule management screens. */
data class NativeFilterListItem(
    val id: String,
    val title: String,
    val enabled: Boolean,
    val ruleCount: Int,
    val builtIn: Boolean = true,
)

data class NativeAdblockUiState(
    val enabled: Boolean = true,
    val engineVersion: String = "",
    val pageBlockedCount: Int = 0,
    val totalBlockedCount: Long = 0L,
    val siteBlockingEnabled: Boolean = true,
    val updatingLists: Boolean = false,
    val filterLists: List<NativeFilterListItem> = emptyList(),
    val allowedHosts: List<String> = emptyList(),
    val customRules: List<String> = emptyList(),
)

/** Native Material 3 shield panel (MIRU-SPEC §5.7) backed directly by `adblock-rust` without WebExtension popups. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeAdblockSheet(
    host: String,
    state: NativeAdblockUiState,
    onDismiss: () -> Unit,
    onToggleSiteBlocking: (Boolean) -> Unit,
    onStartElementPicker: () -> Unit,
    onOpenRulesManager: () -> Unit,
) {
    MiruBottomSheet(onDismissRequest = onDismiss, maxHeightFraction = 0.7f) { closeWith ->
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(host, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(
                        stringResource(R.string.blocked_on_page, state.pageBlockedCount),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            val siteSwitchLabel = stringResource(R.string.native_site_toggle)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(enabled = state.enabled) { closeWith { onToggleSiteBlocking(!state.siteBlockingEnabled) } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(siteSwitchLabel, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.native_site_toggle_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.enabled && state.siteBlockingEnabled,
                    enabled = state.enabled,
                    onCheckedChange = { checked -> closeWith { onToggleSiteBlocking(checked) } },
                    modifier = Modifier.semantics { contentDescription = siteSwitchLabel },
                )
            }
            FilledTonalButton(
                onClick = { closeWith(onStartElementPicker) },
                enabled = state.enabled && state.siteBlockingEnabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.ContentCut, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.native_element_picker))
            }
            OutlinedButton(
                onClick = { closeWith(onOpenRulesManager) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.Tune, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.native_filter_lists))
            }
            if (state.totalBlockedCount > 0L) {
                Text(
                    stringResource(R.string.native_total_blocked, state.totalBlockedCount),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Native rules, filter lists, and site exceptions screen for the v2.0 `adblock-rust` backend. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeAdblockRulesScreen(
    state: NativeAdblockUiState,
    onBack: () -> Unit,
    onGlobalEnabledChanged: (Boolean) -> Unit,
    onFilterListToggled: (id: String, enabled: Boolean) -> Unit,
    onRefreshLists: () -> Unit,
    onRemoveAllowedHost: (String) -> Unit,
    onAddCustomRule: (String) -> Unit,
    onRemoveCustomRule: (String) -> Unit,
) {
    var draftRule by remember { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.title)) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
            },
            actions = {
                IconButton(onClick = onRefreshLists, enabled = !state.updatingLists) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.native_update_lists))
                }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            val switchLabel = stringResource(R.string.global_switch)
            ListItem(
                headlineContent = { Text(switchLabel) },
                supportingContent = { Text(stringResource(R.string.native_engine_summary, state.engineVersion.ifBlank { "adblock-rust" })) },
                leadingContent = { Icon(Icons.Outlined.Shield, null) },
                trailingContent = {
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = onGlobalEnabledChanged,
                        modifier = Modifier.semantics { contentDescription = switchLabel },
                    )
                },
            )
            if (state.updatingLists) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeading(stringResource(R.string.native_filter_lists))
            state.filterLists.forEach { list ->
                ListItem(
                    headlineContent = { Text(list.title) },
                    supportingContent = { Text(stringResource(R.string.native_rule_count, list.ruleCount)) },
                    trailingContent = {
                        Switch(
                            checked = list.enabled,
                            enabled = state.enabled,
                            onCheckedChange = { onFilterListToggled(list.id, it) },
                            modifier = Modifier.semantics { contentDescription = list.title },
                        )
                    },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeading(stringResource(R.string.exceptions))
            if (state.allowedHosts.isEmpty()) {
                Text(
                    stringResource(R.string.native_no_exceptions),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.allowedHosts.forEach { allowedHost ->
                    ListItem(
                        headlineContent = { Text(allowedHost) },
                        trailingContent = {
                            IconButton(onClick = { onRemoveAllowedHost(allowedHost) }) {
                                Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.native_remove_rule))
                            }
                        },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeading(stringResource(R.string.native_custom_rules))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draftRule,
                    onValueChange = { draftRule = it },
                    placeholder = { Text(stringResource(R.string.native_custom_rule_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    onClick = {
                        val trimmed = draftRule.trim()
                        if (trimmed.isNotEmpty()) {
                            onAddCustomRule(trimmed)
                            draftRule = ""
                        }
                    },
                    enabled = draftRule.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.native_add_rule)) }
            }
            state.customRules.forEach { rule ->
                ListItem(
                    headlineContent = { Text(rule, style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = {
                        IconButton(onClick = { onRemoveCustomRule(rule) }) {
                            Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.native_remove_rule))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
    )
}
