// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.miru.browser.engine.SitePermissionChoice
import app.miru.browser.engine.SitePermissionKind
import app.miru.browser.engine.SitePermissionPrompt
import app.miru.browser.engine.SitePermissionState

@Composable
private fun permissionName(kind: SitePermissionKind): String = stringResource(when(kind) {
    SitePermissionKind.CAMERA -> R.string.site_permission_camera
    SitePermissionKind.MICROPHONE -> R.string.site_permission_microphone
    SitePermissionKind.LOCATION -> R.string.site_permission_location
})

@Composable
private fun choiceName(choice: SitePermissionChoice): String = stringResource(when(choice) {
    SitePermissionChoice.ALLOW -> R.string.site_permission_allow
    SitePermissionChoice.BLOCK -> R.string.site_permission_block
    SitePermissionChoice.ASK -> R.string.site_permission_ask
})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SitePermissionSheet(
    prompt: SitePermissionPrompt,
    onDismiss: () -> Unit,
    onAnswer: (allow: Boolean, remember: Boolean) -> Unit,
) {
    // Never retain a private origin or permission decision in Android saved state.
    var rememberChoice by remember(prompt.token) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.site_permission_request), style = MaterialTheme.typography.titleLarge)
            Text(prompt.origin, style = MaterialTheme.typography.bodyLarge)
            prompt.topLevelOrigin?.takeIf { it != prompt.origin }?.let {
                Text(stringResource(R.string.site_permission_embedded, it), style = MaterialTheme.typography.bodySmall)
            }
            prompt.kinds.forEach { Text(permissionName(it), style = MaterialTheme.typography.titleMedium) }
            Row(Modifier.fillMaxWidth().clickable { rememberChoice = !rememberChoice }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it })
                Text(stringResource(if(prompt.isPrivate) R.string.site_permission_private_remember else R.string.site_permission_remember),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onAnswer(false, rememberChoice) }) { Text(stringResource(R.string.site_permission_block)) }
                FilledTonalButton(onClick = { onAnswer(true, rememberChoice) }) { Text(stringResource(R.string.site_permission_allow)) }
            }
        }
    }
}

/** Only the current, engine-owned projection supplies tokens for settings mutations. */
@Composable
internal fun SitePermissionControls(
    state: SitePermissionState,
    onChange: (SitePermissionKind, SitePermissionChoice) -> Unit,
    onReset: () -> Unit,
    onSystemSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.site_permission_title), style = MaterialTheme.typography.titleMedium)
        Text(state.origin, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.site_permission_reload_notice), style = MaterialTheme.typography.bodySmall)
        if(state.isPrivate) Text(stringResource(R.string.site_permission_private), style = MaterialTheme.typography.bodySmall)
        SitePermissionKind.entries.forEach { kind ->
            var expanded by remember(state.token, kind) { mutableStateOf(false) }
            val name = permissionName(kind)
            val description = stringResource(R.string.site_permission_change, name)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name)
                    if(state.systemGranted[kind] != true) Text(stringResource(R.string.site_permission_system_missing), style = MaterialTheme.typography.bodySmall)
                }
                Box {
                    TextButton(onClick = { expanded = true }, enabled = !state.busy,
                        modifier = Modifier.semantics { contentDescription = description }) {
                        Text(choiceName(state.choices[kind] ?: SitePermissionChoice.ASK))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        SitePermissionChoice.entries.forEach { choice ->
                            DropdownMenuItem(text = { Text(choiceName(choice)) }, onClick = { expanded = false; onChange(kind, choice) })
                        }
                    }
                }
            }
        }
        if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(state.error) Text(stringResource(R.string.site_permission_failed), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onReset, enabled = !state.busy) { Text(stringResource(R.string.site_permission_reset)) }
        if(state.systemGranted.values.any { !it }) TextButton(onClick = onSystemSettings) { Text(stringResource(R.string.site_permission_settings)) }
    }
}
