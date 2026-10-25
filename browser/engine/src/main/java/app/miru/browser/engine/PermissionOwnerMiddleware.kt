// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

/** Capture real engine identity before AC registers its asynchronous store observer or starts loading. */
internal class PermissionOwnerMiddleware(private val permissions: BrowserPermissions) : Middleware<BrowserState, BrowserAction> {
    override fun invoke(store: Store<BrowserState, BrowserAction>, next: (BrowserAction) -> Unit, action: BrowserAction) {
        fun invalidate(id: String) = permissions.invalidateTab(id)
        when(action) {
            is EngineAction.LinkEngineSessionAction -> store.state.tabs.firstOrNull { it.id == action.tabId }?.let { tab ->
                permissions.onSessionLinked(tab.id, action.engineSession, tab.content.url, tab.content.private, tab.contextId)
            }
            is TabListAction.AddTabAction -> action.tab.engineState.engineSession?.let { session ->
                permissions.onSessionLinked(action.tab.id, session, action.tab.content.url, action.tab.content.private, action.tab.contextId)
            }
            is TabListAction.SelectTabAction -> if(action.tabId != store.state.selectedTabId) store.state.selectedTabId?.let(::invalidate)
            is TabListAction.RemoveTabAction -> invalidate(action.tabId)
            is TabListAction.RemoveTabsAction -> action.tabIds.forEach(::invalidate)
            is TabListAction.RemoveAllTabsAction -> permissions.invalidateAll()
            is TabListAction.RemoveAllPrivateTabsAction -> store.state.tabs.filter { it.content.private }.forEach { invalidate(it.id) }
            is TabListAction.RemoveAllNormalTabsAction -> store.state.tabs.filterNot { it.content.private }.forEach { invalidate(it.id) }
            is EngineAction.LoadUrlAction -> invalidate(action.tabId)
            is EngineAction.LoadDataAction -> invalidate(action.tabId)
            is EngineAction.ReloadAction -> invalidate(action.tabId)
            is EngineAction.GoBackAction -> invalidate(action.tabId)
            is EngineAction.GoForwardAction -> invalidate(action.tabId)
            is EngineAction.GoToHistoryIndexAction -> invalidate(action.tabId)
            is EngineAction.ToggleDesktopModeAction -> invalidate(action.tabId)
            is EngineAction.UnlinkEngineSessionAction -> invalidate(action.tabId)
            is EngineAction.SuspendEngineSessionAction -> invalidate(action.tabId)
            is EngineAction.KillEngineSessionAction -> invalidate(action.tabId)
            is ContentAction.UpdateUrlAction -> if(store.state.tabs.firstOrNull { it.id == action.sessionId }?.content?.url != action.url) invalidate(action.sessionId)
            is ContentAction.UpdatePermissionsRequest -> if(!permissions.acceptRequest(action.sessionId, action.permissionRequest, app = false)) return
            is ContentAction.UpdateAppPermissionsRequest -> if(!permissions.acceptRequest(action.sessionId, action.appPermissionRequest, app = true)) return
            else -> Unit
        }
        next(action)
        when(action) {
            is ContentAction.UpdatePermissionsRequest -> permissions.requestReduced(action.sessionId, action.permissionRequest, app = false)
            is ContentAction.UpdateAppPermissionsRequest -> permissions.requestReduced(action.sessionId, action.appPermissionRequest, app = true)
            else -> Unit
        }
    }
}
