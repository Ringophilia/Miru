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

/** Install the real-session observer before AC can register its asynchronous observer or load a page. */
internal class PromptOwnerMiddleware(private val prompts: BrowserPrompts) : Middleware<BrowserState, BrowserAction> {
    override fun invoke(store: Store<BrowserState, BrowserAction>, next: (BrowserAction) -> Unit, action: BrowserAction) {
        fun invalidate(id: String) = prompts.invalidateTab(id)
        var reducedAction = action
        when(action) {
            is EngineAction.LinkEngineSessionAction -> store.state.tabs.firstOrNull { it.id == action.tabId }?.let { tab ->
                prompts.onSessionLinked(tab.id, action.engineSession, tab.content.url, tab.content.private, tab.contextId)
            }
            is TabListAction.AddTabAction -> {
                if(action.select && action.tab.id != store.state.selectedTabId) store.state.selectedTabId?.let(::invalidate)
                action.tab.engineState.engineSession?.let { session ->
                    prompts.onSessionLinked(action.tab.id, session, action.tab.content.url, action.tab.content.private, action.tab.contextId)
                }
            }
            is TabListAction.AddMultipleTabsAction -> action.tabs.forEach { tab ->
                tab.engineState.engineSession?.let { session ->
                    prompts.onSessionLinked(tab.id, session, tab.content.url, tab.content.private, tab.contextId)
                }
            }
            is TabListAction.SelectTabAction -> if(action.tabId != store.state.selectedTabId) store.state.selectedTabId?.let(::invalidate)
            is TabListAction.RemoveTabAction -> invalidate(action.tabId)
            is TabListAction.RemoveTabsAction -> action.tabIds.forEach(::invalidate)
            is TabListAction.RemoveAllTabsAction -> prompts.invalidateAll()
            is TabListAction.RemoveAllPrivateTabsAction -> store.state.tabs.filter { it.content.private }.forEach { invalidate(it.id) }
            is TabListAction.RemoveAllNormalTabsAction -> store.state.tabs.filterNot { it.content.private }.forEach { invalidate(it.id) }
            is EngineAction.LoadUrlAction -> prompts.onNavigationAttempt(action.tabId)
            is EngineAction.LoadDataAction -> prompts.onNavigationAttempt(action.tabId)
            is EngineAction.ReloadAction -> prompts.onNavigationAttempt(action.tabId)
            is EngineAction.GoBackAction -> prompts.onNavigationAttempt(action.tabId)
            is EngineAction.GoForwardAction -> prompts.onNavigationAttempt(action.tabId)
            is EngineAction.GoToHistoryIndexAction -> prompts.onNavigationAttempt(action.tabId)
            is EngineAction.ToggleDesktopModeAction -> prompts.onNavigationAttempt(action.tabId)
            is EngineAction.UnlinkEngineSessionAction -> invalidate(action.tabId)
            is EngineAction.SuspendEngineSessionAction -> invalidate(action.tabId)
            is EngineAction.KillEngineSessionAction -> invalidate(action.tabId)
            is ContentAction.UpdatePromptRequestAction -> if(!prompts.acceptRequest(action.sessionId, action.promptRequest)) return
            is ContentAction.ReplacePromptRequestAction -> {
                if(!prompts.acceptReplacement(action.sessionId, action.previousPromptUid, action.promptRequest)) return
                reducedAction = action.copy(previousPromptUid = prompts.replacementStoreUid(action.promptRequest, action.previousPromptUid))
            }
            // Delayed store URL/loading actions are not new native navigation events.
            else -> Unit
        }
        next(reducedAction)
        when(action) {
            is ContentAction.UpdatePromptRequestAction -> prompts.requestReduced(action.sessionId, action.promptRequest)
            is ContentAction.ReplacePromptRequestAction -> prompts.requestReduced(action.sessionId, action.promptRequest)
            is ContentAction.ConsumePromptRequestAction -> prompts.requestConsumed(action.sessionId, action.promptRequest)
            else -> Unit
        }
    }
}
