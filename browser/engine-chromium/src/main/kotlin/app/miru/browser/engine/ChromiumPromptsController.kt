// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.content.Context
import android.security.KeyChain
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.PrivateKey

internal class ChromiumFileSelection(
    val token: Long,
    val mimeTypes: List<String>,
    val multiple: Boolean,
    val isPrivate: Boolean,
    val capture: BrowserFileCapture,
    val pending: () -> Boolean,
    val documentAlive: () -> Boolean,
    val deliver: (List<StagedUploadFile>) -> Boolean,
    val cancel: () -> Unit,
)

internal class ChromiumFolderSelection(
    val token: Long,
    val isPrivate: Boolean,
    val pending: () -> Boolean,
    val documentAlive: () -> Boolean,
    val flowCurrent: () -> Boolean,
    val confirm: (PreparedUploadFolder, (Boolean) -> Unit) -> Boolean,
    val cancel: () -> Unit,
)

internal data class StagedUploadFile(
    val file: File,
    val displayName: String,
    val mimeType: String,
)

/**
 * Application-lifetime coordinator for all Chromium tab prompts:
 * JS dialogs, beforeunload, HTTP auth, repost, client certificates, file/folder uploads,
 * HTML select/date/color form controls, and blocked popups.
 */
internal class ChromiumPromptsController(
    private val scope: CoroutineScope,
    private val selectedTabId: () -> String?,
    private val lookupTab: (String) -> TabMeta?,
    private val lookupTabByNativePtr: (Long) -> TabMeta? = { null },
    private val hasPrivateTabs: () -> Boolean,
    popupPermissions: PopupPermissions,
    private val resolveJsDialogNative: (tabId: String, dialogId: Long, accept: Boolean, input: String) -> Unit,
    private val resolveHttpAuthNative: (authId: Long, submit: Boolean, userName: String, password: String) -> Unit,
    private val resolveRepostNative: (tabId: String, resubmit: Boolean) -> Unit,
    private val resolveClientCertNative: (requestId: Long, encodedChain: Array<ByteArray>?, privateKey: PrivateKey?) -> Unit = { _, _, _ -> },
    private val resolveFileChooserFilesNative: (requestId: Long, files: List<StagedUploadFile>) -> Unit = { _, _ -> },
    private val resolveFileChooserFolderNative: (requestId: Long, folder: PreparedUploadFolder) -> Unit = { _, _ -> },
    private val cancelFileChooserNative: (requestId: Long) -> Unit = { _ -> },
    private val resolveDateTimeNative: (requestId: Long, accepted: Boolean, value: String) -> Unit = { _, _, _ -> },
    private val resolveColorNative: (requestId: Long, accepted: Boolean, argb: Int) -> Unit = { _, _, _ -> },
    private val loadKeyChainIdentity: (alias: String) -> Pair<Array<ByteArray>?, PrivateKey?> = { null to null },
    openPopupTarget: (openerTabId: String, targetUrl: String, isPrivate: Boolean) -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BrowserPromptsController {

    constructor(
        context: Context,
        scope: CoroutineScope,
        selectedTabId: () -> String?,
        lookupTab: (String) -> TabMeta?,
        lookupTabByNativePtr: (Long) -> TabMeta?,
        hasPrivateTabs: () -> Boolean,
        resolveJsDialogNative: (tabId: String, dialogId: Long, accept: Boolean, input: String) -> Unit,
        resolveHttpAuthNative: (authId: Long, submit: Boolean, userName: String, password: String) -> Unit,
        resolveRepostNative: (tabId: String, resubmit: Boolean) -> Unit,
        resolveClientCertNative: (requestId: Long, encodedChain: Array<ByteArray>?, privateKey: PrivateKey?) -> Unit,
        resolveFileChooserFilesNative: (requestId: Long, files: List<StagedUploadFile>) -> Unit,
        resolveFileChooserFolderNative: (requestId: Long, folder: PreparedUploadFolder) -> Unit,
        cancelFileChooserNative: (requestId: Long) -> Unit,
        resolveDateTimeNative: (requestId: Long, accepted: Boolean, value: String) -> Unit,
        resolveColorNative: (requestId: Long, accepted: Boolean, argb: Int) -> Unit,
        openPopupTarget: (openerTabId: String, targetUrl: String, isPrivate: Boolean) -> Unit,
    ) : this(
        scope = scope,
        selectedTabId = selectedTabId,
        lookupTab = lookupTab,
        lookupTabByNativePtr = lookupTabByNativePtr,
        hasPrivateTabs = hasPrivateTabs,
        popupPermissions = ChromiumPopupBlocker.sharedPreferencesPopups(context),
        resolveJsDialogNative = resolveJsDialogNative,
        resolveHttpAuthNative = resolveHttpAuthNative,
        resolveRepostNative = resolveRepostNative,
        resolveClientCertNative = resolveClientCertNative,
        resolveFileChooserFilesNative = resolveFileChooserFilesNative,
        resolveFileChooserFolderNative = resolveFileChooserFolderNative,
        cancelFileChooserNative = cancelFileChooserNative,
        resolveDateTimeNative = resolveDateTimeNative,
        resolveColorNative = resolveColorNative,
        loadKeyChainIdentity = { alias ->
            val chain = runCatching { KeyChain.getCertificateChain(context, alias)?.map { it.encoded }?.toTypedArray() }.getOrNull()
            val key = runCatching { KeyChain.getPrivateKey(context, alias) }.getOrNull()
            if (chain != null && chain.isNotEmpty() && key != null) chain to key else null to null
        },
        openPopupTarget = openPopupTarget,
    )

    internal data class TabMeta(
        val tabId: String,
        val pageUrl: String,
        val isPrivate: Boolean,
        val hardGeneration: Long,
        val locationGeneration: Long,
        val attemptGeneration: Long,
        val documentGeneration: Long = 1L,
    )

    private class PendingPrompt(
        val token: Long,
        val tabId: String,
        val topOrigin: String?,
        val isPrivate: Boolean,
        val hardGeneration: Long,
        val locationGeneration: Long,
        val attemptGeneration: Long,
        val documentGeneration: Long,
        val native: ChromiumNativePrompt,
    )

    private class TabDialogPolicy(
        var suppressDialogs: Boolean = false,
        var scriptDialogCount: Int = 0,
    )

    private val popupBlocker = ChromiumPopupBlocker(
        scope = scope,
        mainDispatcher = mainDispatcher,
        popupPermissions = popupPermissions,
        lookupTab = lookupTab,
        selectedTabId = selectedTabId,
        hasPrivateTabs = hasPrivateTabs,
        openPopupTarget = openPopupTarget,
        maxPendingPerTab = MAX_PENDING_PER_TAB,
    )

    private val tabPolicies = mutableMapOf<String, TabDialogPolicy>()
    private val pendingPrompts = mutableListOf<PendingPrompt>()
    private val certGate = ClientCertificatePickerGate()
    private var sequence = 0L

    private val mutablePrompt = MutableStateFlow<BrowserPromptState?>(null)
    override val prompt: StateFlow<BrowserPromptState?> = mutablePrompt.asStateFlow()
    override val blockedPopup: StateFlow<BlockedPopup?> = popupBlocker.blockedPopup
    override val popupSite: StateFlow<PopupSiteState?> = popupBlocker.popupSite

    private val mutableCertResults = MutableSharedFlow<ClientCertificateResult>(extraBufferCapacity = 1)
    override val clientCertificateResults: SharedFlow<ClientCertificateResult> = mutableCertResults.asSharedFlow()

    private val mutableLifecycle = MutableStateFlow(0L)
    val lifecycleChanges: StateFlow<Long> = mutableLifecycle.asStateFlow()

    fun onNavigationAttempt(tabId: String) {
        cancelPrompts(tabId) { it.native.shouldDismissOnAttempt }
        popupBlocker.cancelTab(tabId)
        publish()
    }

    fun onLocationChange(tabId: String, preserveAuth: Boolean = false) {
        tabPolicies[tabId]?.let {
            it.suppressDialogs = false
            it.scriptDialogCount = 0
        }
        cancelPrompts(tabId) {
            !it.native.isUploadPrompt && !(preserveAuth && it.native is ChromiumNativePrompt.HttpAuth)
        }
        popupBlocker.cancelTab(tabId)
        publish()
    }

    fun onMainFrameDocumentCommitted(tabId: String, isSameDocument: Boolean) {
        if (!isSameDocument) {
            tabPolicies[tabId]?.let {
                it.suppressDialogs = false
                it.scriptDialogCount = 0
            }
            cancelPrompts(tabId) { true }
            popupBlocker.cancelTab(tabId)
        }
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
        popupBlocker.cancelTab(tabId)
        publish()
    }

    fun invalidateAll() {
        tabPolicies.clear()
        pendingPrompts.toList().forEach { finishPrompt(it) { it.native.cancel(it.tabId) } }
        popupBlocker.cancelAll()
        publish()
    }

    fun syncState() {
        popupBlocker.syncState()
        pendingPrompts.toList().filterNot(::isPromptValid).forEach { finishPrompt(it) { it.native.cancel(it.tabId) } }
        publish()
    }

    fun onJavaScriptDialog(tabId: String, dialogId: Long, dialogType: Int, message: String, defaultPrompt: String) {
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
            native = ChromiumNativePrompt.JsDialog(
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
        if (tab == null || tabId != selectedTabId() || pendingPrompts.count { it.tabId == tabId } >= MAX_PENDING_PER_TAB) {
            resolveJsDialogNative(tabId, dialogId, false, "")
            return
        }
        enqueuePrompt(tab, ChromiumNativePrompt.BeforeUnload(dialogId, isReload) { id, accept, input ->
            resolveJsDialogNative(tabId, id, accept, input)
        })
    }

    fun onDialogsCancelled(tabId: String, resetState: Boolean) {
        if (resetState) {
            tabPolicies[tabId]?.let { it.suppressDialogs = false; it.scriptDialogCount = 0 }
        }
        pendingPrompts.removeAll {
            it.tabId == tabId && (it.native is ChromiumNativePrompt.JsDialog || it.native is ChromiumNativePrompt.BeforeUnload)
        }
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
        if (tab == null || tabId != selectedTabId() || pendingPrompts.count { it.tabId == tabId } >= MAX_PENDING_PER_TAB) {
            resolveHttpAuthNative(authId, false, "", "")
            return
        }
        enqueuePrompt(tab, ChromiumNativePrompt.HttpAuth(authId, url, challenger, realm, scheme, isProxy, previousFailed, resolveHttpAuthNative))
    }

    fun onHttpAuthCancelled(authId: Long) {
        if (pendingPrompts.removeAll { (it.native as? ChromiumNativePrompt.HttpAuth)?.authId == authId }) publish()
    }

    fun onRepostDialog(tabId: String) {
        val tab = lookupTab(tabId)
        if (tab == null || tabId != selectedTabId() ||
            pendingPrompts.any { it.tabId == tabId && it.native is ChromiumNativePrompt.Repost } ||
            pendingPrompts.count { it.tabId == tabId } >= MAX_PENDING_PER_TAB
        ) {
            resolveRepostNative(tabId, false)
            return
        }
        enqueuePrompt(tab, ChromiumNativePrompt.Repost { resubmit -> resolveRepostNative(tabId, resubmit) })
    }

    fun onClientCertificateRequest(requestId: Long, tabPtr: Long, host: String, encodedPrincipals: Array<ByteArray>) {
        val tab = lookupTabByNativePtr(tabPtr)
        if (tab == null || tab.tabId != selectedTabId() || pendingPrompts.count { it.tabId == tab.tabId } >= MAX_PENDING_PER_TAB) {
            resolveClientCertNative(requestId, null, null)
            return
        }
        enqueuePrompt(
            tab = tab,
            native = ChromiumNativePrompt.ClientCertificate(
                requestId = requestId,
                host = host,
                issuers = decodeIssuers(encodedPrincipals),
                cancelNative = { id -> resolveClientCertNative(id, null, null) },
            ),
        )
    }

    fun onClientCertificateCancelled(requestId: Long) {
        if (pendingPrompts.removeAll { (it.native as? ChromiumNativePrompt.ClientCertificate)?.requestId == requestId }) publish()
    }

    fun onFileChooserRequest(tabId: String, requestId: Long, mode: Int, acceptTypes: Array<String>, captureMode: Int) {
        val tab = lookupTab(tabId)
        if (tab == null || tabId != selectedTabId() || pendingPrompts.count { it.tabId == tabId } >= MAX_PENDING_PER_TAB) {
            cancelFileChooserNative(requestId)
            return
        }
        if (mode == 2) {
            enqueuePrompt(tab, ChromiumNativePrompt.FolderPicker(requestId, cancelFileChooserNative))
            return
        }
        val capture = when (captureMode) {
            1 -> BrowserFileCapture.ANY
            2 -> BrowserFileCapture.FRONT_CAMERA
            3 -> BrowserFileCapture.BACK_CAMERA
            else -> BrowserFileCapture.NONE
        }
        enqueuePrompt(
            tab = tab,
            native = ChromiumNativePrompt.FilePicker(
                requestId = requestId,
                mimeTypes = acceptTypes.toList(),
                multiple = mode == 1,
                capture = capture,
                cancelNative = cancelFileChooserNative,
            ),
        )
    }

    fun onSelectPopup(
        tabId: String,
        requestId: Long,
        labels: Array<String>,
        itemTypes: IntArray,
        multiple: Boolean,
        selectedIndices: IntArray,
        callback: (IntArray?) -> Unit,
    ) {
        val tab = lookupTab(tabId)
        if (tab == null || tabId != selectedTabId() || pendingPrompts.count { it.tabId == tabId } >= MAX_PENDING_PER_TAB) {
            callback(null)
            return
        }
        // Retire an existing Choice entry on live option updates without invoking its stale cancel callback.
        pendingPrompts.removeAll { it.tabId == tabId && it.native is ChromiumNativePrompt.Choice }
        val (choices, indexById) = buildSelectChoices(labels, itemTypes, multiple, selectedIndices)
        enqueuePrompt(tab, ChromiumNativePrompt.Choice(requestId, multiple, choices, indexById, callback))
    }

    fun onSelectPopupClosed(tabId: String, requestId: Long) {
        if (pendingPrompts.removeAll {
                it.tabId == tabId && it.native is ChromiumNativePrompt.Choice && it.native.requestId == requestId
            }
        ) publish()
    }

    fun onDateTimePrompt(
        tabId: String,
        requestId: Long,
        typeCode: Int,
        rawValue: String,
        rawMin: String,
        rawMax: String,
        rawStep: String,
    ) {
        val tab = lookupTab(tabId)
        if (tab == null || tabId != selectedTabId() || pendingPrompts.count { it.tabId == tabId } >= MAX_PENDING_PER_TAB) {
            resolveDateTimeNative(requestId, false, "")
            return
        }
        val type = when (typeCode) {
            1 -> FormDateType.MONTH
            2 -> FormDateType.WEEK
            3 -> FormDateType.TIME
            4 -> FormDateType.DATE_AND_TIME
            else -> FormDateType.DATE
        }
        val spec = FormDateInput(
            type = type,
            initial = rawValue,
            minimum = rawMin.takeIf { it.isNotBlank() },
            maximum = rawMax.takeIf { it.isNotBlank() },
            step = rawStep.takeIf { it.isNotBlank() },
        )
        enqueuePrompt(tab, ChromiumNativePrompt.DateTime(requestId, spec, resolveDateTimeNative))
    }

    fun onDateTimePromptClosed(tabId: String, requestId: Long) {
        if (pendingPrompts.removeAll {
                it.tabId == tabId && it.native is ChromiumNativePrompt.DateTime && it.native.requestId == requestId
            }
        ) publish()
    }

    fun onColorPrompt(tabId: String, requestId: Long, initialColor: String) {
        val tab = lookupTab(tabId)
        if (tab == null || tabId != selectedTabId() || pendingPrompts.count { it.tabId == tabId } >= MAX_PENDING_PER_TAB) {
            resolveColorNative(requestId, false, 0)
            return
        }
        enqueuePrompt(tab, ChromiumNativePrompt.Color(requestId, initialColor, resolveColorNative))
    }

    fun onColorPromptClosed(tabId: String, requestId: Long) {
        if (pendingPrompts.removeAll {
                it.tabId == tabId && it.native is ChromiumNativePrompt.Color && it.native.requestId == requestId
            }
        ) publish()
    }

    internal fun fileSelection(token: Long): ChromiumFileSelection? {
        val entry = currentPrompt(token, BrowserPromptKind.FILE) ?: return null
        val picker = entry.native as? ChromiumNativePrompt.FilePicker ?: return null
        fun docAlive(): Boolean {
            val tab = lookupTab(entry.tabId) ?: return false
            return tab.isPrivate == entry.isPrivate &&
                tab.hardGeneration == entry.hardGeneration &&
                tab.documentGeneration == entry.documentGeneration
        }
        return ChromiumFileSelection(
            token = token,
            mimeTypes = picker.mimeTypes,
            multiple = picker.multiple,
            isPrivate = entry.isPrivate,
            capture = picker.capture,
            pending = { currentPrompt(token, BrowserPromptKind.FILE) === entry },
            documentAlive = ::docAlive,
            deliver = { staged ->
                if (currentPrompt(token, BrowserPromptKind.FILE) !== entry || !docAlive()) false
                else {
                    finishPrompt(entry) { resolveFileChooserFilesNative(picker.requestId, staged) }
                    publish()
                    true
                }
            },
            cancel = {
                if (currentPrompt(token, BrowserPromptKind.FILE) === entry) {
                    finishPrompt(entry) { picker.cancel(entry.tabId) }
                    publish()
                }
            },
        )
    }

    internal fun folderSelection(token: Long): ChromiumFolderSelection? {
        val entry = currentPrompt(token, BrowserPromptKind.FOLDER) ?: return null
        val picker = entry.native as? ChromiumNativePrompt.FolderPicker ?: return null
        fun docAlive(): Boolean {
            val tab = lookupTab(entry.tabId) ?: return false
            return tab.isPrivate == entry.isPrivate &&
                tab.hardGeneration == entry.hardGeneration &&
                tab.documentGeneration == entry.documentGeneration
        }
        return ChromiumFolderSelection(
            token = token,
            isPrivate = entry.isPrivate,
            pending = { currentPrompt(token, BrowserPromptKind.FOLDER) === entry },
            documentAlive = ::docAlive,
            flowCurrent = { docAlive() && selectedTabId() == entry.tabId },
            confirm = { folder, onResolved ->
                if (currentPrompt(token, BrowserPromptKind.FOLDER) !== entry || !docAlive() || selectedTabId() != entry.tabId) {
                    false
                } else {
                    pendingPrompts.remove(entry)
                    pendingPrompts += PendingPrompt(
                        token = ++sequence,
                        tabId = entry.tabId,
                        topOrigin = entry.topOrigin,
                        isPrivate = entry.isPrivate,
                        hardGeneration = entry.hardGeneration,
                        locationGeneration = entry.locationGeneration,
                        attemptGeneration = entry.attemptGeneration,
                        documentGeneration = entry.documentGeneration,
                        native = ChromiumNativePrompt.FolderConfirm(
                            requestId = picker.requestId,
                            folder = folder,
                            onResolved = onResolved,
                            cancelNative = cancelFileChooserNative,
                        ),
                    )
                    publish()
                    true
                }
            },
            cancel = {
                pendingPrompts.firstOrNull {
                    it.token == token || (it.native as? ChromiumNativePrompt.FolderConfirm)?.requestId == picker.requestId
                }?.let { target ->
                    finishPrompt(target) { target.native.cancel(target.tabId) }
                    publish()
                }
            },
        )
    }

    fun shouldAllowPopupWindow(tabId: String, targetUrl: String, userGesture: Boolean, openerSuppressed: Boolean) =
        popupBlocker.shouldAllowPopupWindow(tabId, targetUrl, userGesture, openerSuppressed) { ++sequence }

    override fun allowPopup(token: Long) = popupBlocker.allowPopup(token)
    override fun dismissPopup(token: Long) = popupBlocker.dismissPopup(token)
    override fun setPopupAllowed(site: PopupSiteState, allowed: Boolean) = popupBlocker.setPopupAllowed(site, allowed)
    fun savedPopupOrigins(): Set<String> = popupBlocker.savedOrigins()
    fun clearPopupOrigin(origin: String) = popupBlocker.clearOrigin(origin)
    fun clearPopupPermissions() = popupBlocker.clearAll()

    override fun acknowledgeAlert(token: Long, allowMoreDialogs: Boolean) {
        val entry = currentPrompt(token, BrowserPromptKind.ALERT) ?: return
        val js = entry.native as ChromiumNativePrompt.JsDialog
        applyScriptSuppression(entry.tabId, entry, allowMoreDialogs)
        finishPrompt(entry) { js.resolve(js.dialogId, true, "") }
        publish()
    }

    override fun prepareClientCertificate(token: Long): ClientCertificateSelection? {
        val entry = currentPrompt(token, BrowserPromptKind.CERTIFICATE) ?: return null
        val cert = entry.native as ChromiumNativePrompt.ClientCertificate
        if (!certGate.begin(token)) return null
        publish()
        return ClientCertificateSelection(cert.host, cert.issuers)
    }

    override fun completeClientCertificate(token: Long, alias: String?, unavailable: Boolean) {
        scope.launch(mainDispatcher) {
            if (!certGate.complete(token)) return@launch
            val entry = currentPrompt(token, BrowserPromptKind.CERTIFICATE)
            if (entry != null) {
                val cert = entry.native as ChromiumNativePrompt.ClientCertificate
                if (alias != null) {
                    val (chain, key) = withContext(ioDispatcher) { loadKeyChainIdentity(alias) }
                    val validEntry = currentPrompt(token, BrowserPromptKind.CERTIFICATE) === entry
                    if (validEntry) {
                        val delivered = chain != null && key != null
                        finishPrompt(entry) { resolveClientCertNative(cert.requestId, chain, key) }
                        mutableCertResults.tryEmit(ClientCertificateResult(token, entry.tabId, delivered, unavailable))
                    } else {
                        resolveClientCertNative(cert.requestId, null, null)
                    }
                } else {
                    finishPrompt(entry) { resolveClientCertNative(cert.requestId, null, null) }
                    mutableCertResults.tryEmit(ClientCertificateResult(token, entry.tabId, selected = false, unavailable = unavailable))
                }
            }
            publish()
        }
    }

    override fun answerConfirm(token: Long, button: BrowserPromptButton, allowMoreDialogs: Boolean) {
        val entry = currentPrompt(token, BrowserPromptKind.CONFIRM) ?: return
        val js = entry.native as ChromiumNativePrompt.JsDialog
        applyScriptSuppression(entry.tabId, entry, allowMoreDialogs)
        finishPrompt(entry) { js.resolve(js.dialogId, button == BrowserPromptButton.POSITIVE, "") }
        publish()
    }

    override fun answerText(token: Long, text: String, allowMoreDialogs: Boolean) {
        val entry = currentPrompt(token, BrowserPromptKind.TEXT) ?: return
        val js = entry.native as ChromiumNativePrompt.JsDialog
        applyScriptSuppression(entry.tabId, entry, allowMoreDialogs)
        finishPrompt(entry) { js.resolve(js.dialogId, true, text) }
        publish()
    }

    override fun answerBeforeUnload(token: Long, leave: Boolean) {
        val entry = currentPrompt(token, BrowserPromptKind.BEFORE_UNLOAD) ?: return
        val bu = entry.native as ChromiumNativePrompt.BeforeUnload
        finishPrompt(entry) { bu.resolve(bu.dialogId, leave, "") }
        publish()
    }

    override fun answerRepost(token: Long, resubmit: Boolean) {
        val entry = currentPrompt(token, BrowserPromptKind.REPOST) ?: return
        val repost = entry.native as ChromiumNativePrompt.Repost
        finishPrompt(entry) { repost.resolve(resubmit) }
        publish()
    }

    override fun authenticate(token: Long, userName: String, password: String) {
        val entry = currentPrompt(token, BrowserPromptKind.AUTHENTICATION) ?: return
        val auth = entry.native as ChromiumNativePrompt.HttpAuth
        finishPrompt(entry) { auth.resolve(auth.authId, true, userName, password) }
        publish()
    }

    override fun answerChoice(token: Long, selectedIds: Set<String>) {
        val entry = currentPrompt(token) ?: return
        val choice = entry.native as? ChromiumNativePrompt.Choice ?: return
        if (!validChoiceSelection(choice.choices, selectedIds, choice.multiple)) return
        val indices = selectedIds.mapNotNull(choice.indexById::get).toIntArray()
        finishPrompt(entry) { choice.callback(indices) }
        publish()
    }

    override fun answerDate(token: Long, value: String) {
        val entry = currentPrompt(token, BrowserPromptKind.DATE_TIME) ?: return
        val date = entry.native as ChromiumNativePrompt.DateTime
        if (!date.spec.isValid(value)) return
        val normalized = date.spec.normalized(value) ?: return
        finishPrompt(entry) { date.resolve(date.requestId, true, normalized) }
        publish()
    }

    override fun clearDate(token: Long) {
        val entry = currentPrompt(token, BrowserPromptKind.DATE_TIME) ?: return
        val date = entry.native as ChromiumNativePrompt.DateTime
        finishPrompt(entry) { date.resolve(date.requestId, true, "") }
        publish()
    }

    override fun answerColor(token: Long, value: String) {
        val entry = currentPrompt(token, BrowserPromptKind.COLOR) ?: return
        val color = entry.native as ChromiumNativePrompt.Color
        val normalized = normalizedFormColor(value) ?: return
        val rgb = normalized.removePrefix("#").toInt(16)
        val argb = (0xFF shl 24) or rgb
        finishPrompt(entry) { color.resolve(color.requestId, true, argb) }
        publish()
    }

    override fun answerFolderUpload(token: Long, allow: Boolean) {
        val entry = currentPrompt(token, BrowserPromptKind.FOLDER_CONFIRM) ?: return
        val fc = entry.native as ChromiumNativePrompt.FolderConfirm
        val tab = lookupTab(entry.tabId)
        val docAlive = tab != null && tab.isPrivate == entry.isPrivate &&
            tab.hardGeneration == entry.hardGeneration && tab.documentGeneration == entry.documentGeneration
        if (!pendingPrompts.remove(entry)) return
        val accepted = allow && docAlive
        runCatching {
            fc.onResolved(accepted)
            if (accepted) resolveFileChooserFolderNative(fc.requestId, fc.folder)
            else fc.cancelNative(fc.requestId)
        }
        publish()
    }

    override fun dismiss(token: Long, allowMoreDialogs: Boolean) {
        val entry = currentPrompt(token) ?: return
        if (entry.native is ChromiumNativePrompt.JsDialog) {
            applyScriptSuppression(entry.tabId, entry, allowMoreDialogs)
        }
        finishPrompt(entry) { entry.native.cancel(entry.tabId) }
        publish()
    }

    private fun enqueuePrompt(tab: TabMeta, native: ChromiumNativePrompt) {
        pendingPrompts += PendingPrompt(
            token = ++sequence,
            tabId = tab.tabId,
            topOrigin = siteOrigin(tab.pageUrl),
            isPrivate = tab.isPrivate,
            hardGeneration = tab.hardGeneration,
            locationGeneration = tab.locationGeneration,
            attemptGeneration = tab.attemptGeneration,
            documentGeneration = tab.documentGeneration,
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
        if (entry.native.isUploadPrompt) return tab.documentGeneration == entry.documentGeneration
        if (tab.locationGeneration != entry.locationGeneration) return false
        if (entry.native.shouldDismissOnAttempt && tab.attemptGeneration != entry.attemptGeneration) return false
        return true
    }

    private fun applyScriptSuppression(tabId: String, current: PendingPrompt, allowMore: Boolean) {
        if (allowMore) return
        tabPolicies.getOrPut(tabId) { TabDialogPolicy() }.suppressDialogs = true
        pendingPrompts.filter { it !== current && it.tabId == tabId && it.native is ChromiumNativePrompt.JsDialog }
            .toList().forEach { other -> finishPrompt(other) { other.native.cancel(other.tabId) } }
    }

    private fun cancelPrompts(tabId: String, predicate: (PendingPrompt) -> Boolean) {
        pendingPrompts.filter { it.tabId == tabId && predicate(it) }.toList()
            .forEach { finishPrompt(it) { it.native.cancel(it.tabId) } }
    }

    private fun finishPrompt(entry: PendingPrompt, callback: () -> Unit) {
        if (!pendingPrompts.remove(entry)) return
        runCatching(callback)
    }

    private fun publish() {
        mutablePrompt.value = pendingPrompts.filter(::isPromptValid).minByOrNull { it.token }?.let { entry ->
            projectChromiumPrompt(entry.token, entry.tabId, entry.topOrigin, entry.isPrivate, certGate.busy, entry.native)
        }
        popupBlocker.publish()
        mutableLifecycle.value += 1L
    }

    private companion object {
        const val MAX_PENDING_PER_TAB = 8
    }
}
