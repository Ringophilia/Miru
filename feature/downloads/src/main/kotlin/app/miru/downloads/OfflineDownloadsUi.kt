// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.miru.offline.OfflineItem
import app.miru.offline.OfflineSelection

internal fun OfflineItem.downloadRow() = UnifiedDownload(
    item = DownloadItem(
        id = id, url = url, fileName = title, mimeType = mimeType,
        downloadedBytes = downloadedBytes, totalBytes = totalBytes,
        status = status, error = error, sourcePage = sourcePage,
        createdAt = createdAt, completedAt = completedAt, category = DownloadCategory.VIDEO,
        isPrivate = isPrivate,
    ),
    offline = true,
    quality = qualityLabel,
    percent = percent,
)

/** A process-only selection; neither media credentials nor private source titles enter saved state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineConfirmSheet(
    selection: OfflineSelection,
    onDismiss: () -> Unit,
    onConfirm: (qualityId: String, title: String) -> Unit,
) {
    var title by remember(selection) { mutableStateOf(selection.source.title.take(200)) }
    var selected by remember(selection) {
        mutableStateOf(selection.qualities.filter { it.height > 0 }.minByOrNull { kotlin.math.abs(it.height - 720) }?.id ?: selection.qualities.firstOrNull()?.id)
    }
    var privateAcknowledged by remember(selection) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.offline_download_title), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(title, { title = it.take(200) }, Modifier.fillMaxWidth().testTag("offline_download_title"), singleLine = true, label = { Text(stringResource(R.string.offline_video_name)) })
            Text(stringResource(R.string.offline_quality_title), style = MaterialTheme.typography.titleMedium)
            if(selection.qualities.isEmpty()) Text(stringResource(R.string.offline_quality_unavailable))
            selection.qualities.forEachIndexed { index, quality ->
                Row(Modifier.fillMaxWidth().testTag("offline_quality_$index").selectable(selected = selected == quality.id, role = Role.RadioButton, onClick = { selected = quality.id }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == quality.id, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(quality.label)
                }
            }
            Text(stringResource(R.string.offline_download_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(selection.source.isPrivate) {
                Text(stringResource(R.string.offline_private_warning), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().testTag("offline_private_acknowledgement").toggleable(value = privateAcknowledged, role = Role.Checkbox, onValueChange = { privateAcknowledged = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(privateAcknowledged, onCheckedChange = null)
                    Text(stringResource(R.string.offline_private_acknowledgement), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.download_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(enabled = title.isNotBlank() && selected != null && (!selection.source.isPrivate || privateAcknowledged),
                    onClick = { selected?.let { onConfirm(it, title.trim()) } }, modifier = Modifier.testTag("offline_download_start")) {
                    Text(stringResource(R.string.download_start))
                }
            }
        }
    }
}
