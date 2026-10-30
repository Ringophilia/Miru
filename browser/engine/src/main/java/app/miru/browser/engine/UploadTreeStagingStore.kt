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
    val lastModified: Long? = null, val mimeType: String? = null)

/** Bounded, single-pass provider copying; logical paths are independent of the private layout. */
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
        realRoot?.let { validateFolderStagingDirectory(it); return it }
        if(Files.isSymbolicLink(configuredRoot)) throw IOException("Folder staging root is redirected")
        Files.createDirectories(configuredRoot)
        return configuredRoot.toRealPath().also { validateFolderStagingDirectory(it); realRoot = it }
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
    private class Ancestor(val id: String, val parent: Ancestor?) {
        fun contains(candidate: String): Boolean {
            var ancestor: Ancestor? = this
            while(ancestor != null) { if(ancestor.id == candidate) return true; ancestor = ancestor.parent }
            return false
        }
    }
    private data class Directory(val entry: UploadTreeEntry, val logicalPath: String, val ancestors: Ancestor)

    /** Blocking IO on the caller's dispatcher. Any error rejects and removes the entire tree. */
    suspend fun copy(source: UploadTreeSource): PreparedUploadFolder {
        check(copying.compareAndSet(false, true)) { "A folder lease can be prepared once" }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { activeInput.get()?.close() } }
            try {
                val copiedRoot = synchronized(lock) {
                    fun checkCurrent() { continuation.context.ensureActive(); check(!closed.get()) { "Folder lease ended" } }
                    checkCurrent()
                    validateFolderStagingDirectory(stagingRoot)
                    validateFolderStagingDirectory(leaseDirectory)
                    val root = source.root()
                    if(!root.directory || root.id.isBlank()) throw IOException("Selected item is not a directory")
                    validateFolderStagingName(root.name)
                    val output = Files.createDirectory(leaseDirectory.resolve(root.name))
                    val payloads = mutableListOf<UploadFolderPayload>()
                    val stack = ArrayDeque<Directory>()
                    stack.addLast(Directory(root, "/${root.name}", Ancestor(root.id, null)))
                    var entries = 1
                    var files = 0
                    var bytes = 0L
                    var metadataBytes = 0L
                    fun accountMetadata(entry: UploadTreeEntry, logicalPath: String) {
                        if(entry.id.isBlank() || entry.id.length > MAX_ID_CHARS)
                            throw IOException("Invalid folder document identity")
                        metadataBytes += entry.id.toByteArray(Charsets.UTF_8).size.toLong() +
                            entry.name.toByteArray(Charsets.UTF_8).size + logicalPath.toByteArray(Charsets.UTF_8).size +
                            (entry.mimeType?.toByteArray(Charsets.UTF_8)?.size ?: 0)
                        if(metadataBytes > MAX_METADATA_BYTES) throw IOException("Folder metadata budget exceeded")
                    }
                    accountMetadata(root, "/${root.name}")
                    while(stack.isNotEmpty()) {
                        checkCurrent()
                        val directory = stack.removeLast()
                        validateFolderStagingDirectory(output)
                        val children = source.children(directory.entry.id, maximumEntries - entries)
                        if(children.size > maximumEntries - entries) throw IOException("Folder entry budget exceeded")
                        entries += children.size
                        for(child in children) {
                            checkCurrent()
                            validateFolderStagingName(child.name)
                            val logicalPath = "${directory.logicalPath}/${child.name}"
                            accountMetadata(child, logicalPath)
                            if(child.directory) {
                                if(directory.ancestors.contains(child.id)) throw IOException("Folder provider contains a cycle")
                                stack.addLast(Directory(child, logicalPath, Ancestor(child.id, directory.ancestors)))
                            } else {
                                if(++files > maximumFiles) throw IOException("Folder file budget exceeded")
                                val mime = normalizeUploadMime(child.mimeType)
                                validateUploadTime(child.lastModified)
                                // Every occurrence gets its own private directory. The physical basename
                                // becomes File.name; Gecko gets the original logical path from the manifest.
                                val payloadRoot = output.resolve(".miru-payload")
                                if(files == 1) Files.createDirectory(payloadRoot)
                                validateFolderStagingDirectory(payloadRoot)
                                val targetDirectory = Files.createDirectory(payloadRoot.resolve(files.toString()))
                                val target = targetDirectory.resolve(child.name)
                                var fileBytes = 0L
                                val incoming = TreeClosingInput(source.open(child.id))
                                activeInput.set(incoming)
                                try {
                                    incoming.use { input ->
                                        checkCurrent()
                                        validateFolderStagingDirectory(targetDirectory)
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
                                                fileBytes += count
                                            }
                                        }
                                    }
                                } finally { activeInput.compareAndSet(incoming, null) }
                                child.lastModified?.let { Files.setLastModifiedTime(target, FileTime.fromMillis(it)) }
                                payloads += UploadFolderPayload(target.toString(), logicalPath, mime, child.lastModified, fileBytes)
                            }
                        }
                    }
                    checkCurrent()
                    PreparedUploadFolder.validate(stagingRoot, output, payloads, files, bytes)
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
            validateFolderStagingDirectory(stagingRoot)
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

    private companion object {
        const val MAX_METADATA_BYTES = 1024L * 1024
        const val MAX_ID_CHARS = 16 * 1024
    }
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
