// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/** Read-only SAF traversal. All calls belong to the uploader's IO dispatcher and cancellation. */
internal class AndroidUploadTreeSource(context: Context, private val tree: Uri,
    private val signal: CancellationSignal) : UploadTreeSource {
    private val resolver = context.contentResolver
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS)

    override fun root(): UploadTreeEntry {
        signal.throwIfCanceled()
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        return resolver.query(uri, columns, null, null, null, signal)?.use { cursor ->
            if(!cursor.moveToFirst()) throw IOException("Folder root is unavailable")
            val root = entry(cursor)
            if(cursor.moveToNext()) throw IOException("Folder root is ambiguous")
            root
        } ?: throw IOException("Folder root is unavailable")
    }

    override fun children(id: String, maximum: Int): List<UploadTreeEntry> {
        signal.throwIfCanceled()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        return resolver.query(uri, columns, null, null, null, signal)?.use { cursor ->
            buildList {
                var metadataBytes = 0L
                while(cursor.moveToNext()) {
                    signal.throwIfCanceled()
                    if(size >= maximum) throw IOException("Folder entry budget exceeded")
                    val child = entry(cursor)
                    metadataBytes += child.id.toByteArray(Charsets.UTF_8).size.toLong() +
                        child.name.toByteArray(Charsets.UTF_8).size + (child.mimeType?.toByteArray(Charsets.UTF_8)?.size ?: 0)
                    if(metadataBytes > 1024L * 1024) throw IOException("Folder query metadata budget exceeded")
                    add(child)
                }
            }
        } ?: throw IOException("Folder children are unavailable")
    }

    private fun entry(cursor: Cursor): UploadTreeEntry {
        if(cursor.isNull(0) || cursor.isNull(1) || cursor.isNull(2)) throw IOException("Folder metadata is incomplete")
        val mime = cursor.getString(2).lowercase(Locale.ROOT)
        val directory = mime == DocumentsContract.Document.MIME_TYPE_DIR
        val name = cursor.getString(1)
        if(!cursor.isNull(4) && cursor.getInt(4) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0)
            throw IOException("Virtual folder files need a different conversion contract")
        val id = cursor.getString(0)
        if(id.isBlank() || id.length > 16 * 1024) throw IOException("Invalid folder document identity")
        validateFolderStagingName(name)
        return UploadTreeEntry(id, name, directory,
            if(cursor.isNull(3)) null else cursor.getLong(3), if(directory) null else normalizeUploadMime(mime))
    }

    override fun open(id: String): InputStream {
        signal.throwIfCanceled()
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val descriptor = resolver.openAssetFileDescriptor(uri, "r", signal)
            ?: throw IOException("Folder file is unavailable")
        return try {
            object : FilterInputStream(descriptor.createInputStream()) {
                override fun close() { try { super.close() } finally { descriptor.close() } }
            }
        } catch(failure: Throwable) { descriptor.close(); throw failure }
    }
}
