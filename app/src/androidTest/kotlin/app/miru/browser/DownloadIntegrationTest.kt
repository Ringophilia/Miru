// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.miru.downloads.DownloadCoordinator
import app.miru.downloads.DownloadItem
import app.miru.downloads.DownloadRequest
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real foreground service, Room checkpoints, HTTP Range transfers and MediaStore output. */
@RunWith(AndroidJUnit4::class)
class DownloadIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity.applicationContext
    private val coordinator get() = DownloadCoordinator.get(context)

    @Test fun segmentedDownloadResumesAtCheckpointsAndKeepsCredentialsOffDisk() {
        DownloadTestServer().use { server ->
            coordinator.updatePreferences { it.copy(threadsPerFile = 4, maxConcurrent = 1) }
            val cookie = "miru_cookie_memory_only_${System.nanoTime()}"
            val id = coordinator.enqueue(DownloadRequest("${server.origin}/file", fileName = "miru-resume-test.bin", headers = mapOf("Cookie" to cookie)))
            try {
                await(id) { it.status == "running" && it.downloadedBytes > 0 }
                coordinator.pause(id)
                val paused = await(id) { it.status == "paused" }
                assertTrue("A partial checkpoint must be persisted", paused.downloadedBytes in 1 until server.size)
                // Let cancellation settle before resuming; production must also handle rapid user taps.
                SystemClock.sleep(200)
                val requestCount = server.requests.size
                coordinator.resume(id)
                val complete = await(id, 40_000) { it.status == "completed" }
                assertEquals(server.size, complete.downloadedBytes)
                assertEquals(server.hash(), outputHash(complete))
                val resumed = server.requests.drop(requestCount).filter { it.method == "GET" && it.range != null }
                val partSize = server.size / 4
                assertTrue("A resumed range must start after its original segment boundary", resumed.any { it.range!!.first % partSize != 0L })
                assertTrue("The download must have used multiple segments", server.requests.filter { it.method == "GET" }.mapNotNull { it.range?.second }.distinct().size >= 4)
                context.databaseList().flatMap { name -> listOf(context.getDatabasePath(name), context.getDatabasePath("$name-wal"), context.getDatabasePath("$name-shm")) }
                    .filter { it.isFile }.forEach { file ->
                        assertFalse("Credential marker leaked into ${file.name}", file.readBytes().toString(Charsets.ISO_8859_1).contains(cookie))
                    }
            } finally { coordinator.delete(id, deleteFile = true) }
        }
    }

    @Test fun changedEtagDiscardsPartialBytesBeforeResume() {
        DownloadTestServer().use { server ->
            val id = coordinator.enqueue(DownloadRequest("${server.origin}/file", fileName = "miru-validator-test.bin"))
            try {
                await(id) { it.status == "running" && it.downloadedBytes > 0 }
                coordinator.pause(id)
                await(id) { it.status == "paused" }
                SystemClock.sleep(200)
                server.version.incrementAndGet() // Changes both validator and every byte of the entity.
                coordinator.resume(id)
                val complete = await(id, 40_000) { it.status == "completed" }
                assertEquals("Old cached bytes must not be mixed into the new representation", server.hash(), outputHash(complete))
                assertTrue(server.requests.any { it.ifRange == "\"miru-device-v2\"" })
            } finally { coordinator.delete(id, deleteFile = true) }
        }
    }

    @Test fun interruptedSegmentRetriesWithoutCorruptingOutput() {
        DownloadTestServer(interruptOnce = true, delayMillis = 0).use { server ->
            val id = coordinator.enqueue(DownloadRequest("${server.origin}/file", fileName = "miru-retry-test.bin"))
            try {
                val complete = await(id, 45_000) { it.status == "completed" }
                assertTrue("The fixture must actually interrupt a response", server.interruptions.get() > 0)
                assertEquals(server.hash(), outputHash(complete))
                assertTrue(server.requests.count { it.method == "GET" } > 4)
            } finally { coordinator.delete(id, deleteFile = true) }
        }
    }

    private fun await(id: String, timeout: Long = 30_000, predicate: (DownloadItem) -> Boolean): DownloadItem {
        val deadline = SystemClock.elapsedRealtime() + timeout
        var last: DownloadItem? = null
        while(SystemClock.elapsedRealtime() < deadline) {
            last = coordinator.downloads.value.firstOrNull { it.id == id }
            if(last != null && predicate(last)) return last
            if(last?.status == "failed") fail("Download failed: ${last.error}")
            SystemClock.sleep(40)
        }
        error("Timed out waiting for download: $last")
    }
    private fun outputHash(item: DownloadItem): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(Uri.parse(checkNotNull(item.destUri)))!!.use { input ->
            val buffer = ByteArray(64 * 1024)
            while(true) { val count = input.read(buffer); if(count < 0) break; digest.update(buffer,0,count) }
        }
        return digest.digest().hex()
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

