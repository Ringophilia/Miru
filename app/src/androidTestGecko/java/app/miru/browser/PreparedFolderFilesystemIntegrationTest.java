// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.junit.Assert.*;

import android.os.Looper;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.miru.browser.engine.PreparedUploadFolder;
import app.miru.browser.engine.UploadFolderPayload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual Android filesystem checks of the production validator; no prompt or UI upload claim. */
@RunWith(AndroidJUnit4.class)
public final class PreparedFolderFilesystemIntegrationTest {
    private static final String PREFIX = "prepared-folder-filesystem-";
    private static final byte[] NORMAL_BYTES = "ordinary control bytes".getBytes(StandardCharsets.UTF_8);
    // Equal lengths keep final snapshot rejection independent of payload-size changes.
    private static final byte[] OUTSIDE_BYTES = "outside fixture bytes!".getBytes(StandardCharsets.UTF_8);
    private Path cache;
    private Path fixture;
    private String fixtureName;
    private Path staging;
    private Path root;
    private Path outsideDirectory;
    private Path outsideFile;

    @Before
    public void createDedicatedFixture() throws Exception {
        assertNotSame("Filesystem validation must run off Main", Looper.getMainLooper(), Looper.myLooper());
        assertEquals(NORMAL_BYTES.length, OUTSIDE_BYTES.length);
        cache = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getCacheDir().toPath().toRealPath();
        fixtureName = PREFIX + UUID.randomUUID();
        fixture = Files.createDirectory(cache.resolve(fixtureName));
        requireFixtureBoundary();
        staging = Files.createDirectory(fixture.resolve("staging"));
        root = Files.createDirectory(staging.resolve("Root"));
        // "Outside" means outside the selected Root, wholly inside this disposable fixture.
        outsideDirectory = Files.createDirectory(fixture.resolve("outside"));
        outsideFile = Files.write(outsideDirectory.resolve("LICENSE"), OUTSIDE_BYTES);
        assertOutsideFixtureIntact();
    }

    @Test
    public void payloadSymlinkIsRejectedAndItsFixtureTargetIsUnchanged() throws Exception {
        Path directory = Files.createDirectory(root.resolve("id"));
        Path payload = Files.write(directory.resolve("LICENSE"), NORMAL_BYTES);
        PreparedUploadFolder snapshot = assertNormalControlAccepted(payload);
        Files.delete(payload);

        // Android's actual symlink syscall must succeed. Unsupported links fail, never skip.
        Os.symlink(outsideFile.toString(), payload.toString());
        assertTrue(Files.isSymbolicLink(payload));
        assertEquals(outsideFile, Files.readSymbolicLink(payload));
        expectRejected(payload, OUTSIDE_BYTES.length);
        expectSnapshotRejected(snapshot);
        assertTrue("Validation must leave the link intact", Files.isSymbolicLink(payload));
        assertOutsideFixtureIntact();

        Files.delete(payload); // Deletes this link, without touching its target.
        Files.write(payload, NORMAL_BYTES);
        assertNormalControlAccepted(payload);
        assertOutsideFixtureIntact();
    }

    @Test
    public void ancestorDirectorySymlinkIsRejectedAndItsFixtureTargetIsUnchanged() throws Exception {
        Path directory = Files.createDirectory(root.resolve("id"));
        Path payload = Files.write(directory.resolve("LICENSE"), NORMAL_BYTES);
        PreparedUploadFolder snapshot = assertNormalControlAccepted(payload);
        Files.delete(payload);
        Files.delete(directory);

        Files.createSymbolicLink(directory, outsideDirectory);
        assertTrue(Files.isSymbolicLink(directory));
        assertEquals(outsideDirectory, Files.readSymbolicLink(directory));
        // The leaf is not itself a link: only its ancestor redirects outside the selected Root.
        assertFalse(Files.isSymbolicLink(payload));
        expectRejected(payload, OUTSIDE_BYTES.length);
        expectSnapshotRejected(snapshot);
        assertTrue("Validation must leave the ancestor link intact", Files.isSymbolicLink(directory));
        assertOutsideFixtureIntact();

        Files.delete(directory); // Never recurse into the ancestor link.
        Files.createDirectory(directory);
        Files.write(payload, NORMAL_BYTES);
        assertNormalControlAccepted(payload);
        assertOutsideFixtureIntact();
    }

