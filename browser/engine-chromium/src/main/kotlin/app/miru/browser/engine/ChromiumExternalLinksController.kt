package app.miru.browser.engine

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application-lifetime coordinator for external app / `intent:` / `android-app:` navigations
 * on Chromium tabs. Mirrors Gecko [BrowserExternalLinks] safety semantics: validates tab ownership,
 * normalizes/sanitizes Intents (dropping components, selectors, extras, ClipData, and URI grants),
 * surfaces uninstalled targets with `canOpenApp = false`, and preserves web fallback confirmation.
 */
internal class ChromiumExternalLinksController(
    private val lookupTab: (String) -> TabMeta?,
    private val selectedTabId: () -> String?,
    private val loadUrlInTab: (tabId: String, url: String) -> Unit,
    private val resolvePlan: (String) -> ExternalPlan?,
) : BrowserExternalLinksController {

    constructor(
        context: Context,
        lookupTab: (String) -> TabMeta?,
        selectedTabId: () -> String?,
        loadUrlInTab: (tabId: String, url: String) -> Unit,
    ) : this(
        lookupTab = lookupTab,
        selectedTabId = selectedTabId,
        loadUrlInTab = loadUrlInTab,
        resolvePlan = defaultPlanResolver(context),
    )

    internal data class TabMeta(
        val tabId: String,
        val isPrivate: Boolean,
        val locationGeneration: Long,
        val attemptGeneration: Long,
        val currentUrl: String,
    )

    internal data class ExternalPlan(
        val intent: Intent?,
        val url: String,
        val label: String?,
        val fallback: String?,
    )

    private data class Pending(
        val ui: ExternalLinkPrompt,
        val plan: ExternalPlan,
        val sourceUrl: String,
        val locationGeneration: Long,
        val attemptGeneration: Long,
    )

    private val mutablePrompt = MutableStateFlow<ExternalLinkPrompt?>(null)
    override val prompt: StateFlow<ExternalLinkPrompt?> = mutablePrompt.asStateFlow()

    private val suppressedTabs = mutableSetOf<String>()
    private var sequence = 0L
    private var pending: Pending? = null

    fun onNavigation(tabId: String) {
        suppressedTabs.remove(tabId)
        if (pending?.ui?.tabId == tabId) {
            clearPending(pending!!.ui.token)
        }
    }

    fun onExternalProtocol(tabId: String, url: String, hasUserGesture: Boolean = true) {
        val tab = lookupTab(tabId) ?: return
        if (tab.tabId != selectedTabId()) return
        val origin = siteOrigin(tab.currentUrl) ?: return
        val scheme = linkScheme(url) ?: return
        if (isBrowserScheme(scheme) || scheme in setOf("file", "content", "miru")) return

        val current = pending
        if (current != null && !isPendingValid(current)) {
            clearPending(current.ui.token)
        }
        if (pending != null || (!hasUserGesture && tabId in suppressedTabs)) return
        suppressedTabs.add(tabId)

        val plan = resolvePlan(url) ?: return
        val ui = ExternalLinkPrompt(
            token = ++sequence,
            tabId = tab.tabId,
            origin = origin,
            isPrivate = tab.isPrivate,
            url = plan.url,
            appName = plan.label,
            canOpenApp = plan.intent != null,
            canOpenWeb = plan.fallback != null,
            webUrl = plan.fallback,
        )
        pending = Pending(
            ui = ui,
            plan = plan,
            sourceUrl = tab.currentUrl,
            locationGeneration = tab.locationGeneration,
            attemptGeneration = tab.attemptGeneration,
        )
        mutablePrompt.value = ui
    }

    fun syncState() {
        val current = pending ?: return
        if (!isPendingValid(current)) {
            clearPending(current.ui.token)
        }
    }

    override fun dismiss(token: Long) {
        clearPending(token)
    }

    override fun open(token: Long, activity: Activity): Boolean {
        val current = pending?.takeIf { it.ui.token == token } ?: return false
        if (!isPendingValid(current)) {
            clearPending(current.ui.token)
            return false
        }
        clearPending(token)

        current.plan.intent?.let { intent ->
            try {
                activity.startActivity(Intent(intent))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
            if (current.plan.fallback != null && isPendingValid(current)) {
                val updated = current.ui.copy(token = ++sequence, appName = null, canOpenApp = false)
                pending = current.copy(ui = updated, plan = current.plan.copy(intent = null))
                mutablePrompt.value = updated
                return true
            }
            return false
        }

        current.plan.fallback?.let { fallbackUrl ->
            loadUrlInTab(current.ui.tabId, fallbackUrl)
            return true
        }
        return false
    }

    private fun isPendingValid(entry: Pending): Boolean {
        val tab = lookupTab(entry.ui.tabId) ?: return false
        return tab.tabId == selectedTabId() &&
            tab.isPrivate == entry.ui.isPrivate &&
            tab.currentUrl == entry.sourceUrl &&
            tab.locationGeneration == entry.locationGeneration &&
            tab.attemptGeneration == entry.attemptGeneration
    }

    private fun clearPending(token: Long) {
        val current = pending ?: return
        if (current.ui.token == token) {
            pending = null
            mutablePrompt.value = null
        }
    }

    companion object {
        private val packageRegex = Regex("[A-Za-z][A-Za-z0-9_.]*")

        private fun defaultPlanResolver(context: Context): (String) -> ExternalPlan? = { uri ->
            runCatching {
                val parsed = if (linkScheme(uri) in setOf("intent", "android-app")) {
                    Intent.parseUri(uri, Intent.URI_INTENT_SCHEME or Intent.URI_ANDROID_APP_SCHEME)
                } else {
                    Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                }
                val data = parsed.data?.normalizeScheme()?.toString() ?: return@runCatching null
                val pkg = parsed.`package`?.takeIf { it.matches(packageRegex) }
                val fallback = webFallback(parsed.getStringExtra("browser_fallback_url"))
                    ?: data.takeIf { pkg == null && webFallback(it) != null }
                if (pkg == context.packageName || !externalDataAllowed(data, pkg != null)) {
                    return@runCatching fallback?.let { ExternalPlan(null, data, null, it) }
                }
                // Rebuild instead of forwarding arbitrary components, selectors, extras, ClipData, or URI grants.
                val clean = Intent(Intent.ACTION_VIEW, Uri.parse(data))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setPackage(pkg)
                val pm = context.packageManager
                val matches = pm.queryIntentActivities(clean, PackageManager.MATCH_DEFAULT_ONLY).filter {
                    it.activityInfo.exported &&
                        it.activityInfo.enabled &&
                        it.activityInfo.applicationInfo.enabled &&
                        it.activityInfo.packageName != context.packageName
                }
                if (matches.isEmpty()) return@runCatching ExternalPlan(null, data, null, fallback)
                val preferred = pm.resolveActivity(clean, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
                val chosen = matches.firstOrNull {
                    it.activityInfo.packageName == preferred?.packageName &&
                        it.activityInfo.name == preferred?.name
                } ?: matches.singleOrNull()
                if (chosen != null) {
                    clean.component = ComponentName(chosen.activityInfo.packageName, chosen.activityInfo.name)
                    ExternalPlan(clean, data, chosen.loadLabel(pm).toString(), fallback)
                } else {
                    ExternalPlan(Intent.createChooser(clean, null), data, null, fallback)
                }
            }.getOrNull()
        }
    }
}
