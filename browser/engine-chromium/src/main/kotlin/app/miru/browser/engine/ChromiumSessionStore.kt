// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.graphics.Bitmap
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Atomic non-private tab session persistence, bounded in-memory `undoClose` history,
 * and an 8 MiB memory-only thumbnail cache for the Chromium backend.
 * Private tabs and credentials never touch disk or the undo stack.
 */
internal class ChromiumSessionStore(private val sessionFile: File) {
    internal data class SavedTabState(
        val id: String,
        val url: String,
        val title: String,
        val desktopMode: Boolean,
    )

    internal data class SessionSnapshot(
        val tabs: List<SavedTabState>,
        val selectedTabId: String?,
    )

    internal data class ClosedTabBatch(
        val tabs: List<SavedTabEntry>,
        val selectedTabId: String?,
    )

    internal data class SavedTabEntry(
        val state: SavedTabState,
        val index: Int,
    )

    private val writeLock = Any()
    private val undoStack = ArrayDeque<ClosedTabBatch>()
    private val thumbnails = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun putThumbnail(tabId: String, bitmap: Bitmap) {
        thumbnails.put(tabId, bitmap)
    }

    fun getThumbnail(tabId: String): Bitmap? = thumbnails.get(tabId)

    fun removeThumbnail(tabId: String) {
        thumbnails.remove(tabId)
    }

    fun retainThumbnails(activeIds: Set<String>) {
        thumbnails.snapshot().keys.forEach { key ->
            if (key !in activeIds) thumbnails.remove(key)
        }
    }

    fun clearThumbnails() {
        thumbnails.evictAll()
    }

    fun recordClosedNormalTabs(entries: List<SavedTabEntry>, selectedTabId: String?) {
        if (entries.isEmpty()) return
        synchronized(undoStack) {
            while (undoStack.size >= MAX_UNDO_BATCHES) {
                undoStack.removeFirst()
            }
            undoStack.addLast(ClosedTabBatch(tabs = entries, selectedTabId = selectedTabId))
        }
    }

    fun popUndoBatch(): ClosedTabBatch? = synchronized(undoStack) {
        if (undoStack.isEmpty()) null else undoStack.removeLast()
    }

    fun clearUndo() = synchronized(undoStack) { undoStack.clear() }

    fun save(normalTabs: List<SavedTabState>, selectedNormalTabId: String?) {
        val json = JSONObject().apply {
            put("version", 1)
            put("selectedTabId", selectedNormalTabId.orEmpty())
            val arr = JSONArray()
            normalTabs.forEach { tab ->
                arr.put(
                    JSONObject().apply {
                        put("id", tab.id)
                        put("url", tab.url)
                        put("title", tab.title)
                        put("desktopMode", tab.desktopMode)
                    },
                )
            }
            put("tabs", arr)
        }
        synchronized(writeLock) {
            runCatching {
                sessionFile.parentFile?.mkdirs()
                val tmp = File(sessionFile.parentFile, "${sessionFile.name}.tmp")
                tmp.writeText(json.toString(), Charsets.UTF_8)
                if (!tmp.renameTo(sessionFile)) {
                    tmp.copyTo(sessionFile, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }

    fun load(): SessionSnapshot? = synchronized(writeLock) {
        if (!sessionFile.isFile || sessionFile.length() !in 1L..MAX_SESSION_BYTES) return@synchronized null
        return@synchronized runCatching {
            val root = JSONObject(sessionFile.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("tabs") ?: return@runCatching null
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").trim()
                    val url = obj.optString("url").trim()
                    if (id.isEmpty() || url.isEmpty()) continue
                    add(
                        SavedTabState(
                            id = id,
                            url = url,
                            title = obj.optString("title"),
                            desktopMode = obj.optBoolean("desktopMode", false),
                        ),
                    )
                }
            }
            if (list.isEmpty()) null else SessionSnapshot(
                tabs = list,
                selectedTabId = root.optString("selectedTabId").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    private companion object {
        const val MAX_UNDO_BATCHES = 10
        const val MAX_SESSION_BYTES = 2L * 1024L * 1024L
    }
}
