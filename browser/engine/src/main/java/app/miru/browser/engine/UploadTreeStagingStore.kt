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
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

/** Provider IO is supplied by the caller, including cancellation of queries and stream opening. */
internal interface UploadTreeSource {
    fun root(): UploadTreeEntry
    fun children(id: String, maximum: Int): List<UploadTreeEntry>
    fun open(id: String): InputStream
}

internal data class UploadTreeEntry(val id: String, val name: String, val directory: Boolean,
    val lastModified: Long? = null)

/** The public Gecko folder API scans this complete physical tree after its real confirmation. */
internal class UploadTreeStagingStore(root: File) {
    private val configuredRoot = root.toPath().toAbsolutePath().normalize()
    private val lock = Any()
    private var realRoot: Path? = null
    private val active = mutableSetOf<Path>()

    fun clearOrphans() = synchronized(lock) {
        Files.newDirectoryStream(directory()).use { children ->
            children.filter { it.fileName.toString().startsWith(PREFIX) && it !in active }.forEach(::deleteTreeUpload)
        }
    }

    fun createLease(budgetBytes: Long, maximumFiles: Int = 512, maximumEntries: Int = 4096): UploadTreeStagingLease = synchronized(lock) {
        require(budgetBytes >= 0 && maximumFiles >= 0 && maximumEntries > 0)
        val root = directory()
        val path = Files.createTempDirectory(root, PREFIX)
        active.add(path)
        UploadTreeStagingLease(root, path, budgetBytes, maximumFiles, maximumEntries) {
            synchronized(lock) { active.remove(path) }
        }
    }

    private fun directory(): Path {
        realRoot?.let { validateTreeDirectory(it); return it }
        if(Files.isSymbolicLink(configuredRoot)) throw IOException("Folder staging root is redirected")
        Files.createDirectories(configuredRoot)
        return configuredRoot.toRealPath().also { validateTreeDirectory(it); realRoot = it }
    }

    private companion object { const val PREFIX = "folder-lease-" }
}

internal class UploadTreeStagingLease(
    private val stagingRoot: Path,
    private val leaseDirectory: Path,
    private val budgetBytes: Long,
    private val maximumFiles: Int,
    private val maximumEntries: Int,
    private val onClosed: () -> Unit,
) : Closeable {
    private val lock = Any()
    private val closed = AtomicBoolean()
    private val copying = AtomicBoolean()
    private val activeInput = AtomicReference<InputStream?>()
    private var cleaned = false
    private data class Directory(val entry: UploadTreeEntry, val path: Path, val ancestors: Set<String>)

    /** Blocking IO on the caller's dispatcher. Any error rejects and removes the entire tree. */
    suspend fun copy(source: UploadTreeSource): File {
        check(copying.compareAndSet(false, true)) { "A folder lease can be prepared once" }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { activeInput.get()?.close() } }
            try {
                val copiedRoot = synchronized(lock) {
                    fun checkCurrent() { continuation.context.ensureActive(); check(!closed.get()) { "Folder lease ended" } }
                    checkCurrent()
                    validateTreeDirectory(stagingRoot)
                    validateTreeDirectory(leaseDirectory)
                    val root = source.root()
                    if(!root.directory || root.id.isBlank()) throw IOException("Selected item is not a directory")
                    validateTreeName(root.name)
                    val output = Files.createDirectory(leaseDirectory.resolve(root.name))
                    val stack = ArrayDeque<Directory>()
                    stack.addLast(Directory(root, output, setOf(root.id)))
                    var entries = 1
                    var files = 0
                    var bytes = 0L
                    while(stack.isNotEmpty()) {
                        checkCurrent()
                        val directory = stack.removeLast()
                        validateTreeDirectory(directory.path)
                        val children = source.children(directory.entry.id, maximumEntries - entries)
                        if(children.size > maximumEntries - entries) throw IOException("Folder entry budget exceeded")
                        entries += children.size
                        val names = mutableSetOf<String>()
                        for(child in children) {
                            checkCurrent()
                            validateTreeName(child.name)
                            if(child.id.isBlank() || !names.add(child.name))
                                throw IOException("Folder has ambiguous or duplicate entry names")
                            validateTreeDirectory(directory.path)
                            val target = directory.path.resolve(child.name)
                            if(child.directory) {
                                if(child.id in directory.ancestors) throw IOException("Folder provider contains a cycle")
                                Files.createDirectory(target)
                                stack.addLast(Directory(child, target, directory.ancestors + child.id))
                            } else {
                                if(++files > maximumFiles) throw IOException("Folder file budget exceeded")
                                val incoming = TreeClosingInput(source.open(child.id))
                                activeInput.set(incoming)
                                try {
                                    incoming.use { input ->
                                        checkCurrent()
                                        validateTreeDirectory(directory.path)
                                        Files.newOutputStream(target, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { out ->
                                            val buffer = ByteArray(64 * 1024)
                                            while(true) {
                                                checkCurrent()
                                                var count = input.read(buffer)
                                                if(count < 0) break
                                                if(count == 0) {
                                                    val next = input.read()
                                                    if(next < 0) break
                                                    buffer[0] = next.toByte(); count = 1
                                                }
                                                checkCurrent()
                                                if(count.toLong() > budgetBytes - bytes) throw IOException("Folder byte budget exceeded")
                                                out.write(buffer, 0, count)
                                                bytes += count
                                            }
                                        }
                                    }
                                } finally { activeInput.compareAndSet(incoming, null) }
                                child.lastModified?.takeIf { it >= 0 }?.let { Files.setLastModifiedTime(target, FileTime.fromMillis(it)) }
                            }
                        }
                    }
                    checkCurrent()
                    output.toFile()
                }
                continuation.resume(copiedRoot) { _, _, _ -> runCatching { close() } }
            } catch(failure: Throwable) {
                runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
                continuation.resumeWithException(failure)
            }
        }
    }

    override fun close() {
        closed.set(true)
        runCatching { activeInput.get()?.close() }
        synchronized(lock) {
            if(cleaned) return
            validateTreeDirectory(stagingRoot)
            deleteTreeUpload(leaseDirectory)
            cleaned = true
            onClosed()
        }
    }

    private class TreeClosingInput(private val input: InputStream) : InputStream() {
        private val ended = AtomicBoolean()
        override fun read(): Int = input.read()
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = input.read(bytes, offset, length)
        override fun close() { if(ended.compareAndSet(false, true)) input.close() }
    }
}

private fun validateTreeName(name: String) {
    if(name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name || '\u0000' in name ||
        name.toByteArray(Charsets.UTF_8).size > 255) throw IOException("Invalid folder entry name")
}

private fun validateTreeDirectory(path: Path) {
    if(!Files.isDirectory(path, NOFOLLOW_LINKS) || path.toRealPath() != path)
        throw IOException("Folder staging directory is unavailable or redirected")
}

private fun deleteTreeUpload(path: Path) {
    if(!Files.exists(path, NOFOLLOW_LINKS)) return
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            Files.delete(file); return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
            if(failure != null) throw failure
            Files.delete(directory); return FileVisitResult.CONTINUE
        }
    })
}
