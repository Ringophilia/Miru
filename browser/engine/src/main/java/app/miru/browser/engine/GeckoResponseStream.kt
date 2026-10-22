// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mozilla.components.concept.fetch.Response

/** Starts consuming Gecko's one-shot body only when the downloader reads it. Memory is bounded at 64 KiB. */
internal class GeckoResponseStream(private val response: Response, private val scope: CoroutineScope) : InputStream() {
    private val input = PipedInputStream(64 * 1024)
    private val output = PipedOutputStream(input)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var writer: Job? = null
    @Volatile private var failure: Throwable? = null

    private fun start() {
        if(closed.get()) throw IOException("Download stream is closed")
        if(started.compareAndSet(false, true)) writer = scope.launch(Dispatchers.IO) {
            try { response.body.useStream { source -> output.use { source.copyTo(it) } } }
            catch(error: Exception) { if(!closed.get()) failure = error; runCatching { output.close() } }
        }
    }
    override fun read(): Int { start(); return input.read().also { if(it == -1) failure?.let { cause -> throw IOException("Gecko response failed", cause) } } }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int { start(); return input.read(buffer, offset, length).also { if(it == -1) failure?.let { cause -> throw IOException("Gecko response failed", cause) } } }
    override fun close() {
        if(closed.compareAndSet(false, true)) {
            writer?.cancel()
            runCatching { input.close() }; runCatching { output.close() }; response.close()
        }
    }
}
