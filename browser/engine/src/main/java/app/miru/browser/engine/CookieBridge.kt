// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.util.UUID
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.coroutines.resume
import app.miru.media.DetectedMedia
import app.miru.media.MediaIndex
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtension
import org.json.JSONObject

/** A built-in, native-only capability for reading the corresponding Gecko cookie jar. */
internal class CookieBridge(private val scope: CoroutineScope, private val store: BrowserStore) {
    val media = MediaIndex()
    private val sessions = mutableMapOf<String, EngineSession>()
    private val pages = mutableMapOf<String, String>()
    private val frameMedia = mutableMapOf<Port, Pair<String, List<DetectedMedia>>>()
    private val frameOwners = mutableMapOf<Pair<String, Int>, Port>()
    private val contentPorts = mutableMapOf<Port, String>()
    private var webHoldEnabled = true
    private var webHoldSpeed = 2f
    private val preparation = mutableMapOf<String, CompletableDeferred<JSONObject?>>()
    private val hintChannel = kotlinx.coroutines.channels.Channel<Pair<String, Boolean>>(kotlinx.coroutines.channels.Channel.CONFLATED)
    val hints = hintChannel.receiveAsFlow()
    private var extension: WebExtension? = null
    private var port: Port? = null
    private var connected = CompletableDeferred<Unit>()
    private var configured = CompletableDeferred<Unit>()
    private val pending = mutableMapOf<String, CompletableDeferred<String?>>()
    private class ImageRelay(val tabId: String, val session: EngineSession, val pageUrl: String,
        val documentUrl: String, val documentNonce: String, val relayNonce: String, val port: Port, val sequence: Long)
    private class ImageQuery(val relay: ImageRelay, val response: CompletableDeferred<ContextImagePolicy?>)
    private val imageRelays = linkedMapOf<String, ImageRelay>()
    private val imageQueries = mutableMapOf<String, ImageQuery>()
    private var imageRelaySequence = 0L

    init { scope.launch { store.stateFlow.collect { state ->
        val ids = state.tabs.map { it.id }.toSet()
        media.retain(ids)
        sessions.keys.retainAll(ids)
        frameMedia.entries.removeAll { it.value.first !in ids }
        frameOwners.keys.removeAll { it.first !in ids }
        contentPorts.entries.removeAll { it.value !in ids }
        imageRelays.entries.removeAll { (_, relay) -> state.tabs.none { tab ->
            tab.id == relay.tabId && tab.engineState.engineSession === relay.session &&
                tab.content.url.substringBefore('#') == relay.pageUrl.substringBefore('#')
        } }
        imageQueries.values.filter { query -> state.tabs.none { tab -> tab.id == query.relay.tabId &&
            tab.engineState.engineSession === query.relay.session && tab.content.url.substringBefore('#') == query.relay.pageUrl.substringBefore('#')
        } }.forEach { it.response.complete(null) }
        pages.keys.retainAll(ids)
        state.tabs.forEach { tab ->
            val page = tab.content.url.substringBefore('#')
            if(pages.put(tab.id, page) != page) { frameMedia.entries.removeAll { it.value.first == tab.id }; frameOwners.keys.removeAll { it.first == tab.id }; media.clear(tab.id) }
        }
        attach()
    } } }

    fun start(engine: GeckoEngine) {
        fun bindConfigured(value: WebExtension) {
            extension = value
            sessions.clear()
            value.registerBackgroundMessageHandler("miru", object : MessageHandler {
                override fun onPortConnected(port: Port) { this@CookieBridge.port = port; connected.complete(Unit) }
                override fun onPortDisconnected(port: Port) {
                    this@CookieBridge.port = null
                    connected = CompletableDeferred()
                    pending.values.forEach { it.complete(null) }; pending.clear()
                }
                override fun onPortMessage(message: Any, port: Port) {
                    val data = message as? JSONObject ?: return
                    if (data.optString("kind") != "cookies") return
                    pending.remove(data.optString("id"))?.complete(if(data.optBoolean("error")) null else data.optString("cookie"))
                }
            })
            attach()
            configured.complete(Unit)
        }
        engine.installBuiltInWebExtension("bridge@miru.app", "resource://android/assets/extensions/miru-bridge/", onSuccess = { value ->
            // AC returns a replacement WebExtension after private-permission changes.
            // Both background and content delegates must belong to that final instance.
            if(value.isAllowedInPrivateBrowsing()) bindConfigured(value)
            else engine.setAllowedInPrivateBrowsing(value, true, onSuccess = ::bindConfigured, onError = { bindConfigured(value) })
        })
    }

