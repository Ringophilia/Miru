// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

/** One application-scoped owner per dedicated root. Neither names nor contents are logged. */
class UploadStagingStore(root: File) {
    private val configuredRoot = root.toPath().toAbsolutePath().normalize()
    private val lock = Any()
    private var realRoot: Path? = null
    private val active = mutableSetOf<Path>()

    /** Call at process startup; leases owned by this store remain intact. Does not follow links. */
    fun clearOrphans() = synchronized(lock) {
        val directory = directory()
        Files.newDirectoryStream(directory).use { children ->
            children.filter { it.fileName.toString().startsWith(LEASE_PREFIX) && it !in active }
                .forEach(::deleteUploadTree)
        }
    }

    fun createLease(budgetBytes: Long = Long.MAX_VALUE): UploadStagingLease = synchronized(lock) {
        require(budgetBytes >= 0) { "Upload budget must be nonnegative" }
        val root = directory()
        val path = Files.createTempDirectory(root, LEASE_PREFIX)
        active.add(path)
        UploadStagingLease(root, path, budgetBytes) { synchronized(lock) { active.remove(path) } }
    }

    private fun directory(): Path {
        realRoot?.let { validateUploadDirectory(it); return it }
        if(Files.isSymbolicLink(configuredRoot)) throw IOException("Upload root must not be a symbolic link")
        Files.createDirectories(configuredRoot)
        if(!Files.isDirectory(configuredRoot, NOFOLLOW_LINKS)) throw IOException("Upload root is not a directory")
        return configuredRoot.toRealPath().also { realRoot = it }
    }

    private companion object { const val LEASE_PREFIX = "upload-lease-" }
}

/**
 * Temporary files belong to this lease until [close], including after a successful native answer.
 * [copy] performs blocking IO on its caller's dispatcher. A provider-specific cancellation signal
 * must also be supplied by [open] when opening a provider can itself block before returning a stream.
 */
class UploadStagingLease internal constructor(
    private val root: Path,
    private val directory: Path,
    private val budgetBytes: Long,
    private val onClosed: () -> Unit,
) : Closeable {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val input = AtomicReference<ClosingInput?>()
    private val committed = mutableMapOf<Path, Long>()
    private var bytesUsed = 0L
    private var cleaned = false

    /** Preserves the basename; each file gets its own directory so equal names never overwrite. */
    suspend fun copy(displayName: String, open: () -> InputStream): File {
        validateUploadName(displayName)
        return suspendCancellableCoroutine { continuation ->
            val thisInput = AtomicReference<ClosingInput?>()
            // This handler runs without the copy lock, so cancellation can unblock a blocked read.
            continuation.invokeOnCancellation { runCatching { thisInput.get()?.close() } }
            try {
                val result = synchronized(lock) {
                    continuation.context.ensureActive()
                    checkOpen()
                    validateUploadDirectory(root)
                    validateUploadDirectory(directory)
                    val fileDirectory = Files.createTempDirectory(directory, "file-")
                    val target = fileDirectory.resolve(displayName)
                    var copied = 0L
                    try {
                        val source = ClosingInput(open())
                        thisInput.set(source)
                        input.set(source)
                        try {
                            source.use { incoming ->
                                continuation.context.ensureActive()
                                checkOpen()
                                Files.newOutputStream(target, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { outgoing ->
                                    val buffer = ByteArray(64 * 1024)
                                    while(true) {
                                        continuation.context.ensureActive()
                                        checkOpen()
                                        var count = incoming.read(buffer)
                                        if(count < 0) break
                                        if(count == 0) {
                                            val byte = incoming.read()
                                            if(byte < 0) break
                                            buffer[0] = byte.toByte()
                                            count = 1
                                        }
                                        continuation.context.ensureActive()
                                        checkOpen()
                                        if(count.toLong() > budgetBytes - bytesUsed - copied) {
                                            throw IOException("Upload staging budget exceeded")
                                        }
                                        outgoing.write(buffer, 0, count)
                                        copied += count
                                    }
                                }
                            }
                        } finally {
                            thisInput.compareAndSet(source, null)
                            input.compareAndSet(source, null)
                        }
                        continuation.context.ensureActive()
                        checkOpen()
                        committed[fileDirectory] = copied
                        bytesUsed += copied
                        target.toFile()
                    } catch(failure: Throwable) {
                        runCatching { deleteUploadTree(fileDirectory) }.exceptionOrNull()?.let(failure::addSuppressed)
                        throw failure
                    }
                }
                // Cancellation can win after copying but before the suspended caller receives the file.
                continuation.resume(result) { _, file, _ -> runCatching { discard(file.toPath().parent) } }
            } catch(failure: Throwable) { continuation.resumeWithException(failure) }
        }
    }

    override fun close() {
        closed.set(true)
        // Do this before taking the lock: a provider read may be holding it indefinitely.
        runCatching { input.get()?.close() }
        synchronized(lock) {
            if(cleaned) return
            validateUploadDirectory(root)
            deleteUploadTree(directory)
            committed.clear()
            bytesUsed = 0
            cleaned = true
            onClosed()
        }
    }

    private fun checkOpen() { check(!closed.get()) { "Upload staging lease is closed" } }

    private fun discard(fileDirectory: Path) = synchronized(lock) {
        if(fileDirectory.parent != directory) return@synchronized
        val size = committed[fileDirectory] ?: return@synchronized
        validateUploadDirectory(root)
        deleteUploadTree(fileDirectory)
        committed.remove(fileDirectory)
        bytesUsed -= size
    }

    private class ClosingInput(private val delegate: InputStream) : InputStream() {
        private val closed = AtomicBoolean(false)
        override fun read() = delegate.read()
        override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate.read(buffer, offset, length)
        override fun close() { if(closed.compareAndSet(false, true)) delegate.close() }
    }
}

private fun validateUploadName(name: String) {
    require(name.isNotEmpty() && name != "." && name != ".." &&
        '/' !in name && '\\' !in name && '\u0000' !in name && name.toByteArray(Charsets.UTF_8).size <= 255) {
        "Invalid upload display name"
    }
}

private fun validateUploadDirectory(directory: Path) {
    if(!Files.isDirectory(directory, NOFOLLOW_LINKS) || directory.toRealPath() != directory) {
        throw IOException("Upload directory is unavailable or redirected")
    }
}

/** walkFileTree without FOLLOW_LINKS deletes symlinks themselves, never their targets. */
private fun deleteUploadTree(path: Path) {
    if(!Files.exists(path, NOFOLLOW_LINKS)) return
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            Files.deleteIfExists(file)
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
            if(failure != null) throw failure
            Files.deleteIfExists(directory)
            return FileVisitResult.CONTINUE
        }
    })
}
