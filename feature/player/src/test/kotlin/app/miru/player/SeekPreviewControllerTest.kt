// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.miru.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class SeekPreviewControllerTest {
    @Test fun sameBucketMovesShareOneRequestAndCacheWhileTheDisplayedTimeStaysExact() = runTest {
        val reader = PendingReader()
        var factories = 0
        val controller = SeekPreviewController<String>(backgroundScope)
        controller.bind(1) { factories++; reader }
        controller.request(-50)
        runCurrent()
        assertEquals(listOf(0L), reader.positions)
        controller.request(9_999)
        controller.request(1)
        runCurrent()
        assertEquals(1, reader.pending.size)
        reader.pending[0].complete("zero", 0)
        runCurrent()
        assertEquals(1L, controller.state.value.targetMs)
        assertEquals("zero", controller.state.value.frame?.image)
        controller.request(9_999)
        runCurrent()
        assertEquals(9_999L, controller.state.value.targetMs)
        assertEquals("zero", controller.state.value.frame?.image)
        assertEquals(1, factories)
        assertEquals(1, reader.positions.size)
        controller.close()
    }

    @Test fun rapidMovesProduceOnlyTheActiveExtractionAndTheLatestPendingBucket() = runTest {
        val reader = PendingReader()
        val controller = SeekPreviewController<String>(backgroundScope)
        controller.bind(8) { reader }
        controller.request(1_111)
        runCurrent()
        for(index in 1..100) controller.request(index * 10_000L + 123)
        runCurrent()
        assertEquals(listOf(0L), reader.positions)
        reader.pending[0].complete("old", 0)
        runCurrent()
        assertEquals(listOf(0L, 1_000_000L), reader.positions)
        assertEquals(1_000_123L, controller.state.value.targetMs)
        assertNull(controller.state.value.frame)
        reader.pending[1].complete("latest", 1_000_000)
        runCurrent()
        assertEquals("latest", controller.state.value.frame?.image)
        assertEquals(0, reader.cancels)
        controller.close()
    }

    @Test fun oldBucketCompletionCanBeCachedButCannotReplaceTheLatestTargetOrCachedImage() = runTest {
        val reader = PendingReader()
        val controller = SeekPreviewController<String>(backgroundScope)
        controller.bind(1) { reader }
        controller.request(1_000)
        runCurrent()
        controller.request(25_000)
        reader.pending[0].complete("first", 0)
        runCurrent()
        assertEquals(25_000L, controller.state.value.targetMs)
        assertNull(controller.state.value.frame)
        assertEquals(listOf(0L, 20_000L), reader.positions)
        controller.request(3_500) // Revisit the completed bucket while another read runs.
        assertEquals("first", controller.state.value.frame?.image)
        reader.pending[1].complete("second", 20_000)
        runCurrent()
        assertEquals(3_500L, controller.state.value.targetMs)
        assertEquals("first", controller.state.value.frame?.image)
        assertEquals(2, reader.positions.size)
        controller.close()
    }

    @Test fun hideRejectsUncooperativeLateResultsButRetainsCompletedSourceCache() = runTest {
        val old = PendingReader()
        val fresh = PendingReader()
        var factories = 0
        val controller = SeekPreviewController<String>(backgroundScope)
        controller.bind(1) { if(factories++ == 0) old else fresh }
        controller.request(1_000)
        runCurrent()
        old.pending[0].complete("cached", 0)
        runCurrent()
        controller.request(11_000)
        runCurrent()
        controller.hide()
        assertEquals(SeekPreviewState<String>(), controller.state.value)
        assertEquals(1, old.cancels)
        assertEquals(1, old.closes)
        old.pending[1].complete("late-hidden", 10_000)
        runCurrent()
        assertEquals(SeekPreviewState<String>(), controller.state.value)
        controller.request(17_000)
        runCurrent()
        assertEquals(2, factories)
        assertEquals(listOf(10_000L), fresh.positions)
        assertNull(controller.state.value.frame)
        fresh.pending[0].complete("fresh", 10_000)
        runCurrent()
        controller.hide()
        controller.request(2_000)
        runCurrent()
        assertEquals("cached", controller.state.value.frame?.image)
        assertEquals(2, factories) // A cache hit does not need a new decoder.
        controller.close()
    }

    @Test fun bindRejectsLateResultsAndClearsCacheEvenWhenARevisionNumberIsReused() = runTest {
        val old = PendingReader()
        val fresh = PendingReader()
        val rebound = PendingReader()
        val controller = SeekPreviewController<String>(backgroundScope)
        controller.bind(4) { old }
        controller.request(1_000)
        runCurrent()
        controller.bind(5) { fresh }
        controller.request(1_000)
        runCurrent()
        old.pending[0].complete("old-source", 0)
        runCurrent()
        assertNull(controller.state.value.frame)
        fresh.pending[0].complete("new-source", 0)
        runCurrent()
        assertEquals("new-source", controller.state.value.frame?.image)
        controller.bind(5) { rebound }
        assertEquals(SeekPreviewState<String>(), controller.state.value)
        controller.request(1_000)
        runCurrent()
        assertNull(controller.state.value.frame)
        assertEquals(listOf(0L), rebound.positions)
        rebound.pending[0].complete("rebound", 0)
        runCurrent()
        assertEquals("rebound", controller.state.value.frame?.image)
        assertEquals(1, old.cancels)
        assertEquals(1, old.closes)
        assertEquals(1, fresh.cancels)
        assertEquals(1, fresh.closes)
        controller.close()
    }

    @Test fun closePermanentlyRejectsLateFramesBindsAndRequests() = runTest {
        val old = PendingReader()
        var newFactories = 0
        val controller = SeekPreviewController<String>(backgroundScope)
        controller.bind(1) { old }
        controller.request(1_000)
        runCurrent()
        controller.close()
        old.pending[0].complete("late-closed", 0)
        controller.bind(2) { newFactories++; PendingReader() }
        controller.request(100_000)
        controller.hide()
        controller.close()
        runCurrent()
        assertEquals(SeekPreviewState<String>(), controller.state.value)
        assertEquals(0, newFactories)
        assertEquals(1, old.cancels)
        assertEquals(1, old.closes)
    }

    @Test fun frameFailureShowsOnlyTimeAndTheSameTargetCanRecoverWithANewReader() = runTest {
        val broken = PendingReader()
        val fresh = PendingReader()
        val errors = mutableListOf<Throwable>()
        var factories = 0
        val controller = SeekPreviewController<String>(backgroundScope, errors::add)
        controller.bind(2) { if(factories++ == 0) broken else fresh }
        controller.request(12_345)
        runCurrent()
        val failure = IllegalStateException("decoder failed")
        broken.pending[0].fail(failure)
        runCurrent()
        assertEquals(SeekPreviewState<String>(12_345), controller.state.value)
        assertEquals(listOf(failure), errors)
        assertEquals(1, factories) // No automatic failure retry loop.
        assertEquals(1, broken.cancels)
        assertEquals(1, broken.closes)
        controller.request(12_345)
        runCurrent()
        fresh.pending[0].complete("recovered", 10_000)
        runCurrent()
        assertEquals("recovered", controller.state.value.frame?.image)
        assertEquals(2, factories)
        controller.close()
    }

    @Test fun factoryFailureIsTimeOnlyAndDoesNotPoisonASubsequentRequest() = runTest {
        val reader = PendingReader()
        val errors = mutableListOf<Throwable>()
        val failure = IllegalArgumentException("factory failed")
        var attempts = 0
        val controller = SeekPreviewController<String>(backgroundScope, errors::add)
        controller.bind(1) { if(attempts++ == 0) throw failure else reader }
        controller.request(30_001)
        runCurrent()
        assertEquals(SeekPreviewState<String>(30_001), controller.state.value)
        assertEquals(listOf(failure), errors)
        controller.request(30_001)
        runCurrent()
        reader.pending[0].complete("recovered", 30_000)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals("recovered", controller.state.value.frame?.image)
        controller.close()
    }

    @Test fun failedOlderBucketStillStartsOnlyTheLatestTargetAndAnObserverCannotStrandIt() = runTest {
        val broken = PendingReader()
        val fresh = PendingReader()
        var factories = 0
        val controller = SeekPreviewController<String>(backgroundScope) { error("observer failed") }
        controller.bind(1) { if(factories++ == 0) broken else fresh }
        controller.request(1_000)
        runCurrent()
        controller.request(11_000)
        controller.request(41_234)
        broken.pending[0].fail(IllegalStateException("read failed"))
        runCurrent()
        assertEquals(SeekPreviewState<String>(41_234), controller.state.value)
        assertEquals(listOf(40_000L), fresh.positions)
        fresh.pending[0].complete("latest", 40_000)
        runCurrent()
        assertEquals("latest", controller.state.value.frame?.image)
        assertEquals(2, factories)
        controller.close()
    }

    @Test fun failureObserverCanRebindWithoutStartingTheSupersededPendingRead() = runTest {
        val broken = PendingReader()
        val fresh = PendingReader()
        lateinit var controller: SeekPreviewController<String>
        controller = SeekPreviewController(backgroundScope) {
            controller.bind(2) { fresh }
            controller.request(91_234)
        }
        controller.bind(1) { broken }
        controller.request(1_000)
        runCurrent()
        controller.request(21_000)
        broken.pending[0].fail(IllegalStateException("needs new source"))
        runCurrent()
        assertEquals(listOf(0L), broken.positions)
        assertEquals(listOf(90_000L), fresh.positions)
        assertEquals(SeekPreviewState<String>(91_234), controller.state.value)
        fresh.pending[0].complete("new-source", 90_000)
        runCurrent()
        assertEquals("new-source", controller.state.value.frame?.image)
        controller.close()
    }

    @Test fun obsoleteReaderFailureDoesNotReportAgainstTheNewSource() = runTest {
        val old = PendingReader()
        val fresh = PendingReader()
        val errors = mutableListOf<Throwable>()
        val controller = SeekPreviewController<String>(backgroundScope, errors::add)
        controller.bind(1) { old }
        controller.request(1_000)
        runCurrent()
        controller.bind(2) { fresh }
        controller.request(2_000)
        runCurrent()
        old.pending[0].fail(IllegalStateException("obsolete"))
        runCurrent()
        assertTrue(errors.isEmpty())
        assertNull(controller.state.value.frame)
        fresh.pending[0].complete("current", 0)
        runCurrent()
        assertEquals("current", controller.state.value.frame?.image)
        controller.close()
    }

    @Test fun aFactoryThatReturnsAfterHideMustCloseItsReaderWithoutExtracting() = runTest {
        val abandoned = PendingReader()
        val fresh = PendingReader()
        var awaiting: Continuation<SeekPreviewReader<String>>? = null
        var attempts = 0
        val controller = SeekPreviewController<String>(backgroundScope)
        controller.bind(1) {
            if(attempts++ == 0) suspendCoroutine { awaiting = it } else fresh
        }
        controller.request(1_000)
        runCurrent()
        assertNotNull(awaiting)
        controller.hide()
        awaiting!!.resume(abandoned)
        runCurrent()
        assertTrue(abandoned.positions.isEmpty())
        assertEquals(1, abandoned.cancels)
        assertEquals(1, abandoned.closes)
        assertEquals(SeekPreviewState<String>(), controller.state.value)
        controller.request(1_000)
        runCurrent()
        fresh.pending[0].complete("fresh", 0)
        runCurrent()
        assertEquals("fresh", controller.state.value.frame?.image)
        controller.close()
    }

    @Test fun lateFactoryOnARealDispatcherCannotReplaceAnAlreadyDisplayedNewSource() = runBlocking {
        withTimeout(5_000) {
            val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val factory = CompletableDeferred<Continuation<SeekPreviewReader<String>>>()
            val abandoned = AsyncReader()
            val fresh = AsyncReader()
            val controller = SeekPreviewController<String>(scope)
            try {
                controller.bind(1) { suspendCoroutine { factory.complete(it) } }
                controller.request(1_000)
                val lateFactory = factory.await()
                controller.bind(2) { fresh }
                controller.request(32_345)
                val current = fresh.pending.await()
                assertEquals(30_000L, current.positionMs)
                current.complete("new-source", 30_000)
                controller.state.first { it.frame?.image == "new-source" }

                // Resume the cancelled old factory from another thread after the
                // replacement source has already produced a visible frame.
                lateFactory.resume(abandoned)
                abandoned.closed.await()
                assertFalse(abandoned.pending.isCompleted)
                assertEquals(SeekPreviewState(32_345, SeekPreviewFrame("new-source", 30_000)), controller.state.value)
            } finally {
                controller.close()
                scope.cancel()
                dispatcher.close()
            }
        }
    }

    @Test fun failureDeliveryAndConcurrentRebindHaveASingleGenerationOrder() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val binder = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val reporting = CountDownLatch(1)
        val finishReporting = CountDownLatch(1)
        val bindingStarted = CountDownLatch(1)
        val bindingFinished = CountDownLatch(1)
        val controller = SeekPreviewController<String>(scope) {
            reporting.countDown()
            check(finishReporting.await(5, TimeUnit.SECONDS))
        }
        try {
            controller.bind(1) { throw IllegalStateException("factory failed") }
            controller.request(1_000)
            assertTrue(reporting.await(5, TimeUnit.SECONDS))
            val rebind = binder.submit {
                bindingStarted.countDown()
                controller.bind(2) { AsyncReader() }
                bindingFinished.countDown()
            }
            assertTrue(bindingStarted.await(5, TimeUnit.SECONDS))
            // Once an error is being delivered, a new generation cannot finish
            // binding while that error is still being acted on by the caller.
            assertFalse(bindingFinished.await(100, TimeUnit.MILLISECONDS))
            finishReporting.countDown()
            rebind.get(5, TimeUnit.SECONDS)
            assertEquals(SeekPreviewState<String>(), controller.state.value)
        } finally {
            finishReporting.countDown()
            controller.close()
            scope.cancel()
            dispatcher.close()
            binder.shutdownNow()
        }
    }

    private class AsyncReader : SeekPreviewReader<String> {
        val pending = CompletableDeferred<Pending>()
        val closed = CompletableDeferred<Unit>()
        override suspend fun frame(positionMs: Long): SeekPreviewFrame<String> = suspendCoroutine {
            pending.complete(Pending(positionMs, it))
        }
        override fun cancel() = Unit
        override fun close() { closed.complete(Unit) }
    }

    /** Deliberately ignores coroutine cancellation to exercise actual late continuations. */
    private class PendingReader : SeekPreviewReader<String> {
        val pending = mutableListOf<Pending>()
        val positions get() = pending.map { it.positionMs }
        var cancels = 0
        var closes = 0
        override suspend fun frame(positionMs: Long): SeekPreviewFrame<String> = suspendCoroutine {
            pending += Pending(positionMs, it)
        }
        override fun cancel() { cancels++ }
        override fun close() { closes++ }
    }

    private class Pending(val positionMs: Long, private val continuation: Continuation<SeekPreviewFrame<String>>) {
        fun complete(image: String, presentationTimeMs: Long) = continuation.resume(SeekPreviewFrame(image, presentationTimeMs))
        fun fail(failure: Throwable) = continuation.resumeWithException(failure)
    }
}
