package app.miru.browser.engine

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI

/**
 * Application-lifetime coordinator for Chromium tab prompts:
 * JavaScript dialogs (`alert`/`confirm`/`prompt`/`beforeunload` with repeated suppression),
 * HTTP 401/407 authentication (`MiruLoginDelegate`), POST resubmission warnings (`REPOST`),
 * and `window.open` popup blocking with 6-second allow/dismiss banner & persistent allowlist.
 */
internal class ChromiumPromptsController(
    private val scope: CoroutineScope,
    private val selectedTabId: () -> String?,
    private val lookupTab: (String) -> TabMeta?,
    private val hasPrivateTabs: () -> Boolean,
    private val popupPermissions: PopupPermissions,
    private val resolveJsDialogNative: (tabId: String, dialogId: Long, accept: Boolean, input: String) -> Unit,
    private val resolveHttpAuthNative: (authId: Long, submit: Boolean, userName: String, password: String) -> Unit,
    private val resolveRepostNative: (tabId: String, resubmit: Boolean) -> Unit,
    private val openPopupTarget: (openerTabId: String, targetUrl: String, isPrivate: Boolean) -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : BrowserPromptsController {

    constructor(
        context: Context,
        scope: CoroutineScope,
        selectedTabId: () -> String?,
        lookupTab: (String) -> TabMeta?,
        hasPrivateTabs: () -> Boolean,
        resolveJsDialogNative: (tabId: String, dialogId: Long, accept: Boolean, input: String) -> Unit,
        resolveHttpAuthNative: (authId: Long, submit: Boolean, userName: String, password: String) -> Unit,
        resolveRepostNative: (tabId: String, resubmit: Boolean) -> Unit,
        openPopupTarget: (openerTabId: String, targetUrl: String, isPrivate: Boolean) -> Unit,
    ) : this(
        scope = scope,
        selectedTabId = selectedTabId,
        lookupTab = lookupTab,
        hasPrivateTabs = hasPrivateTabs,
        popupPermissions = sharedPreferencesPopups(context),
        resolveJsDialogNative = resolveJsDialogNative,
        resolveHttpAuthNative = resolveHttpAuthNative,
        resolveRepostNative = resolveRepostNative,
        openPopupTarget = openPopupTarget,
    )

    internal data class TabMeta(
        val tabId: String,
        val pageUrl: String,
        val isPrivate: Boolean,
        val hardGeneration: Long,
        val locationGeneration: Long,
        val attemptGeneration: Long,
    )

    private sealed class NativeRequest {
        abstract val shouldDismissOnLoad: Boolean
        abstract fun cancel(tabId: String)

        data class JsDialog(
            val dialogId: Long,
            val kind: BrowserPromptKind,
            val message: String,
            val defaultPrompt: String,
            val hasShownMany: Boolean,
            val resolve: (Long, Boolean, String) -> Unit,
        ) : NativeRequest() {
            override val shouldDismissOnLoad: Boolean = true
            override fun cancel(tabId: String) = resolve(dialogId, false, "")
        }

        data class BeforeUnload(
            val dialogId: Long,
            val isReload: Boolean,
            val resolve: (Long, Boolean, String) -> Unit,
        ) : NativeRequest() {
            override val shouldDismissOnLoad: Boolean = false
            override fun cancel(tabId: String) = resolve(dialogId, false, "")
        }

        data class HttpAuth(
            val authId: Long,
            val url: String,
            val challenger: String,
            val realm: String,
            val scheme: String,
            val isProxy: Boolean,
            val previousFailed: Boolean,
            val resolve: (Long, Boolean, String, String) -> Unit,
        ) : NativeRequest() {
            override val shouldDismissOnLoad: Boolean = false
            override fun cancel(tabId: String) = resolve(authId, false, "", "")
        }

        data class Repost(
            val resolve: (Boolean) -> Unit,
        ) : NativeRequest() {
            override val shouldDismissOnLoad: Boolean = false
            override fun cancel(tabId: String) = resolve(false)
        }
    }

    private class PendingPrompt(
        val token: Long,
        val tabId: String,
        val topOrigin: String?,
        val isPrivate: Boolean,
        val hardGeneration: Long,
        val locationGeneration: Long,
        val attemptGeneration: Long,
        val native: NativeRequest,
    )

    private class PendingPopup(
        val token: Long,
        val tabId: String,
        val origin: String,
        val targetUrl: String,
        val isPrivate: Boolean,
        val hardGeneration: Long,
        val locationGeneration: Long,
        val attemptGeneration: Long,
        var expiryJob: Job? = null,
    )

    private class TabDialogPolicy(
        var suppressDialogs: Boolean = false,
        var scriptDialogCount: Int = 0,
    )

    private val tabPolicies = mutableMapOf<String, TabDialogPolicy>()
    private val pendingPrompts = mutableListOf<PendingPrompt>()
    private val pendingPopups = mutableListOf<PendingPopup>()
    private var sequence = 0L

    private val mutablePrompt = MutableStateFlow<BrowserPromptState?>(null)
    override val prompt: StateFlow<BrowserPromptState?> = mutablePrompt.asStateFlow()

    private val mutablePopup = MutableStateFlow<BlockedPopup?>(null)
    override val blockedPopup: StateFlow<BlockedPopup?> = mutablePopup.asStateFlow()

    private val mutablePopupSite = MutableStateFlow<PopupSiteState?>(null)
    override val popupSite: StateFlow<PopupSiteState?> = mutablePopupSite.asStateFlow()

    private val mutableCertResults = MutableSharedFlow<ClientCertificateResult>(extraBufferCapacity = 1)
    override val clientCertificateResults: SharedFlow<ClientCertificateResult> = mutableCertResults.asSharedFlow()

    fun onNavigationAttempt(tabId: String) {
        cancelPrompts(tabId) { it.native.shouldDismissOnLoad }
        cancelPopups(tabId) { true }
        publish()
    }

    fun onLocationChange(tabId: String, preserveAuth: Boolean = false) {
        tabPolicies[tabId]?.let {
            it.suppressDialogs = false
            it.scriptDialogCount = 0
        }
        cancelPrompts(tabId) { !(preserveAuth && it.native is NativeRequest.HttpAuth) }
        cancelPopups(tabId) { true }
        publish()
    }

    fun onMainFrameDocumentReady(tabId: String) {
        tabPolicies[tabId]?.let {
            it.suppressDialogs = false
            it.scriptDialogCount = 0
        }
    }

    fun invalidateTab(tabId: String) {
        tabPolicies.remove(tabId)
        cancelPrompts(tabId) { true }
        cancelPopups(tabId) { true }
        publish()
    }

    fun invalidateAll() {
        tabPolicies.clear()
        pendingPrompts.toList().forEach { finishPrompt(it) { it.native.cancel(it.tabId) } }
        pendingPopups.toList().forEach { finishPopup(it, allow = false) }
        publish()
    }

    fun syncState() {
        if (!hasPrivateTabs()) popupPermissions.clearPrivate()
        pendingPrompts.toList().filterNot(::isPromptValid).forEach { finishPrompt(it) { it.native.cancel(it.tabId) } }
        pendingPopups.toList().filterNot(::isPopupValid).forEach { finishPopup(it, allow = false) }
        publish()
    }

    fun onJavaScriptDialog(
        tabId: String,
        dialogId: Long,
        dialogType: Int,
        message: String,
        defaultPrompt: String,
    ) {
        val tab = lookupTab(tabId)
        val policy = tabPolicies.getOrPut(tabId) { TabDialogPolicy() }
        if (tab == null || tabId != selectedTabId() || policy.suppressDialogs ||
            pendingPrompts.count { it.tabId == tabId } >= MAX_PENDING_PER_TAB
        ) {
            resolveJsDialogNative(tabId, dialogId, false, "")
            return
        }
        val kind = when (dialogType) {
            1 -> BrowserPromptKind.CONFIRM
            2 -> BrowserPromptKind.TEXT
            else -> BrowserPromptKind.ALERT
        }
        val hasMany = policy.scriptDialogCount >= 1
        policy.scriptDialogCount++
        enqueuePrompt(
            tab = tab,
            native = NativeRequest.JsDialog(
                dialogId = dialogId,
                kind = kind,
                message = message,
                defaultPrompt = defaultPrompt,
                hasShownMany = hasMany,
                resolve = { id, accept, input -> resolveJsDialogNative(tabId, id, accept, input) },
            ),
        )
    }

    fun onBeforeUnloadDialog(tabId: String, dialogId: Long, isReload: Boolean) {
        val tab = lookupTab(tabId)
        if (tab == null || tabId != selectedTabId() ||
            pendingPrompts.count { it.tabId == tabId } >= MAX_PENDING_PER_TAB
        ) {
            resolveJsDialogNative(tabId, dialogId, false, "")
            return
        }
        enqueuePrompt(
            tab = tab,
            native = NativeRequest.BeforeUnload(
                dialogId = dialogId,
                isReload = isReload,
                resolve = { id, accept, input -> resolveJsDialogNative(tabId, id, accept, input) },
            ),
        )
    }

    fun onDialogsCancelled(tabId: String, resetState: Boolean) {
        if (resetState) {
            tabPolicies[tabId]?.let {
                it.suppressDialogs = false
                it.scriptDialogCount = 0
            }
        }
        pendingPrompts.toList()
            .filter {
                it.tabId == tabId &&
                    (it.native is NativeRequest.JsDialog || it.native is NativeRequest.BeforeUnload)
            }
            .forEach { entry -> pendingPrompts.remove(entry) }
        publish()
    }

    fun onHttpAuthRequest(
        tabId: String,
        authId: Long,
        url: String,
        challenger: String,
        realm: String,
        scheme: String,
        isProxy: Boolean,
        previousFailed: Boolean,
    ) {
        val tab = lookupTab(tabId)
        if (tab == null || tabId != selectedTabId() ||
            pendingPrompts.count { it.tabId == tabId } >= MAX_PENDING_PER_TAB
        ) {
            resolveHttpAuthNative(authId, false, "", "")
            return
        }
        enqueuePrompt(
            tab = tab,
            native = NativeRequest.HttpAuth(
                authId = authId,
                url = url,
                challenger = challenger,
                realm = realm,
                scheme = scheme,
                isProxy = isProxy,
                previousFailed = previousFailed,
                resolve = resolveHttpAuthNative,
            ),
        )
    }

    fun onHttpAuthCancelled(authId: Long) {
        val entry = pendingPrompts.firstOrNull {
            (it.native as? NativeRequest.HttpAuth)?.authId == authId
        } ?: return
        pendingPrompts.remove(entry)
        publish()
    }

    fun onRepostDialog(tabId: String) {
        val tab = lookupTab(tabId)
        if (tab == null || tabId != selectedTabId() ||
            pendingPrompts.any { it.tabId == tabId && it.native is NativeRequest.Repost } ||
            pendingPrompts.count { it.tabId == tabId } >= MAX_PENDING_PER_TAB
        ) {
            resolveRepostNative(tabId, false)
            return
        }
        enqueuePrompt(
            tab = tab,
            native = NativeRequest.Repost { resubmit -> resolveRepostNative(tabId, resubmit) },
        )
    }

    fun shouldAllowPopupWindow(
        tabId: String,
        targetUrl: String,
        userGesture: Boolean,
        openerSuppressed: Boolean,
    ): Boolean {
        val tab = lookupTab(tabId) ?: return false
        if (tabId != selectedTabId() || openerSuppressed) return false
        val origin = siteOrigin(tab.pageUrl) ?: return false
        if (!isWebPopupTarget(targetUrl)) return false
        val contextKey = contextKeyFor(tab.isPrivate)
        if (popupPermissions.allowed(origin, tab.isPrivate, contextKey)) {
            return true
        }
        if (userGesture) {
            return true
        }
        queueBlockedPopup(tab, origin, targetUrl)
        return false
    }

    private fun queueBlockedPopup(tab: TabMeta, origin: String, targetUrl: String) {
        if (pendingPopups.count { it.tabId == tab.tabId } >= MAX_PENDING_PER_TAB) return
        val popup = PendingPopup(
            token = ++sequence,
            tabId = tab.tabId,
            origin = origin,
            targetUrl = targetUrl,
            isPrivate = tab.isPrivate,
            hardGeneration = tab.hardGeneration,
            locationGeneration = tab.locationGeneration,
            attemptGeneration = tab.attemptGeneration,
        )
        pendingPopups += popup
        popup.expiryJob = scope.launch(mainDispatcher) {
            delay(POPUP_BANNER_TIMEOUT_MS)
            if (popup in pendingPopups) {
                finishPopup(popup, allow = false)
                publish()
            }
        }
        publish()
    }

    override fun allowPopup(token: Long) {
        val popup = pendingPopups.firstOrNull { it.token == token } ?: return
        if (!isPopupValid(popup)) {
            finishPopup(popup, allow = false)
            publish()
            return
        }
        val tab = lookupTab(popup.tabId) ?: return
        popupPermissions.set(tab.pageUrl, tab.isPrivate, contextKeyFor(tab.isPrivate), true)
        finishPopup(popup, allow = true)
        publish()
    }

    override fun dismissPopup(token: Long) {
        val popup = pendingPopups.firstOrNull { it.token == token } ?: return
        finishPopup(popup, allow = false)
        publish()
    }

    override fun setPopupAllowed(site: PopupSiteState, allowed: Boolean) {
        if (mutablePopupSite.value != site) return
        val tab = lookupTab(site.tabId) ?: return
        if (siteOrigin(tab.pageUrl) != site.origin || selectedTabId() != site.tabId) return
        popupPermissions.set(site.origin, tab.isPrivate, contextKeyFor(tab.isPrivate), allowed)
        if (allowed) {
            pendingPopups.filter { it.tabId == site.tabId }.toList().forEach { finishPopup(it, allow = true) }
        }
        publish()
    }

    fun savedPopupOrigins(): Set<String> = popupPermissions.savedDefaultAllowedOrigins()

    fun clearPopupOrigin(origin: String) {
        popupPermissions.clearOrigin(origin)
        publish()
    }

    fun clearPopupPermissions() {
        popupPermissions.clearAll()
        pendingPopups.toList().forEach { finishPopup(it, allow = false) }
        publish()
    }

    override fun acknowledgeAlert(token: Long, allowMoreDialogs: Boolean) {
        val entry = currentPrompt(token, BrowserPromptKind.ALERT) ?: return
        val js = entry.native as NativeRequest.JsDialog
        applyScriptSuppression(entry.tabId, entry, allowMoreDialogs)
        finishPrompt(entry) { js.resolve(js.dialogId, true, "") }
        publish()
    }

    override fun answerConfirm(token: Long, button: BrowserPromptButton, allowMoreDialogs: Boolean) {
        val entry = currentPrompt(token, BrowserPromptKind.CONFIRM) ?: return
        val js = entry.native as NativeRequest.JsDialog
        applyScriptSuppression(entry.tabId, entry, allowMoreDialogs)
        val accept = button == BrowserPromptButton.POSITIVE
        finishPrompt(entry) { js.resolve(js.dialogId, accept, "") }
        publish()
    }

    override fun answerText(token: Long, text: String, allowMoreDialogs: Boolean) {
        val entry = currentPrompt(token, BrowserPromptKind.TEXT) ?: return
        val js = entry.native as NativeRequest.JsDialog
        applyScriptSuppression(entry.tabId, entry, allowMoreDialogs)
        finishPrompt(entry) { js.resolve(js.dialogId, true, text) }
        publish()
    }

    override fun answerBeforeUnload(token: Long, leave: Boolean) {
        val entry = currentPrompt(token, BrowserPromptKind.BEFORE_UNLOAD) ?: return
        val beforeUnload = entry.native as NativeRequest.BeforeUnload
        finishPrompt(entry) { beforeUnload.resolve(beforeUnload.dialogId, leave, "") }
        publish()
    }

    override fun answerRepost(token: Long, resubmit: Boolean) {
        val entry = currentPrompt(token, BrowserPromptKind.REPOST) ?: return
        val repost = entry.native as NativeRequest.Repost
        finishPrompt(entry) { repost.resolve(resubmit) }
        publish()
    }

    override fun authenticate(token: Long, userName: String, password: String) {
        val entry = currentPrompt(token, BrowserPromptKind.AUTHENTICATION) ?: return
        val auth = entry.native as NativeRequest.HttpAuth
        finishPrompt(entry) { auth.resolve(auth.authId, true, userName, password) }
        publish()
    }

    override fun dismiss(token: Long, allowMoreDialogs: Boolean) {
        val entry = currentPrompt(token) ?: return
        if (entry.native is NativeRequest.JsDialog) {
            applyScriptSuppression(entry.tabId, entry, allowMoreDialogs)
        }
        finishPrompt(entry) { entry.native.cancel(entry.tabId) }
        publish()
    }

    override fun prepareClientCertificate(token: Long): ClientCertificateSelection? = null
    override fun completeClientCertificate(token: Long, alias: String?, unavailable: Boolean) = Unit
    override fun answerChoice(token: Long, selectedIds: Set<String>) = Unit
    override fun answerDate(token: Long, value: String) = Unit
    override fun clearDate(token: Long) = Unit
    override fun answerColor(token: Long, value: String) = Unit
    override fun answerFolderUpload(token: Long, allow: Boolean) = Unit

    private fun enqueuePrompt(tab: TabMeta, native: NativeRequest) {
        pendingPrompts += PendingPrompt(
            token = ++sequence,
            tabId = tab.tabId,
            topOrigin = siteOrigin(tab.pageUrl),
            isPrivate = tab.isPrivate,
            hardGeneration = tab.hardGeneration,
            locationGeneration = tab.locationGeneration,
            attemptGeneration = tab.attemptGeneration,
            native = native,
        )
        publish()
    }

    private fun currentPrompt(token: Long, expectedKind: BrowserPromptKind? = null): PendingPrompt? {
        val displayed = mutablePrompt.value ?: return null
        if (displayed.token != token) return null
        val entry = pendingPrompts.firstOrNull { it.token == token } ?: return null
        if (!isPromptValid(entry)) {
            finishPrompt(entry) { entry.native.cancel(entry.tabId) }
            publish()
            return null
        }
        if (expectedKind != null && displayed.kind != expectedKind) return null
        return entry
    }

    private fun isPromptValid(entry: PendingPrompt): Boolean {
        val tab = lookupTab(entry.tabId) ?: return false
        if (selectedTabId() != entry.tabId || tab.isPrivate != entry.isPrivate) return false
        if (tab.hardGeneration != entry.hardGeneration) return false
        if (tab.locationGeneration != entry.locationGeneration) return false
        if (entry.native.shouldDismissOnLoad && tab.attemptGeneration != entry.attemptGeneration) return false
        return true
    }

    private fun isPopupValid(popup: PendingPopup): Boolean {
        val tab = lookupTab(popup.tabId) ?: return false
        return selectedTabId() == popup.tabId &&
            tab.isPrivate == popup.isPrivate &&
            tab.hardGeneration == popup.hardGeneration &&
            tab.locationGeneration == popup.locationGeneration &&
            tab.attemptGeneration == popup.attemptGeneration
    }

    private fun applyScriptSuppression(tabId: String, current: PendingPrompt, allowMore: Boolean) {
        if (allowMore) return
        tabPolicies.getOrPut(tabId) { TabDialogPolicy() }.suppressDialogs = true
        pendingPrompts
            .filter { it !== current && it.tabId == tabId && it.native is NativeRequest.JsDialog }
            .toList()
            .forEach { other -> finishPrompt(other) { other.native.cancel(other.tabId) } }
    }

    private fun cancelPrompts(tabId: String, predicate: (PendingPrompt) -> Boolean) {
        pendingPrompts.filter { it.tabId == tabId && predicate(it) }.toList()
            .forEach { finishPrompt(it) { it.native.cancel(it.tabId) } }
    }

    private fun cancelPopups(tabId: String, predicate: (PendingPopup) -> Boolean) {
        pendingPopups.filter { it.tabId == tabId && predicate(it) }.toList()
            .forEach { finishPopup(it, allow = false) }
    }

    private fun finishPrompt(entry: PendingPrompt, callback: () -> Unit) {
        if (!pendingPrompts.remove(entry)) return
        runCatching(callback)
    }

    private fun finishPopup(popup: PendingPopup, allow: Boolean) {
        if (!pendingPopups.remove(popup)) return
        popup.expiryJob?.cancel()
        if (allow) {
            openPopupTarget(popup.tabId, popup.targetUrl, popup.isPrivate)
        }
    }

    private fun publish() {
        mutablePrompt.value = pendingPrompts.filter(::isPromptValid).minByOrNull { it.token }?.let(::projectPrompt)
        mutablePopup.value = pendingPopups.filter(::isPopupValid).minByOrNull { it.token }?.let {
            BlockedPopup(it.token, it.tabId, it.origin, it.isPrivate)
        }
        val selected = selectedTabId()?.let(lookupTab)
        val origin = selected?.pageUrl?.let(::siteOrigin)
        mutablePopupSite.value = if (selected != null && origin != null) {
            PopupSiteState(
                tabId = selected.tabId,
                origin = origin,
                isPrivate = selected.isPrivate,
                allowed = popupPermissions.allowed(origin, selected.isPrivate, contextKeyFor(selected.isPrivate)),
            )
        } else {
            null
        }
    }

    private fun projectPrompt(entry: PendingPrompt): BrowserPromptState {
        val base = BrowserPromptState(
            token = entry.token,
            tabId = entry.tabId,
            kind = promptKind(entry.native),
            origin = null,
            topOrigin = entry.topOrigin,
            isPrivate = entry.isPrivate,
        )
        return when (val native = entry.native) {
            is NativeRequest.JsDialog -> when (native.kind) {
                BrowserPromptKind.ALERT -> base.copy(
                    message = native.message,
                    hasShownManyDialogs = native.hasShownMany,
                )
                BrowserPromptKind.CONFIRM -> base.copy(
                    message = native.message,
                    hasShownManyDialogs = native.hasShownMany,
                )
                BrowserPromptKind.TEXT -> base.copy(
                    inputLabel = native.message,
                    inputValue = native.defaultPrompt,
                    hasShownManyDialogs = native.hasShownMany,
                )
                else -> base
            }
            is NativeRequest.BeforeUnload -> base
            is NativeRequest.Repost -> base
            is NativeRequest.HttpAuth -> {
                val authOrigin = siteOrigin(native.url)
                val topOrigin = entry.topOrigin
                val crossOrigin = authOrigin != null && topOrigin != null && authOrigin != topOrigin
                val isHttps = native.url.startsWith("https://", ignoreCase = true)
                val level = if (isHttps) BrowserAuthLevel.SECURED else BrowserAuthLevel.NONE
                val method = if (native.isProxy) BrowserAuthMethod.PROXY else BrowserAuthMethod.HOST
                base.copy(
                    origin = authOrigin,
                    title = native.challenger.ifBlank { runCatching { URI(native.url).host }.getOrNull().orEmpty() },
                    message = native.realm.ifBlank { native.scheme },
                    onlyShowPassword = false,
                    previousFailed = native.previousFailed,
                    isCrossOrigin = crossOrigin,
                    authMethod = method,
                    authLevel = level,
                )
            }
        }
    }

    private fun promptKind(native: NativeRequest): BrowserPromptKind = when (native) {
        is NativeRequest.JsDialog -> native.kind
        is NativeRequest.BeforeUnload -> BrowserPromptKind.BEFORE_UNLOAD
        is NativeRequest.HttpAuth -> BrowserPromptKind.AUTHENTICATION
        is NativeRequest.Repost -> BrowserPromptKind.REPOST
    }

    private fun contextKeyFor(isPrivate: Boolean): String? = if (isPrivate) "chromium-private" else null

    companion object {
        private const val MAX_PENDING_PER_TAB = 8
        private const val POPUP_BANNER_TIMEOUT_MS = 6_000L

        private fun sharedPreferencesPopups(context: Context): PopupPermissions {
            val prefs = context.getSharedPreferences("miru_chromium_popups", Context.MODE_PRIVATE)
            return PopupPermissions(
                { prefs.getBoolean(it, false) },
                { key, allowed ->
                    prefs.edit().apply {
                        if (allowed) putBoolean(key, true) else remove(key)
                    }.apply()
                },
                { prefs.edit().clear().apply() },
                { prefs.all.filterValues { it == true }.keys.toSet() },
            )
        }
    }
}
