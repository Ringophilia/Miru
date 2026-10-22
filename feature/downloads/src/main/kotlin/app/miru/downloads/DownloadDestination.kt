// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

internal class DownloadDestination(private val context: Context) {
    fun publish(file: File, item: DownloadEntity, ensureActive: () -> Unit): Pair<String, String> {
        val resolver = context.contentResolver
        if(item.directoryUri != null) {
            val folder = DocumentFile.fromTreeUri(context, Uri.parse(item.directoryUri)) ?: throw IOException("DIRECTORY")
            val name = DownloadLogic.uniqueName(item.fileName) { folder.findFile(it) != null }
            val document = folder.createFile(item.mimeType, name) ?: throw IOException("DIRECTORY")
            try { copy(file, document.uri, ensureActive); ensureActive() }
            catch(error: Exception) { runCatching { document.delete() }; throw error }
            return document.uri.toString() to name
        }
        if(Build.VERSION.SDK_INT < 29) throw IOException("DIRECTORY_REQUIRED")
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val name = DownloadLogic.uniqueName(item.fileName) { candidate ->
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(candidate, "${Environment.DIRECTORY_DOWNLOADS}/Miru/"), null)?.use { it.moveToFirst() } ?: false
        }
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Miru")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IOException("STORAGE")
        try {
            copy(file, uri, ensureActive)
            ensureActive()
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch(error: Exception) { runCatching { resolver.delete(uri, null, null) }; throw error }
        return uri.toString() to name
    }
    private fun copy(file: File, uri: Uri, ensureActive: () -> Unit) {
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                ensureActive()
                output.flush()
            }
        } ?: throw IOException("STORAGE")
    }
    fun delete(uri: String) { context.contentResolver.delete(Uri.parse(uri), null, null) }
}
