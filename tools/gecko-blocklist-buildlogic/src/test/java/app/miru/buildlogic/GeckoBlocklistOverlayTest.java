// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.buildlogic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Standalone integration/negative tests. No project Gradle invocation or Android device is needed. */
public final class GeckoBlocklistOverlayTest {
    private static int passed;
    private static Path aar;
    private static Path snapshots;
    private static Path work;
    private static Path output;
    private static String outputHash;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected: official.aar snapshot-directory work-directory");
        aar = Path.of(args[0]).toAbsolutePath();
        snapshots = Path.of(args[1]).toAbsolutePath();
        work = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(work);
        output = work.resolve("first/omni.ja");
        Path second = work.resolve("second/omni.ja");
        GeckoBlocklistOverlay.build(aar, GeckoBlocklistOverlay.GECKO_ARTIFACT_VERSION, snapshots, output, work.resolve("tmp"));
        GeckoBlocklistOverlay.build(aar, GeckoBlocklistOverlay.GECKO_ARTIFACT_VERSION, snapshots, second, work.resolve("tmp"));
        outputHash = digest(Files.readAllBytes(output));
        passed += GeckoPermissionPolicyPatchTest.run(snapshots.getParent().getParent().resolve("mozilla-permission-policy"));
        passed += GeckoDocumentLocationPatchTest.run(snapshots.getParent().getParent().resolve("mozilla-permission-policy"));
        check(Files.mismatch(output, second) == -1, "Two full builds produce identical bytes");
        independentArchiveAudit(output);

        rejectBuild("Different Gecko version", aar, "158.0.20261022000000", snapshots);
        Path badAar = work.resolve("wrong.aar");
        Files.write(badAar, new byte[] {0, 1, 2});
        rejectBuild("Wrong AAR length", badAar, GeckoBlocklistOverlay.GECKO_ARTIFACT_VERSION, snapshots);
        Files.copy(aar, badAar, StandardCopyOption.REPLACE_EXISTING);
        try (var file = new java.io.RandomAccessFile(badAar.toFile(), "rw")) {
            file.seek(4096); int value = file.read(); file.seek(4096); file.write(value ^ 1);
        }
        rejectBuild("Same-length AAR corruption", badAar, GeckoBlocklistOverlay.GECKO_ARTIFACT_VERSION, snapshots);
        Files.delete(badAar);

        Path altered = work.resolve("altered-snapshots");
        copySnapshots(altered);
        Path hard = altered.resolve(GeckoBlocklistOverlay.HARD);
        Files.delete(hard);
        rejectBuild("Missing snapshot file", aar, GeckoBlocklistOverlay.GECKO_ARTIFACT_VERSION, altered);
        Files.copy(snapshots.resolve(GeckoBlocklistOverlay.HARD), hard);
        Path extra = altered.resolve("unexpected.json"); Files.writeString(extra, "{}");
        rejectBuild("Additional snapshot file", aar, GeckoBlocklistOverlay.GECKO_ARTIFACT_VERSION, altered);
        Files.delete(extra);
        byte[] hardBytes = Files.readAllBytes(hard); hardBytes[0] ^= 1; Files.write(hard, hardBytes);
        rejectBuild("Same-length snapshot corruption", aar, GeckoBlocklistOverlay.GECKO_ARTIFACT_VERSION, altered);

