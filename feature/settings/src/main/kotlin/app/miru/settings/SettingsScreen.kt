package app.miru.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.miru.common.SearchEngine
import app.miru.data.BrowserSettings
import app.miru.data.ThemeMode
import app.miru.data.ToolbarPosition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: BrowserSettings,
    onBack: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onToolbarPosition: (ToolbarPosition) -> Unit,
    onSearchEngine: (SearchEngine) -> Unit,
    onDefaultBrowser: () -> Unit,
    onAbout: () -> Unit,
) {
    var dialog by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
        })
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            GroupLabel(R.string.basic)
            SettingRow(stringResource(R.string.search_engine), engineName(settings.searchEngine)) { dialog = "search" }
            SettingRow(stringResource(R.string.default_browser), stringResource(R.string.set_default), onDefaultBrowser)
            GroupLabel(R.string.appearance)
            SettingRow(stringResource(R.string.theme), themeName(settings.theme)) { dialog = "theme" }
            ListItem(headlineContent = { Text(stringResource(R.string.dynamic_color)) }, supportingContent = { Text(stringResource(R.string.dynamic_color_detail)) }, trailingContent = {
                Switch(checked = settings.dynamicColor, onCheckedChange = onDynamicColor)
            })
            SettingRow(stringResource(R.string.toolbar_position), positionName(settings.toolbarPosition)) { dialog = "position" }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SettingRow(stringResource(R.string.about), stringResource(R.string.about_summary), onAbout)
        }
    }
    when (dialog) {
        "search" -> ChoiceDialog(stringResource(R.string.search_engine), SearchEngine.entries, settings.searchEngine, { engineName(it) }, { onSearchEngine(it); dialog = null }, { dialog = null })
        "theme" -> ChoiceDialog(stringResource(R.string.theme), ThemeMode.entries, settings.theme, { themeName(it) }, { onTheme(it); dialog = null }, { dialog = null })
        "position" -> ChoiceDialog(stringResource(R.string.toolbar_position), ToolbarPosition.entries, settings.toolbarPosition, { positionName(it) }, { onToolbarPosition(it); dialog = null }, { dialog = null })
    }
}

@Composable private fun GroupLabel(id: Int) { Text(stringResource(id), Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
@Composable private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(value) }, trailingContent = { Icon(Icons.Rounded.ChevronRight, null) }, modifier = Modifier.clickable(onClick = onClick))
}
@Composable private fun <T> ChoiceDialog(title: String, choices: List<T>, selected: T, name: @Composable (T) -> String, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column { choices.forEach { value ->
            Row(Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected == value, { onSelect(value) })
                Text(name(value), Modifier.padding(start = 8.dp))
            }
        } }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}
@Composable private fun engineName(value: SearchEngine): String = stringResource(when(value) {
    SearchEngine.GOOGLE -> R.string.google
    SearchEngine.BING -> R.string.bing
    SearchEngine.DUCKDUCKGO -> R.string.duckduckgo
    SearchEngine.BAIDU -> R.string.baidu
})
@Composable private fun themeName(value: ThemeMode): String = stringResource(when(value) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark })
@Composable private fun positionName(value: ToolbarPosition): String = stringResource(if(value == ToolbarPosition.TOP) R.string.top else R.string.bottom)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(versionName: String, onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.about)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } }) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.miru), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.version, versionName))
            Text(stringResource(R.string.preview_detail), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.privacy_detail), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Text(stringResource(R.string.open_source), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onOpenUrl("https://github.com/mozilla-mobile/reference-browser") }) { Text(stringResource(R.string.mozilla_license)) }
            TextButton(onClick = { onOpenUrl("https://www.mozilla.org/MPL/2.0/") }) { Text(stringResource(R.string.mpl)) }
            TextButton(onClick = { onOpenUrl("https://source.android.com/docs/setup/about/licenses") }) { Text(stringResource(R.string.android_license)) }
        }
    }
}
