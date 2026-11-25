// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PlaybackSkipDialog(
    visible: Boolean,
    controller: PlayerController,
    reducedMotion: Boolean,
    onDismiss: () -> Unit,
) {
    val scopes = controller.skipScopes
    if (scopes.isEmpty()) return
    val state by controller.state.collectAsState()
    val rules = controller.skipRules.collectAsState().value
    val suggested = remember(rules, scopes) {
        val map = rules.orEmpty()
        scopes.firstOrNull(map::containsKey) ?: PlaybackSkipScope.suggested(scopes)
    }
    var selected by remember(suggested) { mutableStateOf(suggested) }
    var draft by remember(selected, rules) { mutableStateOf(rules?.get(selected) ?: PlaybackSkipRule()) }
    var selectScope by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val valid = rules != null && state.seekableVod && draft.fits(state.duration)
    val maxSeconds = (state.duration / 1_000L).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    PlayerSidePanel(
        visible = visible,
        title = stringResource(R.string.player_skip_sections),
        onDismiss = onDismiss,
        reducedMotion = reducedMotion,
        footer = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_cancel)) }
            Spacer(Modifier.width(8.dp))
            TextButton(
                enabled = valid && !saving,
                onClick = {
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
                },
            ) { Text(stringResource(R.string.player_skip_save)) }
        },
    ) {
        if (rules == null) return@PlayerSidePanel
        Text(stringResource(R.string.player_skip_scope), color = Color(0xCCFFFFFF))
        Box {
            OutlinedButton(onClick = { selectScope = true }, enabled = !saving) {
                Text(scopeLabel(selected), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(
                expanded = selectScope,
                onDismissRequest = { selectScope = false },
                modifier = Modifier.heightIn(max = 280.dp),
            ) {
                scopes.forEach { scope ->
                    DropdownMenuItem(
                        text = { Text(scopeLabel(scope)) },
                        onClick = {
                            selected = scope
                            selectScope = false
                            failed = false
                        },
                    )
                }
            }
        }
        SkipBoundaryEditor(
            label = R.string.player_skip_intro,
            seconds = draft.introSeconds,
            maxSeconds = maxSeconds,
            tag = "intro",
            onChange = { draft = draft.copy(introSeconds = it) },
            onSetCurrent = {
                draft = draft.copy(
                    introSeconds = (controller.state.value.position / 1_000L).coerceIn(0L, maxSeconds.toLong()).toInt(),
                )
            },
        )
        HorizontalDivider(color = Color(0x1FFFFFFF))
        SkipBoundaryEditor(
            label = R.string.player_skip_outro,
            seconds = draft.outroSeconds,
            maxSeconds = maxSeconds,
            tag = "outro",
            onChange = { draft = draft.copy(outroSeconds = it) },
            onSetCurrent = {
                val current = controller.state.value
                draft = draft.copy(
                    outroSeconds = ((current.duration - current.position) / 1_000L)
                        .coerceIn(0L, maxSeconds.toLong()).toInt(),
                )
            },
        )
        if (!valid || failed) {
            Text(
                stringResource(if (failed) R.string.player_settings_failed else R.string.player_skip_invalid),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun scopeLabel(scope: PlaybackSkipScope): String {
    val path = scope.host + scope.path
    return if (scope.exact) stringResource(R.string.player_skip_exact_page, path) else path
}

@Composable
private fun SkipBoundaryEditor(
    label: Int,
    seconds: Int,
    maxSeconds: Int,
    tag: String,
    onChange: (Int) -> Unit,
    onSetCurrent: () -> Unit,
) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.titleSmall,
        color = Color(0xCCFFFFFF),
        modifier = Modifier.semantics { heading() },
    )
    Row {
        TextButton(
            onClick = { onChange((seconds - 5).coerceAtLeast(0)) },
            enabled = seconds > 0,
            modifier = Modifier.testTag("skip_${tag}_minus"),
        ) { Text("−5") }
        Text(
            stringResource(R.string.player_seconds, seconds),
            color = Color.White,
            modifier = Modifier.align(Alignment.CenterVertically).testTag("skip_${tag}_value"),
        )
        TextButton(
            onClick = { onChange((seconds.toLong() + 5).coerceAtMost(maxSeconds.toLong()).toInt()) },
            enabled = seconds < maxSeconds,
            modifier = Modifier.testTag("skip_${tag}_plus"),
        ) { Text("+5") }
    }
    TextButton(onClick = onSetCurrent, modifier = Modifier.testTag("skip_${tag}_current")) {
        Text(stringResource(R.string.player_skip_set_current))
    }
}
