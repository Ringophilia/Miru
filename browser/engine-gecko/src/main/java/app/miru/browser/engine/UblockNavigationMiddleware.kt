// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.concept.engine.EngineSession
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

/** Installed inside the deferred engine gate, before AC can start loading a newly linked session. */
internal class UblockNavigationMiddleware(
    private val onLoadRequested: (String, String) -> Unit,
    private val onSessionLinked: (String, EngineSession, String) -> Unit,
) : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        when (action) {
            is EngineAction.LinkEngineSessionAction -> {
                store.state.tabs.firstOrNull { it.id == action.tabId }?.let { tab ->
                    onSessionLinked(tab.id, action.engineSession, tab.content.url)
                    if (!action.skipLoading) onLoadRequested(tab.id, tab.content.url)
                }
            }
            is TabListAction.AddTabAction -> action.tab.engineState.engineSession?.let { session ->
                onSessionLinked(action.tab.id, session, action.tab.content.url)
                onLoadRequested(action.tab.id, action.tab.content.url)
            }
            is EngineAction.CreateEngineSessionAction -> {
                // AC restores a persisted Gecko state before dispatching LinkEngineSessionAction.
                store.state.tabs.firstOrNull { it.id == action.tabId }?.let { tab ->
                    if (tab.engineState.engineSession == null && tab.engineState.engineSessionState != null) {
                        onLoadRequested(tab.id, tab.content.url)
                    }
                }
            }
            is EngineAction.LoadUrlAction -> {
                if (store.state.tabs.any { it.id == action.tabId && it.engineState.engineSession != null }) {
                    onLoadRequested(action.tabId, action.url)
                }
                // Without a session AC's linking step owns the initial load. Observe that step
                // instead of counting this command and its eventual load as two navigations.
            }
            is EngineAction.ReloadAction -> observeExistingSession(store.state, action.tabId)
            is EngineAction.GoBackAction -> observeExistingSession(store.state, action.tabId)
            is EngineAction.GoForwardAction -> observeExistingSession(store.state, action.tabId)
            is EngineAction.GoToHistoryIndexAction -> observeExistingSession(store.state, action.tabId)
            is EngineAction.ToggleDesktopModeAction -> observeExistingSession(store.state, action.tabId)
            else -> Unit
        }
        next(action)
    }

    private fun observeExistingSession(state: BrowserState, tabId: String) {
        state.tabs.firstOrNull { it.id == tabId && it.engineState.engineSession != null }?.let {
            onLoadRequested(tabId, it.content.url)
        }
    }
}
