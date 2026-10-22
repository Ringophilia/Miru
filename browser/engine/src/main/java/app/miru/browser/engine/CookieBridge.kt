// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.util.UUID
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

    init { scope.launch { store.stateFlow.collect { state ->
        val ids = state.tabs.map { it.id }.toSet()
        media.retain(ids)
        sessions.keys.retainAll(ids)
        frameMedia.entries.removeAll { it.value.first !in ids }
        frameOwners.keys.removeAll { it.first !in ids }
        contentPorts.entries.removeAll { it.value !in ids }
        pages.keys.retainAll(ids)
        state.tabs.forEach { tab ->
            val page = tab.content.url.substringBefore('#')
            if(pages.put(tab.id, page) != page) { frameMedia.entries.removeAll { it.value.first == tab.id }; frameOwners.keys.removeAll { it.first == tab.id }; media.clear(tab.id) }
        }
        attach()
    } } }

    fun start(engine: GeckoEngine) {
        engine.installBuiltInWebExtension("bridge@miru.app", "resource://android/assets/extensions/miru-bridge/", onSuccess = { value ->
            extension = value
            attach()
            value.registerBackgroundMessageHandler("miru", object : MessageHandler {
                override fun onPortConnected(port: Port) { this@CookieBridge.port = port; connected.complete(Unit) }
                override fun onPortDisconnected(port: Port) {
                    if (this@CookieBridge.port !== port) return
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
            if(value.isAllowedInPrivateBrowsing()) configured.complete(Unit)
            else engine.setAllowedInPrivateBrowsing(value, true, onSuccess = { extension = it; sessions.clear(); attach(); configured.complete(Unit) }, onError = { configured.complete(Unit) })
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
            current.registerContentMessageHandler(session, "miru_media", object : MessageHandler {
                override fun onPortConnected(port: Port) {
                    if(store.state.tabs.none { it.id == tab.id && it.engineState.engineSession === session }) return
                    contentPorts[port] = tab.id
                    sendMediaSettings(port)
                }
                override fun onPortDisconnected(port: Port) { contentPorts.remove(port); frameMedia.remove(port); frameOwners.entries.removeAll { it.value === port }; refresh(tab.id) }
                override fun onPortMessage(message: Any, port: Port) {
                    val data = message as? JSONObject ?: return
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
}
