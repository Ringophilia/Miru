// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PlaybackSkipDialog(controller: PlayerController, onDismiss: () -> Unit) {
    val state by controller.state.collectAsState()
    val rules = controller.skipRules.collectAsState().value ?: return
    val scopes = controller.skipScopes
    val suggested = scopes.firstOrNull(rules::containsKey) ?: PlaybackSkipScope.suggested(scopes)
    var selected by remember { mutableStateOf(suggested) }
    var draft by remember(selected) { mutableStateOf(rules[selected] ?: PlaybackSkipRule()) }
    var selectScope by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val valid = state.seekableVod && draft.fits(state.duration)
    val maxSeconds = (state.duration / 1_000L).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.player_skip_sections)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.player_skip_scope))
                Box {
                    OutlinedButton(onClick = { selectScope = true }, enabled = !saving) {
                        Text(scopeLabel(selected), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = selectScope, onDismissRequest = { selectScope = false },
                        modifier = Modifier.heightIn(max = 280.dp)) {
                        scopes.forEach { scope ->
                            DropdownMenuItem(text = { Text(scopeLabel(scope)) }, onClick = {
                                selected = scope
                                selectScope = false
                                failed = false
                            })
                        }
                    }
                }
                SkipBoundaryEditor(R.string.player_skip_intro, draft.introSeconds, maxSeconds, "intro",
                    onChange = { draft = draft.copy(introSeconds = it) },
                    onSetCurrent = { draft = draft.copy(introSeconds = (controller.state.value.position / 1_000L).coerceIn(0L, maxSeconds.toLong()).toInt()) })
                HorizontalDivider()
                SkipBoundaryEditor(R.string.player_skip_outro, draft.outroSeconds, maxSeconds, "outro",
                    onChange = { draft = draft.copy(outroSeconds = it) },
                    onSetCurrent = {
                        val current = controller.state.value
                        draft = draft.copy(outroSeconds = ((current.duration - current.position) / 1_000L)
                            .coerceIn(0L, maxSeconds.toLong()).toInt())
                    })
                if (!valid || failed) Text(stringResource(
                    if (failed) R.string.player_settings_failed else R.string.player_skip_invalid,
                ), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !saving, onClick = {
                saving = true
                failed = false
                coroutineScope.launch {
                    try {
                        controller.saveSkipRule(selected, draft)
                        onDismiss()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        saving = false
                    }
                }
            }) { Text(stringResource(R.string.player_skip_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_cancel)) } },
    )
}

@Composable
private fun scopeLabel(scope: PlaybackSkipScope): String {
    val path = scope.host + scope.path
    return if (scope.exact) stringResource(R.string.player_skip_exact_page, path) else path
}

@Composable
private fun SkipBoundaryEditor(
    label: Int, seconds: Int, maxSeconds: Int, tag: String,
    onChange: (Int) -> Unit, onSetCurrent: () -> Unit,
) {
    Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
    Row {
        TextButton(onClick = { onChange((seconds - 5).coerceAtLeast(0)) },
            enabled = seconds > 0, modifier = Modifier.testTag("skip_${tag}_minus")) { Text("−5") }
        Text(stringResource(R.string.player_seconds, seconds),
            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically).testTag("skip_${tag}_value"))
        TextButton(onClick = { onChange((seconds.toLong() + 5).coerceAtMost(maxSeconds.toLong()).toInt()) },
            enabled = seconds < maxSeconds, modifier = Modifier.testTag("skip_${tag}_plus")) { Text("+5") }
    }
    TextButton(onClick = onSetCurrent, modifier = Modifier.testTag("skip_${tag}_current")) {
        Text(stringResource(R.string.player_skip_set_current))
    }
}
