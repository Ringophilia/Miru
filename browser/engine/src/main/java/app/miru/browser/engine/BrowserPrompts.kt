/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Request/callback contracts follow Android Components 158.0.2
 * concept-engine PromptRequest and browser-state EngineObserver.
 * Miru owns its Compose projection, request identity, and lifecycle.
 * Modifications Copyright (c) 2026 Ringophilia and xiaomao
 * SPDX-License-Identifier: MPL-2.0
 */
package app.miru.browser.engine

import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.prompt.PromptRequest

/** Main-thread, application-lifetime ownership of actual engine requests; never retains an Activity. */
class BrowserPrompts internal constructor(private val context: Context, scope: CoroutineScope, private val store: BrowserStore) {
    private val bindings = mutableMapOf<String, Binding>()
    // Gecko creates separate PromptRequest wrappers inside notifyObservers. The
    // native completion closure is shared by those wrappers, unlike their uid.
    private val requests = IdentityHashMap<Any, Pending>()
    private val completed = mutableListOf<WeakReference<Any>>()
    // Replacement retires a wrapper/UI token, not its still-live native callback.
    private val retired = mutableListOf<WeakReference<PromptRequest>>()
    private val mutablePrompt = MutableStateFlow<BrowserPromptState?>(null)
    val prompt = mutablePrompt.asStateFlow()
    private val mutableLifecycle = MutableStateFlow(0L)
    internal val lifecycleChanges = mutableLifecycle.asStateFlow()
    private var sequence = 0L
    private var selectedTabId = store.state.selectedTabId
    private class FolderAuthorization(val token: Long, val request: PromptRequest.Folder, val owner: Owner,
        val document: NativeUploadDocument, val rootName: String, val onShown: () -> Unit,
        val onResolved: (Boolean) -> Unit) { var confirmationKey: Any? = null }
    private var folderAuthorization: FolderAuthorization? = null

    private class Binding(
        val tabId: String,
        val session: EngineSession,
        var nativePageUrl: String,
        val isPrivate: Boolean,
        val contextId: String?,
    ) {
        var hardGeneration = 0L
        var locationGeneration = 0L
        var attemptGeneration = 0L
        var suppressDialogs = false
        lateinit var observer: EngineSession.Observer
    }

    private class Owner(val binding: Binding) {
        val hardGeneration = binding.hardGeneration
        val locationGeneration = binding.locationGeneration
        val attemptGeneration = binding.attemptGeneration
        val nativePageUrl = binding.nativePageUrl
    }

    private class Pending(
        val token: Long,
        val request: PromptRequest,
        val nativeKey: Any,
        val owner: Owner,
        val replacedUids: Set<String>? = null,
        var replacedStoreRequest: PromptRequest? = null,
    ) {
        val aliases = mutableListOf(request)
        var storeRequest: PromptRequest? = null
        var accepted = false
        var inStore = false
    }

    init {
        scope.launch(Dispatchers.Main.immediate) { store.stateFlow.collect(::syncState) }
    }

    internal fun onSessionLinked(tabId: String, session: EngineSession, pageUrl: String, isPrivate: Boolean, contextId: String?) {
        val previous = bindings[tabId]
        if(previous?.session === session && previous.isPrivate == isPrivate && previous.contextId == contextId) return
        previous?.let { invalidate(it); it.session.unregister(it.observer) }
        NativeFilePrompts.install(session, context)
        val binding = Binding(tabId, session, pageUrl, isPrivate, contextId)
        binding.observer = object : PermissionNeutralObserver() {
            override fun onPromptRequest(promptRequest: PromptRequest) = capture(binding, promptRequest)
            override fun onPromptDismissed(promptRequest: PromptRequest) = engineDismissed(binding, promptRequest)
            override fun onPromptUpdate(previousPromptRequestUid: String, promptRequest: PromptRequest) =
                captureReplacement(binding, previousPromptRequestUid, promptRequest)
            override fun onLoadUrl() = navigationAttempt(binding)
            override fun onLoadData() = navigationAttempt(binding)
            override fun onLocationChange(url: String, hasUserGesture: Boolean) {
                // AC drops Gecko's same-document flags. A native location event is
                // a conservative boundary, not proof of a distinct DOM document.
                binding.nativePageUrl = url
                binding.locationGeneration++
                binding.suppressDialogs = false
                cancelMatching(binding) { pending ->
                    !uploadRequest(pending.request) || NativeFilePrompts.documentOwner(pending.request)?.isCurrent() != true
                }
            }
            // PageStart/loading=true precedes commit and may contain HTTP auth.
            // Never turn delayed loading/store URL actions into another epoch.
            override fun onCrash() = invalidate(binding)
            override fun onProcessKilled() = invalidate(binding)
        }
        bindings[tabId] = binding
        session.register(binding.observer)
    }

