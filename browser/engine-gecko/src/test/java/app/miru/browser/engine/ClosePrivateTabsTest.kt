// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.UndoHistoryState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.state.recover.toRecoverableTab
import mozilla.components.browser.state.store.BrowserStore
import org.junit.Assert.*
import org.junit.Test

class ClosePrivateTabsTest {
    @Test fun closesOnlyPrivateTabsAndDropsRecoverablePrivateState() {
        val normal = createTab("https://ordinary.example/", title = "Ordinary tab")
        val private = createTab("https://private.example/", private = true)
        val store = BrowserStore(initialState = BrowserState(tabs = listOf(normal, private), selectedTabId = private.id,
            undoHistory = UndoHistoryState("private-undo", listOf(private.toRecoverableTab()), private.id)))
        closePrivateTabsPermanently(store)
        assertEquals(listOf(normal), store.state.tabs)
        assertEquals(normal.id, store.state.selectedTabId)
        assertTrue(store.state.undoHistory.tabs.isEmpty())
    }
    @Test fun onlyPrivateTabsLeaveOneSelectedNormalNativePageAndRepeatedCloseIsHarmless() {
        val private = createTab("https://private.example/", private = true)
        val store = BrowserStore(initialState = BrowserState(tabs = listOf(private), selectedTabId = private.id))
        closePrivateTabsPermanently(store)
        val normal = store.state.tabs.single()
        assertFalse(normal.content.private)
        assertEquals(NEW_TAB_URL, normal.content.url)
        assertEquals(normal.id, store.state.selectedTabId)
        assertNull(normal.engineState.engineSession)
        closePrivateTabsPermanently(store)
        assertEquals(listOf(normal), store.state.tabs)
    }
    @Test fun staleActionPreservesOrdinarySelectionAndOrdinaryUndo() {
        val normal = createTab("https://ordinary.example/")
        val closed = createTab("https://closed.example/")
        val undo = UndoHistoryState("ordinary-undo", listOf(closed.toRecoverableTab()), closed.id)
        val store = BrowserStore(initialState = BrowserState(tabs = listOf(normal), selectedTabId = normal.id, undoHistory = undo))
        closePrivateTabsPermanently(store)
        assertEquals(listOf(normal), store.state.tabs)
        assertEquals(normal.id, store.state.selectedTabId)
        assertEquals(undo, store.state.undoHistory)
    }
}
