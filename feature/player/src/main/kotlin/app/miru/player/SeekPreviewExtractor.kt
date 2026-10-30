// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.inspector.frame.FrameExtractor
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.coroutines.resumeWithException

/**
 * A small owned-bitmap reader. Library future cancellation is insufficient: retire
 * first interrupts this reader's own source transport, then releases the sequenced
 * extractor. A later frame obtains a fresh source factory/player configuration.
 */
class SeekPreviewExtractor(
    context: Context,
    private val session: PlaybackSourceSession,
    private val maxWidth: Int = 160,
    private val maxHeight: Int = 90,
) : SeekPreviewReader<Bitmap> {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val monitor = Any()
    private val serial = Mutex()
    private var closed = false
    private var slot: Slot? = null

    init { require(maxWidth > 0 && maxHeight > 0) }

    override suspend fun frame(positionMs: Long): SeekPreviewFrame<Bitmap> = serial.withLock {
        withContext(Dispatchers.Main.immediate) {
            val current = synchronized(monitor) {
                check(!closed) { "Preview reader is closed" }
                slot ?: newSlot().also { slot = it }
            }
            awaitFrame(current, positionMs.coerceAtLeast(0))
        }
    }

    override fun cancel() { retire(synchronized(monitor) { slot.also { slot = null } }) }
    override fun close() {
        val previous = synchronized(monitor) {
            if(closed) return
            closed = true
            slot.also { slot = null }
        }
        retire(previous)
    }

    private fun newSlot(): Slot {
        val lease = session.previewLease()
        return try {
            Slot(lease, FrameExtractor.Builder(context, lease.mediaItem)
                .setMediaSourceFactory(lease.mediaSourceFactory)
                .setSeekParameters(SeekParameters.EXACT)
                .setEffects(listOf(Presentation.createForHeight(maxHeight))).build())
        } catch(error: Throwable) { lease.close(); throw error }
    }

    private suspend fun awaitFrame(current: Slot, positionMs: Long): SeekPreviewFrame<Bitmap> = suspendCancellableCoroutine { continuation ->
        val future = current.extractor.getFrame(positionMs)
        synchronized(monitor) { current.future = future }
        continuation.invokeOnCancellation {
            synchronized(monitor) { if(slot === current) slot = null }
            retire(current)
        }
        future.addListener({
            try {
                val result = future.get()
                // Copy immediately in the completion callback, before another library
                // extraction can reuse its borrowed result. Never recycle that result.
                val owned = copyBitmap(result.bitmap)
                val valid = synchronized(monitor) { !closed && slot === current && !current.retired }
                if(valid) continuation.resume(SeekPreviewFrame(owned, result.presentationTimeMs)) { _, value, _ -> value.image.recycle() }
                else { owned.recycle(); continuation.cancel(CancellationException("Preview source retired")) }
            } catch(error: Throwable) {
                val cause = if(error is ExecutionException) error.cause ?: error else error
                // Preserve the real source error chain, including 401/403, for the
                // controller's single current-header refresh policy.
                if(continuation.isActive) continuation.resumeWithException(cause)
                synchronized(monitor) { if(slot === current) slot = null }
                retire(current)
            } finally { synchronized(monitor) { if(current.future === future) current.future = null } }
        }, MoreExecutors.directExecutor())
    }

    private fun copyBitmap(borrowed: Bitmap): Bitmap {
        val scale = min(1f, min(maxWidth.toFloat() / borrowed.width, maxHeight.toFloat() / borrowed.height))
        val width = (borrowed.width * scale).roundToInt().coerceAtLeast(1)
        val height = (borrowed.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { owned ->
            Canvas(owned).drawBitmap(borrowed, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun retire(previous: Slot?) {
        if(previous == null) return
        val future = synchronized(monitor) {
            if(previous.retired) return
            previous.retired = true
            previous.future
        }
        previous.lease.cancel()
        future?.cancel(true)
        val release = Runnable { try { previous.extractor.close() } finally { previous.lease.close() } }
        if(Looper.myLooper() == Looper.getMainLooper()) release.run() else main.post(release)
    }

    private class Slot(val lease: PreviewSourceLease, val extractor: FrameExtractor) {
        var future: ListenableFuture<FrameExtractor.Frame>? = null
        var retired = false
    }
}
