// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import app.miru.browser.engine.BrowserController

/** Per-method tab ownership on a controlled, single-instrumentation device. Call on Main. */
internal class BrowserTabFixture {
    private var originalIds: Set<String>? = null
    private var originalSelection: String? = null

    fun capture(browser: BrowserController) {
        check(browser.state.value.ready) { "Capture fixture ownership only after session restoration" }
        check(originalIds == null) { "Fixture ownership must be captured once per test method" }
        originalIds = browser.state.value.tabs.mapTo(mutableSetOf()) { it.id }
        originalSelection = browser.state.value.selectedTabId
    }

    fun closeCreatedTabs(browser: BrowserController) {
        // A failed setup before capture must never turn an empty baseline into permission
        // to remove pre-existing tabs. No global close-all operation belongs in cleanup.
        val preserved = originalIds ?: return
        val owned = browser.state.value.tabs.map { it.id }.filterNot { it in preserved }.toSet()
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (problem: Throwable) {
                val first = failure
                if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
            }
        }
        cleanup {
            browser.prompts.prompt.value?.takeIf { it.tabId in owned }?.let { browser.prompts.dismiss(it.token) }
        }
        owned.forEach { id -> cleanup {
            if (browser.state.value.tabs.any { it.id == id }) browser.closeTab(id)
        } }
        cleanup {
            originalSelection?.takeIf { id -> browser.state.value.tabs.any { it.id == id } }?.let(browser::selectTab)
        }
        cleanup {
            check(browser.state.value.tabs.map { it.id }.containsAll(preserved)) {
                "Fixture cleanup must preserve every pre-existing tab"
            }
        }
        failure?.let { throw it }
    }
}
