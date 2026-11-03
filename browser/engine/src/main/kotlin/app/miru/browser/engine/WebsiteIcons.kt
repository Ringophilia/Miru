// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.icons.Icon
import mozilla.components.browser.icons.IconRequest
import mozilla.components.browser.icons.loader.DataUriIconLoader
import mozilla.components.browser.icons.loader.HttpIconLoader
import mozilla.components.browser.icons.loader.MemoryIconLoader
import mozilla.components.browser.icons.loader.DefaultMemoryInfoProvider
import mozilla.components.browser.icons.preparer.IconPreprarer
import mozilla.components.browser.icons.preparer.MemoryIconPreparer
import mozilla.components.browser.icons.processor.MemoryIconProcessor
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import mozilla.components.concept.fetch.Response
import okhttp3.OkHttpClient

/** Page-declared PNG/ICO/SVG icons, with an origin favicon fallback. No third-party lookup or disk cache. */
class WebsiteIcons(context: Context) {
    private val cache = IconCache()
    private val client = object : Client() {
        private val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
        override fun fetch(request: Request): Response {
            // This independent client has no CookieJar, authenticator, browser headers or disk cache.
            return http.newCall(okhttp3.Request.Builder().url(request.url).build()).execute().use { result ->
                val output = ByteArrayOutputStream()
                result.body?.byteStream()?.let { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 1024 * 1024) throw IOException("Icon exceeds size limit")
                        output.write(buffer, 0, count)
                    }
                }
                val bytes = output.toByteArray()
                Response(result.request.url.toString(), result.code, MutableHeaders().apply {
                    set("Content-Length", bytes.size.toString())
                }, Response.Body(bytes.inputStream(), result.header("Content-Type")))
            }
        }
    }
    private val icons = BrowserIcons(context, client,
        preparers = listOf(MemoryIconPreparer(cache), object : IconPreprarer {
            override fun prepare(context: Context, request: IconRequest): IconRequest {
                if (request.resources.isNotEmpty()) return request
                val url = faviconUrl(request.url) ?: return request
                return request.copy(resources = listOf(IconRequest.Resource(url, IconRequest.Resource.Type.FAVICON)))
            }
        }),
        loaders = listOf(MemoryIconLoader(cache), HttpIconLoader(client, DefaultMemoryInfoProvider(context)), DataUriIconLoader()),
        processors = listOf(MemoryIconProcessor(cache)),
    )

    internal fun install(engine: Engine, store: BrowserStore) = icons.install(engine, store)

    suspend fun load(url: String): Bitmap? {
        if (faviconUrl(url) == null) return null
        return try {
            icons.loadIcon(IconRequest(url)).await().takeUnless { it.source == Icon.Source.GENERATOR }?.bitmap
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    fun clearMemory() = cache.clear()

    /** Private resources never enter the shared cache, including their URL metadata. */
    private class IconCache : MemoryIconPreparer.PreparerMemoryCache,
        MemoryIconLoader.LoaderMemoryCache, MemoryIconProcessor.ProcessorMemoryCache {
        private val resources = LruCache<String, List<IconRequest.Resource>>(256)
        private val bitmaps = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
        override fun getResources(request: IconRequest) = if (request.isPrivate) emptyList() else resources[request.url].orEmpty()
        override fun getBitmap(request: IconRequest, resource: IconRequest.Resource) = if (request.isPrivate) null else bitmaps[resource.url]
        override fun put(request: IconRequest, resource: IconRequest.Resource, icon: Icon) {
            if (request.isPrivate) return
            bitmaps.put(resource.url, icon.bitmap)
            resources.put(request.url, request.resources)
        }
        fun clear() { resources.evictAll(); bitmaps.evictAll() }
    }
}