    internal fun onNavigationAttempt(tabId: String) { bindings[tabId]?.let(::navigationAttempt) }

    private fun navigationAttempt(binding: Binding) {
        binding.attemptGeneration++
        // BeforeUnload belongs to the pending leave/stay decision, and survives
        // the navigation attempt which requested it. Hard/location changes end it.
        cancelMatching(binding) { it.request.shouldDismissOnLoad && !uploadRequest(it.request) }
    }

    internal fun invalidateTab(tabId: String) { bindings[tabId]?.let(::invalidate) }
    internal fun invalidateAll() { bindings.values.toList().forEach(::invalidate) }

    private fun invalidate(binding: Binding) {
        binding.hardGeneration++
        binding.suppressDialogs = false
        cancelMatching(binding) { true }
        folderAuthorization?.takeIf { it.owner.binding === binding }?.let { cancelFolderSelection(it.token) }
    }

    private fun capture(binding: Binding, request: PromptRequest) {
        val key = nativeKey(request)
        if(isCompleted(key) || isRetired(request)) return
        requests[key]?.let { pending ->
            if(pending.owner.binding === binding) addAlias(pending, request)
            return
        }
        var owner = Owner(binding)
        if(request is PromptRequest.FolderUploadPrompt) {
            val authorization = folderAuthorization
            if(authorization == null || authorization.owner.binding !== binding || !folderCurrent(authorization) ||
                request.folderName != authorization.rootName || authorization.confirmationKey != null ||
                !NativeFilePrompts.confirmsFolder(authorization.request, request)) {
                finishUncaptured(binding.tabId, request)
                return
            }
            authorization.confirmationKey = key
            owner = authorization.owner
            authorization.onShown()
        }
        val pending = Pending(++sequence, request, key, owner)
        requests[key] = pending
        if(!supported(request) || (binding.suppressDialogs && scriptDialog(request)) ||
            requests.values.count { it.owner.binding === binding } > MAX_PENDING_PER_TAB) {
            finish(pending) { cancel(request) }
        }
    }

    private fun captureReplacement(binding: Binding, previousUid: String, request: PromptRequest) {
        val key = nativeKey(request)
        if(isCompleted(key) || isRetired(request)) return
        val previous = requests.values.firstOrNull { pending ->
            pending.owner.binding === binding && pending.aliases.any { it.uid == previousUid }
        } ?: requests[key]?.takeIf { it.owner.binding === binding }
        if(previous == null || !valid(previous)) {
            previous?.let { finish(it) { cancel(it.request) } }
            finishUncaptured(binding.tabId, request)
            return
        }
        // An update represents the SAME native callback with new display data.
        // Retire the old UI token without invoking its cancellation callback.
        val previousUids = previous.aliases.mapTo(mutableSetOf()) { it.uid }.also { it += previousUid }
        retire(previous, completeNative = false)
        val replacement = Pending(++sequence, request, key, previous.owner, previousUids,
            previous.storeRequest ?: previous.replacedStoreRequest)
        requests[key] = replacement
        if(!supported(request)) finish(replacement) { cancel(request) }
        publish()
    }

    internal fun acceptRequest(tabId: String, request: PromptRequest): Boolean = accept(tabId, request, null)
    internal fun acceptReplacement(tabId: String, previousUid: String, request: PromptRequest): Boolean =
        accept(tabId, request, previousUid)

