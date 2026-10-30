// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

/** Borrowed image: consumers drop references and never recycle the reader's image. */
data class SeekPreviewFrame<T>(val image: T, val presentationTimeMs: Long)

data class SeekPreviewState<T>(
    val targetMs: Long? = null,
    val frame: SeekPreviewFrame<T>? = null,
)

interface SeekPreviewReader<T> {
    suspend fun frame(positionMs: Long): SeekPreviewFrame<T>
    fun cancel()
    fun close()
}
