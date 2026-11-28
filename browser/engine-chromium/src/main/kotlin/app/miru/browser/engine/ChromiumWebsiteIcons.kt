// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Direct origin favicon fetcher with in-memory LRU cache and no cookies or disk cache. */
internal class ChromiumWebsiteIcons : WebsiteIconLoader {
    private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
    }
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override suspend fun load(url: String): android.graphics.Bitmap? {
        val iconUrl = faviconUrl(url) ?: return null
        cache.get(iconUrl)?.let { return it }
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                client.newCall(okhttp3.Request.Builder().url(iconUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val bytes = response.body?.bytes() ?: return@withContext null
                    if (bytes.size > 1024 * 1024) return@withContext null
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { bmp ->
                        cache.put(iconUrl, bmp)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun clearMemory() = cache.evictAll()
}
