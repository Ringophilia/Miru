// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.common

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class DownloadConcurrencyTest {
    @Test fun mixedBackendWorkersNeverExceedTheCombinedTransferLimit() {
        val limiter = TransferLimiter(2)
        val workers = Executors.newFixedThreadPool(6)
        val ready = CountDownLatch(6)
        val start = CountDownLatch(1)
        val checked = CountDownLatch(6)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(2)
        val current = AtomicInteger()
        val maximum = AtomicInteger()
        try {
            // Even entries model ordinary files; odd entries model adaptive Media3 workers.
            val tasks = (0 until 6).map { workers.submit {
                ready.countDown(); assertTrue(start.await(2, TimeUnit.SECONDS))
                val firstCheck = AtomicBoolean(true)
                limiter.acquire { if(firstCheck.getAndSet(false)) checked.countDown(); false }.use {
                    val count = current.incrementAndGet()
                    maximum.accumulateAndGet(count, ::maxOf)
                    entered.countDown()
                    try { assertTrue(release.await(3, TimeUnit.SECONDS)) } finally { current.decrementAndGet() }
                }
            } }
            assertTrue(ready.await(2, TimeUnit.SECONDS)); start.countDown()
            assertTrue(checked.await(2, TimeUnit.SECONDS)); assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(2, current.get())
            release.countDown()
            tasks.forEach { it.get(3, TimeUnit.SECONDS) }
            assertEquals(2, maximum.get())
            assertEquals(0, current.get())
        } finally { start.countDown(); release.countDown(); workers.shutdownNow() }
    }

    @Test fun increasingLimitAdmitsWaitersAndLoweringItPreservesExistingTransfers() {
        val limiter = TransferLimiter(1)
        val workers = Executors.newFixedThreadPool(3)
        val leases = mutableListOf<TransferLimiter.Lease>()
        try {
            val original = limiter.acquire().also(leases::add)
            val checked = CountDownLatch(2)
            fun waiting() = workers.submit<TransferLimiter.Lease> {
                val firstCheck = AtomicBoolean(true)
                limiter.acquire { if(firstCheck.getAndSet(false)) checked.countDown(); false }
            }
            val a = waiting(); val b = waiting()
            assertTrue(checked.await(2, TimeUnit.SECONDS))
            limiter.setLimit(3)
            val second = a.get(2, TimeUnit.SECONDS).also(leases::add)
            val third = b.get(2, TimeUnit.SECONDS).also(leases::add)
            limiter.setLimit(1)
            val nextChecked = CountDownLatch(1)
            val next = workers.submit<TransferLimiter.Lease> { limiter.acquire { nextChecked.countDown(); false } }
            assertTrue(nextChecked.await(2, TimeUnit.SECONDS))
            assertWaiting(next)
            original.close(); assertWaiting(next) // Two active transfers remain above the new limit.
            second.close(); assertWaiting(next) // One active transfer still owns the only slot.
            third.close()
            next.get(2, TimeUnit.SECONDS).also(leases::add).close()
        } finally { workers.shutdownNow(); leases.forEach(AutoCloseable::close) }
    }

    @Test fun cancellingAWaiterDoesNotConsumeOrLeakAPermit() {
        val limiter = TransferLimiter(1)
        val workers = Executors.newSingleThreadExecutor()
        val original = limiter.acquire()
        val cancelled = AtomicBoolean(false)
        val checked = CountDownLatch(1)
        try {
            val waiting = workers.submit<Boolean> {
                try {
                    limiter.acquire { checked.countDown(); cancelled.get() }.use { false }
                } catch(_: InterruptedException) { true }
            }
            assertTrue(checked.await(2, TimeUnit.SECONDS)); cancelled.set(true)
            assertTrue(waiting.get(2, TimeUnit.SECONDS))
            original.close()
            workers.submit<Boolean> { limiter.acquire().use { true } }.get(2, TimeUnit.SECONDS).also(::assertTrue)
            assertThrows(InterruptedException::class.java) { limiter.acquire { true } }
        } finally { original.close(); workers.shutdownNow() }
    }

    @Test fun threadInterruptionStopsAWaiterAndRejectsAnAlreadyInterruptedCaller() {
        val limiter = TransferLimiter(1)
        val original = limiter.acquire()
        val checked = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val waiter = Thread {
            try { limiter.acquire { checked.countDown(); false }.close() }
            catch(_: InterruptedException) { interrupted.set(true) }
            finally { finished.countDown() }
        }
        try {
            waiter.start(); assertTrue(checked.await(2, TimeUnit.SECONDS))
            waiter.interrupt(); assertTrue(finished.await(2, TimeUnit.SECONDS)); assertTrue(interrupted.get())
            original.close()
            val immediate = Executors.newSingleThreadExecutor()
            try {
                assertTrue(immediate.submit<Boolean> {
                    Thread.currentThread().interrupt()
                    try { limiter.acquire().use { false } } catch(_: InterruptedException) { true }
                }.get(2, TimeUnit.SECONDS))
            } finally { immediate.shutdownNow() }
        } finally { original.close(); waiter.interrupt(); waiter.join(2_000) }
    }

    @Test fun closingAnOldLeaseAgainCannotReleaseTheNextTransfersSlot() {
        val limiter = TransferLimiter(1)
        val worker = Executors.newSingleThreadExecutor()
        val old = limiter.acquire()
        old.close()
        val current = limiter.acquire()
        try {
            repeat(10) { old.close() }
            val checked = CountDownLatch(1)
            val next = worker.submit<TransferLimiter.Lease> { limiter.acquire { checked.countDown(); false } }
            assertTrue(checked.await(2, TimeUnit.SECONDS)); assertWaiting(next)
            current.close()
            next.get(2, TimeUnit.SECONDS).close()
        } finally { current.close(); old.close(); worker.shutdownNow() }
    }

    private fun assertWaiting(future: Future<*>) {
        assertThrows(TimeoutException::class.java) { future.get(100, TimeUnit.MILLISECONDS) }
    }
}
