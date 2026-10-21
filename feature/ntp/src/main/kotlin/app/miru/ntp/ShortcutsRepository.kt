// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.ntp

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.miruShortcuts by preferencesDataStore(name = "miru_shortcuts")

internal data class Shortcut(val id: String, val title: String, val url: String)

internal class ShortcutsRepository(context: Context) {
    private val store = context.miruShortcuts
    private val key = stringPreferencesKey("items_v1")
    val defaults = listOf(
        Shortcut("google", context.getString(R.string.shortcut_google), "https://www.google.com"),
        Shortcut("youtube", context.getString(R.string.shortcut_youtube), "https://www.youtube.com"),
        Shortcut("bilibili", context.getString(R.string.shortcut_bilibili), "https://www.bilibili.com"),
        Shortcut("wikipedia", context.getString(R.string.shortcut_wikipedia), "https://www.wikipedia.org"),
        Shortcut("github", context.getString(R.string.shortcut_github), "https://github.com"),
        Shortcut("zhihu", context.getString(R.string.shortcut_zhihu), "https://www.zhihu.com"),
        Shortcut("weibo", context.getString(R.string.shortcut_weibo), "https://m.weibo.cn"),
        Shortcut("baidu", context.getString(R.string.shortcut_baidu), "https://www.baidu.com"),
    )
    val shortcuts = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { decode(it[key]) }

    suspend fun update(transform: (List<Shortcut>) -> List<Shortcut>) {
        store.edit { preferences ->
            val values = transform(decode(preferences[key])).distinctBy { it.id }.take(8)
            preferences[key] = JSONArray().apply {
                values.forEach { item ->
                    put(JSONObject().put("id", item.id).put("title", item.title).put("url", item.url))
                }
            }.toString()
        }
    }

    private fun decode(value: String?): List<Shortcut> {
        if (value == null) return defaults
        return runCatching {
            val array = JSONArray(value)
            (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                Shortcut(entry.getString("id"), entry.getString("title"), entry.getString("url"))
            }.filter { it.title.isNotBlank() && normalizeShortcutUrl(it.url) != null }.distinctBy { it.id }.take(8)
        }.getOrDefault(defaults)
    }
}

internal fun normalizeShortcutUrl(input: String): String? {
    val value = input.trim()
    if (value.isEmpty() || value.any(Char::isWhitespace)) return null
    val candidate = if ("://" in value) value else "https://$value"
    val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
    return candidate
}
