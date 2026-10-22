// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsScreen(repository: PlayerPreferencesRepository, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val options by repository.settings.collectAsState(initial = PlayerPreferences())
    var dialog by remember { mutableStateOf<String?>(null) }
    var used by remember { mutableLongStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val cleared = stringResource(R.string.player_data_cleared)
    val failed = stringResource(R.string.player_settings_failed)
    fun change(transform: (PlayerPreferences) -> PlayerPreferences) { scope.launch {
        try { repository.update(transform) } catch(_: Exception) { snackbar.showSnackbar(failed) }
    } }
    LaunchedEffect(Unit) { while(isActive) {
        used = withContext(Dispatchers.IO) { runCatching { PlaybackCache.usedBytes(context) }.getOrDefault(0) }
        delay(1_000)
    } }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.player_settings)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.player_back)) }
    }) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            PlayerGroup(R.string.player_cache_group)
            PlayerSetting(stringResource(R.string.player_cache_limit), stringResource(R.string.player_megabytes, options.cacheMb)) { dialog = "cache" }
            PlayerSetting(stringResource(R.string.player_prebuffer), stringResource(R.string.player_seconds, options.bufferSeconds)) { dialog = "buffer" }
            Text(stringResource(R.string.player_buffer_detail), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ListItem(headlineContent = { Text(stringResource(R.string.player_cache_used)) }, supportingContent = { Text(Formatter.formatFileSize(context, used)) }, trailingContent = {
                TextButton(onClick = { dialog = "clear-cache" }, enabled = !busy) { Text(stringResource(R.string.player_clear)) }
            })
            ListItem(headlineContent = { Text(stringResource(R.string.player_clear_progress)) }, trailingContent = {
                TextButton(onClick = { dialog = "clear-progress" }, enabled = !busy) { Text(stringResource(R.string.player_clear)) }
            })
            Text(stringResource(R.string.player_private_cache_detail), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PlayerGroup(R.string.player_gestures)
            PlayerSetting(stringResource(R.string.player_double_tap), stringResource(R.string.player_seconds, options.doubleTapSeconds)) { dialog = "seek" }
            PlayerSetting(stringResource(R.string.player_hold), "${options.holdSpeed}x") { dialog = "hold" }
            PlayerSetting(stringResource(R.string.player_drag_range), stringResource(R.string.player_seconds, options.dragRangeSeconds)) { dialog = "drag" }
            PlayerSwitch(R.string.player_center_double_tap, options.centerDoubleTap) { value -> change { it.copy(centerDoubleTap = value) } }
            PlayerSwitch(R.string.player_brightness_gesture, options.brightnessGesture) { value -> change { it.copy(brightnessGesture = value) } }
            PlayerSwitch(R.string.player_volume_gesture, options.volumeGesture) { value -> change { it.copy(volumeGesture = value) } }
            PlayerSwitch(R.string.player_haptics, options.haptics) { value -> change { it.copy(haptics = value) } }
            PlayerGroup(R.string.player_behavior)
            PlayerSwitch(R.string.player_web_hold, options.webHoldSpeed) { value -> change { it.copy(webHoldSpeed = value) } }
            PlayerSwitch(R.string.player_auto_takeover, options.autoTakeover) { value -> change { it.copy(autoTakeover = value) } }
            PlayerSwitch(R.string.player_auto_pip, options.autoPip) { value -> change { it.copy(autoPip = value) } }
        }
    }
    when(dialog) {
        "cache" -> PlayerChoice(stringResource(R.string.player_cache_limit), listOf(256, 512, 1024, 2048, 4096), options.cacheMb, { stringResource(R.string.player_megabytes, it) }, { value -> change { it.copy(cacheMb = value) }; dialog = null }, { dialog = null })
        "buffer" -> PlayerChoice(stringResource(R.string.player_prebuffer), listOf(15, 30, 60, 120, 180), options.bufferSeconds, { stringResource(R.string.player_seconds, it) }, { value -> change { it.copy(bufferSeconds = value) }; dialog = null }, { dialog = null })
        "seek" -> PlayerChoice(stringResource(R.string.player_double_tap), listOf(5, 10, 15, 30), options.doubleTapSeconds, { stringResource(R.string.player_seconds, it) }, { value -> change { it.copy(doubleTapSeconds = value) }; dialog = null }, { dialog = null })
        "hold" -> PlayerChoice(stringResource(R.string.player_hold), listOf(1.5f, 2f, 2.5f, 3f), options.holdSpeed, { "${it}x" }, { value -> change { it.copy(holdSpeed = value) }; dialog = null }, { dialog = null })
        "drag" -> PlayerChoice(stringResource(R.string.player_drag_range), listOf(60, 120, 180, 300), options.dragRangeSeconds, { stringResource(R.string.player_seconds, it) }, { value -> change { it.copy(dragRangeSeconds = value) }; dialog = null }, { dialog = null })
        "clear-cache", "clear-progress" -> {
            val cache = dialog == "clear-cache"
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text(stringResource(if(cache) R.string.player_clear_cache_title else R.string.player_clear_progress_title)) },
                text = { Text(stringResource(if(cache) R.string.player_clear_cache_detail else R.string.player_clear_progress_detail)) },
                confirmButton = { TextButton(onClick = {
                    dialog = null; busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { if(cache) PlaybackCache.clear(context) else PlaybackHistory.get(context).progress().clear() }
                            snackbar.showSnackbar(cleared)
                        } catch(_: Exception) { snackbar.showSnackbar(failed) }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.player_confirm_clear)) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.player_cancel)) } })
        }
    }
}

@Composable private fun PlayerGroup(label: Int) { Text(stringResource(label), Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
@Composable private fun PlayerSetting(title: String, value: String, onClick: () -> Unit) { ListItem(headlineContent = { Text(title) }, supportingContent = { Text(value) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) }, modifier = Modifier.clickable(onClick = onClick)) }
@Composable private fun PlayerSwitch(label: Int, value: Boolean, onChange: (Boolean) -> Unit) {
    val title = stringResource(label)
    ListItem(headlineContent = { Text(title) }, trailingContent = { Switch(value, onChange, Modifier.semantics { contentDescription = title }) })
}
@Composable private fun <T> PlayerChoice(title: String, choices: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column { choices.forEach { value ->
        Row(Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { RadioButton(value == selected, onClick = { onSelect(value) }); Text(label(value), Modifier.padding(start = 8.dp)) }
    } } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_cancel)) } })
}
