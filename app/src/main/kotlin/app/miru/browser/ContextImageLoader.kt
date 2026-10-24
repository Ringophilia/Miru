// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.util.Base64
import app.miru.browser.engine.BrowserContextMenu
import app.miru.browser.engine.BrowserController
import app.miru.browser.engine.ContextImagePolicy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No disk cache or cookie jar. The original document owns transient request credentials. */
internal suspend fun loadContextImage(browser: BrowserController, owner: BrowserContextMenu): ContextImagePayload? {
    if(!browser.contextMenus.isCurrent(owner)) return null
    val source = owner.imageUrl ?: return null
    if(source.startsWith("data:", true)) return withContext(Dispatchers.Default) {
        val comma = source.indexOf(',')
        if(comma < 0 || source.length > ContextImageStore.MAX_BYTES * 3L) return@withContext null
        val metadata = source.substring(5, comma)
        if(!metadata.startsWith("image/", true)) return@withContext null
        val encoded = source.substring(comma + 1)
        val bytes = if(metadata.endsWith(";base64", true)) Base64.decode(encoded, Base64.DEFAULT) else {
            val output = ByteArrayOutputStream()
            var i = 0
            while(i < encoded.length) {
                if(encoded[i] == '%') {
                    require(i + 2 < encoded.length)
                    output.write(encoded.substring(i + 1, i + 3).toInt(16)); i += 3
                } else { require(encoded[i].code <= 127); output.write(encoded[i++].code) }
                require(output.size() <= ContextImageStore.MAX_BYTES)
            }
            output.toByteArray()
        }
        ContextImagePayload(bytes)
    }
    val original = source.toHttpUrlOrNull() ?: return null
    val policy = browser.contextImagePolicy(owner) ?: return null
    val headers = browser.contextHeaders(owner, source, policy) ?: return null
    var activePolicy: ContextImagePolicy? = policy
    val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS).addNetworkInterceptor { chain ->
            val request = chain.request()
            val target = request.url
            if(original.isHttps && !target.isHttps) throw IOException("Insecure image redirect")
            val sameOrigin = original.scheme == target.scheme && original.host == target.host && original.port == target.port
            val redirected = request.newBuilder().removeHeader("Referer")
            val sentReferrer = activePolicy?.referrerFor(target.toString())
            sentReferrer?.let { redirected.header("Referer", it) }
            if(!sameOrigin) redirected.removeHeader("Cookie").removeHeader("Authorization")
            val response = chain.proceed(redirected.build())
            if(response.isRedirect) {
                val nextPolicy = ContextImagePolicy.responsePolicy(response.headers.values("Referrer-Policy").joinToString(",")) ?: activePolicy?.policy
                // Redirects start from what was actually sent. An origin or missing
                // referrer cannot later regain a private path, query or document URL.
                activePolicy = if(sentReferrer != null && nextPolicy != null) ContextImagePolicy.fromDocument(sentReferrer, nextPolicy) else null
            }
            response
        }.build()
    val request = Request.Builder().url(original).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
    return withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            check(it.isSuccessful) { "Image unavailable" }
                            val body = checkNotNull(it.body)
                            require(body.contentLength() <= ContextImageStore.MAX_BYTES)
                            val output = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(16 * 1024)
                                while(true) {
                                    if(!continuation.isActive) throw IOException("Image cancelled")
                                    val count = input.read(buffer)
                                    if(count < 0) break
                                    require(output.size().toLong() + count <= ContextImageStore.MAX_BYTES)
                                    output.write(buffer, 0, count)
                                }
                            }
                            output.toByteArray()
                        }
                        if(continuation.isActive) continuation.resume(ContextImagePayload(bytes))
                    } catch(error: Exception) { if(continuation.isActive) continuation.resumeWithException(error) }
                }
            })
        }
    }
}
