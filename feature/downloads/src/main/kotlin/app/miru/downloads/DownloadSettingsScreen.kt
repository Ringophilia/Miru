// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.downloads

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSettingsScreen(coordinator: DownloadCoordinator, onBack: () -> Unit) {
    val preferences by coordinator.settings.collectAsState()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            coordinator.updatePreferences { it.copy(directoryUri = uri.toString()) }
        }
    }
    val askLabel = stringResource(R.string.download_ask)
    val deleteLabel = stringResource(R.string.download_delete_with_record)
    val wifiOnlyLabel = stringResource(R.string.download_video_wifi_only)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.download_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.download_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            ListItem(
                headlineContent = { Text(askLabel) },
                trailingContent = {
                    Switch(
                        checked = preferences.askBeforeDownload,
                        onCheckedChange = { checked -> coordinator.updatePreferences { it.copy(askBeforeDownload = checked) } },
                        modifier = Modifier.semantics { contentDescription = askLabel },
                    )
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.download_location)) },
                supportingContent = {
                    Text(
                        preferences.directoryUri?.let { Uri.parse(it).lastPathSegment }
                            .orEmpty().ifBlank { stringResource(R.string.download_default_folder) },
                    )
                },
                trailingContent = { Icon(Icons.Outlined.FolderOpen, null) },
                modifier = Modifier.clickable { picker.launch(preferences.directoryUri?.let(Uri::parse)) },
            )
            if (preferences.directoryUri != null && Build.VERSION.SDK_INT >= 29) {
                TextButton(
                    onClick = { coordinator.updatePreferences { it.copy(directoryUri = null) } },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.download_reset_folder))
                }
            }
            val concurrentLabel = stringResource(R.string.download_concurrent, preferences.maxConcurrent)
            Text(concurrentLabel, Modifier.padding(16.dp))
            Slider(
                value = preferences.maxConcurrent.toFloat(),
                onValueChange = { value -> coordinator.updatePreferences { it.copy(maxConcurrent = value.toInt()) } },
                modifier = Modifier.padding(horizontal = 24.dp).semantics { contentDescription = concurrentLabel },
                valueRange = 1f..5f,
                steps = 3,
            )
            val threadsLabel = stringResource(R.string.download_threads, preferences.threadsPerFile)
            Text(threadsLabel, Modifier.padding(16.dp))
            Slider(
                value = preferences.threadsPerFile.toFloat(),
                onValueChange = { value -> coordinator.updatePreferences { it.copy(threadsPerFile = value.toInt()) } },
                modifier = Modifier.padding(horizontal = 24.dp).semantics { contentDescription = threadsLabel },
                valueRange = 1f..8f,
                steps = 6,
            )
            ListItem(
                headlineContent = { Text(wifiOnlyLabel) },
                supportingContent = { Text(stringResource(R.string.download_video_wifi_only_summary)) },
                trailingContent = {
                    Switch(
                        checked = preferences.videoWifiOnly,
                        onCheckedChange = { checked -> coordinator.updatePreferences { it.copy(videoWifiOnly = checked) } },
                        modifier = Modifier.semantics { contentDescription = wifiOnlyLabel },
                    )
                },
            )
            ListItem(
                headlineContent = { Text(deleteLabel) },
                trailingContent = {
                    Switch(
                        checked = preferences.deleteFileWithRecord,
                        onCheckedChange = { checked -> coordinator.updatePreferences { it.copy(deleteFileWithRecord = checked) } },
                        modifier = Modifier.semantics { contentDescription = deleteLabel },
                    )
                },
            )
        }
    }
}