    /** AC's reducer must remove its own wrapper uid, which may differ from the native observer's uid. */
    internal fun replacementStoreUid(request: PromptRequest, previousUid: String): String =
        requests[nativeKey(request)]?.replacedStoreRequest?.uid ?: previousUid

    private fun accept(tabId: String, request: PromptRequest, replacedUid: String?): Boolean {
        val key = nativeKey(request)
        if(isCompleted(key) || isRetired(request)) return false
        val pending = requests[key]
        if(pending == null) { finishUncaptured(tabId, request); return false }
        if(pending.accepted) return false
        // A delayed action for an older wrapper can share this native callback.
        // Ignore it without completing the replacement's callback or UI token.
        if((replacedUid == null && pending.replacedUids != null) ||
            (replacedUid != null && pending.replacedUids?.contains(replacedUid) != true)) return false
        if(pending.owner.binding.tabId != tabId || !valid(pending)) {
            finish(pending) { cancel(request) }
            return false
        }
        addAlias(pending, request)
        pending.storeRequest = request
        pending.accepted = true
        return true
    }

    internal fun requestReduced(tabId: String, request: PromptRequest) {
        val pending = requests[nativeKey(request)] ?: return
        if(pending.storeRequest !== request) return
        val tab = store.state.tabs.firstOrNull { it.id == tabId }
        if(!pending.accepted || !valid(pending) || tab?.content?.promptRequests?.none { it === request } != false) {
            finish(pending) { cancel(request) }
            return
        }
        pending.replacedStoreRequest = null
        pending.inStore = true
        publish()
    }

    internal fun requestConsumed(tabId: String, request: PromptRequest) {
        requests[nativeKey(request)]?.takeIf { it.owner.binding.tabId == tabId }?.let { pending ->
            // Engine dismissals retire in the synchronous native observer first.
            // An external removal must still end any otherwise unresolved request.
            val currentStoreRequest = pending.storeRequest ?: return
            val tab = store.state.tabs.firstOrNull { it.id == tabId }
            if(tab?.content?.promptRequests?.none { it === currentStoreRequest } != false) {
                finish(pending, consume = false) { cancel(pending.request) }
            }
        }
    }

    private fun engineDismissed(binding: Binding, request: PromptRequest) {
        val key = nativeKey(request)
        val pending = requests[key]
        if(pending?.owner?.binding === binding) {
            finish(pending) { /* Gecko has already ended this request. */ }
        } else if(!isCompleted(key)) {
            rememberCompleted(key)
            consume(binding.tabId, request)
        }
    }

    private fun valid(pending: Pending): Boolean {
        if(requests[pending.nativeKey] !== pending) return false
        val owner = pending.owner
        val binding = owner.binding
        val tab = store.state.tabs.firstOrNull { it.id == binding.tabId } ?: return false
        return store.state.selectedTabId == binding.tabId && bindings[binding.tabId] === binding &&
            tab.engineState.engineSession === binding.session && tab.content.private == binding.isPrivate &&
            tab.contextId == binding.contextId && binding.hardGeneration == owner.hardGeneration &&
            (if(uploadRequest(pending.request)) NativeFilePrompts.documentOwner(pending.request)?.isCurrent() == true &&
                (pending.request !is PromptRequest.FolderUploadPrompt || folderAuthorization?.let {
                    it.owner === owner && it.confirmationKey === pending.nativeKey && folderCurrent(it)
                } == true)
             else binding.locationGeneration == owner.locationGeneration &&
                 (!pending.request.shouldDismissOnLoad || binding.attemptGeneration == owner.attemptGeneration))
    }

