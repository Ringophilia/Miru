// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.request.RequestInterceptor

/** Native web requests are denied immediately. Only a current UI confirmation can dispatch an Activity. */
class BrowserExternalLinks internal constructor(private val context: Context, private val scope: CoroutineScope,
    private val store: BrowserStore, private val openWeb: (String) -> Unit) : RequestInterceptor, BrowserExternalLinksController {
    private data class Plan(val intent: Intent?, val url: String, val label: String?, val fallback: String?)
    private data class Pending(val ui: ExternalLinkPrompt, val session: EngineSession, val sourceUrl: String, val plan: Plan)
    private var pending: Pending? = null
    private var sequence = 0L
    private val suppressed = Collections.newSetFromMap(IdentityHashMap<EngineSession, Boolean>())
    private val mutablePrompt = MutableStateFlow<ExternalLinkPrompt?>(null)
    override val prompt = mutablePrompt.asStateFlow()
    init {
        scope.launch(Dispatchers.Main.immediate) { store.stateFlow.collect { state ->
            suppressed.retainAll(state.tabs.mapNotNull { it.engineState.engineSession }.toSet())
            pending?.takeUnless(::valid)?.let { dismiss(it.ui.token) }
        } }
    }
    override fun interceptsAppInitiatedRequests() = true
    override fun onLoadRequest(engineSession: EngineSession, uri: String, lastUri: String?, hasUserGesture: Boolean,
        isSameDomain: Boolean, isRedirect: Boolean, isDirectNavigation: Boolean, isSubframeRequest: Boolean): RequestInterceptor.InterceptionResponse? {
        val scheme = linkScheme(uri)
        if(scheme != null && isBrowserScheme(scheme)) {
            if(!isSubframeRequest) scope.launch(Dispatchers.Main.immediate) {
                suppressed.remove(engineSession)
                pending?.takeIf { it.session === engineSession }?.let { dismiss(it.ui.token) }
            }
            return null
        }
        if(!isSubframeRequest && scheme != null && scheme !in setOf("file", "content", "miru")) {
            scope.launch(Dispatchers.Main.immediate) { offer(engineSession, uri, hasUserGesture) }
        }
        return RequestInterceptor.InterceptionResponse.Deny
    }
    private fun offer(session: EngineSession, uri: String, userGesture: Boolean) {
        val tab = store.state.tabs.firstOrNull { it.engineState.engineSession === session && it.id == store.state.selectedTabId && !it.engineState.crashed } ?: return
        val origin = siteOrigin(tab.content.url) ?: return
        if(pending != null || (!userGesture && session in suppressed)) return
        suppressed.add(session)
        val plan = plan(uri) ?: return
        val ui = ExternalLinkPrompt(++sequence, tab.id, origin, tab.content.private, plan.url, plan.label, plan.intent != null, plan.fallback != null, plan.fallback)
        pending = Pending(ui, session, tab.content.url, plan)
        mutablePrompt.value = ui
    }
    private fun plan(uri: String): Plan? = runCatching {
        val parsed = if(linkScheme(uri) in setOf("intent", "android-app")) Intent.parseUri(uri, Intent.URI_INTENT_SCHEME or Intent.URI_ANDROID_APP_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        val data = parsed.data?.normalizeScheme()?.toString() ?: return null
        val pkg = parsed.`package`?.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_.]*")) }
        val fallback = webFallback(parsed.getStringExtra("browser_fallback_url")) ?:
            data.takeIf { pkg == null && webFallback(it) != null }
        if(pkg == context.packageName || !externalDataAllowed(data, pkg != null)) {
            return if(fallback != null) Plan(null, data, null, fallback) else null
        }
        // Rebuild instead of forwarding arbitrary components, selectors, extras, ClipData or URI grants.
        val clean = Intent(Intent.ACTION_VIEW, Uri.parse(data)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg)
        val pm = context.packageManager
        val matches = pm.queryIntentActivities(clean, PackageManager.MATCH_DEFAULT_ONLY).filter {
            it.activityInfo.exported && it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled && it.activityInfo.packageName != context.packageName
        }
        if(matches.isEmpty()) return Plan(null, data, null, fallback)
        val preferred = pm.resolveActivity(clean, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        val chosen = matches.firstOrNull { it.activityInfo.packageName == preferred?.packageName && it.activityInfo.name == preferred?.name }
            ?: matches.singleOrNull()
        if(chosen != null) {
            clean.component = ComponentName(chosen.activityInfo.packageName, chosen.activityInfo.name)
            Plan(clean, data, chosen.loadLabel(pm).toString(), fallback)
        } else Plan(Intent.createChooser(clean, null), data, null, fallback)
    }.getOrNull()

    private fun valid(value: Pending): Boolean = store.state.tabs.any {
        it.id == value.ui.tabId && it.id == store.state.selectedTabId && it.engineState.engineSession === value.session &&
            !it.engineState.crashed && it.content.url == value.sourceUrl && it.content.private == value.ui.isPrivate
    }
    override fun dismiss(token: Long) {
        if(pending?.ui?.token != token) return
        pending = null; mutablePrompt.value = null
    }
    /** A failed app launch keeps its web fallback behind a fresh confirmation. */
    override fun open(token: Long, activity: Activity): Boolean {
        val value = pending?.takeIf { it.ui.token == token && valid(it) } ?: return false
        dismiss(token)
        value.plan.intent?.let { intent ->
            try { activity.startActivity(Intent(intent)); return true }
            catch (_: android.content.ActivityNotFoundException) { }
            catch (_: SecurityException) { }
            if(value.plan.fallback != null && valid(value)) {
                val ui = value.ui.copy(token = ++sequence, appName = null, canOpenApp = false)
                pending = value.copy(ui = ui, plan = value.plan.copy(intent = null))
                mutablePrompt.value = ui
                return true
            }
            return false
        }
        value.plan.fallback?.let { openWeb(it); return true }
        return false
    }
}
