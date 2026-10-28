// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
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
                while(cursor.moveToNext()) {
                    signal.throwIfCanceled()
                    if(size >= maximum) throw IOException("Folder entry budget exceeded")
                    add(entry(cursor))
                }
            }
        } ?: throw IOException("Folder children are unavailable")
    }

    private fun entry(cursor: Cursor): UploadTreeEntry {
        if(cursor.isNull(0) || cursor.isNull(1) || cursor.isNull(2)) throw IOException("Folder metadata is incomplete")
        val mime = cursor.getString(2).lowercase(Locale.ROOT)
        val directory = mime == DocumentsContract.Document.MIME_TYPE_DIR
        val name = cursor.getString(1)
        if(!directory) {
            if(!cursor.isNull(4) && cursor.getInt(4) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0)
                throw IOException("Virtual folder files need a different conversion contract")
            val extension = name.takeIf { it.lastIndexOf('.') > 0 }?.substringAfterLast('.')?.lowercase(Locale.ROOT)
            val inferred = extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            // The public root route lets Gecko infer MIME from the copied basename. Never
            // silently advertise preserving a conflicting explicit provider MIME declaration.
            if(mime.isNotBlank() && mime != "application/octet-stream" && (inferred == null || inferred != mime))
                throw IOException("Folder file MIME cannot be preserved by a local directory")
        }
        return UploadTreeEntry(cursor.getString(0), name, directory,
            if(cursor.isNull(3)) null else cursor.getLong(3))
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