    private fun syncState(state: BrowserState) {
        if(selectedTabId != state.selectedTabId) {
            selectedTabId?.let { bindings[it]?.let(::invalidate) }
            selectedTabId = state.selectedTabId
        }
        bindings.keys.toList().forEach { id ->
            val binding = bindings.getValue(id)
            val tab = state.tabs.firstOrNull { it.id == id }
            if(tab?.engineState?.engineSession !== binding.session || tab.content.private != binding.isPrivate || tab.contextId != binding.contextId) {
                invalidate(binding)
                binding.session.unregister(binding.observer)
                bindings.remove(id)
            }
        }
        requests.values.toList().filterNot(::valid).forEach { pending -> finish(pending) { cancel(pending.request) } }
        folderAuthorization?.takeUnless(::folderCurrent)?.let { cancelFolderSelection(it.token) }
        publish()
        mutableLifecycle.value++
    }

    private fun cancelMatching(binding: Binding, predicate: (Pending) -> Boolean) {
        requests.values.toList().filter { it.owner.binding === binding && predicate(it) }
            .forEach { pending -> finish(pending) { cancel(pending.request) } }
        publish()
    }

    private fun publish() {
        mutablePrompt.value = requests.values.filter { it.inStore && valid(it) }.minByOrNull { it.token }?.let(::project)
    }

    private fun current(token: Long, kind: BrowserPromptKind? = null): Pending? {
        if(mutablePrompt.value?.token != token) return null
        val pending = requests.values.firstOrNull { it.token == token } ?: return null
        if(!valid(pending)) { finish(pending) { cancel(pending.request) }; return null }
        return pending.takeIf { kind == null || mutablePrompt.value?.kind == kind }
    }

    fun acknowledgeAlert(token: Long, allowMoreDialogs: Boolean = true) {
        val pending = current(token, BrowserPromptKind.ALERT) ?: return
        val request = pending.request as PromptRequest.Alert
        setScriptPolicy(pending, allowMoreDialogs)
        finish(pending) { request.onConfirm(allowMoreDialogs) }
    }

    fun answerConfirm(token: Long, button: BrowserPromptButton, allowMoreDialogs: Boolean = true) {
        val pending = current(token, BrowserPromptKind.CONFIRM) ?: return
        val request = pending.request as PromptRequest.Confirm
        setScriptPolicy(pending, allowMoreDialogs)
        finish(pending) {
            when(button) {
                BrowserPromptButton.POSITIVE -> request.onConfirmPositiveButton(allowMoreDialogs)
                BrowserPromptButton.NEGATIVE -> request.onConfirmNegativeButton(allowMoreDialogs)
                BrowserPromptButton.NEUTRAL -> request.onConfirmNeutralButton(allowMoreDialogs)
            }
        }
    }

    fun answerText(token: Long, text: String, allowMoreDialogs: Boolean = true) {
        val pending = current(token, BrowserPromptKind.TEXT) ?: return
        val request = pending.request as PromptRequest.TextPrompt
        setScriptPolicy(pending, allowMoreDialogs)
        finish(pending) { request.onConfirm(allowMoreDialogs, text) }
    }

    fun answerBeforeUnload(token: Long, leave: Boolean) {
        val pending = current(token, BrowserPromptKind.BEFORE_UNLOAD) ?: return
        val request = pending.request as PromptRequest.BeforeUnload
        finish(pending) { if(leave) request.onLeave() else request.onStay() }
    }

    fun answerRepost(token: Long, resubmit: Boolean) {
        val pending = current(token, BrowserPromptKind.REPOST) ?: return
        val request = pending.request as PromptRequest.Repost
        finish(pending) { if(resubmit) request.onConfirm() else request.onDismiss() }
    }

    fun authenticate(token: Long, userName: String, password: String) {
        val pending = current(token, BrowserPromptKind.AUTHENTICATION) ?: return
        val request = pending.request as PromptRequest.Authentication
        finish(pending) { request.onConfirm(userName, password) }
    }

    fun answerFolderUpload(token: Long, allow: Boolean) {
        val pending = current(token, BrowserPromptKind.FOLDER_CONFIRM) ?: return
        val request = pending.request as PromptRequest.FolderUploadPrompt
        finish(pending) { if(allow) request.onConfirm() else request.onDismiss() }
    }

    fun dismiss(token: Long, allowMoreDialogs: Boolean = true) {
        val pending = current(token) ?: return
        if(scriptDialog(pending.request)) setScriptPolicy(pending, allowMoreDialogs)
        finish(pending) { cancel(pending.request) }
    }

