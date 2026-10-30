// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreparedUploadFolderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val staging get() = temporary.root.toPath().toRealPath()
    private fun root() = Files.createDirectory(staging.resolve("Root"))
    private fun entry(root: java.nio.file.Path, directory: String = "id", name: String = "LICENSE"): UploadFolderPayload {
        val target = Files.createDirectory(root.resolve(directory)).resolve(name)
        Files.write(target, byteArrayOf(1, 2, 3))
        return UploadFolderPayload(target.toString(), "/Root/$name", "text/plain", 0L, 3L)
    }

    @Test fun logicalDuplicatePathsArePreservedWhilePhysicalPayloadsMustBeDistinct() {
        val root = root()
        val first = entry(root, "one")
        val second = entry(root, "two")
        val folder = PreparedUploadFolder.validate(staging, root, listOf(first, second), 2, 6)
        assertEquals(2, folder.payloads.size)
        assertEquals(listOf("/Root/LICENSE", "/Root/LICENSE"), folder.payloads.map { it.logicalRelativePath })
        reject { PreparedUploadFolder.validate(staging, root, listOf(first, first), 2, 6) }
    }

    @Test fun absentExtraAndUnlistedFilesCannotBecomeAPartialManifest() {
        val root = root()
        val entry = entry(root)
        reject { PreparedUploadFolder.validate(staging, root, emptyList(), 1, 3) }
        reject { PreparedUploadFolder.validate(staging, root, listOf(entry), 2, 3) }
        reject { PreparedUploadFolder.validate(staging, root, emptyList(), 0, 0) }
        val folder = PreparedUploadFolder.validate(staging, root, listOf(entry), 1, 3)
        val unexpected = File(root.toFile(), "unexpected").apply { writeText("extra") }
        reject { folder.validateFor(staging) }
        assertTrue(unexpected.delete())
        assertTrue(File(entry.filePath).delete())
        reject { folder.validateFor(staging) }
    }

    @Test fun outsideRootAndSamePrefixSiblingDoNotReceiveTheDirectoryCapability() {
        val root = root()
        val entry = entry(root)
        val sibling = Files.createDirectory(staging.resolve("Root-sibling"))
        val outside = Files.write(sibling.resolve("LICENSE"), byteArrayOf(1, 2, 3))
        reject { PreparedUploadFolder.validate(staging, root, listOf(entry.copy(filePath = outside.toString())), 1, 3) }
        reject { PreparedUploadFolder.validate(sibling, root, listOf(entry), 1, 3) }
    }

    @Test fun logicalPathRequiresTheLeadingSlashRootAndActualBasename() {
        val root = root()
        val entry = entry(root)
        for(path in listOf("Root/LICENSE", "/Other/LICENSE", "/Root/../LICENSE", "/Root//LICENSE",
            "/Root/renamed", "/Root/dir\\name/LICENSE", "/Root/LICENSE/")) {
            reject { PreparedUploadFolder.validate(staging, root, listOf(entry.copy(logicalRelativePath = path)), 1, 3) }
        }
    }

    @Test fun fileSizeAndTotalBytesMustMatchTheCompletedPayload() {
        val root = root()
        val entry = entry(root)
        reject { PreparedUploadFolder.validate(staging, root, listOf(entry.copy(size = 2)), 1, 3) }
        reject { PreparedUploadFolder.validate(staging, root, listOf(entry), 1, 4) }
        val folder = PreparedUploadFolder.validate(staging, root, listOf(entry), 1, 3)
        File(entry.filePath).appendBytes(byteArrayOf(4))
        reject { folder.validateFor(staging) }
    }

    @Test fun linkedPayloadAndLinkedDirectoryAreRejectedWithoutReadingTheirTargets() {
        val root = root()
        val outside = File(temporary.root, "outside.txt").apply { writeText("private") }.toPath()
        val linked = root.resolve("outside.txt")
        try { Files.createSymbolicLink(linked, outside) }
        catch(unavailable: Exception) { assumeNoException("Host cannot create test symlinks", unavailable); return }
        reject { PreparedUploadFolder.validate(staging, root, listOf(
            UploadFolderPayload(linked.toString(), "/Root/outside.txt", "text/plain", null, 7)), 1, 7) }
        Files.delete(linked)
        val externalDirectory = Files.createDirectory(staging.resolve("external"))
        Files.write(externalDirectory.resolve("LICENSE"), byteArrayOf(1))
        val linkDirectory = Files.createSymbolicLink(root.resolve("linked"), externalDirectory)
        reject { PreparedUploadFolder.validate(staging, root, listOf(
            UploadFolderPayload(linkDirectory.resolve("LICENSE").toString(), "/Root/LICENSE", "text/plain", null, 1)), 1, 1) }
    }

    @Test fun emptyPublicFallbackRequiresAnActuallyEmptyRoot() {
        val root = root()
        val folder = PreparedUploadFolder.validate(staging, root, emptyList(), 0, 0)
        assertTrue(folder.payloads.isEmpty())
        Files.createDirectory(root.resolve("private-id"))
        reject { folder.validateFor(staging) }
    }

    @Test fun unknownZeroAndPreEpochTimeAreDifferentAndUnsafeDoubleValuesFail() {
        val root = root()
        val entry = entry(root)
        for(time in listOf(null, 0L, -1L, 1_700_000_000_123L)) {
            val folder = PreparedUploadFolder.validate(staging, root, listOf(entry.copy(lastModified = time)), 1, 3)
            assertEquals(time, folder.payloads.single().lastModified)
        }
        for(time in listOf(Long.MAX_VALUE, Long.MIN_VALUE, 9_007_199_254_740_992L)) {
            reject { PreparedUploadFolder.validate(staging, root, listOf(entry.copy(lastModified = time)), 1, 3) }
        }
    }

    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected complete manifest rejection") }
        catch(expected: IOException) { }
    }
}