        byte[] index = originalIndex();
        Map<String, byte[]> additions = GeckoBlocklistOverlay.readPinnedSnapshots(snapshots);
        expectIOException("Original index timestamp mismatch", () -> GeckoBlocklistOverlay.validateSnapshotIndex(
            replace(index, "1792413409072", "1792413409073"), additions));
        Map<String, byte[]> changed = copy(additions);
        changed.put(resource(GeckoBlocklistOverlay.SNAPSHOT), replace(changed.get(resource(GeckoBlocklistOverlay.SNAPSHOT)), "1792413409072", "1792413409073"));
        expectIOException("Collection timestamp mismatch", () -> GeckoBlocklistOverlay.validateSnapshotIndex(index, changed));
        Map<String, byte[]> invalidJson = copy(additions);
        invalidJson.put(resource(GeckoBlocklistOverlay.SNAPSHOT), "{".getBytes(StandardCharsets.UTF_8));
        expectIOException("Malformed collection JSON", () -> GeckoBlocklistOverlay.validateSnapshotIndex(index, invalidJson));
        Map<String, byte[]> metadataMismatch = copy(additions);
        metadataMismatch.put(resource(GeckoBlocklistOverlay.HARD) + ".meta.json", replace(metadataMismatch.get(resource(GeckoBlocklistOverlay.HARD) + ".meta.json"), "bloomfilter-base", "otherfilter-base"));
        expectIOException("Metadata must equal its collection record", () -> GeckoBlocklistOverlay.validateSnapshotIndex(index, metadataMismatch));
        rejectMetadata(index, additions, "Attachment hash validation", "bf59d309000ab016e6fcfb558b2ba61ba1368ad4a4a2de424f9f5012a5e47b5a", "af59d309000ab016e6fcfb558b2ba61ba1368ad4a4a2de424f9f5012a5e47b5a");
        rejectMetadata(index, additions, "Attachment length validation", "938855", "938854");
        rejectMetadata(index, additions, "Attachment type validation", "bloomfilter-base", "unknown-base");
        rejectMetadata(index, additions, "Attachment key format validation", "{guid}:{version}", "{guid}");
        rejectMetadata(index, additions, "Attachment location validation", "staging/addons-bloomfilters/", "other/addons-bloomfilters/");

