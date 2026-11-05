// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.SecurityInfo
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

/** A new load cannot display the previous document's certificate, including same-host reloads. */
internal class SecurityInfoResetMiddleware : Middleware<BrowserState, BrowserAction> {
    override fun invoke(store: Store<BrowserState, BrowserAction>, next: (BrowserAction) -> Unit, action: BrowserAction) {
        val id = when (action) {
            is ContentAction.UpdateLoadingStateAction -> action.sessionId.takeIf { action.loading }
            is EngineAction.LinkEngineSessionAction -> action.tabId
            is EngineAction.LoadUrlAction -> action.tabId
            is EngineAction.LoadDataAction -> action.tabId
            is EngineAction.ReloadAction -> action.tabId
            is EngineAction.GoBackAction -> action.tabId
            is EngineAction.GoForwardAction -> action.tabId
            is EngineAction.GoToHistoryIndexAction -> action.tabId
            else -> null
        }
        if (id != null) next(ContentAction.UpdateSecurityInfoAction(id, SecurityInfo.Unknown))
        next(action)
    }
}
