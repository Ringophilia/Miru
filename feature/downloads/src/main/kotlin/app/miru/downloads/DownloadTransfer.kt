// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.downloads

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class RemoteFile(val size: Long, val etag: String?, val modified: String?, val ranges: Boolean, val mime: String?, val disposition: String?)
internal class HttpFailure(val status: Int) : IOException("HTTP_$status")
internal class DownloadTransfer(private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followRedirects(true).addNetworkInterceptor { chain ->
    val request=chain.request()
    val origin=request.tag(String::class.java)
    val current="${request.url.scheme}://${request.url.host}:${request.url.port}"
    chain.proceed(if(origin!=null&&origin!=current)request.newBuilder().removeHeader("Cookie").removeHeader("Authorization").removeHeader("Origin").build()else request)
}.build()) {
    private val calls = ConcurrentHashMap<String, MutableSet<Call>>()
    fun cancel(id: String) { calls.remove(id)?.toList()?.forEach(Call::cancel) }
    fun probe(url: String, headers: Map<String, String>,id:String?=null): RemoteFile {
        val head = request(url, headers).head().build()
        executeProbe(id,head).use { response ->
            if(response.isSuccessful) return RemoteFile(response.header("Content-Length")?.toLongOrNull() ?: -1, response.header("ETag"), response.header("Last-Modified"), response.header("Accept-Ranges")?.equals("bytes", true) == true, response.header("Content-Type"), response.header("Content-Disposition"))
            if(response.code !in setOf(405, 501)) throw HttpFailure(response.code)
        }
        executeProbe(id,request(url, headers).header("Range", "bytes=0-0").build()).use { response ->
            if(!response.isSuccessful) throw HttpFailure(response.code)
            val total = if(response.code == 206) response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() else response.header("Content-Length")?.toLongOrNull()
            return RemoteFile(total ?: -1, response.header("ETag"), response.header("Last-Modified"), response.code == 206, response.header("Content-Type"), response.header("Content-Disposition"))
        }
    }
    private fun executeProbe(id:String?,request:Request):okhttp3.Response {
        val call=client.newCall(request)
        val set=id?.let { calls.getOrPut(it){ConcurrentHashMap.newKeySet()} }
        set?.add(call)
        try{return call.execute()}finally{set?.remove(call);if(id!=null&&set?.isEmpty()==true)calls.remove(id,set)}
    }
    suspend fun segment(id: String, url: String, headers: Map<String, String>, part: DownloadSegment, file: File, ranged: Boolean, validator: String?, totalSize:Long=-1, onProgress: suspend (Long) -> Unit) = withContext(Dispatchers.IO) {
        var downloaded = part.downloaded
        if(part.end >= 0 && downloaded >= part.end - part.start + 1) return@withContext
        repeat(4) { attempt ->
            ensureActive()
            val from = part.start + downloaded
            val builder = request(url, headers)
            if(ranged) { builder.header("Range", "bytes=$from-${if(part.end >= 0) part.end else ""}"); validator?.let { builder.header("If-Range", it) } }
            else downloaded = 0
            val call = client.newCall(builder.build())
            val set = calls.getOrPut(id) { ConcurrentHashMap.newKeySet() }
            set.add(call)
            try {
                call.execute().use { response ->
                    if(!response.isSuccessful) throw HttpFailure(response.code)
                    if(ranged && response.code != 206) throw IOException("RANGE_CHANGED")
                    if(ranged) {
                        val range = response.header("Content-Range") ?: throw IOException("INVALID_RANGE")
                        val match=Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(range)?:throw IOException("INVALID_RANGE")
                        if(match.groupValues[1].toLong()!=from || (part.end>=0&&match.groupValues[2].toLong()!=part.end))throw IOException("INVALID_RANGE")
                        if(totalSize>=0&&match.groupValues[3].toLongOrNull()!=totalSize)throw IOException("RANGE_CHANGED")
                        if(validator!=null) {
                            val received=if(validator.startsWith('"'))response.header("ETag")else response.header("Last-Modified")
                            if(received!=null&&received!=validator)throw IOException("RANGE_CHANGED")
                        }
                    }
                    val body = response.body ?: throw IOException("EMPTY_BODY")
                    RandomAccessFile(file, "rw").use { target ->
                        if(!ranged) target.setLength(0)
                        target.seek(if(ranged) from else 0)
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var checkpointAt = System.nanoTime()
                            while(true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if(read < 0) break
                                if(part.end >= 0 && downloaded + read > part.end - part.start + 1) throw IOException("INVALID_LENGTH")
                                target.write(buffer, 0, read); downloaded += read
                                if(System.nanoTime() - checkpointAt > 250_000_000) { onProgress(downloaded); checkpointAt = System.nanoTime() }
                            }
                            if(part.end >= 0 && downloaded != part.end - part.start + 1) throw IOException("INCOMPLETE_BODY")
                        }
                    }
                    onProgress(downloaded)
                    return@withContext
                }
            } catch(c: CancellationException) { throw c }
            catch(error: IOException) {
                onProgress(downloaded)
                ensureActive()
                if((error is HttpFailure && error.status !in setOf(408,429) && error.status<500) || error.message in setOf("RANGE_CHANGED", "INVALID_RANGE", "INVALID_LENGTH") || attempt == 3) throw error
                delay(1_000L shl attempt)
            } finally { set.remove(call); if(set.isEmpty())calls.remove(id,set) }
        }
    }
    private fun request(url: String, headers: Map<String, String>): Request.Builder = Request.Builder().url(url).apply {
        val parsed=url.toHttpUrl()
        tag(String::class.java,"${parsed.scheme}://${parsed.host}:${parsed.port}")
        headers.forEach { (key, value) -> if(key.lowercase() !in setOf("host", "content-length", "range", "accept-encoding", "connection")) header(key, value) }
        header("Accept-Encoding", "identity")
    }
}
