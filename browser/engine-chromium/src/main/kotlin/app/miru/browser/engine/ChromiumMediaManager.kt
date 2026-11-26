// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import app.miru.chromium.engine.MiruChromiumRuntime
import app.miru.chromium.engine.MiruChromiumTab
import app.miru.media.DetectedMedia
import app.miru.media.MediaIndex
import app.miru.media.MediaSnifferClassifier
import app.miru.media.MediaSnifferCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Coordinates C++ network-layer media detections, isolated-world DOM metadata/handoff,
 * web long-press speed control, and profile-isolated `CookieManager` header injection.
 */
internal class ChromiumMediaManager(
    private val cookieFetcher: (isPrivate: Boolean, url: String, onResult: (String) -> Unit) -> Unit =
        { priv, url, cb -> MiruChromiumRuntime.getCookiesForUrl(priv, url, cb) },
    private val userAgentProvider: () -> String = MiruChromiumRuntime::getUserAgent,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    internal data class DomMediaInfo(
        val url: String,
        val key: String?,
        val kind: String,
        val duration: Double,
        val position: Double,
        val width: Int,
        val height: Int,
        val playing: Boolean,
        val drm: Boolean,
    )

    private val index = MediaIndex()
    private val coordinator = MediaSnifferCoordinator(index)
    private val domByTab = ConcurrentHashMap<String, List<DomMediaInfo>>()
    private val hintChannel = Channel<Pair<String, Boolean>>(Channel.CONFLATED)

    val state: StateFlow<Map<String, List<DetectedMedia>>> = index.state
    val hints: Flow<Pair<String, Boolean>> = hintChannel.receiveAsFlow()

    fun onNavigation(tabId: String) {
        domByTab.remove(tabId)
        coordinator.onNavigation(tabId)
    }

    fun retainTabs(activeTabIds: Set<String>) {
        domByTab.keys.retainAll(activeTabIds)
        coordinator.retainTabs(activeTabIds)
    }

    fun onNetworkMedia(
        tabId: String,
        pageUrl: String,
        title: String,
        isPrivate: Boolean,
        url: String,
        kind: String,
        mimeType: String,
        contentLength: Long,
        headersJson: String,
    ) {
        if (kind.isEmpty() &&
            MediaSnifferClassifier.classify(url, mimeType.ifEmpty { null }, contentLength.takeIf { it >= 0 }) == null
        ) {
            return
        }
        val headers = parseHeadersJson(headersJson)
        val entry = coordinator.onNetworkMedia(
            tabId = tabId,
            pageUrl = pageUrl,
            title = title,
            isPrivate = isPrivate,
            mediaUrl = url,
            contentType = mimeType.ifEmpty { null },
            contentLength = contentLength.takeIf { it >= 0 },
            headers = headers,
        )
        if (entry != null) mergeDomIntoTab(tabId, pageUrl, title, isPrivate)
    }

    fun onDomMediaEvent(
        tabId: String,
        tabUrl: String,
        tabTitle: String,
        isPrivate: Boolean,
        eventJson: JSONObject,
    ) {
        val isTop = eventJson.optBoolean("isTop", true)
        val reportedPage = eventJson.optString("pageUrl")
        if (isTop && reportedPage.isNotBlank() && tabUrl.isNotBlank() &&
            reportedPage.substringBefore('#') != tabUrl.substringBefore('#')
        ) {
            return
        }
        val effectiveTitle = eventJson.optString("title").ifBlank { tabTitle }
        val array = eventJson.optJSONArray("items") ?: JSONArray()
        val domList = buildList {
            for (i in 0 until minOf(array.length(), 32)) {
                val obj = array.optJSONObject(i) ?: continue
                val rawUrl = obj.optString("url")
                if (rawUrl.isBlank()) continue
                add(
                    DomMediaInfo(
                        url = rawUrl,
                        key = MediaSnifferClassifier.dedupKey(rawUrl),
                        kind = obj.optString("kind", "VIDEO"),
                        duration = obj.optDouble("duration", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                        position = obj.optDouble("position", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                        width = obj.optInt("width", 0).coerceIn(0, 32768),
                        height = obj.optInt("height", 0).coerceIn(0, 32768),
                        playing = obj.optBoolean("playing", false),
                        drm = obj.optBoolean("drm", false),
                    ),
                )
            }
        }
        domByTab[tabId] = domList
        domList.forEach { dom ->
            if (dom.url.startsWith("http://") || dom.url.startsWith("https://")) {
                coordinator.onNetworkMedia(
                    tabId = tabId,
                    pageUrl = tabUrl,
                    title = effectiveTitle,
                    isPrivate = isPrivate,
                    mediaUrl = dom.url,
                    headers = emptyMap(),
                )
            }
        }
        mergeDomIntoTab(tabId, tabUrl, effectiveTitle, isPrivate)
        val eventName = eventJson.optString("event")
        if ((eventName == "play" || eventName == "fullscreen") &&
            index.state.value[tabId].orEmpty().any { it.playableVideo }
        ) {
            hintChannel.trySend(tabId to (eventName == "fullscreen"))
        }
    }

    private fun mergeDomIntoTab(tabId: String, pageUrl: String, title: String, isPrivate: Boolean) {
        val current = index.state.value[tabId].orEmpty()
        val domList = domByTab[tabId].orEmpty()
        if (current.isEmpty() || domList.isEmpty()) return
        val updated = current.map { item ->
            val dom = domList.firstOrNull { it.key != null && it.key == item.id }
                ?: domList.singleOrNull()?.takeIf { it.url.startsWith("blob:") }
            if (dom == null) {
                item
            } else {
                DetectedMedia(
                    id = item.id,
                    tabId = item.tabId,
                    url = item.url,
                    pageUrl = pageUrl.ifBlank { item.pageUrl },
                    title = title.ifBlank { item.title },
                    kind = item.kind,
                    mimeType = item.mimeType,
                    headers = item.headers,
                    duration = if (dom.duration > 0.0) dom.duration else item.duration,
                    position = if (dom.position > 0.0) dom.position else item.position,
                    width = if (dom.width > 0) dom.width else item.width,
                    height = if (dom.height > 0) dom.height else item.height,
                    playing = dom.playing,
                    drm = dom.drm || item.drm,
                    isPrivate = isPrivate,
                )
            }
        }
        index.replace(tabId, updated)
    }

    suspend fun prepareMedia(
        tabId: String,
        nativeTab: MiruChromiumTab?,
        currentPageUrl: String,
        mediaId: String,
    ): PreparedMedia? {
        val item = index.find(tabId, mediaId)?.takeIf { it.playable } ?: return null
        if (currentPageUrl.isNotBlank() && item.pageUrl.isNotBlank() &&
            item.pageUrl.substringBefore('#') != currentPageUrl.substringBefore('#')
        ) {
            return null
        }
        val prepResult = if (nativeTab != null) evaluatePrepareAllFrames(nativeTab, item.url, item.id) else null
        if (prepResult?.optBoolean("drm") == true) return null
        val positionSec = prepResult?.optDouble("position", item.position)
            ?.takeIf { it.isFinite() && it >= 0.0 } ?: item.position
        val headers = freshMediaHeaders(item)
        return PreparedMedia(media = item, headers = headers, positionMs = (positionSec * 1000.0).toLong())
    }

    suspend fun refreshMediaHeaders(tabId: String, mediaId: String): Map<String, String>? {
        val item = index.find(tabId, mediaId) ?: return null
        return freshMediaHeaders(item)
    }

    fun restoreWebMedia(nativeTab: MiruChromiumTab?) {
        nativeTab?.evaluateIsolatedJs(ChromiumPageScripts.restoreMediaScript(), true, null)
    }

    fun configureWebMedia(tabs: Iterable<MiruChromiumTab>, holdEnabled: Boolean, holdSpeed: Float) {
        val script = ChromiumPageScripts.webHoldConfigScript(holdEnabled, holdSpeed)
        tabs.forEach { it.evaluateIsolatedJs(script, true, null) }
    }

    suspend fun headersForMediaUrl(url: String, isPrivate: Boolean): Map<String, String> =
        buildFreshHeaders(url = url, baseHeaders = emptyMap(), fallbackPageUrl = "", isPrivate = isPrivate)

    suspend fun downloadHeaders(url: String, sourcePage: String?): Map<String, String>? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return buildFreshHeaders(url = url, baseHeaders = emptyMap(), fallbackPageUrl = sourcePage.orEmpty(), isPrivate = false)
    }

    private suspend fun freshMediaHeaders(item: DetectedMedia): Map<String, String> =
        buildFreshHeaders(url = item.url, baseHeaders = item.headers, fallbackPageUrl = item.pageUrl, isPrivate = item.isPrivate)

    private suspend fun buildFreshHeaders(
        url: String,
        baseHeaders: Map<String, String>,
        fallbackPageUrl: String,
        isPrivate: Boolean,
    ): Map<String, String> {
        val headers = MediaSnifferClassifier.sanitizeCaptureHeaders(baseHeaders).toMutableMap()
        val cookie = awaitCookies(isPrivate, url)
        if (!cookie.isNullOrBlank()) {
            headers.keys.filter { it.equals("cookie", ignoreCase = true) }.toList().forEach(headers::remove)
            headers["Cookie"] = cookie
        }
        if (headers.keys.none { it.equals("user-agent", ignoreCase = true) }) {
            val ua = userAgentProvider()
            if (ua.isNotBlank()) headers["User-Agent"] = ua
        }
        if (headers.keys.none { it.equals("referer", ignoreCase = true) } &&
            (fallbackPageUrl.startsWith("http://") || fallbackPageUrl.startsWith("https://"))
        ) {
            headers["Referer"] = fallbackPageUrl
        }
        return headers
    }

    private suspend fun evaluatePrepareAllFrames(
        nativeTab: MiruChromiumTab,
        mediaUrl: String,
        mediaKey: String,
    ): JSONObject? = withTimeoutOrNull(2_000) {
        val raw = withContext(uiDispatcher) {
            suspendCancellableCoroutine<String> { cont ->
                nativeTab.evaluateIsolatedJs(ChromiumPageScripts.prepareMediaScript(mediaUrl, mediaKey), true) { json ->
                    if (cont.isActive) cont.resume(json)
                }
            }
        }
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return@withTimeoutOrNull null
        var best: JSONObject? = null
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.optBoolean("drm")) return@withTimeoutOrNull obj
            if (obj.optBoolean("found")) {
                if (obj.optBoolean("wasPlaying") || best == null) best = obj
            }
        }
        best
    }

    private suspend fun awaitCookies(isPrivate: Boolean, url: String): String? = withTimeoutOrNull(2_500) {
        withContext(uiDispatcher) {
            suspendCancellableCoroutine { cont ->
                cookieFetcher(isPrivate, url) { cookie ->
                    if (cont.isActive) cont.resume(cookie)
                }
            }
        }
    }

    internal companion object {
        fun parseHeadersJson(headersJson: String): Map<String, String> {
            if (headersJson.isBlank() || headersJson == "{}") return emptyMap()
            return runCatching {
                val obj = JSONObject(headersJson)
                val raw = buildMap {
                    obj.keys().forEach { key ->
                        val v = obj.optString(key)
                        if (v.isNotEmpty()) put(key, v)
                    }
                }
                MediaSnifferClassifier.sanitizeCaptureHeaders(raw)
            }.getOrDefault(emptyMap())
        }
    }
}
