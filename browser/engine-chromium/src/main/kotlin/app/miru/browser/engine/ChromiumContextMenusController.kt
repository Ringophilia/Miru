package app.miru.browser.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Application-lifetime long-press link / image / video context menu controller for Chromium tabs.
 * Tracks ownership/navigation generations and ensures `NotifyContextMenuClosed()` is dispatched
 * whenever a native context menu is replaced or dismissed so subsequent long-presses never deadlock.
 */
internal class ChromiumContextMenusController(
    private val lookupTab: (String) -> TabMeta?,
    private val selectedTabId: () -> String?,
    private val evaluateScript: (tabId: String, script: String, onResult: (String) -> Unit) -> Boolean,
    private val lookupCapturedHeaders: suspend (tabId: String, pageUrl: String, url: String) -> Map<String, String>?,
    private val fallbackHeaders: suspend (url: String, isPrivate: Boolean) -> Map<String, String>,
    private val notifyNativeClosed: (tabId: String) -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : BrowserContextMenusController {

    internal data class TabMeta(
        val tabId: String,
        val isPrivate: Boolean,
        val hardGeneration: Long,
        val locationGeneration: Long,
    )

    private class PendingMenu(
        val menu: BrowserContextMenu,
        val frameUrl: String?,
        val headerReferrerPolicy: String,
        val hardGeneration: Long,
        var closedNotified: Boolean = false,
    )

    private val mutableState = MutableStateFlow<BrowserContextMenu?>(null)
    override val state: StateFlow<BrowserContextMenu?> = mutableState.asStateFlow()

    private var sequence = 0L
    private var lastTouchGestureAtMillis = 0L
    private var pending: PendingMenu? = null

    fun onContextMenu(
        tabId: String,
        mediaType: Int,
        linkUrl: String,
        linkText: String,
        srcUrl: String,
        titleText: String,
        pageUrl: String,
        frameUrl: String,
        referrerPolicy: String,
    ) {
        val tab = lookupTab(tabId)
        if (tab == null || tab.tabId != selectedTabId()) {
            notifyNativeClosed(tabId)
            return
        }
        val cleanLink = linkUrl.trim().takeIf { it.isNotEmpty() }
        val cleanSrc = srcUrl.trim().takeIf { it.isNotEmpty() }
        val kind = when {
            mediaType == 1 && cleanSrc != null -> BrowserContextMenuKind.IMAGE
            mediaType == 2 && cleanSrc != null -> BrowserContextMenuKind.VIDEO
            cleanLink != null -> BrowserContextMenuKind.LINK
            else -> {
                notifyNativeClosed(tabId)
                return
            }
        }
        val cleanPage = pageUrl.trim().ifEmpty { frameUrl.trim() }
        if (cleanPage.isEmpty()) {
            notifyNativeClosed(tabId)
            return
        }
        val cleanFrame = frameUrl.trim().takeIf { it.isNotEmpty() && it != cleanPage }
        val menu = BrowserContextMenu(
            token = ++sequence,
            tabId = tab.tabId,
            pageUrl = cleanPage,
            isPrivate = tab.isPrivate,
            kind = kind,
            linkUrl = cleanLink,
            linkText = linkText.trim().takeIf { it.isNotEmpty() },
            imageUrl = cleanSrc.takeIf { kind == BrowserContextMenuKind.IMAGE },
            videoUrl = cleanSrc.takeIf { kind == BrowserContextMenuKind.VIDEO },
            title = titleText.trim().takeIf { it.isNotEmpty() },
            pageGeneration = tab.locationGeneration,
            gestureStartedAtMillis = lastTouchGestureAtMillis,
        )
        clearCurrent(notifyNative = true)
        pending = PendingMenu(
            menu = menu,
            frameUrl = cleanFrame,
            headerReferrerPolicy = referrerPolicy.trim().lowercase(),
            hardGeneration = tab.hardGeneration,
        )
        mutableState.value = menu
    }

    fun syncState() {
        val current = pending ?: return
        if (!isOwnerValid(current)) {
            clearCurrent(notifyNative = true)
        }
    }

    fun invalidateTab(tabId: String) {
        if (pending?.menu?.tabId == tabId) {
            clearCurrent(notifyNative = true)
        }
    }

    override fun dismiss(token: Long?) {
        val current = pending ?: return
        if (token == null || current.menu.token == token) {
            clearCurrent(notifyNative = true)
        }
    }

    override fun beginTouchGesture() {
        lastTouchGestureAtMillis = System.currentTimeMillis()
        clearCurrent(notifyNative = true)
    }

    override fun isCurrent(request: BrowserContextMenu): Boolean {
        val current = pending ?: return false
        return current.menu.token == request.token && isOwnerValid(current)
    }

    override fun isOwnerCurrent(request: BrowserContextMenu): Boolean {
        val current = pending ?: return false
        return current.menu.token == request.token && isOwnerValid(current)
    }

    override fun close() {
        clearCurrent(notifyNative = true)
    }

    suspend fun contextImagePolicy(request: BrowserContextMenu): ContextImagePolicy? = withContext(mainDispatcher) {
        if (!isOwnerCurrent(request)) return@withContext null
        val imageUrl = request.imageUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val entry = pending ?: return@withContext null

        val script = buildString {
            append("(function(){try{")
            append("var target=").append(JSONObject.quote(imageUrl)).append(";")
            append("var imgs=document.getElementsByTagName('img');")
            append("var matched=false;var imgPolicy='';")
            append("for(var i=0;i<imgs.length;i++){")
            append("var el=imgs[i];")
            append("if(el.currentSrc===target||el.src===target){")
            append("matched=true;imgPolicy=(el.referrerPolicy||'').toLowerCase();break;")
            append("}}")
            append("if(!matched)return null;")
            append("return {imgPolicy:imgPolicy,")
            append("docPolicy:(document.referrerPolicy||'').toLowerCase(),docUrl:document.URL||''};")
            append("}catch(e){return null;}})();")
        }

        val rawJson = suspendCancellableCoroutine<String> { cont ->
            val dispatched = evaluateScript(request.tabId, script) { result ->
                if (cont.isActive) cont.resume(result)
            }
            if (!dispatched && cont.isActive) cont.resume("")
        }
        if (!isOwnerCurrent(request) || rawJson.isBlank()) return@withContext null

        val expectedDocUrl = (entry.frameUrl ?: request.pageUrl).trim()
        val parsed = extractFramePolicyObject(rawJson, expectedDocUrl) ?: return@withContext null
        val docUrl = parsed.optString("docUrl", "").trim()
        if (expectedDocUrl.isNotEmpty() && docUrl.isNotEmpty() && docUrl != expectedDocUrl) {
            return@withContext null
        }

        val imgPolicy = parsed.optString("imgPolicy", "").trim().lowercase()
        val docPolicy = parsed.optString("docPolicy", "").trim().lowercase()
        val effectivePolicy = imgPolicy.ifEmpty { docPolicy }.ifEmpty { entry.headerReferrerPolicy }
        val finalDocUrl = docUrl.ifEmpty { expectedDocUrl }
        ContextImagePolicy.fromDocument(finalDocUrl, effectivePolicy)
    }

    private fun extractFramePolicyObject(rawJson: String, expectedDocUrl: String): JSONObject? {
        val trimmed = rawJson.trim()
        if (trimmed.startsWith("{")) {
            return runCatching { JSONObject(trimmed) }.getOrNull()
        }
        val array = runCatching { org.json.JSONArray(trimmed) }.getOrNull() ?: return null
        var fallback: JSONObject? = null
        for (i in 0 until array.length()) {
            val item = array.opt(i) ?: continue
            val obj = when (item) {
                is JSONObject -> item
                is String -> item.trim().takeIf { it.startsWith("{") }?.let { s ->
                    runCatching { JSONObject(s) }.getOrNull()
                }
                else -> null
            } ?: continue
            val docUrl = obj.optString("docUrl", "").trim()
            if (expectedDocUrl.isNotEmpty() && docUrl == expectedDocUrl) return obj
            if (fallback == null) fallback = obj
        }
        return fallback
    }

    suspend fun contextHeaders(
        request: BrowserContextMenu,
        url: String,
        imagePolicy: ContextImagePolicy?,
    ): Map<String, String>? = withContext(mainDispatcher) {
        if (!isOwnerCurrent(request)) return@withContext null
        val actualImagePolicy = if (request.imageUrl == url) {
            imagePolicy ?: contextImagePolicy(request) ?: return@withContext null
        } else {
            null
        }
        val headers = lookupCapturedHeaders(request.tabId, request.pageUrl, url)
            ?: fallbackHeaders(url, request.isPrivate)
        if (!isOwnerCurrent(request)) return@withContext null
        val capturedReferrer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
        val safeReferrer = if (actualImagePolicy != null) {
            actualImagePolicy.referrerFor(url)
        } else {
            contextReferrer(request.pageUrl, url, capturedReferrer)
        }
        buildMap {
            headers.forEach { (name, value) ->
                if (!name.equals("Referer", true)) put(name, value)
            }
            safeReferrer?.let { put("Referer", it) }
        }
    }

    private fun isOwnerValid(entry: PendingMenu): Boolean {
        val tab = lookupTab(entry.menu.tabId) ?: return false
        return tab.tabId == selectedTabId() &&
            tab.hardGeneration == entry.hardGeneration &&
            tab.locationGeneration == entry.menu.pageGeneration &&
            tab.isPrivate == entry.menu.isPrivate
    }

    private fun clearCurrent(notifyNative: Boolean) {
        val current = pending ?: return
        pending = null
        mutableState.value = null
        if (notifyNative && !current.closedNotified) {
            current.closedNotified = true
            notifyNativeClosed(current.menu.tabId)
        }
    }
}