    /** Internal capability bound to the real pending request; never part of UI/saved state. */
    internal fun fileSelection(token: Long): BrowserFileSelection? {
        val pending = current(token, BrowserPromptKind.FILE) ?: return null
        val request = pending.request as? PromptRequest.File ?: return null
        val document = NativeFilePrompts.documentOwner(request) ?: return null
        val binding = pending.owner.binding
        return BrowserFileSelection(token, request.mimeTypes.toList(), request.isMultipleFilesSelection,
            binding.isPrivate, BrowserFileCapture.valueOf(request.captureMode.name),
            pending = { current(token, BrowserPromptKind.FILE) === pending },
            documentAlive = {
                val tab = store.state.tabs.firstOrNull { it.id == binding.tabId }
                tab?.engineState?.engineSession === binding.session && tab.content.private == binding.isPrivate &&
                    tab.contextId == binding.contextId && !tab.engineState.crashed && document.isCurrent()
            },
            prepare = { files -> NativeFilePrompts.prepareFiles(request, files) },
            deliver = { prepared ->
                if(current(token, BrowserPromptKind.FILE) !== pending || !document.isCurrent()) false
                else {
                    finish(pending) { NativeFilePrompts.selectPrepared(request, prepared) }
                    NativeFilePrompts.wasConfirmed(request)
                }
            },
            cancel = { if(current(token, BrowserPromptKind.FILE) === pending) finish(pending) { request.onDismiss() } },
        )
    }

    internal fun folderSelection(token: Long): BrowserFolderSelection? {
        val pending = current(token, BrowserPromptKind.FOLDER) ?: return null
        val request = pending.request as? PromptRequest.Folder ?: return null
        val document = NativeFilePrompts.documentOwner(request) ?: return null
        val owner = pending.owner
        val binding = owner.binding
        fun documentAlive(): Boolean {
            val tab = store.state.tabs.firstOrNull { it.id == binding.tabId }
            return tab?.engineState?.engineSession === binding.session && tab.content.private == binding.isPrivate &&
                tab.contextId == binding.contextId && !tab.engineState.crashed && document.isCurrent()
        }
        return BrowserFolderSelection(token, binding.isPrivate,
            pending = { current(token, BrowserPromptKind.FOLDER) === pending },
            documentAlive = ::documentAlive,
            flowCurrent = { documentAlive() && store.state.selectedTabId == binding.tabId &&
                bindings[binding.tabId] === binding && binding.hardGeneration == owner.hardGeneration },
            prepare = { directory -> NativeFilePrompts.prepareFolder(request, directory) },
            deliver = { prepared, rootName, onShown, onResolved ->
                if(current(token, BrowserPromptKind.FOLDER) !== pending || !documentAlive() || folderAuthorization != null) false
                else {
                    val authorization = FolderAuthorization(token, request, owner, document, rootName, onShown, onResolved)
                    folderAuthorization = authorization // Before the real callback can trigger the second prompt.
                    finish(pending) { NativeFilePrompts.selectPreparedFolder(request, prepared) }
                    NativeFilePrompts.wasConfirmed(request).also { accepted ->
                        if(!accepted) resolveFolderAuthorization(authorization, false)
                    }
                }
            },
            cancel = {
                current(token, BrowserPromptKind.FOLDER)?.takeIf { it === pending }?.let { finish(it) { request.onDismiss() } }
                cancelFolderSelection(token)
            },
        )
    }

    private fun folderCurrent(authorization: FolderAuthorization): Boolean {
        val owner = authorization.owner
        val binding = owner.binding
        val tab = store.state.tabs.firstOrNull { it.id == binding.tabId } ?: return false
        return store.state.selectedTabId == binding.tabId && bindings[binding.tabId] === binding &&
            binding.hardGeneration == owner.hardGeneration && tab.engineState.engineSession === binding.session &&
            tab.content.private == binding.isPrivate && tab.contextId == binding.contextId &&
            !tab.engineState.crashed && authorization.document.isCurrent()
    }

