/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.miru.browser.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
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
    private var desiredEnabled = preferences.getBoolean("enabled", true)
    private val mutableState = MutableStateFlow(AdblockSnapshot(enabled = desiredEnabled))
    val state = mutableState.asStateFlow()
    private val mutablePopup = MutableStateFlow<EngineSession?>(null)
    val popup = mutablePopup.asStateFlow()
    private var engine: GeckoEngine? = null
    private var extension: WebExtension? = null
    private var defaultAction: Action? = null
    private val tabActions = mutableMapOf<String, Action>()
    private val navigationObservers = mutableMapOf<String, Pair<EngineSession, EngineSession.Observer>>()
    private val unconfirmedPages = mutableSetOf<String>()
    private val readyVersions = mutableSetOf<String>()
    private var setupFinished = false
    private var startup = CompletableDeferred<Unit>()
    private var installJob: Job? = null
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
                unconfirmedPages.retainAll(retainedIds)
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
        if (installJob?.isActive == true) return
        startup = CompletableDeferred()
        setupFinished = false
        mutableState.value = mutableState.value.copy(status = AdblockStatus.INSTALLING, error = null)
        installJob = scope.launch {
            try {
                withTimeout(240_000) {
                Log.d("MiruAdblock", "Listing installed extensions")
                val installed = suspendCancellableCoroutine<List<WebExtension>> { continuation ->
                    target.listInstalledWebExtensions({ if(continuation.isActive) continuation.resume(it) }, { if(continuation.isActive) continuation.resumeWithException(it) })
                }.firstOrNull { it.id == UBLOCK_ID }
                Log.d("MiruAdblock", "Installed uBO found=${installed != null}, version=${installed?.getMetadata()?.version}")
                val selected = if (installed == null || versionOlder(installed.getMetadata()?.version.orEmpty(), UBLOCK_VERSION)) {
                    val localFile = withContext(Dispatchers.IO) { verifiedPackage() }
                    suspendCancellableCoroutine<WebExtension> { continuation ->
                        val operation = target.installWebExtension(Uri.fromFile(localFile).toString(), onSuccess = { if(continuation.isActive) continuation.resume(it) }, onError = { if(continuation.isActive) continuation.resumeWithException(it) })
                        continuation.invokeOnCancellation { scope.launch(Dispatchers.Main.immediate) { operation.cancel() } }
                    }
                } else installed
                extension = selected
                Log.d("MiruAdblock", "Selected uBO version=${selected.getMetadata()?.version}, enabled=${selected.isEnabled()}, private=${selected.isAllowedInPrivateBrowsing()}")
                if (!selected.isAllowedInPrivateBrowsing()) {
                    selected.getMetadata()?.version?.let(readyVersions::remove)
                    extension = suspendCancellableCoroutine { continuation ->
                        target.setAllowedInPrivateBrowsing(selected, true, { if(continuation.isActive) continuation.resume(it) }, { if(continuation.isActive) continuation.resumeWithException(it) })
                    }
                }
                applyDesiredEnabled()
                setupFinished = true
                Log.d("MiruAdblock", "Extension setup finished; readyVersions=$readyVersions")
                attachSessionHandlers(store.state)
                publish()
                completeStartupIfReady()
                }
            } catch (failure: Exception) {
                Log.e("MiruAdblock", "Unable to initialize bundled extension", failure)
                mutableState.value = mutableState.value.copy(status = AdblockStatus.FAILED, error = failure.javaClass.simpleName)
                startup.complete(Unit)
            }
        }
    }

    fun setEnabled(enabled: Boolean, onApplied: () -> Unit = {}) {
        desiredEnabled = enabled
        preferences.edit().putBoolean("enabled", enabled).apply()
        mutableState.value = mutableState.value.copy(enabled = enabled, error = null)
        scope.launch { runCatching { applyDesiredEnabled(); onApplied() }.onFailure { mutableState.value = mutableState.value.copy(status = AdblockStatus.FAILED, error = it.javaClass.simpleName) } }
    }

    private suspend fun applyDesiredEnabled() {
        val target = engine ?: return
        val current = extension ?: return
        val desired = desiredEnabled
        if (current.isEnabled() != desired) {
            current.getMetadata()?.version?.let(readyVersions::remove)
            if (desired) mutableState.value = mutableState.value.copy(status = AdblockStatus.INSTALLING)
            extension = suspendCancellableCoroutine { continuation ->
                val success: (WebExtension) -> Unit = { if(continuation.isActive) continuation.resume(it) }
                val error: (Throwable) -> Unit = { if(continuation.isActive) continuation.resumeWithException(it) }
                if (desired) target.enableWebExtension(current, onSuccess = success, onError = error)
                else target.disableWebExtension(current, onSuccess = success, onError = error)
            }
        }
        publish()
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
        val enabled = current?.isEnabled() ?: desiredEnabled
        val badge = if(enabled) currentAction()?.badgeText.orEmpty() else ""
        mutableState.value = mutableState.value.copy(
            installed = installed,
            enabled = enabled,
            version = current?.getMetadata()?.version,
            privateAllowed = current?.isAllowedInPrivateBrowsing() == true,
            status = when {
                mutableState.value.error != null -> AdblockStatus.FAILED
                installed && !enabled -> AdblockStatus.DISABLED
                installed && setupFinished && current?.getMetadata()?.version in readyVersions -> AdblockStatus.READY
                else -> mutableState.value.status
            },
            badgeText = badge,
            pageNeedsReload = enabled && (badge == "!" || store.state.selectedTabId in unconfirmedPages),
            blockedCount = parseBadgeCount(badge),
            popupAvailable = currentAction() != null && installed && enabled,
        )
    }

    private fun attachSessionHandlers(value: BrowserState) {
        val current = extension
        value.tabs.forEach { tab ->
            tab.engineState.engineSession?.let { session ->
                if(navigationObservers[tab.id]?.first !== session) {
                    navigationObservers.remove(tab.id)?.let { (previous, observer) -> previous.unregister(observer) }
                    val id = tab.id
                    val observer = object : EngineSession.Observer {
                        override fun onLoadingStateChange(loading: Boolean) {
                            if(!loading) return
                            val url = store.state.tabs.firstOrNull { it.id == id }?.content?.url.orEmpty()
                            if(!url.startsWith("http://") && !url.startsWith("https://")) return
                            if(desiredEnabled && mutableState.value.status != AdblockStatus.READY) unconfirmedPages += id
                            else unconfirmedPages -= id
                            publish()
                        }
                    }
                    navigationObservers[id] = session to observer
                    session.register(observer)
                    if(tab.content.loading && desiredEnabled && mutableState.value.status != AdblockStatus.READY) unconfirmedPages += id
                }
                if(current == null) return@let
                if (!current.hasActionHandler(session)) current.registerActionHandler(session, object : ActionHandler {
                    override fun onBrowserAction(extension: WebExtension, session: EngineSession?, action: Action) {
                        tabActions[tab.id] = action; publish()
                    }
                })
                if (!current.hasTabHandler(session)) current.registerTabHandler(session, object : TabHandler {
                    override fun onCloseTab(webExtension: WebExtension, engineSession: EngineSession): Boolean { onCloseTab(tab.id); return true }
                    override fun onUpdateTab(webExtension: WebExtension, engineSession: EngineSession, active: Boolean, url: String?): Boolean {
                        if (active) onSelectTab(tab.id)
                        url?.let { session.loadUrl(it) }
                        return true
                    }
                })
            }
        }
    }

    private val delegate = object : WebExtensionDelegate {
        override fun isInPrivateBrowsing(): Boolean = store.state.tabs.firstOrNull { it.id == store.state.selectedTabId }?.content?.private == true
        override fun onInstallPermissionRequest(extension: WebExtension, permissions: List<String>, origins: List<String>, dataCollectionPermissions: List<String>, onConfirm: (PermissionPromptResponse) -> Unit) {
            // Only the pinned bundled ID is silently approved. Arbitrary website extension installs are denied.
            onConfirm(PermissionPromptResponse(extension.id == UBLOCK_ID, extension.id == UBLOCK_ID, false))
        }
        override fun onUpdatePermissionRequest(extension: WebExtension, newPermissions: List<String>, newOrigins: List<String>, newDataCollectionPermissions: List<String>, onPermissionsGranted: (Boolean) -> Unit) { onPermissionsGranted(false) }
        override fun onOptionalPermissionsRequest(extension: WebExtension, permissions: List<String>, origins: List<String>, dataCollectionPermissions: List<String>, onPermissionsGranted: (Boolean) -> Unit) { onPermissionsGranted(false) }
        override fun onInstalled(extension: WebExtension) { if(extension.id == UBLOCK_ID) { this@UblockController.extension = extension; publish() } }
        override fun onReady(extension: WebExtension) {
            if(extension.id != UBLOCK_ID) return
            this@UblockController.extension = extension
            Log.d("MiruAdblock", "Native ready version=${extension.getMetadata()?.version}")
            extension.getMetadata()?.version?.let(readyVersions::add)
            attachSessionHandlers(store.state); publish()
            completeStartupIfReady()
        }
        override fun onEnabled(extension: WebExtension) { if(extension.id == UBLOCK_ID) { this@UblockController.extension = extension; publish() } }
        override fun onDisabled(extension: WebExtension) { if(extension.id == UBLOCK_ID) { this@UblockController.extension = extension; publish() } }
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
        override fun onDisabledExtensionProcessSpawning() { mutableState.value = mutableState.value.copy(status = AdblockStatus.FAILED, error = "ExtensionProcessUnavailable") }
    }

    private fun completeStartupIfReady() {
        if (setupFinished && (!desiredEnabled || extension?.let { it.isEnabled() && it.getMetadata()?.version in readyVersions } == true)) startup.complete(Unit)
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