private data class RecordedDownloadRequest(val method: String, val range: Pair<Long,Long>?, val ifRange: String?)

/** In-device origin: requests never leave the emulator and are independent of host networking. */
private class DownloadTestServer(
    val size: Long = 8L * 1024 * 1024,
    private val interruptOnce: Boolean = false,
    private val delayMillis: Long = 24,
) : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val open = AtomicBoolean(true)
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val interrupted = AtomicBoolean(false)
    val interruptions = AtomicInteger()
    val requests = CopyOnWriteArrayList<RecordedDownloadRequest>()
    val version = AtomicInteger(1)
    val origin = "http://127.0.0.1:${server.localPort}"
    init {
        thread(isDaemon = true, name = "miru-download-test-origin") {
            while(open.get()) runCatching {
                val socket = server.accept(); sockets += socket
                thread(isDaemon = true) { runCatching { serve(socket) }; sockets -= socket; runCatching { socket.close() } }
            }
        }
    }
    fun hash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0L
        while(offset < size) { val count = minOf(64 * 1024L, size - offset).toInt(); digest.update(bytes(offset,count,version.get())); offset += count }
        return digest.digest().hex()
    }
    private fun bytes(start: Long, length: Int, v: Int): ByteArray = ByteArray(length) { index ->
        val at = start + index
        (((at * 31 + (at / 256) * 17 + 83).toInt() and 255) xor (v - 1)).toByte()
    }
    private fun serve(socket: Socket) {
        socket.soTimeout = 5_000
        val reader = socket.getInputStream().bufferedReader()
        val method = reader.readLine()?.substringBefore(' ') ?: return
        val headers = mutableMapOf<String,String>()
        while(true) { val line = reader.readLine() ?: return; if(line.isEmpty()) break; headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
        val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(headers["range"].orEmpty())
        val range = match?.let { it.groupValues[1].toLong() to (it.groupValues[2].toLongOrNull() ?: size - 1).coerceAtMost(size - 1) }
        requests += RecordedDownloadRequest(method,range,headers["if-range"])
        val currentVersion = version.get()
        val etag = "\"miru-device-v$currentVersion\""
        val useRange = range != null && (headers["if-range"] == null || headers["if-range"] == etag)
        val first = if(useRange) range!!.first else 0L
        val last = if(useRange) range!!.second else size - 1
        val output = socket.getOutputStream()
        if(first >= size || first > last) { output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return }
        val response = buildString {
            append("HTTP/1.1 ${if(useRange) "206 Partial Content" else "200 OK"}\r\n")
            append("Content-Type: application/octet-stream\r\nContent-Length: ${last-first+1}\r\nAccept-Ranges: bytes\r\nETag: $etag\r\n")
            append("Content-Disposition: attachment; filename=\"miru-fixture.bin\"\r\nConnection: close\r\n")
            if(useRange) append("Content-Range: bytes $first-$last/$size\r\n")
            append("\r\n")
        }
        output.write(response.toByteArray())
        if(method == "HEAD") { output.flush(); return }
        val shouldInterrupt = interruptOnce && interrupted.compareAndSet(false,true)
        var offset = first
        while(open.get() && offset <= last) {
            val count = minOf(32 * 1024L,last-offset+1).toInt()
            output.write(bytes(offset,count,currentVersion)); output.flush(); offset += count
            if(shouldInterrupt && offset-first >= 128*1024) { interruptions.incrementAndGet(); return }
            if(delayMillis > 0) SystemClock.sleep(delayMillis)
        }
    }
    override fun close() { open.set(false); server.close(); sockets.forEach { runCatching { it.close() } } }
}
