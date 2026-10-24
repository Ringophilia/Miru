// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.util.UUID
import kotlinx.coroutines.suspendCancellableCoroutine
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.EngineSession
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Excludes transient page progress/title, but detects destructive changes while native preparation awaits. */
internal fun formResetIdentity(state: BrowserState): Pair<String?, List<FormResetTabIdentity>> =
    state.selectedTabId to state.tabs.map { tab ->
        FormResetTabIdentity(tab.id, tab.content.url, tab.content.private, tab.contextId,
            tab.content.desktopMode, tab.parentId, tab.engineState.engineSession)
    }

internal data class FormResetTabIdentity(
    val id: String,
    val url: String,
    val private: Boolean,
    val contextId: String?,
    val desktopMode: Boolean,
    val parentId: String?,
    val session: EngineSession?,
)

/** AddTabAction requires the parent to exist; validate the entire graph before destroying anything. */
internal fun formResetInsertionOrder(tabs: List<TabSessionState>): List<String> {
    val byId = tabs.associateBy { it.id }
    check(byId.size == tabs.size) { "FORM_RESET_DUPLICATE_TABS" }
    val visiting = mutableSetOf<String>()
    val ordered = linkedSetOf<String>()
    fun visit(id: String) {
        if(id in ordered) return
        check(visiting.add(id)) { "FORM_RESET_PARENT_CYCLE" }
        val tab = checkNotNull(byId[id]) { "FORM_RESET_MISSING_PARENT" }
        tab.parentId?.let(::visit)
        visiting.remove(id)
        ordered.add(id)
    }
    tabs.forEach { visit(it.id) }
    return ordered.toList()
}

/**
 * Keep a real attached private context alive before the old ones close. Initial about:blank events
 * are deliberately suppressed by AC 158, so use an inert local document with an observed marker.
 * This never reads/copies cookies and never fetches a URL or writes private state to disk.
 */
internal suspend fun preparePrivateFormSession(
    engine: GeckoEngine,
    contextId: String?,
    desktopMode: Boolean,
): EngineSession {
    val session = engine.createSession(private = true, contextId = contextId)
    val marker = "Miru private form reset ${UUID.randomUUID()}"
    var preparationObserver: EngineSession.Observer? = null
    try {
        session.toggleDesktopMode(enable = desktopMode, reload = false)
        suspendCancellableCoroutine<Unit> { continuation ->
            var started = false
            var finished = false
            var titleSeen = false
            fun finishWhenReady() {
                if(started && finished && titleSeen && continuation.isActive) continuation.resume(Unit)
            }
            val observer = object : EngineSession.Observer {
                override fun onTitleChange(title: String) {
                    if(title == marker) { titleSeen = true; finishWhenReady() }
                }
                override fun onLoadingStateChange(loading: Boolean) {
                    if(loading) { started = true; finished = false }
                    else if(started) { finished = true; finishWhenReady() }
                }
                override fun onCrash() {
                    if(continuation.isActive) continuation.resumeWithException(IllegalStateException("FORM_RESET_SESSION_CRASHED"))
                }
                override fun onProcessKilled() {
                    if(continuation.isActive) continuation.resumeWithException(IllegalStateException("FORM_RESET_SESSION_KILLED"))
                }
            }
            preparationObserver = observer
            session.register(observer)
            try {
                session.loadData("<!doctype html><title>$marker</title>", "text/html", "UTF-8")
            } catch(failure: Exception) {
                if(continuation.isActive) continuation.resumeWithException(failure)
            }
        }
        return session
    } catch(failure: Throwable) {
        runCatching { session.close() }
        throw failure
    } finally {
        preparationObserver?.let(session::unregister)
    }
}
