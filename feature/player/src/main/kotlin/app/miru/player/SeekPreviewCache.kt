// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

internal fun seekPreviewBucket(positionMs: Long): Long = positionMs.coerceAtLeast(0) / 10_000 * 10_000

/** Access-order memory cache. Eviction releases references, never borrowed images. */
internal class SeekPreviewCache<T>(capacity: Int = 60) {
    private val limit = capacity.coerceIn(1, 60)
    private val frames = LinkedHashMap<Long, SeekPreviewFrame<T>>(limit, 0.75f, true)
    val size: Int get() = frames.size

    operator fun get(positionMs: Long): SeekPreviewFrame<T>? = frames[seekPreviewBucket(positionMs)]

    fun put(positionMs: Long, frame: SeekPreviewFrame<T>) {
        frames[seekPreviewBucket(positionMs)] = frame
        while(frames.size > limit) frames.remove(frames.keys.first())
    }

    fun clear() = frames.clear()
}
