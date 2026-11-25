// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import app.miru.common.SearchEngine
import app.miru.common.SearchSuggestionPolicy
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray

/** Never turn an internal page, credentials, path or search term into an icon request. */
fun faviconUrl(url: String): String? = runCatching {
    val uri = URI(url)
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty() || uri.userInfo != null) null
    else URI(uri.scheme.lowercase(), null, uri.host, uri.port, "/favicon.ico", null, null).toASCIIString()
}.getOrNull()

/** Ephemeral, cancellable suggestions. No browser cookies, disk cache or query logging. */
class SearchSuggestionClient(
    private val endpoint: (SearchEngine, String) -> String = SearchSuggestionPolicy::url,
) : Closeable {
    private val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS).build()

    suspend fun fetch(input: String, engine: SearchEngine, enabled: Boolean, isPrivate: Boolean): List<String> {
        if (!SearchSuggestionPolicy.mayRequest(input, engine, enabled, isPrivate)) return emptyList()
        val query = input.trim()
        val request = Request.Builder().url(endpoint(engine, query)).header("Accept", "application/json").build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resume(emptyList()) }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) return@use emptyList<String>()
                            val body = it.body ?: return@use emptyList<String>()
                            val bytes = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(4096)
                                while (bytes.size() <= 65536) {
                                    val count = input.read(buffer, 0, minOf(buffer.size, 65537 - bytes.size()))
                                    if (count < 0) break
                                    bytes.write(buffer, 0, count)
                                }
                            }
                            if (bytes.size() > 65536) return@use emptyList<String>()
                            val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                            val suggestions = JSONArray(bytes.toString(charset.name())).optJSONArray(1) ?: return@use emptyList<String>()
                            (0 until suggestions.length()).mapNotNull { index -> suggestions.opt(index) as? String }
                                .map(String::trim).filter { value -> value.isNotEmpty() && value.length <= 200 && !value.any(Char::isISOControl) && !value.equals(query, true) }
                                .distinctBy { value -> value.lowercase(Locale.ROOT) }.take(5)
                        }
                    }.getOrDefault(emptyList())
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