    /** Main-dispatcher delivery; configuration and port bookkeeping never contain persistent credentials. */
    fun configureWebMedia(holdEnabled: Boolean, holdSpeed: Float) {
        scope.launch(Dispatchers.Main.immediate) {
            webHoldEnabled = holdEnabled
            webHoldSpeed = holdSpeed.takeIf { it.isFinite() }?.coerceIn(0.5f, 3f) ?: 2f
            contentPorts.keys.toList().forEach(::sendMediaSettings)
        }
    }

    private fun sendMediaSettings(port: Port) {
        runCatching { port.postMessage(JSONObject().put("kind", "media-settings").put("holdEnabled", webHoldEnabled).put("holdSpeed", webHoldSpeed)) }
    }

    private fun attach() {
        val current = extension ?: return
        store.state.tabs.forEach { tab ->
            val session = tab.engineState.engineSession ?: return@forEach
            if(sessions[tab.id] === session) return@forEach
            sessions[tab.id] = session
            contentPorts.entries.removeAll { it.value == tab.id }
            imageRelays.entries.removeAll { it.value.tabId == tab.id }
            current.registerContentMessageHandler(session, "miru_media", object : MessageHandler {
                override fun onPortConnected(port: Port) {
                    if(store.state.tabs.none { it.id == tab.id && it.engineState.engineSession === session }) return
                    contentPorts[port] = tab.id
                    sendMediaSettings(port)
                }
                override fun onPortDisconnected(port: Port) { contentPorts.remove(port); frameMedia.remove(port); frameOwners.entries.removeAll { it.value === port }; refresh(tab.id) }
                override fun onPortMessage(message: Any, port: Port) {
                    val data = message as? JSONObject ?: return
                    if(data.optString("kind") in setOf("image-relay", "image-policy", "image-query-status")) {
                        imageMessage(tab.id, session, data, port)
                        return
                    }
                    if(data.optString("kind") == "prepared") { preparation.remove(data.optString("id"))?.complete(data); return }
                    if(data.optString("kind") != "media") return
                    val active = store.state.tabs.firstOrNull { it.id == tab.id && it.engineState.engineSession === session } ?: return
                    if(data.optString("pageUrl").substringBefore('#') != active.content.url.substringBefore('#')) return
                    if(contentPorts.put(port, tab.id) == null) sendMediaSettings(port)
                    val array = data.optJSONArray("items") ?: return
                    val items = (0 until minOf(array.length(), 64)).mapNotNull { i ->
                        val item = array.optJSONObject(i) ?: return@mapNotNull null
                        val url = item.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("blob:") } ?: return@mapNotNull null
                        val rawHeaders = item.optJSONObject("headers")
                        val headers = buildMap { rawHeaders?.keys()?.forEach { key ->
                            if(key.lowercase() in setOf("referer", "origin", "user-agent", "cookie", "authorization")) put(key, rawHeaders.optString(key))
                        } }
                        fun number(key: String): Double = item.optDouble(key, 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0
                        DetectedMedia(item.optString("id"), tab.id, url, active.content.url, item.optString("title").take(512), item.optString("kind"), item.optString("mimeType").takeIf(String::isNotBlank), headers,
                            number("duration"), number("position"), item.optInt("width").coerceIn(0, 32768), item.optInt("height").coerceIn(0, 32768), item.optBoolean("playing"), item.optBoolean("drm"), active.content.private)
                    }
                    frameOwners.put(tab.id to data.optInt("frameId"), port)?.takeIf { it !== port }?.let { frameMedia.remove(it); contentPorts.remove(it) }
                    frameMedia[port] = tab.id to items
                    refresh(tab.id)
                    if(data.optString("event") in setOf("play", "fullscreen")) hintChannel.trySend(tab.id to (data.optString("event") == "fullscreen"))
                }
            })
        }
    }

    private fun refresh(tabId: String) {
        val page = store.state.tabs.firstOrNull { it.id == tabId }?.content?.url ?: return
        media.replace(tabId, frameMedia.values.filter { it.first == tabId }.flatMap { it.second }.filter { it.pageUrl.substringBefore('#') == page.substringBefore('#') })
    }

