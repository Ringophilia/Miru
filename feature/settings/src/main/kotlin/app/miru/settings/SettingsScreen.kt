// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.miru.common.AccentColor
import app.miru.design.MiruChoiceDialog
import app.miru.design.accentPreviewColor
import app.miru.common.SearchEngine
import app.miru.data.BrowserSettings
import app.miru.data.BrowsingDataType
import app.miru.data.SecureDnsMode
import app.miru.data.ThemeMode
import app.miru.data.ToolbarPosition
import kotlin.math.roundToInt

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
    onAdblock: () -> Unit = {},
    onDownloads: () -> Unit = {},
    onPlayer: () -> Unit = {},
    onClearBrowsingData: () -> Unit = {},
    onSearchSuggestions: (Boolean) -> Unit = {},
    onAccent: (AccentColor) -> Unit = {},
    onBlockThirdPartyCookies: (Boolean) -> Unit = {},
    onHttpsOnly: (Boolean) -> Unit = {},
    onSecureDns: (SecureDnsMode, String?) -> Unit = { _, _ -> },
    isDefaultBrowser: Boolean = false,
    onCustomSearchUrl: (String) -> Unit = {},
    onWebZoomPercent: (Int) -> Unit = {},
    onNtpShowShortcuts: (Boolean) -> Unit = {},
    onClearDataOnExit: (Boolean) -> Unit = {},
    onClearOnExitTypes: (Set<BrowsingDataType>) -> Unit = {},
    onSiteSettings: () -> Unit = {},
) {
    var dialog by remember { mutableStateOf<String?>(null) }
    val suggestionsLabel = stringResource(R.string.search_suggestions)
    val dynamicLabel = stringResource(R.string.dynamic_color)
    val shortcutsLabel = stringResource(R.string.ntp_show_shortcuts)
    val clearOnExitLabel = stringResource(R.string.clear_on_exit)
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val usesDynamicColor = dynamicAvailable && settings.dynamicColor
    val customSearchActive = settings.searchEngine == SearchEngine.CUSTOM
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
        })
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            GroupLabel(R.string.basic)
            SettingRow(stringResource(R.string.search_engine), engineSubtitle(settings)) { dialog = "search" }
            ListItem(
                headlineContent = { Text(suggestionsLabel) },
                supportingContent = {
                    Text(stringResource(if (customSearchActive) R.string.search_suggestions_custom_disabled else R.string.search_suggestions_detail))
                },
                trailingContent = {
                    Switch(
                        checked = settings.searchSuggestions && !customSearchActive,
                        onCheckedChange = onSearchSuggestions,
                        enabled = !customSearchActive,
                        modifier = Modifier.semantics { contentDescription = suggestionsLabel },
                    )
                },
            )
            SettingRow(
                stringResource(R.string.default_browser),
                stringResource(if (isDefaultBrowser) R.string.default_browser_is_default else R.string.set_default),
                onDefaultBrowser,
            )
            GroupLabel(R.string.appearance)
            SettingRow(stringResource(R.string.theme), themeName(settings.theme)) { dialog = "theme" }
            ListItem(headlineContent = { Text(dynamicLabel) }, supportingContent = { Text(stringResource(R.string.dynamic_color_detail)) }, trailingContent = {
                Switch(checked = usesDynamicColor, onCheckedChange = onDynamicColor, enabled = dynamicAvailable,
                    modifier = Modifier.semantics { contentDescription = dynamicLabel })
            })
            ListItem(
                headlineContent = { Text(stringResource(R.string.accent_color)) },
                supportingContent = { Text(if (usesDynamicColor) stringResource(R.string.accent_dynamic_hint, accentName(settings.accent)) else accentName(settings.accent)) },
                trailingContent = { Box(Modifier.size(24.dp).background(accentPreviewColor(settings.accent), CircleShape)) },
                modifier = Modifier.clickable(enabled = !usesDynamicColor) { dialog = "accent" },
            )
            Text(
                stringResource(R.string.toolbar_position),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            ToolbarPositionCards(settings.toolbarPosition, onToolbarPosition)
            SettingRow(
                stringResource(R.string.web_zoom),
                stringResource(R.string.web_zoom_value, settings.webZoomPercent),
            ) { dialog = "zoom" }
            ListItem(
                headlineContent = { Text(shortcutsLabel) },
                supportingContent = { Text(stringResource(R.string.ntp_show_shortcuts_detail)) },
                trailingContent = {
                    Switch(
                        checked = settings.ntpShowShortcuts,
                        onCheckedChange = onNtpShowShortcuts,
                        modifier = Modifier.semantics { contentDescription = shortcutsLabel },
                    )
                },
            )
            GroupLabel(R.string.privacy_and_downloads)
            SettingRow(stringResource(R.string.clear_browsing_data), stringResource(R.string.clear_browsing_data_summary), onClearBrowsingData)
            ListItem(
                headlineContent = { Text(clearOnExitLabel) },
                supportingContent = { Text(stringResource(R.string.clear_on_exit_detail)) },
                trailingContent = {
                    Switch(
                        checked = settings.clearDataOnExit,
                        onCheckedChange = onClearDataOnExit,
                        modifier = Modifier.semantics { contentDescription = clearOnExitLabel },
                    )
                },
            )
            if (settings.clearDataOnExit) {
                SettingRow(
                    stringResource(R.string.clear_on_exit_types),
                    stringResource(R.string.clear_on_exit_types_count, settings.clearOnExitTypes.size),
                ) { dialog = "exit-types" }
            }
            SettingRow(
                stringResource(R.string.site_settings),
                stringResource(R.string.site_settings_detail),
                modifier = Modifier.testTag("settings-site-settings"),
                onClick = onSiteSettings,
            )
            BrowserSecurityRows(settings.security, onBlockThirdPartyCookies, onHttpsOnly, onSecureDns)
            SettingRow(stringResource(R.string.adblock), stringResource(R.string.adblock_detail), onAdblock)
            GroupLabel(R.string.downloads_and_player)
            SettingRow(stringResource(R.string.downloads), stringResource(R.string.downloads_detail), onDownloads)
            SettingRow(stringResource(R.string.player), stringResource(R.string.player_detail), onPlayer)
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            SettingRow(stringResource(R.string.about), stringResource(R.string.about_summary), onAbout)
        }
    }
    when (dialog) {
        "search" -> ChoiceDialog(
            stringResource(R.string.search_engine),
            SearchEngine.entries,
            settings.searchEngine,
            { engineName(it) },
            { chosen ->
                if (chosen == SearchEngine.CUSTOM) {
                    dialog = "custom-search"
                } else {
                    onSearchEngine(chosen)
                    dialog = null
                }
            },
            { dialog = null },
        )
        "custom-search" -> CustomSearchDialog(
            initialTemplate = settings.customSearchUrl,
            onSave = { template -> onCustomSearchUrl(template); dialog = null },
            onDismiss = { dialog = null },
        )
        "theme" -> ChoiceDialog(stringResource(R.string.theme), ThemeMode.entries, settings.theme, { themeName(it) }, { onTheme(it); dialog = null }, { dialog = null })
        "accent" -> AccentDialog(settings.accent, { onAccent(it); dialog = null }, { dialog = null })
        "zoom" -> WebZoomDialog(settings.webZoomPercent, onApply = { onWebZoomPercent(it); dialog = null }, onDismiss = { dialog = null })
        "exit-types" -> ClearOnExitTypesDialog(settings.clearOnExitTypes, onSave = { onClearOnExitTypes(it); dialog = null }, onDismiss = { dialog = null })
    }
}

