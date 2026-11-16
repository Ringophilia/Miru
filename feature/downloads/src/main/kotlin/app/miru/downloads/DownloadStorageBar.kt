// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.miru.offline.OfflineCoordinator

@Composable
internal fun DownloadStorageBar(coordinator: DownloadCoordinator, offline: OfflineCoordinator?) {
    val usage by remember(coordinator, offline) {
        coordinator.observeStorage { offline?.downloadStorageBytes() ?: 0L }
    }.collectAsStateWithLifecycle(initialValue = null)
    val reading = usage
    val summary = if (reading == null) stringResource(R.string.download_storage_loading) else {
        val used = formatDownloadBytes(reading.usedBytes)
        val available = reading.availableBytes?.let(::formatDownloadBytes) ?: stringResource(R.string.download_storage_unavailable)
        val knownUsed = if (reading.incomplete) stringResource(R.string.download_storage_at_least, used) else used
        stringResource(R.string.download_storage_summary, knownUsed, available)
    }
    // The byte counts describe the two quantities directly for accessibility services.
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .testTag("download_storage").semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        reading?.usedFraction?.let { fraction ->
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(3.dp).clearAndSetSemantics {},
            )
        }
    }
}
