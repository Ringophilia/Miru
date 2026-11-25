// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser.engine

import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

/** Allows a fully native NTP and tab operations before Gecko is initialized on its required UI thread. */
internal class DeferredEngineMiddleware : Middleware<BrowserState, BrowserAction> {
    private var delegates: List<Middleware<BrowserState, BrowserAction>>? = null
    private val pendingActions = mutableListOf<EngineAction>()

    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        // A running SessionFeature can see a newly selected NTP before Compose disposes its web view.
        // Prevent that transient observation (or NTP refresh/desktop actions) from allocating Gecko content.
        if (action is EngineAction.CreateEngineSessionAction && store.state.findTab(action.tabId)?.content?.url == NEW_TAB_URL) return
        val current = delegates
        if (current == null) {
            if (action is EngineAction) pendingActions += action else next(action)
            return
        }
        val chain = current.asReversed().fold(next) { following, middleware ->
            { forwarded: BrowserAction -> middleware(store, following, forwarded) }
        }
        chain(action)
    }

    fun connect(middleware: List<Middleware<BrowserState, BrowserAction>>, store: BrowserStore) {
        delegates = middleware
        val actions = pendingActions.toList()
        pendingActions.clear()
        actions.forEach(store::dispatch)
    }
}