    private fun cancelFolderSelection(token: Long) {
        val authorization = folderAuthorization?.takeIf { it.token == token } ?: return
        val confirmation = authorization.confirmationKey?.let { requests[it] }
        if(confirmation != null) finish(confirmation) { cancel(confirmation.request) }
        else resolveFolderAuthorization(authorization, false)
    }

    private fun resolveFolderAuthorization(authorization: FolderAuthorization, allow: Boolean) {
        if(folderAuthorization !== authorization) return
        folderAuthorization = null
        authorization.onResolved(allow)
    }

    private fun setScriptPolicy(pending: Pending, allowMore: Boolean) {
        if(allowMore) return
        val binding = pending.owner.binding
        binding.suppressDialogs = true
        requests.values.toList().filter { it !== pending && it.owner.binding === binding && scriptDialog(it.request) }
            .forEach { other -> finish(other) { cancel(other.request) } }
    }

    private fun addAlias(pending: Pending, request: PromptRequest) {
        if(pending.aliases.none { it === request }) pending.aliases += request
    }

    private fun retire(pending: Pending, completeNative: Boolean = true): Boolean {
        if(requests[pending.nativeKey] !== pending) return false
        requests.remove(pending.nativeKey)
        pending.aliases.forEach { retired += WeakReference(it) }
        if(completeNative) rememberCompleted(pending.nativeKey)
        return true
    }

    private inline fun finish(pending: Pending, consume: Boolean = true, callback: () -> Unit) {
        if(!retire(pending)) return
        publish()
        try { callback() }
        catch(_: Exception) { /* A failed native callback must not strand its store entry or retain form data. */ }
        finally {
            if(pending.request is PromptRequest.FolderUploadPrompt) folderAuthorization?.takeIf {
                it.confirmationKey === pending.nativeKey
            }?.let { resolveFolderAuthorization(it, NativeFilePrompts.wasConfirmed(pending.request)) }
            if(consume) {
                pending.storeRequest?.let { consume(pending.owner.binding.tabId, it) }
                pending.replacedStoreRequest?.let { consume(pending.owner.binding.tabId, it) }
            }
        }
    }

    private fun finishUncaptured(tabId: String, request: PromptRequest) {
        val key = nativeKey(request)
        if(isCompleted(key)) return
        rememberCompleted(key)
        try { cancel(request) }
        catch(_: Exception) { /* Still consume this original request; never fall back to the selected tab. */ }
        finally { consume(tabId, request) }
    }

    private fun consume(tabId: String, request: PromptRequest) {
        store.dispatch(ContentAction.ConsumePromptRequestAction(tabId, request))
    }

    private fun isCompleted(key: Any): Boolean {
        completed.removeAll { it.get() == null }
        return completed.any { it.get() === key }
    }

    private fun rememberCompleted(key: Any) {
        if(!isCompleted(key)) completed += WeakReference(key)
    }

    private fun isRetired(request: PromptRequest): Boolean {
        retired.removeAll { it.get() == null }
        return retired.any { it.get() === request }
    }

    /** Callback identity is the shared native request identity in AC 158.0.2. */
    private fun nativeKey(request: PromptRequest): Any = when(request) {
        is PromptRequest.Alert -> request.onConfirm
        // Confirm.onDismiss is an extra per-observer wrapper lambda in Gecko.
        is PromptRequest.Confirm -> request.onConfirmPositiveButton
        is PromptRequest.TextPrompt -> request.onConfirm
        is PromptRequest.BeforeUnload -> request.onLeave
        is PromptRequest.Repost -> request.onConfirm
        is PromptRequest.Authentication -> request.onConfirm
        is PromptRequest.File -> request.onSingleFileSelected
        is PromptRequest.Folder -> request.onSelected
        is PromptRequest.FolderUploadPrompt -> request.onConfirm
        is PromptRequest.CertificateRequest -> request.onComplete
        is PromptRequest.Popup -> request.onDeny
        is PromptRequest.Redirect -> request.onDeny
        is PromptRequest.Dismissible -> request.onDismiss
        else -> request
    }

