// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.media

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.miru.design.MiruBottomSheet
import app.miru.design.siteHost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSheet(
    items: List<DetectedMedia>,
    onDismiss: () -> Unit,
    onPlay: (DetectedMedia) -> Unit,
    resolve: (suspend (DetectedMedia) -> String?)? = null,
    onDownload: ((DetectedMedia) -> Unit)? = null,
    onMessage: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.media_link_copied)
    val currentResolver by rememberUpdatedState(resolve)
    var showShort by remember { mutableStateOf(false) }
    val short = items.filter { it.duration > 0 && it.duration < 30 && !it.playing }
    val visible = if (showShort) items else items - short.toSet()
    MiruBottomSheet(onDismissRequest = onDismiss) { closeWith ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.media_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            if (items.isEmpty()) Text(stringResource(R.string.media_empty))
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 440.dp)) {
                itemsIndexed(visible, key = { _, item -> item.id }) { index, item ->
                    var quality by remember(item.id, item.url, item.headers) { mutableStateOf<String?>(null) }
                    var probing by remember(item.id, item.url, item.headers) { mutableStateOf(false) }
                    // Each visible row owns its work. Closing the sheet or removing a row cancels
                    // semaphore waiters and in-flight requests, including private credentials.
                    LaunchedEffect(item.id, item.url, item.headers, item.width, item.height, item.isPrivate, item.drm) {
                        if (item.playable && item.kind != "AUDIO") {
                            probing = true
                            try {
                                quality = currentResolver?.invoke(item)
                            } finally {
                                probing = false
                            }
                        }
                    }
                    val host = remember(item.pageUrl) { item.pageUrl.takeIf { it.isNotBlank() }?.let(::siteHost).orEmpty() }
                    ListItem(
                        headlineContent = {
                            Text(
                                item.title.ifBlank { stringResource(R.string.media_number, index + 1) },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Column {
                                Text(
                                    listOfNotNull(
                                        item.kind,
                                        quality ?: item.height.takeIf { it > 0 }?.let { "${item.width} × $it" },
                                        if (item.duration > 0) mediaTime(item.duration) else null,
                                    ).joinToString(" · "),
                                    modifier = Modifier.testTag("media_quality_${item.id}"),
                                )
                                if (host.isNotBlank()) {
                                    Text(
                                        host,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (item.playable && item.kind != "AUDIO" && quality == null && item.height <= 0 && resolve != null) {
                                    Text(
                                        stringResource(if (probing) R.string.media_probe_loading else R.string.media_probe_unknown),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!item.playable) Text(stringResource(R.string.media_web_only))
                            }
                        },
                        trailingContent = {
                            Row {
                                IconButton(
                                    onClick = {
                                        context.getSystemService(ClipboardManager::class.java)
                                            ?.setPrimaryClip(ClipData.newPlainText(item.title, item.url))
                                        onMessage?.invoke(copiedMessage)
                                    },
                                    modifier = Modifier.testTag("media_copy_${item.id}"),
                                ) {
                                    Icon(Icons.Outlined.ContentCopy, stringResource(R.string.media_copy_link))
                                }
                                if (onDownload != null) {
                                    IconButton(
                                        onClick = { closeWith { onDismiss(); onDownload(item) } },
                                        enabled = item.playable,
                                        modifier = Modifier.testTag("media_download_${item.id}"),
                                    ) {
                                        Icon(Icons.Outlined.Download, stringResource(R.string.media_download))
                                    }
                                }
                                IconButton(
                                    onClick = { closeWith { onDismiss(); onPlay(item) } },
                                    enabled = item.playable,
                                    modifier = Modifier.testTag("media_play_${item.id}"),
                                ) {
                                    Icon(Icons.Outlined.PlayArrow, stringResource(R.string.media_play))
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    )
                }
            }
            if (short.isNotEmpty()) {
                TextButton(onClick = { showShort = !showShort }) {
                    Text(stringResource(if (showShort) R.string.media_hide_short else R.string.media_short, short.size))
                }
            }
        }
    }
}

private fun mediaTime(seconds: Double): String = "%d:%02d".format(seconds.toLong() / 60, seconds.toLong() % 60)