    private fun imageMessage(tabId: String, session: EngineSession, data: JSONObject, port: Port) {
        val reply = data.optString("kind") in setOf("image-policy", "image-query-status")
        if(reply) android.util.Log.d("MiruImagePolicy", "reply-seen")
        val active = store.state.tabs.firstOrNull { it.id == tabId && it.engineState.engineSession === session } ?: return
        val senderUrl = runCatching { port.senderUrl() }.getOrNull() ?: return
        val documentNonce = data.optString("documentNonce")
        val relayNonce = data.optString("relayNonce")
        if(!IMAGE_NONCE.matches(documentNonce) || !IMAGE_NONCE.matches(relayNonce)) return
        if(data.optString("kind") == "image-relay") {
            val pageUrl = data.optString("pageUrl")
            val documentUrl = data.optString("documentUrl")
            if(pageUrl.substringBefore('#') != active.content.url.substringBefore('#') ||
                !sameDocumentOrigin(documentUrl, senderUrl) || documentUrl.length !in 1..65_536) return
            imageRelays["$tabId:$documentNonce"] = ImageRelay(tabId, session, pageUrl, documentUrl, documentNonce, relayNonce, port, ++imageRelaySequence)
            while(imageRelays.size > 128) imageRelays.remove(imageRelays.keys.first())
            return
        }
        val query = imageQueries[data.optString("id")] ?: run {
            if(reply) android.util.Log.d("MiruImagePolicy", "reply-not-pending")
            return
        }
        val relay = query.relay
        if(relay.tabId != tabId || relay.session !== session || relay.documentNonce != documentNonce || relay.relayNonce != relayNonce ||
            !sameDocumentOrigin(relay.documentUrl, senderUrl) ||
            relay.pageUrl.substringBefore('#') != active.content.url.substringBefore('#') ||
            imageRelays["$tabId:$documentNonce"]?.relayNonce != relayNonce) {
            android.util.Log.d("MiruImagePolicy", "reply-binding-mismatch")
            return
        }
        if(data.optString("kind") == "image-query-status") {
            android.util.Log.d("MiruImagePolicy", if(data.optString("status") == "received") "content-query-received" else "content-query-rejected")
            return
        }
        val result = data.optJSONObject("result")
        val stage = result?.optString("stage")
        if(stage in IMAGE_STAGES) android.util.Log.d("MiruImagePolicy", stage!!)
        val policy = if(result?.optBoolean("found") == true && result.has("policy") && result.has("documentUrl"))
            ContextImagePolicy.fromDocument(result.optString("documentUrl"), result.optString("policy")) else null
        query.response.complete(policy)
    }

