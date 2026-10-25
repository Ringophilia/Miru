/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.miru.browser.engine

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.window.WindowRequest
import mozilla.components.concept.engine.webextension.Action
import mozilla.components.concept.engine.webextension.ActionHandler
import mozilla.components.concept.engine.webextension.PermissionPromptResponse
import mozilla.components.concept.engine.webextension.TabHandler
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.concept.engine.webextension.WebExtensionDelegate

/** Hosts the unmodified AMO-signed extension. Its own popup is the source of truth for site rules. */
internal class UblockController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: BrowserStore,
    private val onNewTab: (EngineSession, String, Boolean, Boolean) -> Unit,
    private val onCloseTab: (String) -> Unit,
    private val onSelectTab: (String) -> Unit,
    private val onOpenUrl: (String) -> Unit,
) {
    private val preferences = context.getSharedPreferences("miru_adblock", Context.MODE_PRIVATE)
    private val installation = UblockInstallState(preferences.getBoolean("enabled", true))
    private val mutableState = MutableStateFlow(AdblockSnapshot(enabled = installation.desiredEnabled))
    val state = mutableState.asStateFlow()
    private val mutablePopup = MutableStateFlow<EngineSession?>(null)
    val popup = mutablePopup.asStateFlow()
    private var engine: GeckoEngine? = null
    private var extension: WebExtension? = null
    private var defaultAction: Action? = null
    private val tabActions = mutableMapOf<String, Action>()
    private val navigationObservers = mutableMapOf<String, Pair<EngineSession, EngineSession.Observer>>()
    private val pageWarnings = UblockPageWarnings()
    private var startup = CompletableDeferred<Unit>()
    private data class PendingInstall(val attempt: Long, val result: CompletableDeferred<WebExtension>)
    private var pendingInstall: PendingInstall? = null
    private var onEnabledApplied: (() -> Unit)? = null
    private val popupObserver = object : EngineSession.Observer {
        override fun onWindowRequest(windowRequest: WindowRequest) {
            if (windowRequest.type == WindowRequest.Type.CLOSE) closePopup()
        }
    }

    init {
        scope.launch {
            store.stateFlow.collect { value ->
                tabActions.keys.retainAll(value.tabs.map { it.id }.toSet())
                val retainedIds = value.tabs.map { it.id }.toSet() + value.undoHistory.tabs.map { it.state.id }
                pageWarnings.retainTabs(retainedIds)
                navigationObservers.keys.filter { it !in retainedIds }.forEach { id ->
                    navigationObservers.remove(id)?.let { (session, observer) -> session.unregister(observer) }
                }
                attachSessionHandlers(value)
                publish()
            }
        }
    }

    fun start(engine: GeckoEngine) {
        this.engine = engine
        engine.registerWebExtensionDelegate(delegate)
        retry()
    }

    /** The setup job continues after a timeout: UI must report STARTING, never a fake success. */
    suspend fun awaitStartup() {
        if (withTimeoutOrNull(1_500) { startup.await(); true } != true) {
            mutableState.value = mutableState.value.copy(startupTimedOut = true)
        }
    }

    fun retry() {
        val target = engine ?: return
        val attempt = installation.beginAttempt() ?: run {
            Log.d("MiruAdblock", "Startup is still pending; retaining the existing native operation")
            return
        }
        startup = CompletableDeferred()
        publish()
        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var stage = "list"
            fun logStage(value: String) {
                stage = value
                Log.d("MiruAdblock", "Startup stage=$value elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
            }
            val watchdog: Job = scope.launch {
                while (installation.activeAttempt == attempt) {
                    delay(240_000)
                    if (installation.deadlineExceeded(attempt)) {
                        Log.w("MiruAdblock", "Startup deadline exceeded stage=$stage elapsedMs=${SystemClock.elapsedRealtime() - startedAt}; native operation retained")
                        publish()
                        startup.complete(Unit)
                    }
                }
            }
            try {
                logStage("list")
                val listed = suspendCancellableCoroutine<List<WebExtension>> { continuation ->
                    target.listInstalledWebExtensions({ if(continuation.isActive) continuation.resume(it) }, { if(continuation.isActive) continuation.resumeWithException(it) })
                }.firstOrNull { it.id == UBLOCK_ID }
                // Native onInstalled/onReady may have arrived while the list request was running.
                val installed = newestExtension(listed, extension)
                Log.d("MiruAdblock", "Installed uBO found=${installed != null}, version=${installed?.getMetadata()?.version}")
                val selected = if (installed == null || versionOlder(installed.getMetadata()?.version.orEmpty(), UBLOCK_VERSION)) {
                    logStage("verify-package")
                    val localFile = withContext(Dispatchers.IO) { verifiedPackage() }
                    installation.startNativeInstall(attempt)
                    logStage("native-install")
                    val pending = PendingInstall(attempt, CompletableDeferred())
                    pendingInstall = pending
                    target.installWebExtension(
                        Uri.fromFile(localFile).toString(),
                        onSuccess = { if (pendingInstall === pending) pending.result.complete(it) },
                        onError = { if (pendingInstall === pending) pending.result.completeExceptionally(it) },
                    )
                    try {
                        try {
                            pending.result.await()
                        } catch (failure: Exception) {
                            // Native errors and lifecycle events can cross in the event queue.
                            // A genuine ready event for the required version takes precedence.
                            extension?.takeIf {
                                installation.hasReadyVersion(it.getMetadata()?.version) &&
                                    !versionOlder(it.getMetadata()?.version.orEmpty(), UBLOCK_VERSION)
                            } ?: throw failure
                        }
                    } finally {
                        if (pendingInstall === pending) pendingInstall = null
                    }
                } else installed
                if (installation.nativeInstallPending) installation.finishNativeInstall(attempt)
                installation.startSetup(attempt)
                selectExtension(newestExtension(selected, extension) ?: selected)
                Log.d("MiruAdblock", "Selected uBO version=${selected.getMetadata()?.version}, enabled=${selected.isEnabled()}, private=${selected.isAllowedInPrivateBrowsing()}")
                while (true) {
                    val current = checkNotNull(extension)
                    if (!current.isAllowedInPrivateBrowsing()) {
                        installation.invalidateReady(current.getMetadata()?.version)
                        logStage("private-permission")
                        val permitted = suspendCancellableCoroutine<WebExtension> { continuation ->
                            target.setAllowedInPrivateBrowsing(current, true, { if(continuation.isActive) continuation.resume(it) }, { if(continuation.isActive) continuation.resumeWithException(it) })
                        }
                        check(permitted.isAllowedInPrivateBrowsing()) { "Private browsing permission was not applied" }
                        selectExtension(permitted)
                    }
                    logStage("enabled-state")
                    applyDesiredEnabled()
                    installation.observeInstalled(checkNotNull(extension).installState())
                    // A setting can change while an enable/disable callback is pending. Read it
                    // again and apply the latest intent before publishing setup completion.
                    if (installation.finishSetup(attempt)) break
                }
                logStage("setup-finished")
                attachSessionHandlers(store.state)
                publish()
                completeStartupIfReady()
                onEnabledApplied?.also { onEnabledApplied = null }?.invoke()
            } catch (failure: Exception) {
                Log.e("MiruAdblock", "Unable to initialize bundled extension", failure)
                if (installation.fail(attempt, failure.javaClass.simpleName)) {
                    publish()
                    startup.complete(Unit)
                }
            } finally {
                watchdog.cancel()
            }
        }
    }

    fun setEnabled(enabled: Boolean, onApplied: () -> Unit = {}) {
        // Repeating the desired value must not restart setup or reload a document.
        // Preserve a real pending toggle's callback; failed setup has a separate Retry action.
        if (installation.desiredEnabled == enabled) return
        installation.setDesiredEnabled(enabled)
        preferences.edit().putBoolean("enabled", enabled).apply()
        onEnabledApplied = onApplied
        publish()
        // All permission and enabled-state writes run in the same startup driver. During a slow
        // install this updates the desired state without opening a second native operation.
        if (installation.activeAttempt == null) retry()
    }

    private suspend fun applyDesiredEnabled() {
        val target = engine ?: return
        val current = extension ?: return
        val desired = installation.desiredEnabled
        if (current.isEnabled() != desired) {
            installation.invalidateReady(current.getMetadata()?.version)
            val applied = suspendCancellableCoroutine<WebExtension> { continuation ->
                val success: (WebExtension) -> Unit = { if(continuation.isActive) continuation.resume(it) }
                val error: (Throwable) -> Unit = { if(continuation.isActive) continuation.resumeWithException(it) }
                if (desired) target.enableWebExtension(current, onSuccess = success, onError = error)
                else target.disableWebExtension(current, onSuccess = success, onError = error)
            }
            selectExtension(applied)
        }
        publish()
    }

    private fun selectExtension(value: WebExtension) {
        extension = value
        installation.observeInstalled(value.installState())
    }

    private fun WebExtension.installState() = UblockInstallState.Installed(getMetadata()?.version, isEnabled(), isAllowedInPrivateBrowsing())

    private fun newestExtension(first: WebExtension?, second: WebExtension?): WebExtension? = when {
        first == null -> second
        second == null -> first
        versionOlder(second.getMetadata()?.version.orEmpty(), first.getMetadata()?.version.orEmpty()) -> first
        else -> second
    }

    fun openPopup(): Boolean {
        if (!mutableState.value.installed || !mutableState.value.enabled) return false
        val action = currentAction() ?: return false
        action.onClick()
        return true
    }

    fun closePopup() { mutablePopup.value?.let { it.unregister(popupObserver); it.close() }; mutablePopup.value = null }
    fun openDashboard(): Boolean = openExtensionPage(extension?.getMetadata()?.optionsPageUrl)
    fun openExceptions(): Boolean = openExtensionPage(extension?.getMetadata()?.baseUrl?.let { "${it}dashboard.html#whitelist.html" })
    fun openLogger(): Boolean = openExtensionPage(extension?.getMetadata()?.baseUrl?.let { "${it}logger-ui.html" })
    private fun openExtensionPage(url: String?): Boolean {
        if (url == null || !mutableState.value.installed || !mutableState.value.enabled) return false
        closePopup(); onOpenUrl(url); return true
    }

    private fun currentAction(): Action? = defaultAction?.copyWithOverride(store.state.selectedTabId?.let(tabActions::get)) ?: store.state.selectedTabId?.let(tabActions::get)

    private fun publish() {
        val current = extension
        val installed = current != null
        val enabled = current?.isEnabled() ?: installation.desiredEnabled
        val badge = if(enabled) currentAction()?.badgeText.orEmpty() else ""
        mutableState.value = mutableState.value.copy(
            installed = installed,
            enabled = enabled,
            version = current?.getMetadata()?.version,
            privateAllowed = current?.isAllowedInPrivateBrowsing() == true,
            status = installation.status,
            error = installation.error,
            badgeText = badge,
            pageNeedsReload = enabled && (badge == "!" || pageWarnings.needsReload(store.state.selectedTabId)),
            blockedCount = parseBadgeCount(badge),
            popupAvailable = currentAction() != null && installed && enabled,
        )
    }

    private fun attachSessionHandlers(value: BrowserState) {
        value.tabs.forEach { tab ->
            tab.engineState.engineSession?.let { session ->
                onSessionLinked(tab.id, session, tab.content.url)
            }
        }
    }

    /** Synchronous middleware hook: a later READY callback must not erase this navigation's gap. */
    fun onLoadRequested(tabId: String, url: String) {
        pageWarnings.loadRequested(tabId, url, protectionUnconfirmed())
        publish()
    }

    /** Called before AC's linking middleware starts a new session's first load. */
    fun onSessionLinked(tabId: String, session: EngineSession, initialUrl: String) {
        if (navigationObservers[tabId]?.first !== session) {
            navigationObservers.remove(tabId)?.let { (previous, observer) -> previous.unregister(observer) }
            var observedUrl = initialUrl
            val observer = object : PermissionNeutralObserver() {
                @Suppress("DEPRECATION")
                override fun onLoadRequest(url: String, triggeredByRedirect: Boolean, triggeredByWebContent: Boolean) {
                    observedUrl = url
                    pageWarnings.loadRequest(tabId, url, triggeredByRedirect, protectionUnconfirmed())
                    publish()
                }
                override fun onLoadingStateChange(loading: Boolean) {
                    pageWarnings.loadingChanged(tabId, loading, observedUrl, protectionUnconfirmed())
                    publish()
                }
                override fun onLocationChange(url: String, hasUserGesture: Boolean) {
                    observedUrl = url
                    pageWarnings.locationChanged(tabId, url)
                    publish()
                }
            }
            navigationObservers[tabId] = session to observer
            session.register(observer)
        }
        val current = extension ?: return
        if (!current.hasActionHandler(session)) current.registerActionHandler(session, object : ActionHandler {
            override fun onBrowserAction(extension: WebExtension, session: EngineSession?, action: Action) {
                tabActions[tabId] = action; publish()
            }
        })
        if (!current.hasTabHandler(session)) current.registerTabHandler(session, object : TabHandler {
            override fun onCloseTab(webExtension: WebExtension, engineSession: EngineSession): Boolean { onCloseTab(tabId); return true }
            override fun onUpdateTab(webExtension: WebExtension, engineSession: EngineSession, active: Boolean, url: String?): Boolean {
                if (active) onSelectTab(tabId)
                url?.let { onLoadRequested(tabId, it); session.loadUrl(it) }
                return true
            }
        })
    }

    private fun protectionUnconfirmed() = installation.desiredEnabled && installation.status != AdblockStatus.READY

    private val delegate = object : WebExtensionDelegate {
        override fun isInPrivateBrowsing(): Boolean = store.state.tabs.firstOrNull { it.id == store.state.selectedTabId }?.content?.private == true
        override fun onInstallPermissionRequest(extension: WebExtension, permissions: List<String>, origins: List<String>, dataCollectionPermissions: List<String>, onConfirm: (PermissionPromptResponse) -> Unit) {
            // Only the pinned bundled ID is silently approved. Arbitrary website extension installs are denied.
            onConfirm(PermissionPromptResponse(extension.id == UBLOCK_ID, extension.id == UBLOCK_ID, false))
        }
        override fun onUpdatePermissionRequest(extension: WebExtension, newPermissions: List<String>, newOrigins: List<String>, newDataCollectionPermissions: List<String>, onPermissionsGranted: (Boolean) -> Unit) { onPermissionsGranted(false) }
        override fun onOptionalPermissionsRequest(extension: WebExtension, permissions: List<String>, origins: List<String>, dataCollectionPermissions: List<String>, onPermissionsGranted: (Boolean) -> Unit) { onPermissionsGranted(false) }
        override fun onInstalled(extension: WebExtension) { if(extension.id == UBLOCK_ID) { selectExtension(newestExtension(extension, this@UblockController.extension) ?: extension); publish() } }
        override fun onReady(extension: WebExtension) {
            if(extension.id != UBLOCK_ID) return
            // Ignore an old-version ready callback while the bundled upgrade is still pending.
            if(installation.nativeInstallPending && versionOlder(extension.getMetadata()?.version.orEmpty(), UBLOCK_VERSION)) return
            this@UblockController.extension = extension
            Log.d("MiruAdblock", "Native ready version=${extension.getMetadata()?.version}")
            val recover = installation.observeReady(extension.installState())
            // A genuine ready event proves this install completed even when Gecko's install
            // result arrives late. The retained driver still has to apply privacy/enabled state.
            pendingInstall?.let { pending ->
                if (installation.activeAttempt == pending.attempt && installation.nativeInstallPending) pending.result.complete(extension)
            }
            attachSessionHandlers(store.state); publish()
            completeStartupIfReady()
            if (recover) retry()
        }
        override fun onEnabled(extension: WebExtension) {
            if(extension.id != UBLOCK_ID) return
            selectExtension(extension); publish()
            if (installation.needsReconciliation) retry()
        }
        override fun onDisabled(extension: WebExtension) {
            if(extension.id != UBLOCK_ID) return
            selectExtension(extension); publish()
            if (installation.needsReconciliation) retry()
        }
        override fun onBrowserActionDefined(extension: WebExtension, action: Action) { if(extension.id == UBLOCK_ID) { defaultAction = action; publish() } }
        override fun onToggleActionPopup(extension: WebExtension, engineSession: EngineSession, action: Action, isPrivate: Boolean): EngineSession? {
            if(extension.id != UBLOCK_ID) { engineSession.close(); return null }
            closePopup()
            engineSession.register(popupObserver)
            mutablePopup.value = engineSession
            return engineSession
        }
        override fun onNewTab(extension: WebExtension, engineSession: EngineSession, active: Boolean, url: String, isPrivate: Boolean) {
            if(extension.id == UBLOCK_ID) { closePopup(); onNewTab(engineSession, url, active, isPrivate) } else engineSession.close()
        }
        override fun onOpenOptionsPage(extension: WebExtension) { if(extension.id == UBLOCK_ID) openDashboard() }
        override fun onDisabledExtensionProcessSpawning() { installation.extensionProcessUnavailable(); publish() }
    }

    private fun completeStartupIfReady() {
        if (installation.setupFinished && installation.status in setOf(AdblockStatus.READY, AdblockStatus.DISABLED)) startup.complete(Unit)
    }

    private fun verifiedPackage(): File {
        val file = File(context.filesDir, "extensions/ublock_origin-$UBLOCK_VERSION.xpi")
        file.parentFile?.mkdirs()
        if (!file.exists() || sha256(file) != UBLOCK_SHA256) {
            context.assets.open("extensions/ublock_origin.xpi").use { input -> file.outputStream().use(input::copyTo) }
        }
        check(sha256(file) == UBLOCK_SHA256) { "Bundled extension integrity mismatch" }
        return file
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream -> val buffer = ByteArray(8192); while(true) { val count = stream.read(buffer); if(count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val UBLOCK_ID = "uBlock0@raymondhill.net"
        const val UBLOCK_VERSION = "1.75.0"
        const val UBLOCK_SHA256 = "5b74415860456370644bd80f16125e865b0e6c356bb5dfcfb84069967eaa5287"
        internal fun versionOlder(installed: String, bundled: String): Boolean {
            val left = installed.split('.').map { it.toIntOrNull() ?: 0 }
            val right = bundled.split('.').map { it.toIntOrNull() ?: 0 }
            for(index in 0 until maxOf(left.size, right.size)) { val difference = (left.getOrNull(index) ?: 0).compareTo(right.getOrNull(index) ?: 0); if(difference != 0) return difference < 0 }
            return false
        }
        internal fun parseBadgeCount(value: String): Int {
            val normalized = value.trim().removeSuffix("+").lowercase()
            return normalized.toIntOrNull() ?: normalized.removeSuffix("k").toFloatOrNull()?.let { (it * 1000).toInt() }?.takeIf { normalized.endsWith("k") } ?: 0
        }
    }
}
