// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.miru.design.SiteIcon
import app.miru.design.showMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class SiteSettingsItem(
    val origin: String,
    val summaryTags: List<String>,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SiteSettingsScreen(
    loadSites: suspend () -> List<SiteSettingsItem>,
    onResetSite: suspend (String) -> Unit,
    onResetAll: suspend () -> Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var sites by remember { mutableStateOf<List<SiteSettingsItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var confirmResetAll by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val doneMessage = stringResource(R.string.site_settings_reset_done)
    val failedMessage = stringResource(R.string.site_settings_reset_failed)

    LaunchedEffect(refreshTick) {
        loading = true
        try {
            sites = loadSites()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            sites = emptyList()
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.site_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (sites.isNotEmpty()) {
                        TextButton(
                            onClick = { confirmResetAll = true },
                            enabled = !busy,
                            modifier = Modifier.testTag("site-settings-reset-all"),
                        ) {
                            Text(stringResource(R.string.site_settings_reset_all))
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            loading && sites.isEmpty() -> {
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            sites.isEmpty() -> {
                Box(Modifier.padding(padding).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.site_settings_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(sites, key = { it.origin }) { site ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                SiteIcon(site.origin, site.origin, size = 36.dp)
                                Text(
                                    text = site.origin.removePrefix("https://").removePrefix("http://"),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(
                                    enabled = !busy,
                                    modifier = Modifier.testTag("site-reset-${site.origin}"),
                                    onClick = {
                                        busy = true
                                        scope.launch {
                                            try {
                                                onResetSite(site.origin)
                                                refreshTick++
                                                snackbar.showMessage(doneMessage)
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                snackbar.showMessage(failedMessage)
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.site_settings_reset))
                                }
                            }
                            if (site.summaryTags.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                FlowRow(
                                    modifier = Modifier.padding(start = 48.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    site.summaryTags.forEach { tag ->
                                        SuggestionChip(onClick = {}, label = { Text(tag, style = MaterialTheme.typography.labelSmall) })
                                    }
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }

    if (confirmResetAll) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = {
                Text(
                    stringResource(R.string.site_settings_reset_all_title),
                    modifier = Modifier.semantics { heading() },
                )
            },
            text = { Text(stringResource(R.string.site_settings_reset_all_confirm)) },
            dismissButton = {
                TextButton(onClick = { confirmResetAll = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("site-settings-reset-all-confirm"),
                    onClick = {
                        confirmResetAll = false
                        busy = true
                        scope.launch {
                            try {
                                if (onResetAll()) {
                                    refreshTick++
                                    snackbar.showMessage(doneMessage)
                                } else {
                                    snackbar.showMessage(failedMessage)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                snackbar.showMessage(failedMessage)
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.site_settings_reset_all))
                }
            },
        )
    }
}
