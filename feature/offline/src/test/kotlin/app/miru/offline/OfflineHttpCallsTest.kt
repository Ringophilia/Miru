// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.offline

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class OfflineHttpCallsTest {
    @Test fun cancelInterruptsAnAlreadyReadingResponseWithoutCancelingAnotherTransfer() {
        HeldBody().use { first -> HeldBody().use { second ->
            val client = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
            val firstScope = OfflineHttpCalls()
            val secondScope = OfflineHttpCalls()
            val executor = Executors.newFixedThreadPool(2)
            try {
                val firstReading = CountDownLatch(1)
                val secondReading = CountDownLatch(1)
                fun read(scope: OfflineHttpCalls, source: HeldBody, reading: CountDownLatch) = executor.submit<Boolean> {
                    try {
                        scope.factory(client).newCall(Request.Builder().url(source.url).build()).execute().use { response ->
                            val body = requireNotNull(response.body)
                            assertEquals(1, body.source().readByte().toInt())
                            reading.countDown()
                            body.source().readByte()
                        }
                        false
                    } catch(_: IOException) { true }
                }
                val firstRead = read(firstScope, first, firstReading)
                val secondRead = read(secondScope, second, secondReading)
                assertTrue(firstReading.await(5, TimeUnit.SECONDS))
                assertTrue(secondReading.await(5, TimeUnit.SECONDS))
                // The server has not released either response body. Only an explicit socket
                // cancellation can return before its normal 20-second read timeout.
                firstScope.cancel()
                assertTrue(firstRead.get(3, TimeUnit.SECONDS))
                assertFalse("A different UUID's socket must keep running", secondRead.isDone)
                second.release.countDown()
                assertFalse(secondRead.get(3, TimeUnit.SECONDS))
            } finally {
                firstScope.cancel(); secondScope.cancel()
                executor.shutdownNow()
            }
        } }
    }

    @Test fun aCanceledTransferCannotStartItsNextSegmentOrAuthenticationRetry() {
        val scope = OfflineHttpCalls()
        val factory = scope.factory(OkHttpClient())
        scope.cancel()
        val call = factory.newCall(Request.Builder().url("http://127.0.0.1:1/segment.ts").build())
        assertTrue(call.isCanceled())
        try { call.execute(); fail("Canceled calls must not reach the origin") }
        catch(_: IOException) { assertTrue(call.isCanceled()) }
    }

    private class HeldBody : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val release = CountDownLatch(1)
        @Volatile private var socket: Socket? = null
        val url = "http://127.0.0.1:${server.localPort}/segment.ts"
        init { thread(isDaemon = true, name = "miru-held-body-test") {
            try {
                server.accept().use { connection ->
                    socket = connection
                    val input = connection.getInputStream().bufferedReader()
                    while(!input.readLine().isNullOrEmpty()) { }
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n".toByteArray())
                        write(1); flush()
                        release.await(20, TimeUnit.SECONDS)
                        write(2); flush()
                    }
                }
            } catch(_: IOException) { }
        } }
        override fun close() { release.countDown(); socket?.close(); server.close() }
    }
}
