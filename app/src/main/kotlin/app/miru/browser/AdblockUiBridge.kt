// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.miru.adblock.AdblockPopupSheet
import app.miru.adblock.AdblockScreen
import app.miru.adblock.AdblockSheet
import app.miru.adblock.AdblockUiState
import app.miru.adblock.NativeAdblockRulesScreen
import app.miru.adblock.NativeAdblockSheet
import app.miru.adblock.NativeAdblockUiState
import app.miru.adblock.NativeFilterListItem
import app.miru.browser.engine.AdblockSnapshot
import app.miru.browser.engine.BrowserEngineController
import app.miru.browser.engine.BrowserPopupSession
import app.miru.browser.engine.NativeAdblockState

internal fun NativeAdblockState.toUiState(adblock: AdblockSnapshot): NativeAdblockUiState =
    NativeAdblockUiState(
        enabled = adblock.enabled && enabled,
        engineVersion = engineVersion,
        pageBlockedCount = adblock.blockedCount,
        totalBlockedCount = adblock.totalBlockedCount,
        siteBlockingEnabled = adblock.siteBlockingEnabled,
        updatingLists = updatingLists,
        filterLists = filterLists.map { list ->
            NativeFilterListItem(
                id = list.id,
                title = list.title,
                enabled = list.enabled,
                ruleCount = list.ruleCount,
                builtIn = list.builtIn,
            )
        },
        allowedHosts = allowedHosts,
        customRules = customRules,
    )

@Composable
internal fun MiruAdblockSheetHost(
    host: String,
    adblock: AdblockSnapshot,
    nativeState: NativeAdblockState,
    adblockUi: AdblockUiState,
    adblockPopup: BrowserPopupSession?,
    controller: BrowserEngineController,
    onDismissPanel: () -> Unit,
    onOpenRulesRoute: () -> Unit,
    onOpenLegacyDashboard: () -> Unit,
) {
    if (adblock.nativeControlsAvailable) {
        NativeAdblockSheet(
            host = host,
            state = nativeState.toUiState(adblock),
            onDismiss = onDismissPanel,
            onToggleSiteBlocking = { enabled ->
                onDismissPanel()
                if (host.isNotBlank() && controller.setSiteAdblockEnabled(host, enabled)) {
                    controller.reload()
                }
            },
            onStartElementPicker = {
                onDismissPanel()
                controller.startAdblockElementPicker()
            },
            onOpenRulesManager = {
                onDismissPanel()
                onOpenRulesRoute()
            },
        )
        return
    }
    if (adblockPopup == null) {
        AdblockSheet(
            host = host,
            count = adblock.blockedCount,
            state = adblockUi,
            onDismiss = onDismissPanel,
            onRetry = controller::retryAdblock,
            onOpenSiteControls = { if (controller.openAdblockPopup()) onDismissPanel() },
            onOpenDashboard = onOpenLegacyDashboard,
            onReload = { onDismissPanel(); controller.reload() },
        )
    } else {
        AdblockPopupSheet(
            host = host,
            count = adblock.blockedCount,
            onDismiss = { controller.closeAdblockPopup(); controller.reload() },
            onOpenDashboard = onOpenLegacyDashboard,
            pageNeedsReload = adblock.pageNeedsReload,
        ) {
            LegacyAdblockPopupContent(adblockPopup)
        }
    }
}

@Composable
internal fun MiruAdblockRouteScreen(
    adblock: AdblockSnapshot,
    nativeState: NativeAdblockState,
    adblockUi: AdblockUiState,
    controller: BrowserEngineController,
    onBack: () -> Unit,
    onOpenLegacyDashboard: (exceptions: Boolean) -> Unit,
) {
    if (adblock.nativeControlsAvailable) {
        NativeAdblockRulesScreen(
            state = nativeState.toUiState(adblock),
            onBack = onBack,
            onGlobalEnabledChanged = controller::setAdblockEnabled,
            onFilterListToggled = controller::toggleAdblockFilterList,
            onRefreshLists = controller::refreshAdblockLists,
            onRemoveAllowedHost = { host -> controller.setSiteAdblockEnabled(host, true) },
            onAddCustomRule = { rule -> controller.addAdblockCustomRule(rule) },
            onRemoveCustomRule = { rule -> controller.removeAdblockCustomRule(rule) },
        )
    } else {
        AdblockScreen(
            state = adblockUi,
            onBack = onBack,
            onEnabledChanged = controller::setAdblockEnabled,
            onRetry = controller::retryAdblock,
            onOpenExceptions = { onOpenLegacyDashboard(true) },
            onOpenDashboard = { onOpenLegacyDashboard(false) },
        )
    }
}

@Composable
private fun LegacyAdblockPopupContent(popup: BrowserPopupSession) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember(popup) { popup.createView(context) }
    DisposableEffect(view, lifecycle) {
        var started = false
        fun start() { if (!started) { view.onStart(); view.onResume(); started = true } }
        fun stop() { if (started) { view.onPause(); view.onStop(); started = false } }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { lifecycle.removeObserver(observer); stop(); view.release(); popup.close() }
    }
    AndroidView(
        factory = { view.asView().apply { layoutParams = ViewGroup.LayoutParams(-1, -1) } },
        modifier = Modifier.fillMaxSize(),
    )
}