        for (String unsafe : List.of("../bad", "/absolute", "a/../bad", "a/./bad", "a//bad", "C:/bad", "a\\bad", "a\u0000bad", "")) {
            expectIOException("Reject unsafe archive path " + unsafe.replace("\u0000", "<NUL>"), () -> GeckoBlocklistOverlay.validatePath(unsafe));
        }
        check(outputHash.equals(digest(Files.readAllBytes(output))), "All rejected builds preserve the last valid output");
        try (var paths = Files.list(work.resolve("tmp"))) {
            check(paths.findAny().isEmpty(), "Temporary extraction directories are cleaned after builds");
        }
        System.out.println("PASS: " + passed + " checks");
        System.out.println("overlayBytes=" + Files.size(output));
        System.out.println("overlaySha256=" + outputHash);
    }

    /** Independent ZipFile/digest implementation, rather than reusing the production verifier. */
    private static void independentArchiveAudit(Path built) throws Exception {
        Path original = work.resolve("audit-original-omni.ja");
        try (ZipFile archive = new ZipFile(aar.toFile()); InputStream input = archive.getInputStream(archive.getEntry("assets/omni.ja"))) {
            Files.copy(input, original, StandardCopyOption.REPLACE_EXISTING);
        }
        List<String> manifest = new ArrayList<>();
        manifest.add("path\tsha256\tbytes\tcompressionMethod");
        try (ZipFile upstream = new ZipFile(original.toFile()); ZipFile overlay = new ZipFile(built.toFile())) {
            Map<String, ZipEntry> sources = entries(upstream), result = entries(overlay);
            check(sources.size() == 4079 && result.size() == 4084, "4077 unchanged resources, two reviewed module patches, exactly five additions");
            int unchanged = 0, permissionPatches = 0, navigationPatches = 0;
            for (var source : sources.entrySet()) {
                ZipEntry target = result.remove(source.getKey());
                if (target == null) throw new AssertionError("Lost entry: " + source.getKey());
                byte[] before = read(upstream, source.getValue()), after = read(overlay, target);
                if (source.getKey().equals(GeckoPermissionPolicyPatch.RESOURCE)) {
                    if (!digest(before).equals(GeckoPermissionPolicyPatch.ORIGINAL_SHA256) ||
                        !digest(after).equals(GeckoPermissionPolicyPatch.PATCHED_SHA256) ||
                        !Arrays.equals(after, Files.readAllBytes(snapshots.getParent().getParent().resolve("mozilla-permission-policy/GeckoViewPermission.sys.mjs")))) {
                        throw new AssertionError("Changed permission policy is not the reviewed exact source");
                    }
                    permissionPatches++;
                } else if (source.getKey().equals(GeckoDocumentLocationPatch.RESOURCE)) {
                    if (!digest(before).equals(GeckoDocumentLocationPatch.ORIGINAL_SHA256) ||
                        !digest(after).equals(GeckoDocumentLocationPatch.PATCHED_SHA256) ||
                        !Arrays.equals(after, Files.readAllBytes(snapshots.getParent().getParent().resolve("mozilla-permission-policy/GeckoViewNavigation.sys.mjs")))) {
                        throw new AssertionError("Changed navigation module is not the reviewed exact source");
                    }
                    navigationPatches++;
                } else if (!Arrays.equals(before, after)) {
                    throw new AssertionError("Changed original entry: " + source.getKey());
                } else {
                    unchanged++;
                }
                if (source.getValue().getMethod() != target.getMethod()) throw new AssertionError("Changed compression method: " + source.getKey());
                manifest.add(source.getKey() + "\t" + digest(before) + "\t" + before.length + "\t" + target.getMethod());
            }
            check(manifest.size() == 4080 && unchanged == 4077 && permissionPatches == 1 && navigationPatches == 1,
                "4077 original payloads and all compression methods match; two exact module patches");
            check(Arrays.equals(read(upstream, upstream.getEntry(GeckoBlocklistOverlay.INDEX)), read(overlay, overlay.getEntry(GeckoBlocklistOverlay.INDEX))), "Original last_modified.json remains byte-identical");
            Map<String, byte[]> expected = new TreeMap<>();
            try (var paths = Files.walk(snapshots)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) expected.put("defaults/settings/" + snapshots.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
            }
            check(result.keySet().equals(expected.keySet()), "Only the five pinned Mozilla resource paths were added");
            for (var addition : expected.entrySet()) {
                check(Arrays.equals(addition.getValue(), read(overlay, result.get(addition.getKey()))), "Original snapshot bytes: " + addition.getKey());
            }
        }
        Files.write(work.resolve("original-resource-sha256.tsv"), manifest, StandardCharsets.UTF_8);
        Files.delete(original);
    }

    private static Map<String, ZipEntry> entries(ZipFile archive) {
        Map<String, ZipEntry> result = new TreeMap<>();
        for (Enumeration<? extends ZipEntry> values = archive.entries(); values.hasMoreElements();) {
            ZipEntry entry = values.nextElement();
            if (result.put(entry.getName(), entry) != null) throw new AssertionError("Duplicate archive name");
        }
        return result;
    }

    private static byte[] originalIndex() throws Exception {
        Path original = work.resolve("index-original-omni.ja");
        try (ZipFile archive = new ZipFile(aar.toFile()); InputStream input = archive.getInputStream(archive.getEntry("assets/omni.ja"))) {
            Files.copy(input, original, StandardCopyOption.REPLACE_EXISTING);
        }
        try (ZipFile archive = new ZipFile(original.toFile())) {
            return read(archive, archive.getEntry(GeckoBlocklistOverlay.INDEX));
        } finally { Files.delete(original); }
    }

    private static void rejectMetadata(byte[] index, Map<String, byte[]> additions, String name, String from, String to) throws Exception {
        Map<String, byte[]> altered = copy(additions);
        String meta = resource(GeckoBlocklistOverlay.HARD) + ".meta.json";
        altered.put(meta, replace(altered.get(meta), from, to));
        // Change both copies so the test reaches attachment checks rather than only record equality.
        altered.put(resource(GeckoBlocklistOverlay.SNAPSHOT), replace(altered.get(resource(GeckoBlocklistOverlay.SNAPSHOT)), from, to));
        expectIOException(name, () -> GeckoBlocklistOverlay.validateSnapshotIndex(index, altered));
    }

    private static void copySnapshots(Path destination) throws IOException {
        try (var paths = Files.walk(snapshots)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(snapshots.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static byte[] replace(byte[] bytes, String from, String to) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.contains(from)) throw new AssertionError("Missing test mutation: " + from);
        return text.replace(from, to).getBytes(StandardCharsets.UTF_8);
    }
    private static Map<String, byte[]> copy(Map<String, byte[]> source) { return new HashMap<>(source); }
    private static String resource(String path) { return "defaults/settings/" + path; }
    private static byte[] read(ZipFile archive, ZipEntry entry) throws IOException {
        try (InputStream input = archive.getInputStream(entry)) { return input.readAllBytes(); }
    }
    private static String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++; System.out.println("OK " + name);
    }
    private static void rejectBuild(String name, Path artifact, String version, Path input) throws Exception {
        expectIOException(name, () -> GeckoBlocklistOverlay.build(artifact, version, input, output, work.resolve("tmp")));
    }
    private static void expectIOException(String name, Operation operation) throws Exception {
        try { operation.run(); }
        catch (IOException expected) { check(true, name + " (" + expected.getMessage() + ")"); return; }
        throw new AssertionError("Accepted invalid input: " + name);
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
}
