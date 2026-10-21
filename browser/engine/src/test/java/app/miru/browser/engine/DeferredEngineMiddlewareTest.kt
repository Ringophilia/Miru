package app.miru.browser.engine

import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.Middleware
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredEngineMiddlewareTest {
    @Test fun `native NTP ignores engine allocation while web tabs allow it`() {
        val gate = DeferredEngineMiddleware()
        val tab = createTab(NEW_TAB_URL)
        val store = BrowserStore(initialState = BrowserState(tabs = listOf(tab), selectedTabId = tab.id), middleware = listOf(gate))
        val observed = mutableListOf<EngineAction>()
        val fakeEngine: Middleware<BrowserState, BrowserAction> = { _, next, action ->
            if (action is EngineAction) observed += action else next(action)
        }
        gate.connect(listOf(fakeEngine), store)
        store.dispatch(EngineAction.CreateEngineSessionAction(tab.id))
        assertTrue(observed.isEmpty())

        val webTab = createTab("https://example.com")
        store.dispatch(TabListAction.AddTabAction(webTab, select = true))
        val createWebSession = EngineAction.CreateEngineSessionAction(webTab.id)
        store.dispatch(createWebSession)
        assertEquals(listOf(createWebSession), observed)
    }

    @Test fun `native tab actions work before Gecko is initialized`() {
        val gate = DeferredEngineMiddleware()
        val store = BrowserStore(middleware = listOf(gate))
        val tab = createTab(NEW_TAB_URL)
        store.dispatch(TabListAction.AddTabAction(tab, select = true))
        assertEquals(tab.id, store.state.selectedTabId)
        assertEquals(NEW_TAB_URL, store.state.tabs.single().content.url)
    }

    @Test fun `engine requests wait for initialization then replay in original order`() {
        val gate = DeferredEngineMiddleware()
        val store = BrowserStore(middleware = listOf(gate))
        val first = EngineAction.ReloadAction("first")
        val second = EngineAction.ReloadAction("second")
        val observed = mutableListOf<BrowserAction>()
        store.dispatch(first)
        store.dispatch(second)
        assertTrue(observed.isEmpty())

        // Real EngineMiddleware consumes ReloadAction before it reaches the reducer. The test double
        // models that boundary without creating Gecko (the reducer intentionally rejects raw engine commands).
        val observer: Middleware<BrowserState, BrowserAction> = { _, _, action ->
            observed += action
        }
        gate.connect(listOf(observer), store)
        assertEquals(listOf(first, second), observed)

        val third = EngineAction.ReloadAction("third")
        store.dispatch(third)
        assertEquals(listOf(first, second, third), observed)
    }
}
