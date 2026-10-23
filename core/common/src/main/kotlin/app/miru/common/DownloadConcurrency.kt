// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.common

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Limits whole file/adaptive transfers, independently of their segment worker counts. */
class TransferLimiter(initialLimit: Int = 3) {
    private val lock = ReentrantLock(true)
    private val changed = lock.newCondition()
    private var limit = initialLimit.coerceIn(1, 5)
    private var active = 0

    /** Existing transfers keep their leases; reductions only delay new admissions. */
    fun setLimit(value: Int) = lock.withLock {
        limit = value.coerceIn(1, 5)
        changed.signalAll()
    }

    /**
     * Blocks until a slot is available. The callback must be fast and non-blocking.
     * Cancellation and interruption are checked even if a slot is immediately free.
     */
    @Throws(InterruptedException::class)
    fun acquire(cancelled: () -> Boolean = { false }): Lease {
        lock.lockInterruptibly()
        try {
            while(true) {
                if(Thread.interrupted() || cancelled()) throw InterruptedException("Transfer cancelled")
                if(active < limit) {
                    active++
                    return Lease(this)
                }
                changed.await(100, TimeUnit.MILLISECONDS)
            }
        } finally { lock.unlock() }
    }

    private fun release() = lock.withLock {
        check(active > 0)
        active--
        changed.signalAll()
    }

    /** A lease may be closed from another thread and may safely be closed more than once. */
    class Lease internal constructor(private val owner: TransferLimiter) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() { if(closed.compareAndSet(false, true)) owner.release() }
    }
}

/** Both download backends share this process-wide budget. */
object DownloadConcurrency {
    val transfers = TransferLimiter()
}
