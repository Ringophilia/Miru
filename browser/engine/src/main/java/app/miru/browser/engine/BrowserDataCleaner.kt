// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import app.miru.data.BrowsingDataClearRequest
import app.miru.data.BrowsingDataClearResult
import app.miru.data.BrowsingDataType
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtension
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Native-only deletion capability. No page identifiers, headers or raw errors enter its protocol. */
class BrowserDataCleaner(
    private val requireEngine: suspend () -> GeckoEngine,
    private val resetFormData: suspend () -> Unit,
    private val clearSitePermissions: suspend (suspend () -> Unit) -> Unit,
) {
    private val mutex = Mutex()
    private var extension: WebExtension? = null
    private var port: Port? = null
    private var connected = CompletableDeferred<Port>()
    private val pending = mutableMapOf<String, CompletableDeferred<Boolean>>()

    suspend fun clear(request: BrowsingDataClearRequest): BrowsingDataClearResult = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            val selected = request.types.intersect(ENGINE_TYPES)
            if(!request.isValid) return@withLock BrowsingDataClearResult(failed = selected)
            val completed = mutableSetOf<BrowsingDataType>()
            val failed = mutableSetOf<BrowsingDataType>()
            suspend fun run(types: Set<BrowsingDataType>, block: suspend () -> Unit) {
                if(types.isEmpty()) return
                try { withTimeout(45_000) { block() }; completed.addAll(types) }
                catch(timeout: kotlinx.coroutines.TimeoutCancellationException) { failed.addAll(types) }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { failed.addAll(types) }
            }
            if(BrowsingDataType.COOKIES in selected) run(setOf(BrowsingDataType.COOKIES)) {
                val engine = requireEngine()
                val connection = connection(engine)
                val id = UUID.randomUUID().toString()
                val response = CompletableDeferred<Boolean>()
                pending[id] = response
                try {
                    connection.postMessage(JSONObject().put("kind", "clear-cookies").put("id", id).put("since", request.since))
                    check(response.await()) { "COOKIES_CLEAR_FAILED" }
                } finally { pending.remove(id) }
            }
            val flags = selected.mapNotNull { type -> when(type) {
                BrowsingDataType.SITE_DATA -> type to Engine.BrowsingData.DOM_STORAGES
                BrowsingDataType.WEB_CACHE -> type to Engine.BrowsingData.ALL_CACHES
                BrowsingDataType.AUTH_SESSIONS -> type to Engine.BrowsingData.AUTH_SESSIONS
                else -> null
            } }.toMap()
            run(flags.keys) {
                val engine = requireEngine()
                suspendCancellableCoroutine<Unit> { continuation ->
                    engine.clearData(Engine.BrowsingData.select(*flags.values.toIntArray()), onSuccess = {
                        if(continuation.isActive) continuation.resume(Unit)
                    }, onError = {
                        if(continuation.isActive) continuation.resumeWithException(IllegalStateException("ENGINE_CLEAR_FAILED"))
                    })
                }
            }
            if(BrowsingDataType.SITE_SETTINGS in selected) run(setOf(BrowsingDataType.SITE_SETTINGS)) {
                val engine = requireEngine()
                clearSitePermissions {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        engine.clearData(Engine.BrowsingData.select(Engine.BrowsingData.ALL_SITE_SETTINGS), onSuccess = {
                            if(continuation.isActive) continuation.resume(Unit)
                        }, onError = {
                            if(continuation.isActive) continuation.resumeWithException(IllegalStateException("SITE_SETTINGS_CLEAR_FAILED"))
                        })
                    }
                }
            }
            if(BrowsingDataType.FORM_DATA in selected) run(setOf(BrowsingDataType.FORM_DATA), resetFormData)
            BrowsingDataClearResult(completed, failed)
        }
    }

    private suspend fun connection(engine: GeckoEngine): Port {
        if(extension == null) {
            val installed = suspendCancellableCoroutine<WebExtension> { continuation ->
                val operation = engine.installBuiltInWebExtension("data-cleaner@miru.app", "resource://android/assets/extensions/miru-data-cleaner/",
                    onSuccess = { if(continuation.isActive) continuation.resume(it) },
                    onError = { if(continuation.isActive) continuation.resumeWithException(IllegalStateException("CLEAR_CAPABILITY_UNAVAILABLE")) })
                continuation.invokeOnCancellation { operation.cancel() }
            }
            // Private permission updates return a replacement native extension. Register on
            // that final instance so an upgrade/reload cannot strand the pending native port.
            val configured = if(!installed.isAllowedInPrivateBrowsing()) suspendCancellableCoroutine<WebExtension> { continuation ->
                engine.setAllowedInPrivateBrowsing(installed, true, onSuccess = {
                    if(continuation.isActive) continuation.resume(it)
                }, onError = {
                    if(continuation.isActive) continuation.resumeWithException(IllegalStateException("PRIVATE_CLEAR_UNAVAILABLE"))
                })
            } else installed
            configured.registerBackgroundMessageHandler("miru_data_cleaner", object : MessageHandler {
                override fun onPortConnected(port: Port) {
                    this@BrowserDataCleaner.port = port
                    connected.complete(port)
                }
                override fun onPortDisconnected(port: Port) {
                    // AC 158 constructs a new GeckoPort wrapper for each callback;
                    // its delegate already matches the currently connected native port.
                    this@BrowserDataCleaner.port = null
                    connected = CompletableDeferred()
                    pending.values.forEach { it.complete(false) }; pending.clear()
                }
                override fun onPortMessage(message: Any, port: Port) {
                    // Private native channel + an outstanding random request ID identify
                    // the reply. Wrapper reference identity is not native port identity.
                    val data = message as? JSONObject ?: return
                    if(data.optString("kind") == "clear-result") pending.remove(data.optString("id"))?.complete(data.optBoolean("ok"))
                }
            })
            extension = configured
        }
        return port ?: connected.await()
    }

    companion object {
        val ENGINE_TYPES = setOf(BrowsingDataType.COOKIES, BrowsingDataType.SITE_DATA, BrowsingDataType.WEB_CACHE,
            BrowsingDataType.SITE_SETTINGS, BrowsingDataType.AUTH_SESSIONS, BrowsingDataType.FORM_DATA)
    }
}
