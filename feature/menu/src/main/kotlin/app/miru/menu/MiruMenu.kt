// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.menu

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.AddToHomeScreen
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

enum class MenuAction {
    FORWARD, BOOKMARK, DOWNLOAD, PAGE_INFO, RELOAD,
    NEW_TAB, NEW_PRIVATE_TAB, HISTORY, DOWNLOADS, BOOKMARKS,
    SHARE, FIND_IN_PAGE, ADD_TO_HOME, DESKTOP_SITE, ADBLOCK, SETTINGS,
}

@Composable
fun MiruMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAction: (MenuAction) -> Unit,
    enabledActions: Set<MenuAction>,
    canGoForward: Boolean = false,
    loading: Boolean = false,
    desktopMode: Boolean = false,
    bookmarked: Boolean = false,
    modifier: Modifier = Modifier,
    activeDownloads: Int = 0,
    blockedCount: Int = 0,
) {
    fun invoke(action: MenuAction) { onDismiss(); onAction(action) }
    // Unavailable page actions are left out rather than greyed; on the NTP the whole row disappears.
    // Forward stays visible but disabled on web pages, matching Chrome.
    val pageActions = listOf(MenuAction.FORWARD, MenuAction.BOOKMARK, MenuAction.DOWNLOAD, MenuAction.PAGE_INFO, MenuAction.RELOAD).filter { it in enabledActions }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = modifier.width(260.dp)) {
        if (pageActions.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                pageActions.forEach { action ->
                    when (action) {
                        MenuAction.FORWARD -> MenuIcon(Icons.AutoMirrored.Outlined.ArrowForward, R.string.menu_forward, canGoForward) { invoke(action) }
                        MenuAction.BOOKMARK -> MenuIcon(
                            if (bookmarked) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            if (bookmarked) R.string.menu_bookmark_remove else R.string.menu_bookmark,
                            tint = if (bookmarked) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        ) { invoke(action) }
                        MenuAction.DOWNLOAD -> MenuIcon(Icons.Outlined.Download, R.string.menu_download_page) { invoke(action) }
                        MenuAction.PAGE_INFO -> MenuIcon(Icons.Outlined.Info, R.string.menu_page_info) { invoke(action) }
                        else -> MenuIcon(if (loading) Icons.Outlined.Close else Icons.Outlined.Refresh, if (loading) R.string.menu_stop else R.string.menu_reload) { invoke(action) }
                    }
                }
            }
            HorizontalDivider()
        }
        MenuRow(R.string.menu_new_tab, Icons.Outlined.Add, MenuAction.NEW_TAB, enabledActions, ::invoke)
        MenuRow(R.string.menu_new_incognito, Icons.Outlined.VisibilityOff, MenuAction.NEW_PRIVATE_TAB, enabledActions, ::invoke)
        if (enabledActions.any { it in setOf(MenuAction.HISTORY, MenuAction.DOWNLOADS, MenuAction.BOOKMARKS) }) {
            HorizontalDivider()
            MenuRow(R.string.menu_history, Icons.Outlined.History, MenuAction.HISTORY, enabledActions, ::invoke)
            if (MenuAction.DOWNLOADS in enabledActions) DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_downloads)) },
                leadingIcon = { Icon(Icons.Outlined.Download, null) },
                trailingIcon = if (activeDownloads > 0) ({ Text(stringResource(R.string.menu_download_active, activeDownloads), style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")) }) else null,
                onClick = { invoke(MenuAction.DOWNLOADS) },
            )
            MenuRow(R.string.menu_bookmarks, Icons.Outlined.StarBorder, MenuAction.BOOKMARKS, enabledActions, ::invoke)
        }
        HorizontalDivider()
        MenuRow(R.string.menu_share, Icons.Outlined.Share, MenuAction.SHARE, enabledActions, ::invoke)
        MenuRow(R.string.menu_find, Icons.Outlined.FindInPage, MenuAction.FIND_IN_PAGE, enabledActions, ::invoke)
        MenuRow(R.string.menu_add_to_home, Icons.AutoMirrored.Outlined.AddToHomeScreen, MenuAction.ADD_TO_HOME, enabledActions, ::invoke)
        if (MenuAction.DESKTOP_SITE in enabledActions) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_desktop_site)) },
                leadingIcon = { Icon(Icons.Outlined.Computer, null) },
                trailingIcon = { Checkbox(checked = desktopMode, onCheckedChange = null) },
                onClick = { invoke(MenuAction.DESKTOP_SITE) },
            )
        }
        HorizontalDivider()
        if (MenuAction.ADBLOCK in enabledActions) DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_adblock)) },
            leadingIcon = { Icon(Icons.Outlined.Shield, null) },
            trailingIcon = if (blockedCount > 0) ({ Text(blockedCount.toString(), style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")) }) else null,
            onClick = { invoke(MenuAction.ADBLOCK) },
        )
        MenuRow(R.string.menu_settings, Icons.Outlined.Settings, MenuAction.SETTINGS, enabledActions, ::invoke)
    }
}

@Composable
private fun MenuIcon(icon: ImageVector, label: Int, enabled: Boolean = true, tint: Color = LocalContentColor.current, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icon, stringResource(label), Modifier.size(24.dp), tint = if (enabled) tint else LocalContentColor.current)
    }
}

@Composable
private fun MenuRow(label: Int, icon: ImageVector, action: MenuAction, enabled: Set<MenuAction>, onAction: (MenuAction) -> Unit) {
    if (action in enabled) {
        DropdownMenuItem(
            text = { Text(stringResource(label)) },
            leadingIcon = { Icon(icon, null) },
            onClick = { onAction(action) },
        )
    }
}