    private fun supported(request: PromptRequest) = request is PromptRequest.Alert || request is PromptRequest.Confirm ||
        request is PromptRequest.TextPrompt || request is PromptRequest.BeforeUnload || request is PromptRequest.Repost ||
        request is PromptRequest.Authentication || (uploadRequest(request) && NativeFilePrompts.isManaged(request))

    private fun uploadRequest(request: PromptRequest) = request is PromptRequest.File || request is PromptRequest.Folder ||
        request is PromptRequest.FolderUploadPrompt

    private fun scriptDialog(request: PromptRequest) = request is PromptRequest.Alert || request is PromptRequest.Confirm || request is PromptRequest.TextPrompt

    private fun cancel(request: PromptRequest) {
        when(request) {
            is PromptRequest.BeforeUnload -> request.onStay()
            is PromptRequest.CertificateRequest -> request.onComplete(null)
            is PromptRequest.Popup -> request.onDeny()
            is PromptRequest.Redirect -> request.onDeny()
            is PromptRequest.Dismissible -> request.onDismiss()
            else -> error("Unhandled prompt request type")
        }
    }

    private fun project(pending: Pending): BrowserPromptState {
        val request = pending.request
        val binding = pending.owner.binding
        val topOrigin = siteOrigin(pending.owner.nativePageUrl)
        // These AC requests do not expose the initiating frame's origin.
        val base = BrowserPromptState(pending.token, binding.tabId, kind(request), null, topOrigin, binding.isPrivate)
        return when(request) {
            is PromptRequest.Alert -> base.copy(title = request.title, message = request.message, hasShownManyDialogs = request.hasShownManyDialogs)
            is PromptRequest.Confirm -> base.copy(title = request.title, message = request.message, hasShownManyDialogs = request.hasShownManyDialogs,
                positiveButtonTitle = request.positiveButtonTitle, negativeButtonTitle = request.negativeButtonTitle, neutralButtonTitle = request.neutralButtonTitle)
            is PromptRequest.TextPrompt -> base.copy(title = request.title, inputLabel = request.inputLabel, inputValue = request.inputValue,
                hasShownManyDialogs = request.hasShownManyDialogs)
            is PromptRequest.BeforeUnload -> base.copy(title = request.title)
            is PromptRequest.Repost -> base
            is PromptRequest.Authentication -> base.copy(origin = request.uri?.let(::siteOrigin), title = request.title, message = request.message,
                userName = request.userName, onlyShowPassword = request.onlyShowPassword, previousFailed = request.previousFailed,
                isCrossOrigin = request.isCrossOrigin, authMethod = BrowserAuthMethod.valueOf(request.method.name),
                authLevel = BrowserAuthLevel.valueOf(request.level.name))
            is PromptRequest.File -> base.copy(fileMimeTypes = request.mimeTypes.toList(),
                fileMultiple = request.isMultipleFilesSelection, fileCapture = BrowserFileCapture.valueOf(request.captureMode.name))
            is PromptRequest.Folder -> base
            is PromptRequest.FolderUploadPrompt -> base.copy(folderName = request.folderName)
            else -> error("Unsupported prompt cannot be projected")
        }
    }

    private fun kind(request: PromptRequest) = when(request) {
        is PromptRequest.Alert -> BrowserPromptKind.ALERT
        is PromptRequest.Confirm -> BrowserPromptKind.CONFIRM
        is PromptRequest.TextPrompt -> BrowserPromptKind.TEXT
        is PromptRequest.BeforeUnload -> BrowserPromptKind.BEFORE_UNLOAD
        is PromptRequest.Repost -> BrowserPromptKind.REPOST
        is PromptRequest.Authentication -> BrowserPromptKind.AUTHENTICATION
        is PromptRequest.File -> BrowserPromptKind.FILE
        is PromptRequest.Folder -> BrowserPromptKind.FOLDER
        is PromptRequest.FolderUploadPrompt -> BrowserPromptKind.FOLDER_CONFIRM
        else -> error("Unsupported prompt has no UI kind")
    }

    companion object { private const val MAX_PENDING_PER_TAB = 8 }
}