    @Test
    public void unlistedDirectorySymlinkIsRejectedByTheCompleteInventory() throws Exception {
        Path payload = Files.write(Files.createDirectory(root.resolve("id")).resolve("LICENSE"), NORMAL_BYTES);
        PreparedUploadFolder snapshot = assertNormalControlAccepted(payload);
        Path unlisted = root.resolve("unlisted-link");
        Os.symlink(outsideDirectory.toString(), unlisted.toString());
        assertTrue(Files.isSymbolicLink(unlisted));
        // The listed payload stays valid. Only the extra unlisted link changes the root inventory.
        expectRejected(payload, NORMAL_BYTES.length);
        expectSnapshotRejected(snapshot);
        assertTrue(Files.isSymbolicLink(unlisted));
        assertOutsideFixtureIntact();

        Files.delete(unlisted);
        assertNormalControlAccepted(payload);
        assertOutsideFixtureIntact();
    }

    private PreparedUploadFolder validate(Path payload, long size) throws IOException {
        UploadFolderPayload entry = new UploadFolderPayload(
                payload.toString(), "/Root/LICENSE", "text/plain", Long.valueOf(0L), size);
        // Direct calls to the exact public JVM ABI generated for Kotlin internal declarations.
        return PreparedUploadFolder.Companion.validate(staging, root,
                Collections.singletonList(entry), 1, size);
    }

    private PreparedUploadFolder assertNormalControlAccepted(Path payload) throws IOException {
        assertFalse(Files.isSymbolicLink(payload));
        assertArrayEquals(NORMAL_BYTES, Files.readAllBytes(payload));
        PreparedUploadFolder prepared = validate(payload, NORMAL_BYTES.length);
        assertEquals(root.toString(), prepared.getRootPath());
        assertEquals("Root", prepared.getRootName());
        assertEquals(1, prepared.getPayloads().size());
        assertEquals(payload.toString(), prepared.getPayloads().get(0).getFilePath());
        assertEquals("/Root/LICENSE", prepared.getPayloads().get(0).getLogicalRelativePath());
        assertEquals(1, prepared.validateFor(staging).getPayloads().size());
        return prepared;
    }

    private void expectRejected(Path payload, long size) throws IOException {
        try {
            validate(payload, size);
            fail("The production validator accepted a linked or incomplete physical root");
        } catch (IOException expected) {
            // Only the production validator's filesystem rejection satisfies this assertion.
        }
    }

    private void expectSnapshotRejected(PreparedUploadFolder snapshot) throws IOException {
        try {
            snapshot.validateFor(staging);
            fail("The pre-link snapshot passed its final filesystem recheck");
        } catch (Exception rejected) {
            // Kotlin has no @Throws ABI here; Java still requires the exact filesystem failure.
            assertTrue("Snapshot rejection must be an IOException: " + rejected, rejected instanceof IOException);
            // A previously valid snapshot must reject the same linked physical root at bind time.
        }
    }

    private void assertOutsideFixtureIntact() throws IOException {
        assertTrue(Files.isDirectory(outsideDirectory, NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(outsideFile, NOFOLLOW_LINKS));
        assertFalse(Files.isSymbolicLink(outsideFile));
        assertArrayEquals(OUTSIDE_BYTES, Files.readAllBytes(outsideFile));
    }

    private void requireFixtureBoundary() throws IOException {
        if (fixture == null || cache == null || fixtureName == null
                || !fixtureName.matches(PREFIX + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || !fixture.getFileName().toString().equals(fixtureName)
                || !fixture.isAbsolute() || !fixture.normalize().equals(fixture)
                || !fixture.getParent().equals(cache)
                || Files.isSymbolicLink(fixture) || !Files.isDirectory(fixture, NOFOLLOW_LINKS)
                || !fixture.toRealPath().equals(fixture)) {
            throw new IOException("Refusing cleanup outside the dedicated UUID fixture");
        }
    }

    @After
    public void removeOnlyDedicatedFixtureWithoutFollowingLinks() throws Exception {
        if (fixture == null || !Files.exists(fixture, NOFOLLOW_LINKS)) return;
        // Validate the absolute UUID boundary before the sole recursive cleanup.
        requireFixtureBoundary();
        final Path boundary = fixture;
        Files.walkFileTree(boundary, new SimpleFileVisitor<Path>() {
            private void requireInside(Path path) throws IOException {
                if (!path.normalize().equals(path) || !path.startsWith(boundary)) {
                    throw new IOException("Refusing cleanup outside fixture boundary");
                }
            }
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                requireInside(directory);
                if (Files.isSymbolicLink(directory) || !directory.toRealPath().equals(directory)) {
                    throw new IOException("Refusing redirected fixture directory cleanup");
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                requireInside(file);
                Files.delete(file); // walkFileTree has no FOLLOW_LINKS; symlinks are deleted as links.
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                requireInside(directory);
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
        assertFalse(Files.exists(boundary, NOFOLLOW_LINKS));
    }
}
