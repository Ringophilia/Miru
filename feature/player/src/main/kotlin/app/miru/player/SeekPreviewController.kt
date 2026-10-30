// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One extraction and one latest target; MOVE events never create an extraction queue. */
class SeekPreviewController<T>(
    private val scope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private val mutable = MutableStateFlow(SeekPreviewState<T>())
    val state: StateFlow<SeekPreviewState<T>> = mutable.asStateFlow()
    private val cache = SeekPreviewCache<T>()
    private var binding: Binding<T>? = null
    private var generation = Any()
    private var reader: SeekPreviewReader<T>? = null
    private var active: Work<T>? = null
    private var targetBucket: Long? = null
    private var closed = false

    fun bind(sourceRevision: Long, factory: suspend () -> SeekPreviewReader<T>) {
        val retired = synchronized(lock) {
            if(closed) return
            val old = detach()
            binding = Binding(sourceRevision, factory)
            cache.clear()
            old
        }
        retire(retired)
    }

    fun request(positionMs: Long) {
        val next = synchronized(lock) {
            if(closed) return
            val target = positionMs.coerceAtLeast(0)
            val bucket = seekPreviewBucket(target)
            targetBucket = bucket
            val cached = cache[bucket]
            mutable.value = SeekPreviewState(target, cached)
            if(cached == null && active == null) newWork(bucket) else null
        }
        next?.let(::start)
    }

    /** Hiding invalidates in-flight work but retains this source's completed LRU. */
    fun hide() {
        val retired = synchronized(lock) {
            if(closed) return
            detach()
        }
        retire(retired)
    }

    fun close() {
        val retired = synchronized(lock) {
            if(closed) return
            closed = true
            val old = detach()
            binding = null
            cache.clear()
            old
        }
        retire(retired)
    }

    // Caller holds lock. The generation also separates hide/show on the same revision.
    private fun detach(): Retired<T> {
        generation = Any()
        targetBucket = null
        mutable.value = SeekPreviewState()
        return Retired(active?.job, reader).also { active = null; reader = null }
    }

    private fun newWork(bucket: Long): Work<T>? {
        val source = binding ?: return null
        if(!scope.isActive) return null
        return Work(source, generation, bucket).also { work ->
            active = work
            work.job = scope.launch(start = CoroutineStart.LAZY) { extract(work) }
        }
    }

    private fun start(work: Work<T>) {
        // Includes cancellation before a lazy coroutine ever starts.
        work.job.invokeOnCompletion { cause ->
            if(cause != null) finish(work, null, null, cancelled = true)
        }
        work.job.start()
    }

    private suspend fun extract(work: Work<T>) {
        var result: SeekPreviewFrame<T>? = null
        var failure: Throwable? = null
        var cancelled = false
        try {
            val existing = synchronized(lock) {
                // A reserved lazy job may be superseded before its dispatcher starts it.
                if(!isCurrent(work)) return
                reader
            }
            val current = existing ?: work.source.factory().let { created ->
                val accepted = synchronized(lock) {
                    if(isCurrent(work)) { reader = created; true } else false
                }
                if(!accepted) {
                    retire(Retired(null, created))
                    return
                }
                created
            }
            currentCoroutineContext().ensureActive()
            result = current.frame(work.bucket)
            currentCoroutineContext().ensureActive()
        } catch(_: CancellationException) {
            cancelled = true
        } catch(error: Throwable) {
            failure = error
        } finally {
            finish(work, result, failure, cancelled)
        }
    }

    private fun finish(work: Work<T>, result: SeekPreviewFrame<T>?, failure: Throwable?, cancelled: Boolean) {
        var retired: Retired<T>? = null
        val next = synchronized(lock) {
            if(!isCurrent(work)) return
            active = null
            if(failure != null || cancelled) {
                retired = Retired(null, reader)
                reader = null
                if(targetBucket == work.bucket) mutable.value = mutable.value.copy(frame = null)
            } else if(result != null) {
                cache.put(work.bucket, result)
                if(targetBucket == work.bucket) mutable.value = mutable.value.copy(frame = result)
            }
            val latest = targetBucket
            // A failed current bucket is retried only by a subsequent user request.
            if(latest != null && latest != work.bucket && cache[latest] == null) newWork(latest) else null
        }
        retired?.let(::retire)
        try {
            synchronized(lock) {
                // Reporting can trigger an authentication rebind. Keep delivery and
                // its generation check atomic with bind/hide/close on other threads.
                if(failure != null && sameGeneration(work)) runCatching { onFailure(failure) }
            }
        } finally {
            next?.let(::start)
        }
    }

    private fun sameGeneration(work: Work<T>): Boolean =
        !closed && binding === work.source && generation === work.generation

    private fun isCurrent(work: Work<T>): Boolean = active === work && sameGeneration(work)

    private fun retire(retired: Retired<T>) {
        // Cleanup must attempt all three operations even when an adapter throws.
        runCatching { retired.reader?.cancel() }
        retired.job?.cancel()
        runCatching { retired.reader?.close() }
    }

    private class Binding<T>(val sourceRevision: Long, val factory: suspend () -> SeekPreviewReader<T>)
    private class Work<T>(val source: Binding<T>, val generation: Any, val bucket: Long) { lateinit var job: Job }
    private data class Retired<T>(val job: Job?, val reader: SeekPreviewReader<T>?)
}
