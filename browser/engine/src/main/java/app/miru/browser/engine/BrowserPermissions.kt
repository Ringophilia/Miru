/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Request/consume contracts follow Android Components 158.0.2
 * feature-sitepermissions and browser-state. Miru owns its Compose UI,
 * document ownership, native result routing, and isolated decision store.
 * Modifications Copyright (c) 2026 Ringophilia and xiaomao
 * SPDX-License-Identifier: MPL-2.0
 */
package app.miru.browser.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.util.IdentityHashMap
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.browser.engine.gecko.permission.GeckoPermissionRequest
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.permission.Permission
import mozilla.components.concept.engine.permission.PermissionRequest
import org.mozilla.geckoview.GeckoRuntime

/** Application lifetime coordinator. No Activity, saved-state, or URL-bearing diagnostics. */
class BrowserPermissions internal constructor(
    context: Context,
    private val scope: CoroutineScope,
    private val store: BrowserStore,
    runtime: () -> GeckoRuntime?,
    private val onSiteChanged: (origin: String, isPrivate: Boolean, contextId: String?) -> Unit,
    private val onAllSiteSettingsCleared: () -> Unit,
    private val inspectGrantFocus: () -> Unit,
) {
    private val context = context.applicationContext
    private val repository = SitePermissionRepository(this.context, runtime)
    private val decisions = Mutex()
    private var policyGeneration = 0L
    private var clearing = false
    private var sequence = 0L
    private val bindings = mutableMapOf<String, Binding>()
    private val captured = IdentityHashMap<PermissionRequest, Owner>()
    private val requests = IdentityHashMap<PermissionRequest, Pending>()
    private val queue = ArrayDeque<Pending>()
    private var worker: Job? = null
    private var current: Pending? = null
    private var nativeGate: NativeGate? = null
    private val temporary = mutableMapOf<TemporaryKey, SitePermissionChoice>()
    private var siteOwner: Owner? = null
    private var siteJob: Job? = null
    private var siteToken = 0L
    private val mutablePrompt = MutableStateFlow<SitePermissionPrompt?>(null)
    val prompt = mutablePrompt.asStateFlow()
    private val mutableRuntime = MutableStateFlow<RuntimePermissionRequest?>(null)
    val runtimeRequest = mutableRuntime.asStateFlow()
    private val mutableSite = MutableStateFlow<SitePermissionState?>(null)
    val siteState = mutableSite.asStateFlow()
    private var permissionHost: WeakReference<Any>? = null
    private val hostFocused = MutableStateFlow(false)

    fun attachPermissionHost(host: Any) {
        permissionHost = WeakReference(host)
        hostFocused.value = false
    }

    fun onHostWindowFocusChanged(host: Any, focused: Boolean) {
        if(permissionHost?.get() === host) hostFocused.value = focused
    }

    fun detachPermissionHost(host: Any) {
        if(permissionHost?.get() === host) {
            permissionHost = null
            hostFocused.value = false
        }
    }

    /** Called before Gecko sessions can be created or restored; failure keeps the engine gate closed. */
    internal suspend fun prepareRuntime() = decisions.withLock { repository.prepareNativePolicy() }

    private class Binding(
        val tabId: String,
        val session: EngineSession,
        var pageUrl: String,
        val isPrivate: Boolean,
        val contextId: String?,
    ) {
        var generation = 0L
        lateinit var observer: EngineSession.Observer
    }

    private class Owner(val binding: Binding, val generation: Long, val pageUrl: String) {
        val site get() = siteOrigin(pageUrl)?.let { PermissionSite(it, binding.isPrivate, binding.contextId) }
    }

    private class Pending(
        val token: Long,
        val request: PermissionRequest,
        val owner: Owner,
        val app: Boolean,
        val kinds: Set<SitePermissionKind>,
        val site: PermissionSite?,
        val policy: Long,
    ) {
        var complete = false
        var answer: CompletableDeferred<Answer?>? = null
    }

    private data class Answer(val allow: Boolean, val remember: Boolean)
    private data class TemporaryKey(val binding: Binding, val generation: Long, val site: PermissionSite, val kind: SitePermissionKind)
    private class NativeGate(val token: Long, val permissions: List<String>, val isPrivate: Boolean, val pending: Pending) {
        var launched = false
        var abandoned = false
        val result = CompletableDeferred<Map<String, Boolean>>()
    }

    init {
        scope.launch(Dispatchers.Main.immediate) {
            store.stateFlow.collect(::syncState)
        }
    }

    internal fun onSessionLinked(tabId: String, session: EngineSession, url: String, isPrivate: Boolean, contextId: String?) {
        if(bindings[tabId]?.session === session) return
        bindings.remove(tabId)?.let { old -> invalidate(old); old.session.unregister(old.observer) }
        val binding = Binding(tabId, session, url, isPrivate, contextId)
        binding.observer = object : EngineSession.Observer {
            override fun onContentPermissionRequest(permissionRequest: PermissionRequest) = capture(binding, permissionRequest)
            override fun onAppPermissionRequest(permissionRequest: PermissionRequest) = capture(binding, permissionRequest)
            override fun onCancelContentPermissionRequest(permissionRequest: PermissionRequest) {
                requests[permissionRequest]?.let { finish(it, false) }
                captured.remove(permissionRequest)
            }
            override fun onLoadUrl() = invalidate(binding)
            override fun onLoadingStateChange(loading: Boolean) { if(loading) invalidate(binding) }
            override fun onLocationChange(url: String, hasUserGesture: Boolean) {
                if(url != binding.pageUrl) { invalidate(binding); binding.pageUrl = url }
            }
            override fun onCrash() = invalidate(binding)
            override fun onProcessKilled() = invalidate(binding)
        }
        bindings[tabId] = binding
        session.register(binding.observer)
    }

    private fun capture(binding: Binding, request: PermissionRequest) {
        captured[request] = Owner(binding, binding.generation, binding.pageUrl)
    }

    internal fun invalidateTab(tabId: String) { bindings[tabId]?.let(::invalidate) }
    internal fun invalidateAll() { bindings.values.toList().forEach(::invalidate) }

    /** Called before AC reduces an incoming update, including updates from retired sessions. */
    internal fun acceptRequest(tabId: String, request: PermissionRequest, app: Boolean): Boolean {
        val owner = captured[request]
        val kinds = kinds(request, app)
        if(clearing || owner == null || owner.binding.tabId != tabId || !isCurrent(owner) || kinds == null) {
            android.util.Log.d("MiruPermissions", "request-rejected app=$app ownerKnown=${owner != null} current=${owner?.let(::isCurrent) == true} supported=${kinds != null}")
            captured.remove(request)
            request.reject()
            return false
        }
        // AC's Media implementation has no merge override. Reject a duplicate rather than lose its callback.
        if(request is GeckoPermissionRequest.Media) {
            val existing = store.state.tabs.firstOrNull { it.id == tabId }?.content?.permissionRequestsList
                ?.any { it !== request && it.uri == request.uri && it.permissions == request.permissions } == true
            if(existing) { captured.remove(request); request.reject(); return false }
        }
        return true
    }

    /** AC may have merged callbacks into an existing request; only its canonical object is queued. */
    internal fun requestReduced(tabId: String, incoming: PermissionRequest, app: Boolean) {
        val owner = captured.remove(incoming) ?: return
        val content = store.state.tabs.firstOrNull { it.id == tabId }?.content ?: return
        val list = if(app) content.appPermissionRequestsList else content.permissionRequestsList
        val canonical = list.firstOrNull { it.uri == incoming.uri && it.permissions == incoming.permissions } ?: return
        if(requests.containsKey(canonical)) return
        val kinds = kinds(canonical, app) ?: return
        val origin = if(app) null else siteOrigin(canonical.uri)
        if(!app && origin == null) {
            canonical.reject()
            consume(tabId, canonical, app)
            return
        }
        val pending = Pending(++sequence, canonical, owner, app, kinds,
            origin?.let { PermissionSite(it, owner.binding.isPrivate, owner.binding.contextId) }, policyGeneration)
        requests[canonical] = pending
        queue.addLast(pending)
        pump()
    }

    private fun pump() {
        if(worker?.isActive == true) return
        worker = scope.launch(Dispatchers.Main.immediate) {
            while(queue.isNotEmpty()) {
                val pending = queue.removeFirst()
                if(pending.complete) continue
                current = pending
                try {
                    withTimeout(120_000) { process(pending) }
                } catch(cancelled: CancellationException) {
                    finish(pending, false)
                    if(!kotlin.coroutines.coroutineContext[Job]!!.isActive) throw cancelled
                } catch(_: Exception) {
                    finish(pending, false)
                } finally {
                    if(mutablePrompt.value?.token == pending.token) mutablePrompt.value = null
                    current = null
                }
            }
        }
    }

    private suspend fun process(pending: Pending) {
        if(!valid(pending)) { finish(pending, false); return }
        if(pending.app) {
            val allowed = nativePermissions(pending.kinds).let { required ->
                if(nativeSatisfied(pending.kinds)) true else awaitNative(pending, required)
            }
            finish(pending, allowed && awaitHostFocus(pending) && valid(pending) && nativeSatisfied(pending.kinds))
            return
        }
        if(pending.request is GeckoPermissionRequest.Media && !nativeSatisfied(pending.kinds)) {
            // Gecko may still enumerate a media request after Android denied its
            // preceding app request. Miru only supports camera/microphone sources,
            // both of which require their Android grant. Do not ask the website or
            // relaunch a refused system dialog; leave its saved choice unchanged.
            finish(pending, false)
            return
        }
        val site = checkNotNull(pending.site)
        val choices = decisions.withLock {
            if(!valid(pending)) return@withLock emptyMap()
            repository.read(site)
        }
        if(!valid(pending)) { finish(pending, false); return }
        val effective = pending.kinds.associateWith { kind ->
            choices[kind].takeUnless { it == SitePermissionChoice.ASK }
                ?: temporary[TemporaryKey(pending.owner.binding, pending.owner.generation, site, kind)]
                ?: SitePermissionChoice.ASK
        }
        if(effective.values.any { it == SitePermissionChoice.BLOCK }) { finish(pending, false); return }
        val unknown = effective.filterValues { it == SitePermissionChoice.ASK }.keys
        var answer: Answer? = null
        if(unknown.isNotEmpty()) {
            val deferred = CompletableDeferred<Answer?>()
            pending.answer = deferred
            mutablePrompt.value = SitePermissionPrompt(pending.token, site.origin, siteOrigin(pending.owner.pageUrl), site.isPrivate, unknown)
            answer = deferred.await()
            mutablePrompt.value = null
            if(answer == null || !valid(pending)) { finish(pending, false); return }
            if(!answer.allow) {
                saveAnswer(pending, unknown, SitePermissionChoice.BLOCK, answer.remember)
                finish(pending, false)
                refreshSite()
                return
            }
        }
        val nativeAllowed = nativeSatisfied(pending.kinds) || awaitNative(pending, nativePermissions(pending.kinds))
        if(!nativeAllowed || !valid(pending) || !nativeSatisfied(pending.kinds) || !awaitHostFocus(pending)) { finish(pending, false); return }
        if(answer != null) saveAnswer(pending, unknown, SitePermissionChoice.ALLOW, answer.remember)
        finish(pending, valid(pending) && hostFocused.value && nativeSatisfied(pending.kinds))
        refreshSite()
    }

    private suspend fun saveAnswer(pending: Pending, kinds: Set<SitePermissionKind>, choice: SitePermissionChoice, remember: Boolean) {
        decisions.withLock {
            if(!valid(pending)) return@withLock
            val site = checkNotNull(pending.site)
            for(kind in kinds) {
                if(remember) repository.put(site, kind, choice)
                else temporary[TemporaryKey(pending.owner.binding, pending.owner.generation, site, kind)] = choice
            }
        }
    }

    private suspend fun awaitHostFocus(pending: Pending): Boolean = withTimeoutOrNull(5_000) {
        // A Material sheet and Android's system permission dialog own separate
        // windows. Restore the real web window before granting native capture.
        // Rotation rebinds a weak host; a retired Activity cannot change its focus.
        hostFocused.first { it }
        valid(pending)
    } == true

    fun answer(token: Long, allow: Boolean, remember: Boolean) {
        android.util.Log.d("MiruPermissions", "answer token=$token allow=$allow current=${current?.token == token}")
        current?.takeIf { it.token == token && valid(it) }?.answer?.complete(Answer(allow, remember))
    }

    fun dismiss(token: Long) {
        current?.takeIf { it.token == token }?.let { it.answer?.complete(null); finish(it, false) }
    }

    private suspend fun awaitNative(pending: Pending, permissions: List<String>): Boolean {
        // An abandoned *launched* dialog retains this slot until its actual callback arrives.
        while(nativeGate != null) {
            if(!valid(pending)) return false
            delay(50)
        }
        if(!valid(pending)) return false
        val missing = permissions.filterNot(::systemGranted)
        if(missing.isEmpty()) return nativeSatisfied(pending.kinds)
        val gate = NativeGate(++sequence, missing, pending.owner.binding.isPrivate, pending)
        nativeGate = gate
        mutableRuntime.value = RuntimePermissionRequest(gate.token, gate.permissions, gate.isPrivate)
        return try {
            gate.result.await()
            valid(pending) && nativeSatisfied(pending.kinds)
        } finally {
            if(nativeGate === gate) {
                gate.abandoned = true
                mutableRuntime.value = null
                if(!gate.launched) nativeGate = null
            }
        }
    }

    /** Return true exactly once before calling ActivityResultLauncher.launch. */
    fun markRuntimeRequestLaunched(token: Long): Boolean {
        val gate = nativeGate ?: return false
        if(gate.token != token || gate.launched || gate.abandoned) return false
        if(!valid(gate.pending)) {
            gate.abandoned = true
            nativeGate = null
            mutableRuntime.value = null
            gate.result.complete(emptyMap())
            return false
        }
        gate.launched = true
        mutableRuntime.value = mutableRuntime.value?.copy(launched = true)
        return true
    }

    /** ActivityResult has no request token; this always addresses the retained, single launched slot. */
    fun onRuntimePermissionsResult(results: Map<String, Boolean>): Boolean {
        val gate = nativeGate?.takeIf { it.launched } ?: return false
        // Compute before completing: resuming the worker may finish the request.
        // A retired owner's result drains its slot without showing UI on a new page.
        val stillRelevant = !gate.abandoned && valid(gate.pending)
        nativeGate = null
        mutableRuntime.value = null
        gate.result.complete(results)
        refreshSite()
        return stillRelevant
    }

    private fun finish(pending: Pending, allow: Boolean) {
        if(pending.complete) return
        android.util.Log.d("MiruPermissions", "finish token=${pending.token} app=${pending.app} kinds=${pending.kinds} allow=$allow ownerCurrent=${isCurrent(pending.owner)} nativeAllowed=${nativeSatisfied(pending.kinds)}")
        if(allow) {
            inspectGrantFocus()
            if(pending.request is GeckoPermissionRequest.Media) {
                val requested = pending.request.permissions
                android.util.Log.d("MiruPermissions", "media-sources video=${requested.count { it is Permission.ContentVideoCamera }} audio=${requested.count { it is Permission.ContentAudioMicrophone }} missingIds=${requested.count { it.id.isNullOrBlank() }} distinctIds=${requested.map { it.id }.distinct().size}")
            }
        }
        pending.complete = true
        pending.answer?.complete(null)
        requests.remove(pending.request)
        try {
            if(allow) pending.request.grant(pending.request.permissions) else pending.request.reject()
            android.util.Log.d("MiruPermissions", "callback-returned token=${pending.token} allow=$allow")
        } catch(failure: Exception) {
            // Native callback messages can include a site. Record its type only.
            android.util.Log.w("MiruPermissions", "callback-failed type=${failure.javaClass.simpleName}")
            throw failure
        } finally {
            consume(pending.owner.binding.tabId, pending.request, pending.app)
        }
        if(mutablePrompt.value?.token == pending.token) mutablePrompt.value = null
    }

    private fun consume(tabId: String, request: PermissionRequest, app: Boolean) {
        store.dispatch(if(app) ContentAction.ConsumeAppPermissionsRequest(tabId, request)
            else ContentAction.ConsumePermissionsRequest(tabId, request))
    }

    private fun valid(pending: Pending) = !pending.complete && !clearing && pending.policy == policyGeneration && isCurrent(pending.owner)

    private fun isCurrent(owner: Owner): Boolean {
        val binding = owner.binding
        val tab = store.state.tabs.firstOrNull { it.id == binding.tabId } ?: return false
        return store.state.selectedTabId == binding.tabId && bindings[binding.tabId] === binding &&
            tab.engineState.engineSession === binding.session && tab.content.private == binding.isPrivate &&
            binding.generation == owner.generation && tab.content.url == owner.pageUrl
    }

    private fun invalidate(binding: Binding) {
        if(requests.values.any { it.owner.binding === binding }) android.util.Log.d("MiruPermissions", "owner-invalidated")
        binding.generation++
        requests.values.toList().filter { it.owner.binding === binding }.forEach { finish(it, false) }
        captured.entries.removeAll { it.value.binding === binding }
        val oldSites = temporary.keys.filter { it.binding === binding }.map { it.site }.distinct()
        temporary.keys.removeAll { it.binding === binding }
        if(current?.owner?.binding === binding) {
            nativeGate?.let { gate ->
                gate.abandoned = true
                mutableRuntime.value = null
                if(!gate.launched) { nativeGate = null; gate.result.complete(emptyMap()) }
            }
        }
        if(siteOwner?.binding === binding) { siteOwner = null; mutableSite.value = null; siteJob?.cancel() }
        if(oldSites.isNotEmpty()) scope.launch(Dispatchers.Main.immediate) {
            decisions.withLock {
                oldSites.forEach { site ->
                    try {
                        if(repository.read(site)[SitePermissionKind.LOCATION] == SitePermissionChoice.ASK) {
                            repository.setNativeLocation(site, SitePermissionChoice.ASK)
                        }
                    } catch(cancelled: CancellationException) { throw cancelled }
                    catch(_: Exception) { /* A subsequent request still needs Miru's document decision. */ }
                }
            }
        }
    }

    private fun syncState(state: BrowserState) {
        bindings.keys.toList().forEach { id ->
            val binding = bindings.getValue(id)
            val tab = state.tabs.firstOrNull { it.id == id }
            if(tab?.engineState?.engineSession !== binding.session) {
                invalidate(binding)
                binding.session.unregister(binding.observer)
                bindings.remove(id)
            } else if(tab.content.url != binding.pageUrl) {
                invalidate(binding)
                binding.pageUrl = tab.content.url
            }
        }
        requests.values.toList().filterNot(::valid).forEach { finish(it, false) }
        if(state.tabs.none { it.content.private }) {
            repository.clearPrivate()
            temporary.keys.removeAll { it.site.isPrivate }
        }
        val selected = state.tabs.firstOrNull { it.id == state.selectedTabId }
        val binding = selected?.let { bindings[it.id] }
        val existing = siteOwner
        if(binding == null || siteOrigin(selected.content.url) == null) {
            siteOwner = null; siteJob?.cancel(); mutableSite.value = null
        } else if(existing?.binding !== binding || existing.generation != binding.generation || existing.pageUrl != selected.content.url) {
            refreshSite()
        }
    }

    fun refreshSite() {
        siteJob?.cancel()
        val selected = store.state.tabs.firstOrNull { it.id == store.state.selectedTabId } ?: return
        val binding = bindings[selected.id]
        val origin = siteOrigin(selected.content.url)
        if(binding == null || origin == null) { siteOwner = null; mutableSite.value = null; return }
        val owner = Owner(binding, binding.generation, selected.content.url)
        val site = PermissionSite(origin, binding.isPrivate, binding.contextId)
        siteOwner = owner
        val token = ++sequence
        siteToken = token
        mutableSite.value = SitePermissionState(token, selected.id, origin, binding.isPrivate,
            SitePermissionKind.entries.associateWith { SitePermissionChoice.ASK }, systemStates(), busy = true)
        siteJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                val choices = decisions.withLock {
                    val stored = repository.read(site).toMutableMap()
                    SitePermissionKind.entries.forEach { kind ->
                        temporary[TemporaryKey(binding, owner.generation, site, kind)]?.let { stored[kind] = it }
                    }
                    repository.nativeLocationChoice(site)?.let { native ->
                        if(stored[SitePermissionKind.LOCATION] == SitePermissionChoice.ASK) stored[SitePermissionKind.LOCATION] = native
                    }
                    stored
                }
                if(siteToken == token && isCurrent(owner)) mutableSite.value = mutableSite.value?.copy(choices = choices, systemGranted = systemStates(), busy = false)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) {
                if(siteToken == token && isCurrent(owner)) mutableSite.value = mutableSite.value?.copy(busy = false, error = true)
            }
        }
    }

    fun setForSite(token: Long, kind: SitePermissionKind, choice: SitePermissionChoice) = modifySite(token) { site ->
        repository.put(site, kind, choice)
        temporary.keys.removeAll { it.site == site && it.kind == kind }
    }

    fun resetSite(token: Long) = modifySite(token) { site ->
        repository.reset(site)
        temporary.keys.removeAll { it.site == site }
    }

    private fun modifySite(token: Long, update: suspend (PermissionSite) -> Unit) {
        val owner = siteOwner ?: return
        val value = mutableSite.value?.takeIf { it.token == token && !it.busy } ?: return
        if(!isCurrent(owner) || clearing) return
        mutableSite.value = value.copy(busy = true, error = false)
        siteJob?.cancel()
        siteJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                decisions.withLock {
                    if(!isCurrent(owner) || clearing || siteToken != token) return@withLock
                    requests.values.toList().filter { it.site == owner.site }.forEach { finish(it, false) }
                    val site = checkNotNull(owner.site)
                    // Once an explicit setting mutation starts, finish its bounded
                    // write and stop affected documents even if the user switches
                    // tabs meanwhile. A cancelled IO write may already have committed.
                    withContext(NonCancellable) {
                        update(site)
                        onSiteChanged(site.origin, site.isPrivate, site.contextId)
                    }
                }
                if(isCurrent(owner) && siteToken == token) refreshSite()
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) {
                if(isCurrent(owner) && siteToken == token) mutableSite.value = value.copy(error = true, busy = false, systemGranted = systemStates())
            }
        }
    }

    /** B20's Gecko clear callback is awaited under the same barrier as every decision write. */
    internal suspend fun clearAllSiteSettings(clearGecko: suspend () -> Unit) = withContext(Dispatchers.Main.immediate) {
        clearing = true
        policyGeneration++
        requests.values.toList().forEach { finish(it, false) }
        captured.clear()
        temporary.clear()
        siteJob?.cancel()
        nativeGate?.let { gate ->
            gate.abandoned = true; mutableRuntime.value = null
            if(!gate.launched) { nativeGate = null; gate.result.complete(emptyMap()) }
        }
        try {
            decisions.withLock {
                repository.clearAll()
                clearGecko()
                onAllSiteSettingsCleared()
            }
        } finally {
            clearing = false
            refreshSite()
        }
    }

    private fun systemGranted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun nativeSatisfied(kinds: Set<SitePermissionKind>) = kinds.all { kind -> when(kind) {
        SitePermissionKind.CAMERA -> systemGranted(Manifest.permission.CAMERA)
        SitePermissionKind.MICROPHONE -> systemGranted(Manifest.permission.RECORD_AUDIO)
        SitePermissionKind.LOCATION -> systemGranted(Manifest.permission.ACCESS_COARSE_LOCATION) || systemGranted(Manifest.permission.ACCESS_FINE_LOCATION)
    } }
    private fun systemStates() = SitePermissionKind.entries.associateWith { nativeSatisfied(setOf(it)) }
    private fun nativePermissions(kinds: Set<SitePermissionKind>) = kinds.flatMap { kind -> when(kind) {
        SitePermissionKind.CAMERA -> listOf(Manifest.permission.CAMERA)
        SitePermissionKind.MICROPHONE -> listOf(Manifest.permission.RECORD_AUDIO)
        SitePermissionKind.LOCATION -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    } }.distinct()

    private fun kinds(request: PermissionRequest, app: Boolean): Set<SitePermissionKind>? {
        if(request.permissions.isEmpty()) return null
        val mapped = request.permissions.map { permission -> if(app) when(permission) {
            is Permission.AppCamera -> SitePermissionKind.CAMERA
            is Permission.AppAudio -> SitePermissionKind.MICROPHONE
            is Permission.AppLocationCoarse, is Permission.AppLocationFine -> SitePermissionKind.LOCATION
            else -> null
        } else when(permission) {
            is Permission.ContentVideoCamera -> SitePermissionKind.CAMERA
            is Permission.ContentAudioMicrophone -> SitePermissionKind.MICROPHONE
            is Permission.ContentGeoLocation -> SitePermissionKind.LOCATION
            else -> null
        } }
        return if(mapped.any { it == null }) null else mapped.filterNotNull().toSet()
    }
}
