// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.release;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Small synthetic ZIP fixtures only; no Android build, device, signing or Zopfli. */
public final class ApkCompressionReuseTest {
    private static int checks;
    private static Path work;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected a new test work directory");
        work = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectory(work);
        reuseChangedPathsAndLicenses();
        unprofitableAndMethodMismatch();
        sameCrcDifferentPayloadAndAttributes();
        rejectsDuplicateNames();
        rejectsCorruption();
        rejectsUnsafePaths();
        pinsAndOutputSafety();
        rejectsWrongOutput();
        System.out.println("PASS: " + checks + " checks");
    }

    private static void reuseChangedPathsAndLicenses() throws Exception {
        byte[] nativeBytes = repetitive();
        Map<String, byte[]> current = entries(
                "lib/arm64-v8a/libfixture.so", nativeBytes,
                "classes.dex", bytes("new-dex-current-only"),
                "assets/privacy.js", bytes("current-private-policy"),
                "assets/new.txt", bytes("new-only"),
                "resources.arsc", bytes("resource-table"),
                "META-INF/AL2.0", bytes("full license A"),
                "META-INF/LGPL2.1", bytes("full license B"),
                "META-INF/licenses/component.SF", bytes("nested license must survive"),
                "META-INF/MANIFEST.MF", bytes("current v1 manifest"),
                "META-INF/CERT.SF", bytes("current v1 sf"),
                "META-INF/CERT.RSA", bytes("current v1 rsa"));
        Map<String, byte[]> cached = new LinkedHashMap<>(current);
        cached.put("classes.dex", bytes("old-cache-dex"));
        cached.put("assets/privacy.js", bytes("old-cache-private-policy"));
        cached.remove("assets/new.txt");
        cached.put("assets/removed.txt", bytes("must not enter output"));
        Path currentPath = write("current.apk", current, 1, List.of("resources.arsc"));
        Path cachePath = write("cache.apk", cached, 9, List.of("resources.arsc"));
        currentPath = withSigningBlock(currentPath, "current-signed.apk");
        cachePath = withSigningBlock(cachePath, "cache-signed.apk");
        check(ApkCompressionReuse.hasApkSigningBlock(currentPath), "fixture current has APK signing block");
        Path output = work.resolve("output-unsigned.apk");
        var result = build(currentPath, cachePath, output);
        Map<String, ApkCompressionReuse.AuditedEntry> actual = ApkCompressionReuse.audit(output);
        check(result.reusedEntries() >= 1, "same native payload reuses smaller cache representation");
        check(choice(result, "lib/arm64-v8a/libfixture.so").origin().equals("cache"), "native origin is cache");
        check(choice(result, "classes.dex").origin().equals("current"), "changed DEX origin is current");
        check(choice(result, "assets/privacy.js").origin().equals("current"), "changed JS origin is current");
        check(choice(result, "assets/new.txt").origin().equals("current"), "added path origin is current");
        check(!actual.containsKey("assets/removed.txt"), "cache-only removed path does not enter output");
        check(actual.containsKey("META-INF/AL2.0") && actual.containsKey("META-INF/LGPL2.1")
                && actual.containsKey("META-INF/licenses/component.SF"), "licenses survive exact signature filtering");
        check(result.removedSignatureEntries() == 3, "exactly three current v1 signature files removed");
        check(actual.size() == current.size() - 3, "complete current non-signature path set retained");
        check(!ApkCompressionReuse.hasApkSigningBlock(output), "APK signing block not copied");
        try (ZipFile zip = new ZipFile(output.toFile())) {
            check(Arrays.equals(zip.getInputStream(zip.getEntry("classes.dex")).readAllBytes(), current.get("classes.dex")),
                    "JDK reader sees exact new DEX");
            check(zip.getEntry("resources.arsc").getMethod() == ZipEntry.STORED, "stored resources remain stored");
        }
        Map<String, ApkCompressionReuse.AuditedEntry> cacheAudit = ApkCompressionReuse.audit(cachePath);
        Map<String, ApkCompressionReuse.AuditedEntry> currentAudit = ApkCompressionReuse.audit(currentPath);
        check(actual.get("lib/arm64-v8a/libfixture.so").compressedSha256()
                .equals(cacheAudit.get("lib/arm64-v8a/libfixture.so").compressedSha256()), "native raw compressed bytes copied from cache");
        check(actual.get("classes.dex").compressedSha256().equals(currentAudit.get("classes.dex").compressedSha256()),
                "changed DEX raw compressed bytes copied from current");
        Path output2 = work.resolve("output-second.apk");
        build(currentPath, cachePath, output2);
        check(ApkCompressionReuse.sha256(output).equals(ApkCompressionReuse.sha256(output2)), "same inputs generate deterministic output");
        check(result.outputSha256().equals(ApkCompressionReuse.sha256(output)) && result.outputBytes() == Files.size(output),
                "result pins actual output");
        check(result.toJson().contains("\"PACKAGED_UNSIGNED\"") && result.toJson().contains("compressedSha256"),
                "report distinguishes unsigned packaging and records entry source bytes");
        Files.writeString(work.resolve("fixture-report.json"), result.toJson());
    }

    private static void unprofitableAndMethodMismatch() throws Exception {
        Map<String, byte[]> payload = entries("lib/a.so", repetitive(), "assets/LICENSE", bytes("license"));
        Path compact = write("compact.apk", payload, 9, List.of());
        Path larger = write("larger.apk", payload, 1, List.of());
        var result = build(compact, larger, work.resolve("unprofitable-output.apk"));
        check(result.reusedEntries() == 0, "larger/equal cache does not enlarge any entry");
        Path stored = write("stored.apk", payload, 9, List.of("lib/a.so", "assets/LICENSE"));
        result = build(stored, compact, work.resolve("method-output.apk"));
        check(result.reusedEntries() == 0 && choice(result, "lib/a.so").entry().payload().method() == ZipEntry.STORED,
                "same bytes with different method use current entry");
    }

    private static void rejectsDuplicateNames() throws Exception {
        Path clean = write("duplicates-clean.apk", entries("assets/first.txt", bytes("one"), "assets/other.txt", bytes("two")), 1, List.of());
        byte[] data = Files.readAllBytes(clean);
        replaceAll(data, bytes("assets/other.txt"), bytes("assets/first.txt"));
        Path duplicate = work.resolve("duplicates.apk");
        Files.write(duplicate, data);
        Path cache = write("duplicates-cache.apk", entries("assets/first.txt", bytes("one")), 9, List.of());
        rejected(duplicate, cache, "duplicate input names rejected independently of Zipflinger map");
        rejected(cache, duplicate, "duplicate cache names rejected independently of Zipflinger map");
    }

    private static void sameCrcDifferentPayloadAndAttributes() throws Exception {
        byte[] currentBytes = bytes("plumless");
        byte[] cachedBytes = bytes("buckeroo");
        CRC32 currentCrc = new CRC32();
        CRC32 cachedCrc = new CRC32();
        currentCrc.update(currentBytes);
        cachedCrc.update(cachedBytes);
        check(currentBytes.length == cachedBytes.length && currentCrc.getValue() == cachedCrc.getValue()
                && !Arrays.equals(currentBytes, cachedBytes), "fixture has equal length/CRC and different payload");
        Path current = write("crc-collision-current.apk", entries("classes.dex", currentBytes, "assets/empty/", new byte[0]), 1, List.of());
        Path cache = write("crc-collision-cache.apk", entries("classes.dex", cachedBytes, "assets/empty/", new byte[0]), 9, List.of());
        var result = build(current, cache, work.resolve("crc-collision-output.apk"));
        check(choice(result, "classes.dex").origin().equals("current"), "actual SHA prevents same-length same-CRC cache substitution");
        check(ApkCompressionReuse.audit(work.resolve("crc-collision-output.apk")).containsKey("assets/empty/"),
                "empty directory path retained");
        Path common = write("attributes-current.apk", entries("lib/a.so", repetitive()), 1, List.of());
        Path compact = write("attributes-compact.apk", entries("lib/a.so", repetitive()), 9, List.of());
        byte[] changed = Files.readAllBytes(compact);
        int central = findSignature(changed, 0x02014b50);
        ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putInt(central + 38, 0644 << 16);
        Path differentAttributes = work.resolve("attributes-cache.apk");
        Files.write(differentAttributes, changed);
        result = build(common, differentAttributes, work.resolve("attributes-output.apk"));
        check(choice(result, "lib/a.so").origin().equals("current"), "cache cannot replace current file attributes");
        changed[central + 5] = 3; // UNIX creator.
        ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putInt(central + 38, 0120777 << 16);
        Path link = work.resolve("symlink-cache.apk");
        Files.write(link, changed);
        rejected(common, link, "symlink cache entries rejected");
    }

    private static void rejectsCorruption() throws Exception {
        Path clean = write("corruption-clean.apk", entries("assets/data.bin", bytes("unique-payload-for-CRC")), 1,
                List.of("assets/data.bin"));
        byte[] data = Files.readAllBytes(clean);
        int payloadPosition = indexOf(data, bytes("unique-payload-for-CRC"));
        data[payloadPosition] ^= 1;
        Path bad = work.resolve("corrupted-stored.apk");
        Files.write(bad, data);
        rejected(bad, clean, "actual payload CRC corruption rejected in current");
        rejected(clean, bad, "actual payload CRC corruption rejected in cache even with matching whole-file pin");
        byte[] centralCorrupt = Files.readAllBytes(clean);
        int central = findSignature(centralCorrupt, 0x02014b50);
        centralCorrupt[central + 16] ^= 1;
        Path wrongCrc = work.resolve("corrupted-crc.apk");
        Files.write(wrongCrc, centralCorrupt);
        rejected(clean, wrongCrc, "forged central CRC rejected");
        byte[] truncated = Files.readAllBytes(clean);
        Path incomplete = work.resolve("incomplete.apk");
        Files.write(incomplete, Arrays.copyOf(truncated, truncated.length - 5));
        rejected(clean, incomplete, "unfinished cache without complete central directory rejected");
    }

    private static void rejectsUnsafePaths() throws Exception {
        Path clean = write("safe.apk", entries("assets/safe.txt", bytes("safe")), 1, List.of());
        for (String unsafe : List.of("../escape", "assets/../escape", "/absolute", "C:/drive", "assets\\escape", "assets//empty")) {
            Path invalid = write("unsafe-" + checks + ".apk", entries(unsafe, bytes("bad")), 1, List.of());
            rejected(invalid, clean, "unsafe current path rejected: " + unsafe);
            rejected(clean, invalid, "unsafe cache path rejected: " + unsafe);
        }
    }

    private static void pinsAndOutputSafety() throws Exception {
        Path clean = write("pin-clean.apk", entries("assets/pin.txt", bytes("pin")), 1, List.of());
        Path output = work.resolve("existing-output.apk");
        byte[] sentinel = bytes("last-good-output");
        Files.write(output, sentinel);
        expectFailure(() -> ApkCompressionReuse.build(clean, "0".repeat(64), clean, ApkCompressionReuse.sha256(clean), output),
                "wrong current whole-file pin rejected");
        check(Arrays.equals(Files.readAllBytes(output), sentinel), "failed validation preserves existing output");
        expectFailure(() -> ApkCompressionReuse.build(clean, ApkCompressionReuse.sha256(clean), clean, "f".repeat(64), output),
                "wrong cache whole-file pin rejected");
        expectFailure(() -> ApkCompressionReuse.build(clean, "short", clean, ApkCompressionReuse.sha256(clean), output),
                "incomplete digest rejected");
        expectFailure(() -> build(clean, clean, clean), "output alias of input rejected");
        Path hardlink = work.resolve("input-hardlink.apk");
        Files.createLink(hardlink, clean);
        expectFailure(() -> build(clean, clean, hardlink), "hardlink output alias rejected");
        Path onlySignature = write("only-signatures.apk", entries("META-INF/MANIFEST.MF", bytes("m")), 1, List.of());
        rejected(onlySignature, clean, "signature-only APK rejected");
        try (var files = Files.list(work)) {
            check(files.noneMatch(path -> path.getFileName().toString().startsWith(".miru-apk-reuse-")),
                    "no temporary APK remains after rejection");
        }
    }

    private static void rejectsWrongOutput() throws Exception {
        Path current = write("verify-current.apk", entries("assets/a", bytes("A"), "assets/b", bytes("B")), 1, List.of());
        Map<String, ApkCompressionReuse.AuditedEntry> expected = ApkCompressionReuse.audit(current);
        List<ApkCompressionReuse.Choice> choices = expected.values().stream()
                .map(entry -> new ApkCompressionReuse.Choice(entry.name(), "current", entry)).toList();
        Path missing = write("verify-missing.apk", entries("assets/a", bytes("A")), 1, List.of());
        expectFailure(() -> ApkCompressionReuse.verifyOutput(missing, choices), "generated path loss rejected");
        Path extra = write("verify-extra.apk", entries("assets/a", bytes("A"), "assets/b", bytes("B"), "assets/c", bytes("C")), 1, List.of());
        expectFailure(() -> ApkCompressionReuse.verifyOutput(extra, choices), "generated extra path rejected");
        Path changed = write("verify-changed.apk", entries("assets/a", bytes("Z"), "assets/b", bytes("B")), 1, List.of());
        expectFailure(() -> ApkCompressionReuse.verifyOutput(changed, choices), "generated changed payload rejected");
    }

    private static ApkCompressionReuse.Result build(Path current, Path cache, Path output) throws IOException {
        return ApkCompressionReuse.build(current, ApkCompressionReuse.sha256(current), cache, ApkCompressionReuse.sha256(cache), output);
    }

    private static ApkCompressionReuse.Choice choice(ApkCompressionReuse.Result result, String path) {
        return result.entries().stream().filter(entry -> entry.name().equals(path)).findFirst().orElseThrow();
    }

    private static Path write(String name, Map<String, byte[]> payloads, int level, List<String> stored) throws IOException {
        Path path = work.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.setLevel(level);
            for (var payload : payloads.entrySet()) {
                ZipEntry entry = new ZipEntry(payload.getKey());
                entry.setTime(315532800000L);
                if (stored.contains(payload.getKey())) {
                    CRC32 crc = new CRC32();
                    crc.update(payload.getValue());
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(payload.getValue().length);
                    entry.setCompressedSize(payload.getValue().length);
                    entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry);
                zip.write(payload.getValue());
                zip.closeEntry();
            }
        }
        return path;
    }

    private static Path withSigningBlock(Path input, String outputName) throws IOException {
        byte[] original = Files.readAllBytes(input);
        int end = original.length - 22;
        int central = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).getInt(end + 16);
        byte[] block = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).putLong(24).putLong(24)
                .put("APK Sig Block 42".getBytes(StandardCharsets.US_ASCII)).array();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(original, 0, central);
        out.write(block);
        out.write(original, central, original.length - central);
        byte[] modified = out.toByteArray();
        ByteBuffer.wrap(modified).order(ByteOrder.LITTLE_ENDIAN).putInt(end + block.length + 16, central + block.length);
        Path output = work.resolve(outputName);
        Files.write(output, modified);
        return output;
    }

    private static Map<String, byte[]> entries(Object... values) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], (byte[]) values[i + 1]);
        return result;
    }

    private static byte[] repetitive() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 4000; i++) out.append("native-byte-fixture-").append(i % 93).append("-common-common-common-\n");
        return bytes(out.toString());
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        throw new AssertionError("Fixture marker not found");
    }

    private static int findSignature(byte[] bytes, int signature) {
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i <= bytes.length - 4; i++) if (data.getInt(i) == signature) return i;
        throw new AssertionError("Fixture ZIP signature not found");
    }

    private static void replaceAll(byte[] bytes, byte[] oldValue, byte[] newValue) {
        if (oldValue.length != newValue.length) throw new AssertionError("Equal-size marker required");
        for (int i = 0; i <= bytes.length - oldValue.length; i++) {
            boolean match = true;
            for (int j = 0; j < oldValue.length; j++) match &= bytes[i + j] == oldValue[j];
            if (match) System.arraycopy(newValue, 0, bytes, i, newValue.length);
        }
    }

    private static void rejected(Path current, Path cache, String label) throws Exception {
        Path output = work.resolve("rejected-output.apk");
        byte[] old = bytes("preserve-existing-output");
        Files.write(output, old);
        expectFailure(() -> build(current, cache, output), label);
        check(Arrays.equals(Files.readAllBytes(output), old), label + " preserves previous output");
    }

    private interface Throwing { void run() throws Exception; }

    private static void expectFailure(Throwing action, String label) throws Exception {
        try { action.run(); }
        catch (IOException | IllegalArgumentException expected) { check(true, label); return; }
        throw new AssertionError("Expected rejection: " + label);
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
        System.out.println("PASS " + checks + ": " + label);
    }
}