    /**
     * The native session owns the relay; the extension background owns its trusted
     * tab/frame routing and press nonce. No URL or private state leaves process memory.
     * A missing live target/HTTP policy baseline/response is an unavailable capability,
     * not permission to invent a document's default policy.
     */
    suspend fun queryImagePolicy(tabId: String, pageUrl: String, url: String, pressedAtMillis: Long, gestureStartedAtMillis: Long): ContextImagePolicy? = withContext(Dispatchers.Main.immediate) {
        val owner = store.state.tabs.firstOrNull { it.id == tabId && it.content.url == pageUrl } ?: return@withContext null
        val session = owner.engineState.engineSession ?: return@withContext null
        if(store.state.selectedTabId != tabId || gestureStartedAtMillis < 0 || pressedAtMillis < gestureStartedAtMillis) return@withContext null
        android.util.Log.d("MiruImagePolicy", "query-start")
        val attempted = mutableSetOf<String>()
        val outstanding = linkedMapOf<String, ImageQuery>()
        var receivedReply = false
        var budgetExpired = false
        val result = try { withTimeoutOrNull(3_500) {
            while(true) {
                // A slow live port remains eligible for the entire capability budget.
                // Retiring it after 700ms discarded otherwise valid late responses on
                // busy/private documents. New connections can be queried concurrently.
                val completed = outstanding.values.firstOrNull { it.response.isCompleted }
                if(completed != null) {
                    receivedReply = true
                    return@withTimeoutOrNull completed.response.await()
                }
                val relay = imageRelays.values.filter { it.tabId == tabId && it.session === session && it.relayNonce !in attempted &&
                    it.pageUrl.substringBefore('#') == pageUrl.substringBefore('#') }.maxByOrNull { it.sequence }
                if(relay == null || outstanding.size >= 8) { kotlinx.coroutines.delay(50); continue }
                android.util.Log.d("MiruImagePolicy", "relay-selected")
                attempted.add(relay.relayNonce)
                val id = UUID.randomUUID().toString()
                val response = CompletableDeferred<ContextImagePolicy?>()
                val query = ImageQuery(relay, response)
                imageQueries[id] = query
                outstanding[id] = query
                try {
                    relay.port.postMessage(JSONObject().put("kind", "query-image-policy").put("id", id)
                        .put("documentNonce", relay.documentNonce).put("relayNonce", relay.relayNonce)
                        .put("url", url).put("pressedAtMillis", pressedAtMillis).put("gestureStartedAtMillis", gestureStartedAtMillis))
                } catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch(_: Exception) {
                    imageQueries.remove(id)
                    outstanding.remove(id)
                    if(imageRelays["$tabId:${relay.documentNonce}"]?.relayNonce == relay.relayNonce) imageRelays.remove("$tabId:${relay.documentNonce}")
                }
                kotlinx.coroutines.delay(50)
            }
            @Suppress("UNREACHABLE_CODE") null
        }.also { budgetExpired = !receivedReply } } finally {
            outstanding.forEach { (id, query) ->
                imageQueries.remove(id)
                // Only an unanswered port at the total deadline is stale. A genuine
                // unavailable-policy reply must not be replaced by an invented default.
                if(budgetExpired && !query.response.isCompleted) {
                    val relay = query.relay
                    if(imageRelays["$tabId:${relay.documentNonce}"]?.relayNonce == relay.relayNonce) imageRelays.remove("$tabId:${relay.documentNonce}")
                }
            }
        }
        val current = store.state.tabs.firstOrNull { it.id == tabId }
        android.util.Log.d("MiruImagePolicy", if(result == null) "query-unavailable" else "query-live")
        result.takeIf { current != null && current.engineState.engineSession === session && current.content.url == pageUrl && store.state.selectedTabId == tabId }
    }

    suspend fun prepare(item: DetectedMedia): JSONObject? = withContext(Dispatchers.Main.immediate) {
        val target = frameMedia.entries.filter { it.value.first == item.tabId && it.value.second.any { candidate -> candidate.id == item.id && candidate.pageUrl == item.pageUrl } }
            .maxWithOrNull(compareBy<Map.Entry<Port, Pair<String, List<DetectedMedia>>>> { entry -> entry.value.second.first { it.id == item.id }.playing }
                .thenBy { entry -> entry.value.second.first { it.id == item.id }.duration })?.key ?: return@withContext null
        withTimeoutOrNull(2_000) {
            val id = UUID.randomUUID().toString()
            val result = CompletableDeferred<JSONObject?>()
            preparation[id] = result
            try { target.postMessage(JSONObject().put("kind", "prepare").put("mediaId", item.id).put("id", id)); result.await() }
            finally { preparation.remove(id) }
        }
    }

    fun restore(tabId: String) { frameMedia.filterValues { it.first == tabId }.keys.forEach { it.postMessage(JSONObject().put("kind", "restore")) } }

    suspend fun cookies(url: String, isPrivate: Boolean): String? = withContext(Dispatchers.Main.immediate) {
        withTimeoutOrNull(3_000) {
            configured.await()
            connected.await()
            val id = UUID.randomUUID().toString()
            val response = CompletableDeferred<String?>()
            pending[id] = response
            try {
                port?.postMessage(JSONObject().put("kind", "cookies").put("id", id).put("url", url).put("private", isPrivate))
                response.await()
            } finally { pending.remove(id) }
        }
    }

    companion object {
        private val IMAGE_NONCE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val IMAGE_STAGES = setOf("press-identity", "target-mismatch", "meta-ambiguous", "baseline-unavailable", "target-invalid", "policy-live")
        private fun sameDocumentOrigin(left: String, right: String): Boolean = runCatching {
            val a = URI(left); val b = URI(right)
            fun port(uri: URI) = if(uri.port != -1) uri.port else if(uri.scheme.equals("https", true)) 443 else 80
            left.substringBefore('#') == right.substringBefore('#') ||
                (a.scheme?.lowercase() in setOf("http", "https") && a.scheme.equals(b.scheme, true) && a.host != null && a.host.equals(b.host, true) && port(a) == port(b))
        }.getOrDefault(false)
    }
}
