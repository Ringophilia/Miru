// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import java.lang.ref.WeakReference

/**
 * Application-lifetime site permission, media capture, Android runtime permission gate,
 * and per-site autoplay coordinator for Chromium tabs. Mirrors Gecko's privacy isolation,
 * document-scoped temporary grants/denials, and Android-first media permission ordering.
 */
internal class ChromiumPermissionsController(
    context: Context,
    private val scope: CoroutineScope,
    private val selectedTabId: () -> String?,
    private val lookupTab: (String) -> TabMeta?,
    private val lookupTabByNativePtr: (Long) -> TabMeta?,
    private val hasPrivateTabs: () -> Boolean,
    private val resolveSitePermissionsNative: (requestId: Long, allowed: Boolean) -> Unit,
    private val resolveMediaAccessNative: (requestId: Long, allowed: Boolean) -> Unit,
    private val onSiteChanged: (origin: String, isPrivate: Boolean) -> Unit,
    private val onAllSiteSettingsCleared: () -> Unit,
    private val store: ChromiumSitePermissionStore = ChromiumSitePermissionStore(context),
    private val autoplay: AutoplayPermissions = sharedAutoplay(context),
    private val systemPermissionChecker: ((String) -> Boolean)? = null,
    private val notificationsEnabledChecker: (() -> Boolean)? = null,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : BrowserPermissionsController {

    internal data class TabMeta(
        val tabId: String,
        val pageUrl: String,
        val isPrivate: Boolean,
        val hardGeneration: Long,
        val locationGeneration: Long,
    )

    private data class Answer(val allow: Boolean, val remember: Boolean)

    private data class TemporaryKey(
        val tabId: String,
        val hardGeneration: Long,
        val locationGeneration: Long,
        val origin: String,
        val isPrivate: Boolean,
        val kind: SitePermissionKind,
    )

    private class Pending(
        val token: Long,
        val nativeRequestId: Long,
        val isMedia: Boolean,
        val tabId: String,
        val origin: String,
        val topLevelOrigin: String?,
        val isPrivate: Boolean,
        val kinds: Set<SitePermissionKind>,
        val hardGeneration: Long,
        val locationGeneration: Long,
        val policyGeneration: Long,
    ) {
        var complete = false
        var answer: CompletableDeferred<Answer?>? = null
    }

    private class NativeGate(
        val token: Long,
        val permissions: List<String>,
        val isPrivate: Boolean,
        val pending: Pending,
    ) {
        var launched = false
        var abandoned = false
        var rationaleAtLaunch = emptySet<String>()
        val result = CompletableDeferred<Map<String, Boolean>>()
    }

    private val appContext = context.applicationContext
    private val decisions = Mutex()
    private val runtimeRecovery = RuntimePermissionRecovery()
    private val temporary = mutableMapOf<TemporaryKey, SitePermissionChoice>()
    private val queue = ArrayDeque<Pending>()

    private var policyGeneration = 0L
    private var clearing = false
    private var sequence = 0L
    private var worker: Job? = null
    private var current: Pending? = null
    private var nativeGate: NativeGate? = null
    private var siteToken = 0L
    private var siteOwner: TabMeta? = null

    private val mutablePrompt = MutableStateFlow<SitePermissionPrompt?>(null)
    override val prompt: StateFlow<SitePermissionPrompt?> = mutablePrompt.asStateFlow()

    private val mutableRuntime = MutableStateFlow<RuntimePermissionRequest?>(null)
    override val runtimeRequest: StateFlow<RuntimePermissionRequest?> = mutableRuntime.asStateFlow()

    private val mutableSite = MutableStateFlow<SitePermissionState?>(null)
    override val siteState: StateFlow<SitePermissionState?> = mutableSite.asStateFlow()

    private var permissionHost: WeakReference<Any>? = null
    private val hostFocused = MutableStateFlow(true)

    override fun attachPermissionHost(host: Any) {
        permissionHost = WeakReference(host)
        val focused = (host as? Activity)?.window?.decorView?.hasWindowFocus()
        hostFocused.value = focused ?: true
    }

    override fun onHostWindowFocusChanged(host: Any, focused: Boolean) {
        if (permissionHost?.get() === host) hostFocused.value = focused
    }

    override fun detachPermissionHost(host: Any) {
        if (permissionHost?.get() === host) {
            permissionHost = null
            hostFocused.value = false
        }
    }

    /** Native `PermissionControllerDelegate` synchronous lookup (`0=ASK, 1=GRANTED, 2=DENIED`). */
    fun querySitePermission(originUrl: String, isPrivate: Boolean, kindCode: Int, tabPtr: Long): Int {
        val origin = siteOrigin(originUrl) ?: return 2
        val kind = kindFromCode(kindCode) ?: return 2
        if (clearing) return 2
        var choice = store.readOne(origin, isPrivate, kind)
        if (choice == SitePermissionChoice.ASK && tabPtr != 0L) {
            lookupTabByNativePtr(tabPtr)?.let { tab ->
                temporary[TemporaryKey(tab.tabId, tab.hardGeneration, tab.locationGeneration, origin, isPrivate, kind)]
                    ?.let { choice = it }
            }
        }
        return when (choice) {
            SitePermissionChoice.BLOCK -> 2
            SitePermissionChoice.ALLOW -> {
                if (kind == SitePermissionKind.PROTECTED_MEDIA || nativeSatisfied(setOf(kind))) 1 else 0
            }
            SitePermissionChoice.ASK -> 0
        }
    }

    /** Native `OverrideWebPreferences` lookup (`0=BLOCK_AUDIBLE, 1=ALLOW_ALL, 2=BLOCK_ALL`). */
    fun queryAutoplayPolicy(url: String, isPrivate: Boolean): Int {
        val origin = siteOrigin(url) ?: return 0
        return when (autoplay.choice(origin, isPrivate, contextKey(isPrivate))) {
            SiteAutoplayChoice.ALLOW_ALL -> 1
            SiteAutoplayChoice.BLOCK_ALL -> 2
            SiteAutoplayChoice.BLOCK_AUDIBLE -> 0
        }
    }

    fun onRequestSitePermissions(
        requestId: Long,
        tabPtr: Long,
        originUrl: String,
        topOriginUrl: String,
        isPrivate: Boolean,
        kindsMask: Int,
    ) {
        enqueueNativeRequest(requestId, isMedia = false, tabPtr, originUrl, topOriginUrl, isPrivate, kindsMask)
    }

    fun onRequestMediaAccess(
        requestId: Long,
        tabPtr: Long,
        originUrl: String,
        topOriginUrl: String,
        isPrivate: Boolean,
        kindsMask: Int,
    ) {
        enqueueNativeRequest(requestId, isMedia = true, tabPtr, originUrl, topOriginUrl, isPrivate, kindsMask)
    }

    private fun enqueueNativeRequest(
        requestId: Long,
        isMedia: Boolean,
        tabPtr: Long,
        originUrl: String,
        topOriginUrl: String,
        isPrivate: Boolean,
        kindsMask: Int,
    ) {
        val tab = lookupTabByNativePtr(tabPtr)
        val origin = siteOrigin(originUrl) ?: siteOrigin(tab?.pageUrl)
        val kinds = decodeKindsMask(kindsMask)
        if (clearing || tab == null || origin == null || kinds.isEmpty() || tab.isPrivate != isPrivate) {
            resolveNative(requestId, isMedia, allowed = false)
            return
        }
        val pending = Pending(
            token = ++sequence,
            nativeRequestId = requestId,
            isMedia = isMedia,
            tabId = tab.tabId,
            origin = origin,
            topLevelOrigin = siteOrigin(topOriginUrl) ?: siteOrigin(tab.pageUrl),
            isPrivate = isPrivate,
            kinds = kinds,
            hardGeneration = tab.hardGeneration,
            locationGeneration = tab.locationGeneration,
            policyGeneration = policyGeneration,
        )
        queue.addLast(pending)
        pump()
    }

    private fun pump() {
        if (worker?.isActive == true) return
        worker = scope.launch(mainDispatcher) {
            while (queue.isNotEmpty()) {
                val pending = queue.removeFirst()
                if (pending.complete) continue
                current = pending
                try {
                    withTimeout(120_000) { process(pending) }
                } catch (cancelled: CancellationException) {
                    finish(pending, allowed = false)
                    if (!kotlin.coroutines.coroutineContext[Job]!!.isActive) throw cancelled
                } catch (_: Exception) {
                    finish(pending, allowed = false)
                } finally {
                    if (mutablePrompt.value?.token == pending.token) mutablePrompt.value = null
                    current = null
                }
            }
        }
    }

    private suspend fun process(pending: Pending) {
        if (!isPendingValid(pending)) {
            finish(pending, allowed = false)
            return
        }
        val stored = decisions.withLock { store.read(pending.origin, pending.isPrivate) }
        val effective = pending.kinds.associateWith { kind ->
            stored[kind].takeUnless { it == SitePermissionChoice.ASK }
                ?: temporary[TemporaryKey(pending.tabId, pending.hardGeneration, pending.locationGeneration, pending.origin, pending.isPrivate, kind)]
                ?: SitePermissionChoice.ASK
        }
        if (effective.values.any { it == SitePermissionChoice.BLOCK }) {
            finish(pending, allowed = false)
            return
        }
        val unknown = effective.filterValues { it == SitePermissionChoice.ASK }.keys

        // Media requests acquire Android camera/microphone first so a runtime denial keeps website choice ASK.
        if (pending.isMedia && !nativeSatisfied(pending.kinds)) {
            val runtimeOk = awaitNative(pending, nativePermissions(pending.kinds))
            if (!runtimeOk || !isPendingValid(pending) || !nativeSatisfied(pending.kinds)) {
                finish(pending, allowed = false)
                return
            }
        }

        var answer: Answer? = null
        if (unknown.isNotEmpty()) {
            val deferred = CompletableDeferred<Answer?>()
            pending.answer = deferred
            mutablePrompt.value = SitePermissionPrompt(
                token = pending.token,
                origin = pending.origin,
                topLevelOrigin = pending.topLevelOrigin,
                isPrivate = pending.isPrivate,
                kinds = unknown,
            )
            answer = deferred.await()
            if (mutablePrompt.value?.token == pending.token) mutablePrompt.value = null
            if (answer == null || !isPendingValid(pending)) {
                finish(pending, allowed = false)
                return
            }
            if (!answer.allow) {
                saveDecision(pending, unknown, SitePermissionChoice.BLOCK, answer.remember)
                finish(pending, allowed = false)
                refreshSite()
                return
            }
        }

        if (!pending.isMedia && !nativeSatisfied(pending.kinds)) {
            if (answer?.allow == true && SitePermissionKind.NOTIFICATIONS in unknown) {
                decisions.withLock {
                    if (isPendingValid(pending)) {
                        store.put(pending.origin, pending.isPrivate, SitePermissionKind.NOTIFICATIONS, SitePermissionChoice.ALLOW)
                    }
                }
            }
            val runtimeOk = awaitNative(pending, nativePermissions(pending.kinds))
            if (!runtimeOk || !isPendingValid(pending) || !nativeSatisfied(pending.kinds)) {
                finish(pending, allowed = false)
                return
            }
        }

        if (!awaitHostFocus(pending)) {
            finish(pending, allowed = false)
            return
        }
        if (answer != null) {
            saveDecision(pending, unknown, SitePermissionChoice.ALLOW, answer.remember)
        }
        finish(pending, allowed = isPendingValid(pending) && nativeSatisfied(pending.kinds))
        refreshSite()
    }

    private suspend fun saveDecision(
        pending: Pending,
        kinds: Set<SitePermissionKind>,
        choice: SitePermissionChoice,
        remember: Boolean,
    ) {
        decisions.withLock {
            if (!isPendingValid(pending)) return@withLock
            for (kind in kinds) {
                if (remember || kind in setOf(SitePermissionKind.NOTIFICATIONS, SitePermissionKind.PROTECTED_MEDIA)) {
                    store.put(pending.origin, pending.isPrivate, kind, choice)
                } else {
                    temporary[
                        TemporaryKey(pending.tabId, pending.hardGeneration, pending.locationGeneration, pending.origin, pending.isPrivate, kind),
                    ] = choice
                }
            }
        }
    }

    private suspend fun awaitHostFocus(pending: Pending): Boolean {
        if (permissionHost?.get() == null) return isPendingValid(pending)
        return withTimeoutOrNull(5_000) {
            hostFocused.first { it }
            isPendingValid(pending)
        } == true
    }

    private suspend fun awaitNative(pending: Pending, permissions: List<String>): Boolean {
        while (nativeGate != null) {
            if (!isPendingValid(pending)) return false
            delay(50)
        }
        if (!isPendingValid(pending)) return false
        val missing = permissions.filterNot(::systemGranted)
        if (missing.isEmpty()) return nativeSatisfied(pending.kinds)
        val gate = NativeGate(++sequence, missing, pending.isPrivate, pending)
        nativeGate = gate
        mutableRuntime.value = RuntimePermissionRequest(gate.token, gate.permissions, gate.isPrivate)
        return try {
            gate.result.await()
            isPendingValid(pending) && nativeSatisfied(pending.kinds)
        } finally {
            if (nativeGate === gate) {
                gate.abandoned = true
                mutableRuntime.value = null
                if (!gate.launched) nativeGate = null
            }
        }
    }

    override fun answer(token: Long, allow: Boolean, remember: Boolean) {
        current?.takeIf { it.token == token && isPendingValid(it) }?.answer?.complete(Answer(allow, remember))
    }

    override fun dismiss(token: Long) {
        current?.takeIf { it.token == token }?.let {
            it.answer?.complete(null)
            finish(it, allowed = false)
        }
    }

    override fun markRuntimeRequestLaunched(token: Long, rationaleBefore: Set<String>): Boolean {
        val gate = nativeGate ?: return false
        if (gate.token != token || gate.launched || gate.abandoned) return false
        if (!isPendingValid(gate.pending)) {
            gate.abandoned = true
            nativeGate = null
            mutableRuntime.value = null
            gate.result.complete(emptyMap())
            return false
        }
        gate.rationaleAtLaunch = rationaleBefore.intersect(gate.permissions.toSet())
        gate.launched = true
        mutableRuntime.value = mutableRuntime.value?.copy(launched = true)
        return true
    }

    override fun onRuntimePermissionsResult(results: Map<String, Boolean>, rationaleAfter: Set<String>): Boolean? {
        val gate = nativeGate?.takeIf { it.launched } ?: return null
        val stillRelevant = !gate.abandoned && isPendingValid(gate.pending)
        val requiresSettings = if (stillRelevant) {
            val effective = results.filterKeys { it in gate.permissions }.mapValues { (permission, granted) ->
                granted || runtimePermissionSatisfied(permission)
            }
            runtimeRecovery.requiresSettings(effective, gate.rationaleAtLaunch, rationaleAfter)
        } else {
            null
        }
        nativeGate = null
        mutableRuntime.value = null
        gate.result.complete(results)
        refreshSite()
        return requiresSettings
    }

    override fun refreshSite() {
        runtimeRecovery.clearGranted(::runtimePermissionSatisfied)
        val selected = selectedTabId()?.let(lookupTab)
        val origin = selected?.pageUrl?.let(::siteOrigin)
        if (selected == null || origin == null) {
            siteOwner = null
            mutableSite.value = null
            return
        }
        siteOwner = selected
        val token = ++sequence
        siteToken = token
        val choices = store.read(origin, selected.isPrivate).toMutableMap()
        for (kind in SitePermissionKind.entries) {
            temporary[
                TemporaryKey(selected.tabId, selected.hardGeneration, selected.locationGeneration, origin, selected.isPrivate, kind),
            ]?.let { choices[kind] = it }
        }
        mutableSite.value = SitePermissionState(
            token = token,
            tabId = selected.tabId,
            origin = origin,
            isPrivate = selected.isPrivate,
            choices = choices,
            systemGranted = systemStates(),
            autoplay = autoplay.choice(origin, selected.isPrivate, contextKey(selected.isPrivate)),
            busy = false,
        )
    }

    override fun setForSite(token: Long, kind: SitePermissionKind, choice: SitePermissionChoice) {
        modifySite(token) { tab, origin ->
            store.put(origin, tab.isPrivate, kind, choice)
            temporary.keys.removeAll { it.origin == origin && it.isPrivate == tab.isPrivate && it.kind == kind }
        }
    }

    override fun resetSite(token: Long) {
        modifySite(token) { tab, origin ->
            store.reset(origin, tab.isPrivate)
            autoplay.set(origin, tab.isPrivate, contextKey(tab.isPrivate), SiteAutoplayChoice.BLOCK_AUDIBLE)
            temporary.keys.removeAll { it.origin == origin && it.isPrivate == tab.isPrivate }
        }
    }

    override fun setAutoplayForSite(token: Long, choice: SiteAutoplayChoice) {
        modifySite(token) { tab, origin ->
            autoplay.set(origin, tab.isPrivate, contextKey(tab.isPrivate), choice)
        }
    }

    private fun modifySite(token: Long, update: (TabMeta, String) -> Unit) {
        val currentState = mutableSite.value?.takeIf { it.token == token && !it.busy } ?: return
        val tab = siteOwner ?: return
        val origin = siteOrigin(tab.pageUrl) ?: return
        if (selectedTabId() != tab.tabId || origin != currentState.origin || clearing) return
        cancelMatching { it.origin == origin && it.isPrivate == tab.isPrivate }
        update(tab, origin)
        onSiteChanged(origin, tab.isPrivate)
        refreshSite()
    }

    fun notificationAllowed(origin: String, isPrivate: Boolean): Boolean {
        val normalized = siteOrigin(origin) ?: return false
        if (clearing || (isPrivate && !hasPrivateTabs())) return false
        return store.readOne(normalized, isPrivate, SitePermissionKind.NOTIFICATIONS) == SitePermissionChoice.ALLOW &&
            nativeSatisfied(setOf(SitePermissionKind.NOTIFICATIONS))
    }

    fun invalidateTab(tabId: String) {
        cancelMatching { it.tabId == tabId }
        temporary.keys.removeAll { it.tabId == tabId }
        if (siteOwner?.tabId == tabId) {
            siteOwner = null
            mutableSite.value = null
        }
        abandonedGateForTab(tabId)
    }

    fun onNavigation(tabId: String) {
        cancelMatching { it.tabId == tabId }
        temporary.keys.removeAll { it.tabId == tabId }
        abandonedGateForTab(tabId)
        refreshSite()
    }

    fun syncState() {
        if (!hasPrivateTabs()) {
            store.clearPrivate()
            autoplay.clearPrivate()
            temporary.keys.removeAll { it.isPrivate }
        }
        cancelMatching { !isPendingValid(it) }
        refreshSite()
    }

    fun listSavedDefaultPermissions(): Map<String, Map<SitePermissionKind, SitePermissionChoice>> =
        store.listSavedDefaultOrigins()

    fun listSavedDefaultAutoplay(): Map<String, SiteAutoplayChoice> = autoplay.savedDefaultEntries()

    fun resetSavedDefaultSite(origin: String) {
        val normalized = siteOrigin(origin) ?: return
        store.reset(normalized, isPrivate = false)
        autoplay.clearOrigin(normalized)
        onSiteChanged(normalized, false)
        refreshSite()
    }

    suspend fun clearAllSiteSettings(clearNative: suspend () -> Unit) {
        clearing = true
        policyGeneration++
        current?.let { finish(it, allowed = false) }
        while (queue.isNotEmpty()) {
            finish(queue.removeFirst(), allowed = false)
        }
        temporary.clear()
        nativeGate?.let { gate ->
            gate.abandoned = true
            mutableRuntime.value = null
            if (!gate.launched) {
                nativeGate = null
                gate.result.complete(emptyMap())
            }
        }
        try {
            decisions.withLock {
                store.clearAll()
                autoplay.clearAll()
                clearNative()
                onAllSiteSettingsCleared()
            }
        } finally {
            clearing = false
            refreshSite()
        }
    }

    private fun abandonedGateForTab(tabId: String) {
        if (current?.tabId == tabId) {
            nativeGate?.let { gate ->
                gate.abandoned = true
                mutableRuntime.value = null
                if (!gate.launched) {
                    nativeGate = null
                    gate.result.complete(emptyMap())
                }
            }
        }
    }

    private fun cancelMatching(predicate: (Pending) -> Boolean) {
        current?.takeIf(predicate)?.let { finish(it, allowed = false) }
        val remaining = ArrayDeque<Pending>()
        while (queue.isNotEmpty()) {
            val item = queue.removeFirst()
            if (predicate(item)) finish(item, allowed = false) else remaining.addLast(item)
        }
        queue.addAll(remaining)
    }

    private fun finish(pending: Pending, allowed: Boolean) {
        if (pending.complete) return
        pending.complete = true
        pending.answer?.complete(null)
        resolveNative(pending.nativeRequestId, pending.isMedia, allowed)
        if (mutablePrompt.value?.token == pending.token) mutablePrompt.value = null
    }

    private fun resolveNative(requestId: Long, isMedia: Boolean, allowed: Boolean) {
        if (isMedia) resolveMediaAccessNative(requestId, allowed) else resolveSitePermissionsNative(requestId, allowed)
    }

    private fun isPendingValid(pending: Pending): Boolean {
        if (pending.complete || clearing || pending.policyGeneration != policyGeneration) return false
        val tab = lookupTab(pending.tabId) ?: return false
        return selectedTabId() == pending.tabId &&
            tab.isPrivate == pending.isPrivate &&
            tab.hardGeneration == pending.hardGeneration &&
            tab.locationGeneration == pending.locationGeneration &&
            siteOrigin(tab.pageUrl) == pending.origin
    }

    private fun systemGranted(permission: String): Boolean =
        systemPermissionChecker?.invoke(permission)
            ?: (appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)

    private fun runtimePermissionSatisfied(permission: String): Boolean = when (permission) {
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION ->
            nativeSatisfied(setOf(SitePermissionKind.LOCATION))
        else -> systemGranted(permission)
    }

    private fun nativeSatisfied(kinds: Set<SitePermissionKind>): Boolean = kinds.all { kind ->
        when (kind) {
            SitePermissionKind.CAMERA -> systemGranted(Manifest.permission.CAMERA)
            SitePermissionKind.MICROPHONE -> systemGranted(Manifest.permission.RECORD_AUDIO)
            SitePermissionKind.LOCATION -> systemGranted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
                systemGranted(Manifest.permission.ACCESS_FINE_LOCATION)
            SitePermissionKind.NOTIFICATIONS -> notificationsEnabledChecker?.invoke()
                ?: ChromiumNotificationsController.enabled(appContext)
            SitePermissionKind.PROTECTED_MEDIA -> true
        }
    }

    private fun systemStates(): Map<SitePermissionKind, Boolean> =
        SitePermissionKind.entries.associateWith { nativeSatisfied(setOf(it)) }

    private fun nativePermissions(kinds: Set<SitePermissionKind>): List<String> = kinds.flatMap { kind ->
        when (kind) {
            SitePermissionKind.CAMERA -> listOf(Manifest.permission.CAMERA)
            SitePermissionKind.MICROPHONE -> listOf(Manifest.permission.RECORD_AUDIO)
            SitePermissionKind.LOCATION -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            SitePermissionKind.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
            SitePermissionKind.PROTECTED_MEDIA -> emptyList()
        }
    }.distinct()

    private fun contextKey(isPrivate: Boolean): String? = if (isPrivate) "chromium-private" else null

    companion object {
        private fun sharedAutoplay(context: Context): AutoplayPermissions {
            val prefs = context.applicationContext.getSharedPreferences("miru_chromium_autoplay", Context.MODE_PRIVATE)
            return AutoplayPermissions(
                { prefs.getString(it, null) },
                { key, value -> prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() },
                { prefs.edit().clear().apply() },
                { prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap() },
            )
        }

        fun kindFromCode(code: Int): SitePermissionKind? = when (code) {
            0 -> SitePermissionKind.CAMERA
            1 -> SitePermissionKind.MICROPHONE
            2 -> SitePermissionKind.LOCATION
            3 -> SitePermissionKind.NOTIFICATIONS
            4 -> SitePermissionKind.PROTECTED_MEDIA
            else -> null
        }

        fun decodeKindsMask(mask: Int): Set<SitePermissionKind> = buildSet {
            for (code in 0..4) {
                if ((mask and (1 shl code)) != 0) kindFromCode(code)?.let(::add)
            }
        }
    }
}
