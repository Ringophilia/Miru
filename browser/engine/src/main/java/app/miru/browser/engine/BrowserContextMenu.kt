// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.HitResult

enum class BrowserContextMenuKind { LINK, IMAGE, VIDEO }

/** Process-only hit target. No cookies or headers, SavedState serialization, or URL-bearing logs. */
class BrowserContextMenu internal constructor(
    val token: Long,
    val tabId: String,
    val pageUrl: String,
    val isPrivate: Boolean,
    val kind: BrowserContextMenuKind,
    val linkUrl: String? = null,
    val linkText: String? = null,
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val title: String? = null,
    internal val pageGeneration: Long,
    internal val gestureStartedAtMillis: Long,
    val pressedAtMillis: Long = System.currentTimeMillis(),
) {
    override fun toString() = "BrowserContextMenu(token=$token, kind=$kind, private=$isPrivate)"
}

/** Observes genuine Gecko engine long-press events; the owning BrowserController supplies its store. */
class BrowserContextMenus(scope: CoroutineScope, private val store: BrowserStore) {
    private class Binding(val session: EngineSession, var pageUrl: String) {
        var generation = 0L
        lateinit var observer: EngineSession.Observer
    }
    private val bindings = mutableMapOf<String, Binding>()
    private val sequence = AtomicLong()
    private var gestureStartedAtMillis = -1L
    private val mutable = MutableStateFlow<BrowserContextMenu?>(null)
    val state = mutable.asStateFlow()
    private val watching: Job = scope.launch(Dispatchers.Main.immediate) {
        try {
            store.stateFlow.collect { value ->
                val tabs = value.tabs.associateBy { it.id }
                bindings.keys.toList().forEach { id ->
                    val binding = bindings.getValue(id)
                    val current = tabs[id]
                    if(current?.engineState?.engineSession !== binding.session) {
                        binding.session.unregister(binding.observer)
                        bindings.remove(id)
                        if(mutable.value?.tabId == id) mutable.value = null
                    } else if(current.content.url != binding.pageUrl) {
                        binding.pageUrl = current.content.url
                        binding.generation++
                        if(mutable.value?.tabId == id) mutable.value = null
                    }
                }
                value.tabs.forEach { tab ->
                    val session = tab.engineState.engineSession ?: return@forEach
                    if(bindings.containsKey(tab.id)) return@forEach
                    val binding = Binding(session, tab.content.url)
                    binding.observer = observer(tab.id, binding)
                    bindings[tab.id] = binding
                    session.register(binding.observer)
                }
                mutable.value?.takeUnless(::isOwnerCurrent)?.let { mutable.value = null }
            }
        } finally {
            bindings.values.forEach { it.session.unregister(it.observer) }
            bindings.clear()
            mutable.value = null
        }
    }

    fun dismiss(token: Long? = null) {
        if(token == null || mutable.value?.token == token) mutable.value = null
    }

    /** Called before dispatching a real native ACTION_DOWN to this EngineView. */
    fun beginTouchGesture() { gestureStartedAtMillis = System.currentTimeMillis() }

    /** Image transfers must still belong to the currently visible menu and owning document. */
    fun isCurrent(request: BrowserContextMenu) = mutable.value?.token == request.token && isOwnerCurrent(request)

    /** An accepted download can outlive its sheet; authentication still belongs to this document. */
    fun isOwnerCurrent(request: BrowserContextMenu): Boolean {
        val binding = bindings[request.tabId] ?: return false
        val value = store.state
        val tab = value.tabs.firstOrNull { it.id == request.tabId } ?: return false
        return value.selectedTabId == request.tabId && tab.content.url == request.pageUrl &&
            tab.content.private == request.isPrivate && tab.engineState.engineSession === binding.session &&
            binding.generation == request.pageGeneration
    }

    fun close() { watching.cancel() }

    private fun observer(tabId: String, binding: Binding) = object : PermissionNeutralObserver() {
        private fun invalidate() {
            binding.generation++
            if(mutable.value?.tabId == tabId) mutable.value = null
        }
        override fun onLoadUrl() = invalidate()
        override fun onLoadingStateChange(loading: Boolean) { if(loading) invalidate() }
        override fun onLocationChange(url: String, hasUserGesture: Boolean) {
            if(url != binding.pageUrl) { binding.pageUrl = url; invalidate() }
        }
        override fun onCrash() = invalidate()
        override fun onProcessKilled() = invalidate()
        override fun onLongPress(hitResult: HitResult) {
            val current = store.state.tabs.firstOrNull { it.id == tabId } ?: return
            if(store.state.selectedTabId != tabId || current.engineState.engineSession !== binding.session) return
            if(current.content.url != binding.pageUrl) { binding.pageUrl = current.content.url; invalidate() }
            val link = when(hitResult) { is HitResult.UNKNOWN -> webUrl(hitResult.src); is HitResult.IMAGE_SRC -> webUrl(hitResult.uri); else -> null }
            val image = when(hitResult) { is HitResult.IMAGE -> resourceUrl(hitResult.src); is HitResult.IMAGE_SRC -> resourceUrl(hitResult.src); else -> null }
            val video = when(hitResult) { is HitResult.VIDEO -> resourceUrl(hitResult.src); else -> null }
                ?: link?.takeIf { URI(it).path.orEmpty().lowercase().matches(Regex(".*\\.(mp4|m3u8|mpd|webm|mkv|mov)$")) }
            val kind = when { image != null -> BrowserContextMenuKind.IMAGE; hitResult is HitResult.VIDEO && video != null -> BrowserContextMenuKind.VIDEO
                link != null -> BrowserContextMenuKind.LINK; else -> return }
            mutable.value = BrowserContextMenu(sequence.incrementAndGet(), tabId, current.content.url, current.content.private,
                kind, link, (hitResult as? HitResult.UNKNOWN)?.linkText?.take(8_192), image, video,
                when(hitResult) { is HitResult.IMAGE -> hitResult.title; is HitResult.VIDEO -> hitResult.title; else -> null }?.take(8_192),
                binding.generation, gestureStartedAtMillis)
        }
    }

    private fun webUrl(value: String) = value.takeIf { runCatching { URI(it).scheme?.lowercase() in setOf("http", "https") && URI(it).host != null }.getOrDefault(false) }
    private fun resourceUrl(value: String) = value.takeIf { it.isNotBlank() && it.length <= 2_000_000 && runCatching {
        URI(it).scheme?.lowercase() in setOf("http", "https", "data", "blob")
    }.getOrDefault(false) }
}