@Composable
private fun WebZoomDialog(
    initialPercent: Int,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var percent by remember(initialPercent) { mutableIntStateOf(initialPercent.coerceIn(50, 200)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.web_zoom), modifier = Modifier.semantics { heading() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.web_zoom_value, percent), style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = percent.toFloat(),
                    onValueChange = { value -> percent = ((value / 10f).roundToInt() * 10).coerceIn(50, 200) },
                    valueRange = 50f..200f,
                    steps = 14,
                    modifier = Modifier.testTag("web-zoom-slider"),
                )
                TextButton(onClick = { percent = 100 }, modifier = Modifier.testTag("web-zoom-reset")) {
                    Text(stringResource(R.string.web_zoom_reset))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        confirmButton = {
            TextButton(onClick = { onApply(percent) }, modifier = Modifier.testTag("web-zoom-save")) {
                Text(stringResource(R.string.security_dns_save))
            }
        },
    )
}

@Composable
private fun ClearOnExitTypesDialog(
    selectedTypes: Set<BrowsingDataType>,
    onSave: (Set<BrowsingDataType>) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(selectedTypes) { mutableStateOf(selectedTypes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clear_on_exit_types), modifier = Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BrowsingDataType.entries.forEach { type ->
                    val checked = type in draft
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(checked, role = Role.Checkbox) {
                                draft = if (checked) draft - type else draft + type
                            }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(typeName(type), Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }, modifier = Modifier.testTag("clear-exit-types-save")) {
                Text(stringResource(R.string.security_dns_save))
            }
        },
    )
}

