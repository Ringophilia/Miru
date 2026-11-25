// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.action.UndoAction
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore

/** Notification close is final. Ordinary UI closes keep their existing undo behavior. */
internal fun closePrivateTabsPermanently(store: BrowserStore) {
    if (store.state.tabs.any { it.content.private }) store.dispatch(TabListAction.RemoveAllPrivateTabsAction)
    if (store.state.undoHistory.tabs.any { it.state.private }) {
        store.dispatch(UndoAction.ClearRecoverableTabs(store.state.undoHistory.tag))
    }
    if (store.state.tabs.isEmpty()) {
        store.dispatch(TabListAction.AddTabAction(createTab(NEW_TAB_URL), select = true))
    } else if (store.state.tabs.none { it.id == store.state.selectedTabId }) {
        store.dispatch(TabListAction.SelectTabAction(store.state.tabs.last().id))
    }
}
