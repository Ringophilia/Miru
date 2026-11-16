// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.downloads

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

internal data class DownloadStorageUsage(val usedBytes: Long, val availableBytes: Long?, val incomplete: Boolean) {
    val usedFraction: Float? get() = availableBytes?.let {
        val total = usedBytes.toDouble() + it
        if (total == 0.0) 0f else (usedBytes / total).toFloat()
    }
}

internal class DownloadStorage(private val context: Context, private val partsDir: File) {
    /** Scope is owned by the visible downloads screen. Provider metadata is slower than local stats. */
    fun observe(downloads: StateFlow<List<DownloadItem>>, offlineBytes: suspend () -> Long): Flow<DownloadStorageUsage> = flow {
        var measuredUris: Set<String>? = null
        var published = MeasuredBytes()
        var refreshPublishedAt = 0L
        while (currentCoroutineContext().isActive) {
            val uris = downloads.value.mapNotNullTo(mutableSetOf()) { it.destUri }
            if (uris != measuredUris || SystemClock.elapsedRealtime() >= refreshPublishedAt) {
                published = measurePublished(uris)
                measuredUris = uris
                refreshPublishedAt = SystemClock.elapsedRealtime() + 10_000L
            }
            val partial = measureParts()
            val offline = try { offlineBytes() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            val available = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrNull()
            emit(DownloadStorageUsage(
                usedBytes = published.bytes + partial.bytes + (offline ?: 0),
                availableBytes = available,
                incomplete = published.incomplete || partial.incomplete || offline == null,
            ))
            delay(1_000L)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private suspend fun measurePublished(uris: Set<String>): MeasuredBytes {
        var bytes = 0L
        var incomplete = false
        for (value in uris) {
            currentCoroutineContext().ensureActive()
            val size = publishedSize(Uri.parse(value))
            if (size == null) incomplete = true else bytes += size
        }
        return MeasuredBytes(bytes, incomplete)
    }

    private fun publishedSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use 0L
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column < 0 || cursor.isNull(column)) null else cursor.getLong(column).takeIf { it >= 0 }
        }
    }.getOrNull()

    private suspend fun measureParts(): MeasuredBytes {
        val files = partsDir.listFiles() ?: return MeasuredBytes(incomplete = true)
        var bytes = 0L
        var incomplete = false
        for (file in files) {
            currentCoroutineContext().ensureActive()
            try {
                val stat = Os.lstat(file.absolutePath)
                // Segmented downloads extend the logical file before writing bytes. Count allocated blocks.
                if (OsConstants.S_ISREG(stat.st_mode)) bytes += stat.st_blocks * 512L
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.ENOENT) incomplete = true
            }
        }
        return MeasuredBytes(bytes, incomplete)
    }

    private data class MeasuredBytes(val bytes: Long = 0, val incomplete: Boolean = false)
}
