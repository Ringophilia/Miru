// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Collections
import java.util.Locale

/** Logical metadata and an independently copied physical file, never a provider URI. */
internal data class UploadFolderPayload(val filePath: String, val logicalRelativePath: String,
    val mimeType: String, val lastModified: Long?, val size: Long)

/**
 * An immutable IO-validated snapshot. Its private constructor prevents an unchecked list from
 * reaching the native bridge. A separate, single-use capability binds it to the actual prompt.
 * The physical root is also the root Gecko authorizes before delivering its prepared file list.
 */
internal class PreparedUploadFolder private constructor(val rootPath: String, val rootName: String,
    payloads: List<UploadFolderPayload>, private val expectedBytes: Long) {
    val payloads: List<UploadFolderPayload> = Collections.unmodifiableList(payloads.toList())

    /** Recheck on IO immediately before binding to a native request; never called by the bridge. */
    fun validateFor(stagingRoot: Path): PreparedUploadFolder =
        validate(stagingRoot, File(rootPath).toPath(), payloads, payloads.size, expectedBytes)

    companion object {
        fun validate(stagingRoot: Path, root: Path, payloads: List<UploadFolderPayload>,
            expectedFiles: Int, expectedBytes: Long): PreparedUploadFolder {
            validateFolderStagingDirectory(stagingRoot)
            validateFolderStagingDirectory(root)
            if(root == stagingRoot || !root.startsWith(stagingRoot) || expectedFiles !in 0..512 ||
                expectedBytes < 0 || payloads.size != expectedFiles) throw IOException("Invalid prepared folder inventory")
            val rootName = root.fileName.toString()
            validateFolderStagingName(rootName)
            val paths = HashSet<Path>()
            var actualBytes = 0L
            var metadataBytes = 0L
            for(payload in payloads) {
                metadataBytes += payload.filePath.toByteArray(Charsets.UTF_8).size.toLong() +
                    payload.logicalRelativePath.toByteArray(Charsets.UTF_8).size + payload.mimeType.toByteArray(Charsets.UTF_8).size
                if(metadataBytes > 1024L * 1024) throw IOException("Prepared folder metadata budget exceeded")
                val components = payload.logicalRelativePath.split('/')
                if(components.size < 3 || components[0].isNotEmpty() || components[1] != rootName)
                    throw IOException("Invalid logical folder path")
                components.drop(1).forEach(::validateFolderStagingName)
                if(normalizeUploadMime(payload.mimeType) != payload.mimeType || payload.size < 0)
                    throw IOException("Invalid folder metadata")
                validateUploadTime(payload.lastModified)
                val path = File(payload.filePath).toPath()
                if(!path.isAbsolute || path.normalize() != path || path == root || !path.startsWith(root) ||
                    path.fileName.toString() != components.last() || !paths.add(path) ||
                    !Files.isRegularFile(path, NOFOLLOW_LINKS) || !Files.isReadable(path) || path.toRealPath() != path)
                    throw IOException("Invalid prepared folder payload")
                // Checking every ancestor forbids redirected paths even if a link resolves back to itself.
                var parent = path.parent
                while(parent != root) { validateFolderStagingDirectory(parent); parent = parent.parent }
                val size = Files.size(path)
                if(size != payload.size || size > expectedBytes - actualBytes)
                    throw IOException("Prepared folder bytes changed")
                actualBytes += size
            }
            var found = 0
            var nodes = 0
            Files.walk(root).use { inventory -> inventory.forEach { path ->
                if(++nodes > 4096) throw IOException("Prepared folder inventory budget exceeded")
                if(Files.isSymbolicLink(path)) throw IOException("Prepared folder contains a link")
                if(Files.isDirectory(path, NOFOLLOW_LINKS)) validateFolderStagingDirectory(path)
                else if(!Files.isRegularFile(path, NOFOLLOW_LINKS) || path !in paths)
                    throw IOException("Prepared folder inventory is incomplete")
                else found++
            } }
            if(found != expectedFiles || actualBytes != expectedBytes)
                throw IOException("Prepared folder inventory changed")
            // Empty selections must have a genuinely empty public root. Empty source subdirectories
            // carry no FileList entries and are deliberately not materialized in this layout.
            if(expectedFiles == 0) Files.newDirectoryStream(root).use {
                if(it.iterator().hasNext()) throw IOException("Empty folder root is not empty")
            }
            return PreparedUploadFolder(root.toString(), rootName, payloads, expectedBytes)
        }
    }
}

internal fun normalizeUploadMime(mime: String?): String {
    // An empty declaration lets Gecko infer a type and cannot meet this manifest contract.
    val normalized = mime?.trim()?.lowercase(Locale.ROOT) ?: throw IOException("Folder file MIME is unknown")
    if(normalized.length > 255 || !MIME.matches(normalized)) throw IOException("Folder file MIME is invalid")
    return normalized
}

internal fun validateUploadTime(time: Long?) {
    // The pinned GeckoBundle.putLong stores a double. Reject values it cannot represent exactly;
    // genuine pre-epoch values and explicit zero remain distinct from an unknown (omitted) time.
    if(time != null && time !in -9_007_199_254_740_991L..9_007_199_254_740_991L)
        throw IOException("Folder modification time cannot be preserved")
}

// Concrete restricted media-type names; parameters, wildcards and control characters are rejected.
private val MIME = Regex("[a-z0-9][a-z0-9!#$&^_.+\\-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+\\-]{0,126}")

internal fun validateFolderStagingName(name: String) {
    if(name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name || '\u0000' in name ||
        File(name).isAbsolute || name.toByteArray(Charsets.UTF_8).size > 255)
        throw IOException("Invalid folder entry name")
}

internal fun validateFolderStagingDirectory(path: Path) {
    if(!path.isAbsolute || path.normalize() != path || !Files.isDirectory(path, NOFOLLOW_LINKS) || path.toRealPath() != path)
        throw IOException("Folder staging directory is unavailable or redirected")
}