@Composable private fun AccentDialog(selected: AccentColor, onSelect: (AccentColor) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.accent_color)) }, text = {
        Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
            AccentColor.entries.forEach { value ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(selected = selected == value, role = Role.RadioButton, onClick = { onSelect(value) }),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).background(accentPreviewColor(value), CircleShape))
                    Text(accentName(value), Modifier.weight(1f).padding(horizontal = 16.dp))
                    RadioButton(selected == value, onClick = null)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}

@Composable private fun accentName(value: AccentColor): String = stringResource(when (value) {
    AccentColor.RINGO_RED -> R.string.accent_ringo_red
    AccentColor.LEAF_GREEN -> R.string.accent_leaf_green
    AccentColor.NAVY -> R.string.accent_navy
    AccentColor.TEAL -> R.string.accent_teal
    AccentColor.PURPLE -> R.string.accent_purple
    AccentColor.GRAPHITE -> R.string.accent_graphite
})

@Composable private fun GroupLabel(id: Int) { Text(stringResource(id), Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
@Composable internal fun SettingRow(title: String, value: String, onClick: () -> Unit) =
    SettingRow(title, value, Modifier, onClick)
@Composable internal fun SettingRow(title: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(value) }, trailingContent = { Icon(Icons.Rounded.ChevronRight, null) }, modifier = modifier.clickable(onClick = onClick))
}
@Composable internal fun <T> ChoiceDialog(title: String, choices: List<T>, selected: T, name: @Composable (T) -> String, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    MiruChoiceDialog(title, choices, selected, stringResource(R.string.close), name, onSelect, onDismiss)
}
@Composable private fun engineSubtitle(settings: BrowserSettings): String =
    if (settings.searchEngine == SearchEngine.CUSTOM) {
        settings.customSearchUrl?.takeIf { it.isNotBlank() } ?: engineName(SearchEngine.CUSTOM)
    } else {
        engineName(settings.searchEngine)
    }

@Composable private fun engineName(value: SearchEngine): String = stringResource(when(value) {
    SearchEngine.GOOGLE -> R.string.google
    SearchEngine.BING -> R.string.bing
    SearchEngine.DUCKDUCKGO -> R.string.duckduckgo
    SearchEngine.BAIDU -> R.string.baidu
    SearchEngine.CUSTOM -> R.string.search_custom
})
@Composable private fun themeName(value: ThemeMode): String = stringResource(when(value) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(versionName: String, onBack: () -> Unit, onOpenUrl: (String) -> Unit, adblockVersion: String? = null) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.about)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } }) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.miru), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.version, versionName))
            Text(stringResource(R.string.author), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.preview_detail), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.privacy_detail), style = MaterialTheme.typography.bodyMedium)
            if (adblockVersion != null) Text(stringResource(R.string.ublock_version, adblockVersion), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Text(stringResource(R.string.open_source), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onOpenUrl("https://github.com/Ringophilia/Miru") }) { Text(stringResource(R.string.project_source)) }
            TextButton(onClick = { onOpenUrl("https://github.com/Ringophilia/Miru/blob/main/LICENSE") }) { Text(stringResource(R.string.project_license)) }
            TextButton(onClick = { onOpenUrl("https://github.com/Ringophilia/Miru/blob/main/third_party/README.md") }) { Text(stringResource(R.string.third_party_notices)) }
            TextButton(onClick = { onOpenUrl("https://github.com/mozilla-mobile/reference-browser") }) { Text(stringResource(R.string.mozilla_license)) }
            TextButton(onClick = { onOpenUrl("https://www.mozilla.org/MPL/2.0/") }) { Text(stringResource(R.string.mpl)) }
            TextButton(onClick = { onOpenUrl("https://source.android.com/docs/setup/about/licenses") }) { Text(stringResource(R.string.android_license)) }
            TextButton(onClick = { onOpenUrl("https://github.com/gorhill/uBlock") }) { Text(stringResource(R.string.ublock_license)) }
        }
    }
}
