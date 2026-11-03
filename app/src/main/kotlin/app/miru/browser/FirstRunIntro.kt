// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.content.Context
import android.app.role.RoleManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.miru.browser.engine.AdblockStatus
import app.miru.data.BrowserSettings
import app.miru.design.MiruTheme
import app.miru.design.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Starts Gecko after a native frame on both the intro and browser paths. */
@Composable
internal fun FirstRunGate(container: AppContainer, onDefaultBrowser: () -> Unit, browserContent: @Composable () -> Unit) {
    val settings by container.settings.settings.collectAsStateWithLifecycle<BrowserSettings?>(initialValue = null)
    val scope = rememberCoroutineScope()
    LaunchedEffect(container.browser) { withFrameNanos { }; withFrameNanos { }; container.browser.initialize() }
    val loaded = settings
    if (loaded?.introCompleted == true) {
        browserContent()
        return
    }
    MiruTheme(themeMode = loaded?.theme?.let { ThemeMode.valueOf(it.name) } ?: ThemeMode.SYSTEM,
        dynamicColor = loaded?.dynamicColor ?: false, accent = loaded?.accent ?: app.miru.common.AccentColor.RINGO_RED) {
        Surface(Modifier.fillMaxSize()) {
            if (loaded != null) FirstRunIntro(container, onDefaultBrowser, scope)
        }
    }
}

internal fun isDefaultBrowser(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 29) {
    context.getSystemService(RoleManager::class.java)?.let { it.isRoleAvailable(RoleManager.ROLE_BROWSER) && it.isRoleHeld(RoleManager.ROLE_BROWSER) } == true
} else {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))
    context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == context.packageName
}

@Composable
private fun FirstRunIntro(container: AppContainer, onDefaultBrowser: () -> Unit, scope: CoroutineScope) {
    val adblock by container.browser.adblock.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val alreadyDefault = remember(context) { isDefaultBrowser(context) }
    var finishing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun finish(requestDefault: Boolean) {
        if (finishing) return
        finishing = true; failed = false
        scope.launch {
            try {
                container.settings.setIntroCompleted(true)
                if (requestDefault && !alreadyDefault) onDefaultBrowser()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: java.io.IOException) { failed = true; finishing = false }
        }
    }
    BackHandler { finish(false) }
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(app.miru.design.R.drawable.miru_wordmark), stringResource(R.string.app_name),
                Modifier.width(176.dp).height(64.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary))
            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.intro_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Icon(Icons.Outlined.Security, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(when {
                !adblock.enabled || adblock.status == AdblockStatus.DISABLED -> R.string.intro_adblock_disabled
                adblock.status == AdblockStatus.READY -> R.string.intro_adblock_ready
                adblock.status == AdblockStatus.FAILED -> R.string.intro_adblock_failed
                else -> R.string.intro_adblock_loading
            }), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(if (alreadyDefault) R.string.intro_already_default else R.string.intro_default_description),
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            Button(onClick = { finish(true) }, enabled = !finishing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (alreadyDefault) R.string.intro_start else R.string.intro_set_default))
            }
            TextButton(onClick = { finish(false) }, enabled = !finishing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.intro_skip))
            }
            if (failed) Text(stringResource(R.string.intro_save_failed), color = MaterialTheme.colorScheme.error)
        }
    }
}
