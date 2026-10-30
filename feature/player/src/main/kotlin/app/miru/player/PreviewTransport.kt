// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import android.net.Uri
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A terminal cancellation boundary for one preview source lease. Its calls may share
 * a pool/dispatcher with playback; only individually registered preview calls are
 * cancelled. The ordinary thirty-second read timeout is not the cancellation path.
 */
class PreviewTransport(client: OkHttpClient?) : Call.Factory, Closeable {
    private val client = client?.newBuilder()?.readTimeout(30, TimeUnit.SECONDS)?.build()
    private val monitor = Any()
    private val calls = mutableSetOf<Call>()
    private val sources = mutableSetOf<OwnedDataSource>()
    @Volatile private var cancelled = false

    override fun newCall(request: Request): Call {
        val actual = checkNotNull(client) { "Offline preview has no HTTP upstream" }.newCall(request)
        synchronized(monitor) { if(cancelled) actual.cancel() else calls += actual }
        return OwnedCall(actual)
    }

    /** Track only the final preview source, never the source used by main playback. */
    fun track(factory: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        OwnedDataSource(factory.createDataSource())
    }

    fun cancel() {
        val pending: Pair<List<Call>, List<OwnedDataSource>> = synchronized(monitor) {
            if(cancelled) return
            cancelled = true
            (calls.toList() to sources.toList()).also { calls.clear(); sources.clear() }
        }
        // OkHttp cancellation interrupts an already pending body read immediately.
        // Do not use dispatcher.cancelAll/connectionPool.evictAll: playback may share them.
        pending.first.forEach { it.cancel() }
        // Cache/content/file source cleanup may block, so never close them on main.
        pending.second.forEach { source -> sourceCloser.execute { runCatching { source.close() } } }
    }

    override fun close() = cancel()

    private fun released(call: Call) { synchronized(monitor) { calls -= call } }

    private inner class OwnedCall(private val actual: Call) : Call by actual {
        override fun clone(): Call = newCall(actual.request())
        override fun execute(): Response = try { wrapResponse(actual.execute()) }
            catch(error: IOException) { released(actual); throw error }

        override fun enqueue(responseCallback: Callback) {
            actual.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    released(actual)
                    responseCallback.onFailure(this@OwnedCall, error)
                }
                override fun onResponse(call: Call, response: Response) {
                    responseCallback.onResponse(this@OwnedCall, wrapResponse(response))
                }
            })
        }

        private fun wrapResponse(response: Response): Response {
            val body = response.body ?: return response.also { released(actual) }
            val tracked = object : ResponseBody() {
                private val input: BufferedSource = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long = try {
                        super.read(sink, byteCount).also { if(it == -1L) released(actual) }
                    } catch(error: IOException) { released(actual); throw error }
                    override fun close() { try { super.close() } finally { released(actual) } }
                }.buffer()
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = input
            }
            return response.newBuilder().body(tracked).build()
        }
    }

    private inner class OwnedDataSource(private val actual: DataSource) : DataSource {
        private val state = Any()
        private var opened = false
        private var closeRequested = false
        override fun addTransferListener(transferListener: TransferListener) = actual.addTransferListener(transferListener)
        override fun getUri(): Uri? = actual.uri
        override fun getResponseHeaders(): Map<String, List<String>> = actual.responseHeaders

        override fun open(dataSpec: DataSpec): Long {
            synchronized(monitor) {
                if(cancelled) throw InterruptedIOException("Preview source cancelled")
                // A DataSource is reusable across non-overlapping opens, e.g. HLS
                // reloads. A normal close must not permanently poison that instance.
                synchronized(state) { closeRequested = false }
                sources += this
            }
            try {
                val length = actual.open(dataSpec)
                val abort = synchronized(state) {
                    if(closeRequested || cancelled) true else { opened = true; false }
                }
                if(abort) {
                    actual.close()
                    throw InterruptedIOException("Preview source cancelled")
                }
                return length
            } catch(error: Exception) {
                // open() can allocate a file/response before failing; clean on this IO
                // loader thread even if cancellation happened while it was opening.
                runCatching { actual.close() }
                synchronized(monitor) { sources -= this }
                throw error
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if(cancelled) throw InterruptedIOException("Preview source cancelled")
            return actual.read(buffer, offset, length)
        }

        override fun close() {
            val shouldClose = synchronized(state) {
                closeRequested = true
                opened.also { opened = false }
            }
            try { if(shouldClose) actual.close() }
            finally { synchronized(monitor) { sources -= this } }
        }
    }

    private companion object {
        val sourceCloser = Executors.newCachedThreadPool { task ->
            Thread(task, "miru-preview-source-close").apply { isDaemon = true }
        }
    }
}
