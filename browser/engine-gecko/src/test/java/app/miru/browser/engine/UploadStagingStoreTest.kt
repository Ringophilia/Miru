// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadStagingStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun equalBasenamesKeepIndependentContentsAndExactNames() = runBlocking {
        val store = store()
        store.createLease().use { lease ->
            val first = lease.copy("same.txt") { ByteArrayInputStream("first".toByteArray()) }
            val second = lease.copy("same.txt") { ByteArrayInputStream("second".toByteArray()) }
            assertEquals("same.txt", first.name)
            assertEquals("same.txt", second.name)
            assertNotEquals(first.parentFile, second.parentFile)
            assertEquals("first", first.readText())
            assertEquals("second", second.readText())
        }
    }

    @Test fun rejectsTraversalAndOversizeUtf8BeforeOpeningInput() = runBlocking {
        val lease = store().createLease()
        lease.use {
            for(name in listOf("", ".", "..", "../outside", "a/b", "a\\b", "bad\u0000name", "é".repeat(128))) {
                expect<IllegalArgumentException> { lease.copy(name) { error("Invalid name opened its input") } }
            }
        }
        assertTrue(stagingRoot().listFiles().orEmpty().isEmpty())
    }

    @Test fun acceptsExactUtf8ByteLimitWithoutRenaming() = runBlocking {
        store().createLease().use { lease ->
            val name = "é".repeat(125) + "a.txt"
            assertEquals(255, name.toByteArray(Charsets.UTF_8).size)
            assertEquals(name, lease.copy(name) { ByteArrayInputStream(byteArrayOf(1)) }.name)
        }
    }

    @Test fun readFailureClosesInputAndRemovesPartialFile() = runBlocking {
        val root = stagingRoot()
        val closed = AtomicInteger()
        UploadStagingStore(root).createLease().use { lease ->
            expect<IOException> { lease.copy("broken.bin") {
                object : InputStream() {
                    private var once = false
                    override fun read(): Int = throw IOException("Synthetic read failure")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if(once) throw IOException("Synthetic read failure")
                        once = true
                        buffer[offset] = 1
                        return 1
                    }
                    override fun close() { closed.incrementAndGet() }
                }
            } }
            assertEquals(1, closed.get())
            assertEquals(0, root.listFiles()!!.single().listFiles()!!.size)
        }
    }

    @Test fun openFailureRemovesItsEmptyPartialDirectory() = runBlocking {
        val root = stagingRoot()
        UploadStagingStore(root).createLease().use { lease ->
            expect<IOException> { lease.copy("unopened.bin") { throw IOException("Synthetic open failure") } }
            assertEquals(0, root.listFiles()!!.single().listFiles()!!.size)
        }
    }

    @Test fun cancellationClosesABlockedInputAndRemovesPartialFile() = runBlocking {
        val root = stagingRoot()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closed = AtomicInteger()
        UploadStagingStore(root).createLease().use { lease ->
            val job = launch(Dispatchers.IO) {
                lease.copy("blocked.bin") {
                    object : InputStream() {
                        override fun read(): Int {
                            entered.countDown()
                            if(!released.await(5, TimeUnit.SECONDS)) throw IOException("Input was not closed")
                            throw IOException("Synthetic closed input")
                        }
                        override fun close() { closed.incrementAndGet(); released.countDown() }
                    }
                }
            }
            assertTrue("Copy did not enter its input", entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            withTimeout(5_000) { job.join() }
            assertEquals(1, closed.get())
            assertEquals(0, root.listFiles()!!.single().listFiles()!!.size)
        }
    }

    @Test fun cancellationDuringOpenClosesItsStreamAsSoonAsOpenReturns() = runBlocking {
        val root = stagingRoot()
        val opening = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val closed = AtomicInteger()
        UploadStagingStore(root).createLease().use { lease ->
            val job = launch(Dispatchers.IO) {
                lease.copy("opening.bin") {
                    opening.countDown()
                    if(!releaseOpen.await(5, TimeUnit.SECONDS)) throw IOException("Synthetic open timed out")
                    object : ByteArrayInputStream(byteArrayOf(1)) {
                        override fun close() { closed.incrementAndGet(); super.close() }
                    }
                }
            }
            try {
                assertTrue("Copy did not open its input", opening.await(5, TimeUnit.SECONDS))
                job.cancel()
            } finally { releaseOpen.countDown() }
            withTimeout(5_000) { job.join() }
            assertEquals(1, closed.get())
            assertEquals(0, root.listFiles()!!.single().listFiles()!!.size)
        }
    }

    @Test fun closingLeaseClosesBlockedInputAndPreventsAnyLaterCopy() = runBlocking {
        val root = stagingRoot()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closed = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val lease = UploadStagingStore(root).createLease()
        val job = launch(Dispatchers.IO) {
            try {
                lease.copy("closing.bin") {
                    object : InputStream() {
                        override fun read(): Int {
                            entered.countDown()
                            if(!released.await(5, TimeUnit.SECONDS)) throw IOException("Input was not closed")
                            throw IOException("Synthetic closed input")
                        }
                        override fun close() { closed.incrementAndGet(); released.countDown() }
                    }
                }
            } catch(caught: Throwable) { failure.set(caught) }
        }
        try {
            assertTrue("Copy did not enter its input", entered.await(5, TimeUnit.SECONDS))
            lease.close()
            withTimeout(5_000) { job.join() }
            assertTrue(failure.get() is IOException)
            assertEquals(1, closed.get())
            assertTrue(root.listFiles().orEmpty().isEmpty())
            expect<IllegalStateException> { lease.copy("late.bin") { error("Closed lease opened input") } }
        } finally { lease.close() }
    }

    @Test fun budgetCountsRealBytesAcrossFilesAndFailureDoesNotConsumeBudget() = runBlocking {
        store().createLease(budgetBytes = 3).use { lease ->
            expect<IOException> { lease.copy("too-large.bin") { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) } }
            val first = lease.copy("one.bin") { ByteArrayInputStream(byteArrayOf(1, 2)) }
            val second = lease.copy("two.bin") { ByteArrayInputStream(byteArrayOf(3)) }
            assertArrayEquals(byteArrayOf(1, 2), first.readBytes())
            assertArrayEquals(byteArrayOf(3), second.readBytes())
            expect<IOException> { lease.copy("overflow.bin") { ByteArrayInputStream(byteArrayOf(4)) } }
            assertEquals(0L, lease.copy("empty.bin") { ByteArrayInputStream(byteArrayOf()) }.length())
        }
    }

    @Test fun rejectsNegativeBudget() {
        expect<IllegalArgumentException> { store().createLease(-1) }
    }

    @Test fun closingOneLeaseIsIdempotentAndKeepsOtherLeaseAndOutsideFile() = runBlocking {
        val root = stagingRoot()
        val outside = temporary.newFile("outside.txt").apply { writeText("outside") }
        val store = UploadStagingStore(root)
        val first = store.createLease()
        val second = store.createLease()
        try {
            val removed = first.copy("same.txt") { ByteArrayInputStream(byteArrayOf(1)) }
            val retained = second.copy("same.txt") { ByteArrayInputStream(byteArrayOf(2)) }
            first.close()
            first.close()
            assertFalse(removed.exists())
            assertTrue(retained.exists())
            assertEquals("outside", outside.readText())
            expect<IllegalStateException> { first.copy("late.txt") { error("Closed lease opened an input") } }
        } finally { first.close(); second.close() }
    }

    @Test fun orphanCleanupPreservesActiveLeaseUnmanagedFilesAndOutsideRoot() = runBlocking {
        val root = stagingRoot()
        val store = UploadStagingStore(root)
        store.createLease().use { active ->
            val retained = active.copy("active.txt") { ByteArrayInputStream(byteArrayOf(1)) }
            val orphan = File(root, "upload-lease-orphan").apply { mkdirs() }
            File(orphan, "partial.bin").writeBytes(byteArrayOf(2))
            val unmanaged = File(root, "unmanaged.txt").apply { writeText("keep") }
            val outside = temporary.newFolder("outside").resolve("keep.txt").apply { writeText("outside") }
            Files.createLink(File(orphan, "outside-alias.txt").toPath(), outside.toPath())
            store.clearOrphans()
            assertFalse(orphan.exists())
            assertTrue(retained.exists())
            assertEquals("keep", unmanaged.readText())
            assertEquals("outside", outside.readText())
        }
    }

    private fun stagingRoot() = File(temporary.root, "staging")
    private fun store() = UploadStagingStore(stagingRoot())

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try { block() }
        catch(failure: Throwable) {
            if(failure is T) return
            throw AssertionError("Unexpected failure type", failure)
        }
        fail("Expected ${T::class.java.simpleName}")
    }
}
