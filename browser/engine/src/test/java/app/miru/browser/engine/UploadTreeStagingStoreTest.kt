// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadTreeStagingStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun staging() = File(temporary.root, "tree-staging")
    private fun store() = UploadTreeStagingStore(staging())
    private fun directory(id: String, name: String = id) = UploadTreeEntry(id, name, true)
    private fun file(id: String, name: String = id) = UploadTreeEntry(id, name, false, 1_700_000_000_000)
    private class Source(val top: UploadTreeEntry, val tree: Map<String, List<UploadTreeEntry>>,
        val bytes: Map<String, ByteArray>) : UploadTreeSource {
        override fun root() = top
        override fun children(id: String, maximum: Int) = tree[id].orEmpty()
        override fun open(id: String): InputStream = ByteArrayInputStream(checkNotNull(bytes[id]))
    }

    @Test fun deepHierarchyAndEqualBasenamesKeepRootPathsAndEveryByte() = runBlocking {
        val tree = mutableMapOf<String, List<UploadTreeEntry>>()
        tree["root"] = listOf(directory("left"), directory("right"), directory("d1"), directory("空目录"))
        tree["left"] = listOf(file("a", "same.txt"))
        tree["right"] = listOf(file("b", "same.txt"))
        for(depth in 1..8) tree["d$depth"] = listOf(directory("d${depth + 1}"))
        tree["d9"] = listOf(file("deep", "深处.bin"))
        store().createLease(100).use { lease ->
            val root = lease.copy(Source(directory("root", "真实根名"), tree,
                mapOf("a" to byteArrayOf(1), "b" to byteArrayOf(2, 3), "deep" to byteArrayOf(0, -1, 4))))
            assertEquals("真实根名", root.name)
            assertArrayEquals(byteArrayOf(1), File(root, "left/same.txt").readBytes())
            assertArrayEquals(byteArrayOf(2, 3), File(root, "right/same.txt").readBytes())
            assertArrayEquals(byteArrayOf(0, -1, 4), File(root, "d1/d2/d3/d4/d5/d6/d7/d8/d9/深处.bin").readBytes())
            assertTrue(File(root, "空目录").isDirectory)
            assertEquals(3, root.walkTopDown().count { it.isFile })
            assertEquals(1_700_000_000_000, File(root, "left/same.txt").lastModified())
        }
        assertTrue(staging().listFiles()!!.isEmpty())
    }

    @Test fun emptyRootAndZeroByteFileAreRealFilesAndDirectories() = runBlocking {
        store().createLease(0).use { lease ->
            val root = lease.copy(Source(directory("root", "EmptyRoot"), emptyMap(), emptyMap()))
            assertTrue(root.isDirectory)
            assertTrue(root.listFiles()!!.isEmpty())
        }
        store().createLease(0).use { lease ->
            val root = lease.copy(Source(directory("root"), mapOf("root" to listOf(file("zero.txt"))),
                mapOf("zero.txt" to byteArrayOf())))
            assertTrue(File(root, "zero.txt").isFile)
            assertEquals(0L, File(root, "zero.txt").length())
        }
    }

    @Test fun actualBytesAggregateAcrossFilesAndOverBudgetLeavesNoPartialTree() = runBlocking {
        val source = Source(directory("root"), mapOf("root" to listOf(file("a"), file("b"))),
            mapOf("a" to ByteArray(4), "b" to ByteArray(5)))
        val lease = store().createLease(8)
        expectFailure { lease.copy(source) }
        assertTrue(staging().listFiles()!!.isEmpty())
        lease.close()
    }

    @Test fun duplicateSiblingNamesNeverOverwriteOrReturnAPartialFolder() = runBlocking {
        val source = Source(directory("root"), mapOf("root" to listOf(file("a", "same.txt"), file("b", "same.txt"))),
            mapOf("a" to byteArrayOf(1), "b" to byteArrayOf(2)))
        expectFailure { store().createLease(100).copy(source) }
        assertTrue(staging().listFiles()!!.isEmpty())
    }

    @Test fun directoryCycleFailsButAFileReferencedFromDifferentPathsIsNotDropped() = runBlocking {
        expectFailure { store().createLease(100).copy(Source(directory("root"),
            mapOf("root" to listOf(directory("root", "again"))), emptyMap())) }
        assertTrue(staging().listFiles()!!.isEmpty())
        val source = Source(directory("root"), mapOf("root" to listOf(directory("a"), directory("b")),
            "a" to listOf(file("shared", "file.txt")), "b" to listOf(file("shared", "file.txt"))), mapOf("shared" to byteArrayOf(7)))
        store().createLease(100).use { lease ->
            val root = lease.copy(source)
            assertArrayEquals(byteArrayOf(7), File(root, "a/file.txt").readBytes())
            assertArrayEquals(byteArrayOf(7), File(root, "b/file.txt").readBytes())
        }
    }

    @Test fun fileAndDirectoryBudgetsRejectTheWholeResult() = runBlocking {
        val source = Source(directory("root"), mapOf("root" to listOf(directory("empty"), file("a"), file("b"))),
            mapOf("a" to byteArrayOf(1), "b" to byteArrayOf(2)))
        expectFailure { store().createLease(100, maximumFiles = 1).copy(source) }
        assertTrue(staging().listFiles()!!.isEmpty())
        expectFailure { store().createLease(100, maximumEntries = 3).copy(source) }
        assertTrue(staging().listFiles()!!.isEmpty())
    }

    @Test fun invalidNamesCannotEscapeTheLeaseOrOpenAProviderFile() = runBlocking {
        for(name in listOf("..", "a/b", "a\\b", "bad\u0000name", "é".repeat(128))) {
            expectFailure { store().createLease(100).copy(Source(directory("root"),
                mapOf("root" to listOf(file("invalid", name))), emptyMap())) }
            assertTrue(staging().listFiles()!!.isEmpty())
        }
        assertFalse(File(temporary.root, "outside").exists())
    }

    @Test fun providerReadFailureClosesTheStreamAndDeletesPreviouslyCopiedFiles() = runBlocking {
        val closed = AtomicInteger()
        val source = object : UploadTreeSource {
            override fun root() = directory("root")
            override fun children(id: String, maximum: Int) = listOf(file("good"), file("broken"))
            override fun open(id: String): InputStream = if(id == "good") ByteArrayInputStream(byteArrayOf(1)) else object : InputStream() {
                override fun read(): Int = throw IOException("Synthetic read failure")
                override fun close() { closed.incrementAndGet() }
            }
        }
        expectFailure { store().createLease(100).copy(source) }
        assertEquals(1, closed.get())
        assertTrue(staging().listFiles()!!.isEmpty())
    }

    @Test fun cancellationClosesABlockedReadWithoutWaitingForTheCopyLock() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicInteger()
        val source = object : UploadTreeSource {
            override fun root() = directory("root")
            override fun children(id: String, maximum: Int) = listOf(file("blocked"))
            override fun open(id: String) = object : InputStream() {
                override fun read(): Int {
                    entered.countDown()
                    if(!release.await(5, TimeUnit.SECONDS)) throw IOException("Read was not cancelled")
                    throw IOException("Synthetic closed stream")
                }
                override fun close() { closed.incrementAndGet(); release.countDown() }
            }
        }
        val lease = store().createLease(100)
        val job = launch(Dispatchers.IO) { lease.copy(source) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancel()
        job.join()
        lease.close()
        assertEquals(1, closed.get())
        assertTrue(staging().listFiles()!!.isEmpty())
    }

    @Test fun orphanSweepPreservesActiveTreesAndTheSelectedRootRemainsUntilClose() = runBlocking {
        val store = store()
        val lease = store.createLease(100)
        val root = lease.copy(Source(directory("root"), mapOf("root" to listOf(file("a"))), mapOf("a" to byteArrayOf(1))))
        val orphan = File(staging(), "folder-lease-orphan").apply { mkdir() }
        File(orphan, "partial").writeText("leftover")
        store.clearOrphans()
        assertFalse(orphan.exists())
        assertArrayEquals(byteArrayOf(1), File(root, "a").readBytes())
        lease.close()
        assertFalse(root.exists())
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected complete folder failure") }
        catch(expected: IOException) { }
    }
}
