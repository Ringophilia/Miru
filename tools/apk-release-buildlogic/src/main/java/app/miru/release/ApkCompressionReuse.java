// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.release;

import com.android.zipflinger.Entry;
import com.android.zipflinger.Location;
import com.android.zipflinger.Source;
import com.android.zipflinger.Zip64;
import com.android.zipflinger.ZipArchive;
import com.android.zipflinger.ZipSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Copies verified compressed entry payloads into a fresh, unsigned APK. This does
 * not compress, align, sign, modify Android payloads, or configure an AGP task.
 */
public final class ApkCompressionReuse {
    public static final String ZIPFLINGER_VERSION = "9.1.1";
    public static final String ZIPFLINGER_SHA256 =
            "d2ad6ddec6da2ae142a8fe3af2f2529f3fbf77852e2db7ff261ad7b924b119fa";
    private static final long MAX_ARCHIVE = 512L * 1024 * 1024;
    private static final long MAX_ENTRY = 512L * 1024 * 1024;
    private static final long MAX_TOTAL = 2L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 100_000;
    private static final int BUFFER_SIZE = 64 * 1024;

    private ApkCompressionReuse() {}

    public record Payload(long length, long crc32, int method, String sha256) {}

    public record AuditedEntry(
            String name, Payload payload, long compressedLength, String compressedSha256,
            short versionMadeBy, int externalAttributes) {}

    public record Choice(String name, String origin, AuditedEntry entry) {}

    public record Result(
            String currentSha256, String cacheSha256, String outputSha256, long outputBytes,
            int removedSignatureEntries, List<Choice> entries) {
        public long reusedEntries() {
            return entries.stream().filter(entry -> entry.origin().equals("cache")).count();
        }

        public String toJson() {
            StringBuilder out = new StringBuilder("{\n  \"status\": \"PACKAGED_UNSIGNED\",\n");
            field(out, "scope", "Payload-preserving raw ZIP copy; alignment, signing and runtime not verified", true);
            field(out, "zipflingerVersion", ZIPFLINGER_VERSION, true);
            field(out, "zipflingerSha256", ZIPFLINGER_SHA256, true);
            field(out, "currentSha256", currentSha256, true);
            field(out, "cacheSha256", cacheSha256, true);
            field(out, "outputSha256", outputSha256, true);
            out.append("  \"outputBytes\": ").append(outputBytes).append(",\n");
            out.append("  \"reusedEntries\": ").append(reusedEntries()).append(",\n");
            out.append("  \"currentEntries\": ").append(entries.size() - reusedEntries()).append(",\n");
            out.append("  \"removedSignatureEntries\": ").append(removedSignatureEntries).append(",\n");
            out.append("  \"entries\": [\n");
            for (int i = 0; i < entries.size(); i++) {
                Choice choice = entries.get(i);
                AuditedEntry entry = choice.entry();
                out.append("    {\"path\": ").append(quote(choice.name()));
                out.append(", \"origin\": ").append(quote(choice.origin()));
                out.append(", \"bytes\": ").append(entry.payload().length());
                out.append(", \"crc32\": ").append(entry.payload().crc32());
                out.append(", \"method\": ").append(entry.payload().method());
                out.append(", \"sha256\": ").append(quote(entry.payload().sha256()));
                out.append(", \"compressedBytes\": ").append(entry.compressedLength());
                out.append(", \"compressedSha256\": ").append(quote(entry.compressedSha256()));
                out.append('}').append(i + 1 < entries.size() ? ",\n" : "\n");
            }
            return out.append("  ]\n}\n").toString();
        }
    }

    /**
     * Both complete input file digests are mandatory. A cached entry is reused
     * only at the same path with matching actual payload SHA/length/CRC/method
     * and file attributes, and only when its compressed representation is smaller.
     * Existing output survives every input or generated-output validation failure.
     */
    public static Result build(
            Path current, String expectedCurrentSha256, Path cache, String expectedCacheSha256,
            Path output) throws IOException {
        verifyDependency();
        current = inputPath(current);
        cache = inputPath(cache);
        output = output.toAbsolutePath().normalize();
        rejectAlias(output, current);
        rejectAlias(output, cache);
        if (Files.isSymbolicLink(output)) throw new IOException("Output cannot be a symbolic link");
        requireDigest(current, expectedCurrentSha256);
        requireDigest(cache, expectedCacheSha256);

        Map<String, AuditedEntry> currentEntries = audit(current);
        Map<String, AuditedEntry> cacheEntries = audit(cache);
        List<Choice> choices = new ArrayList<>();
        int signatures = 0;
        for (AuditedEntry entry : currentEntries.values()) {
            if (isV1Signature(entry.name())) {
                signatures++;
                continue;
            }
            AuditedEntry cached = cacheEntries.get(entry.name());
            boolean reuse = cached != null
                    && entry.payload().equals(cached.payload())
                    && entry.versionMadeBy() == cached.versionMadeBy()
                    && entry.externalAttributes() == cached.externalAttributes()
                    && cached.compressedLength() < entry.compressedLength();
            choices.add(new Choice(entry.name(), reuse ? "cache" : "current", reuse ? cached : entry));
        }
        if (choices.isEmpty()) throw new IOException("No non-signature payload entries");

        Path parent = output.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("Output parent must already exist: " + parent);
        }
        Path temporary = Files.createTempFile(parent, ".miru-apk-reuse-", ".apk");
        try {
            // ZipArchive opens an existing file as an archive; start with a new path.
            Files.delete(temporary);
            ZipSource currentSource = new ZipSource(current);
            ZipSource cacheSource = new ZipSource(cache);
            for (Choice choice : choices) {
                ZipSource source = choice.origin().equals("cache") ? cacheSource : currentSource;
                source.select(choice.name(), choice.name(), ZipSource.COMPRESSION_NO_CHANGE, Source.NO_ALIGNMENT);
            }
            try (ZipArchive target = new ZipArchive(temporary, Zip64.Policy.FORBID)) {
                if (!currentSource.getSelectedEntries().isEmpty()) target.add(currentSource);
                if (!cacheSource.getSelectedEntries().isEmpty()) target.add(cacheSource);
            }
            verifyOutput(temporary, choices);
            // Detect input replacement or mutation during auditing/copying before publishing.
            requireDigest(current, expectedCurrentSha256);
            requireDigest(cache, expectedCacheSha256);
            String outputDigest = sha256(temporary);
            long outputBytes = Files.size(temporary);
            replace(temporary, output);
            return new Result(normalizeDigest(expectedCurrentSha256), normalizeDigest(expectedCacheSha256),
                    outputDigest, outputBytes, signatures, List.copyOf(choices));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Independently validate path set, payload identity, and exact chosen compressed bytes. */
    static void verifyOutput(Path output, List<Choice> choices) throws IOException {
        Map<String, AuditedEntry> actual = audit(output);
        Map<String, AuditedEntry> expected = new TreeMap<>();
        for (Choice choice : choices) {
            if (expected.put(choice.name(), choice.entry()) != null) {
                throw new IOException("Duplicate output selection: " + choice.name());
            }
        }
        if (!actual.equals(expected)) throw new IOException("Generated APK entries differ from verified sources");
        if (hasApkSigningBlock(output)) throw new IOException("Generated output retained APK signing block");
        for (String name : actual.keySet()) {
            if (isV1Signature(name)) throw new IOException("Generated output retained v1 signature: " + name);
        }
    }

    /** JDK payload read and Zipflinger metadata/raw-range read must agree. */
    static Map<String, AuditedEntry> audit(Path file) throws IOException {
        file = inputPath(file);
        ZipSource source;
        try {
            source = new ZipSource(file);
        } catch (RuntimeException error) {
            throw new IOException("Zipflinger rejected archive: " + file, error);
        }
        Map<String, AuditedEntry> entries = new TreeMap<>();
        long total = 0;
        try (ZipFile zip = new ZipFile(file.toFile());
                FileChannel raw = FileChannel.open(file, StandardOpenOption.READ)) {
            Enumeration<? extends ZipEntry> iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                String name = entry.getName();
                validateName(name);
                if (entries.containsKey(name)) throw new IOException("Duplicate ZIP entry: " + name);
                if (entries.size() >= MAX_ENTRIES) throw new IOException("Too many ZIP entries");
                if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED) {
                    throw new IOException("Unsupported compression method: " + name);
                }
                if (entry.getSize() < 0 || entry.getSize() > MAX_ENTRY || entry.getCompressedSize() < 0
                        || entry.getCompressedSize() > MAX_ARCHIVE || entry.getCrc() < 0) {
                    throw new IOException("Invalid entry size/CRC: " + name);
                }
                total = Math.addExact(total, entry.getSize());
                if (total > MAX_TOTAL) throw new IOException("Excessive total uncompressed size");
                MessageDigest digest = digest();
                CRC32 crc = new CRC32();
                long count = 0;
                byte[] buffer = new byte[BUFFER_SIZE];
                try (InputStream stream = zip.getInputStream(entry)) {
                    for (int read; (read = stream.read(buffer)) != -1;) {
                        count += read;
                        if (count > entry.getSize() || count > MAX_ENTRY) {
                            throw new IOException("Entry expands beyond declared length: " + name);
                        }
                        digest.update(buffer, 0, read);
                        crc.update(buffer, 0, read);
                    }
                }
                if (count != entry.getSize() || crc.getValue() != entry.getCrc()) {
                    throw new IOException("Actual entry length/CRC mismatch: " + name);
                }
                if (entry.isDirectory() && count != 0) throw new IOException("Directory has payload: " + name);
                Entry rawEntry = source.entries().get(name);
                if (rawEntry == null || rawEntry.getCompressionFlag() != entry.getMethod()
                        || rawEntry.getUncompressedSize() != count
                        || Integer.toUnsignedLong(rawEntry.getCrc()) != crc.getValue()
                        || rawEntry.getCompressedSize() != entry.getCompressedSize()) {
                    throw new IOException("ZIP readers disagree: " + name);
                }
                // Public Source getters expose attributes without reflecting into Entry.
                source.select(name, name, ZipSource.COMPRESSION_NO_CHANGE, Source.NO_ALIGNMENT);
                Source selected = source.getSelectedEntries().getLast();
                int attributes = selected.getExternalAttributes();
                int type = (attributes >>> 16) & 0170000;
                if (type == 0120000) throw new IOException("Symbolic link ZIP entry: " + name);
                Location location = rawEntry.getPayloadLocation();
                if (location.first < 0 || location.size() != entry.getCompressedSize()
                        || location.first > raw.size() - location.size()) {
                    throw new IOException("Invalid compressed payload range: " + name);
                }
                String compressedDigest = rangeSha256(raw, location.first, location.size());
                Payload payload = new Payload(count, crc.getValue(), entry.getMethod(), hex(digest.digest()));
                entries.put(name, new AuditedEntry(name, payload, entry.getCompressedSize(), compressedDigest,
                        selected.getVersionMadeBy(), attributes));
            }
        } catch (ArithmeticException | IllegalArgumentException error) {
            throw new IOException("Invalid ZIP archive: " + file, error);
        }
        if (!entries.keySet().equals(source.entries().keySet())) {
            throw new IOException("ZIP reader path sets disagree");
        }
        if (entries.isEmpty()) throw new IOException("Archive has no entries");
        return Collections.unmodifiableMap(entries);
    }

    private static Path inputPath(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Input must be a regular file, not a symbolic link: " + normalized);
        }
        long bytes = Files.size(normalized);
        if (bytes < 22 || bytes > MAX_ARCHIVE) throw new IOException("Invalid APK file size: " + bytes);
        return normalized;
    }

    private static void rejectAlias(Path output, Path input) throws IOException {
        if (output.equals(input) || (Files.exists(output) && Files.isSameFile(output, input))) {
            throw new IOException("Output must differ from both inputs");
        }
    }

    static void validateName(String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
            throw new IOException("Unsafe ZIP entry path: " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < 32 || name.charAt(i) == 127) throw new IOException("Control character in ZIP path");
        }
        String check = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String part : check.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IOException("Unsafe ZIP path segment: " + name);
            }
        }
    }

    static boolean isV1Signature(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) return false;
        String leaf = upper.substring("META-INF/".length());
        if (leaf.indexOf('/') >= 0) return false;
        return leaf.equals("MANIFEST.MF") || leaf.endsWith(".SF") || leaf.endsWith(".RSA")
                || leaf.endsWith(".DSA") || leaf.endsWith(".EC") || leaf.startsWith("SIG-");
    }

    /** Check the standard APK signing-block footer immediately before the central directory. */
    static boolean hasApkSigningBlock(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            int tailSize = (int) Math.min(size, 22 + 65535);
            ByteBuffer tail = ByteBuffer.allocate(tailSize).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            readFully(channel, tail, size - tailSize);
            byte[] bytes = tail.array();
            for (int i = bytes.length - 22; i >= 0; i--) {
                if (tail.getInt(i) == 0x06054b50 && i + 22 + Short.toUnsignedInt(tail.getShort(i + 20)) == bytes.length) {
                    long central = Integer.toUnsignedLong(tail.getInt(i + 16));
                    if (central < 24 || central > size - 22) return false;
                    ByteBuffer magic = ByteBuffer.allocate(16);
                    readFully(channel, magic, central - 16);
                    return java.util.Arrays.equals(magic.array(), "APK Sig Block 42".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                }
            }
            throw new IOException("Missing standard ZIP end record");
        }
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(file)) {
            for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String rangeSha256(FileChannel channel, long first, long length) throws IOException {
        MessageDigest digest = digest();
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        for (long position = first, remaining = length; remaining > 0;) {
            buffer.clear().limit((int) Math.min(buffer.capacity(), remaining));
            int read = channel.read(buffer, position);
            if (read <= 0) throw new IOException("Truncated compressed payload");
            digest.update(buffer.array(), 0, read);
            position += read;
            remaining -= read;
        }
        return hex(digest.digest());
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read <= 0) throw new IOException("Truncated ZIP structure");
            position += read;
        }
    }

    private static void requireDigest(Path path, String expected) throws IOException {
        if (!sha256(path).equals(normalizeDigest(expected))) throw new IOException("Input file SHA-256 mismatch: " + path);
    }

    private static String normalizeDigest(String value) throws IOException {
        if (value == null || !value.matches("(?i)[a-f0-9]{64}")) throw new IOException("Expected a complete SHA-256 digest");
        return value.toLowerCase(Locale.ROOT);
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }

    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }

    private static void verifyDependency() throws IOException {
        try {
            Path jar = Path.of(ZipSource.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(jar) || !sha256(jar).equals(ZIPFLINGER_SHA256)) {
                throw new IOException("Only the pinned official Zipflinger " + ZIPFLINGER_VERSION + " JAR is supported");
            }
        } catch (URISyntaxException | SecurityException error) {
            throw new IOException("Unable to verify Zipflinger runtime", error);
        }
    }

    private static void replace(Path temporary, Path target) throws IOException {
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void field(StringBuilder out, String key, String value, boolean comma) {
        out.append("  ").append(quote(key)).append(": ").append(quote(value)).append(comma ? ",\n" : "\n");
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '"') out.append('\\').append(c);
            else if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException("Usage: ApkCompressionReuse current.apk current-sha256 cache.apk cache-sha256 output-unsigned.apk report.json");
        }
        Path current = Path.of(args[0]);
        Path cache = Path.of(args[2]);
        Path output = Path.of(args[4]).toAbsolutePath().normalize();
        Path report = Path.of(args[5]).toAbsolutePath().normalize();
        rejectAlias(report, current.toAbsolutePath().normalize());
        rejectAlias(report, cache.toAbsolutePath().normalize());
        rejectAlias(report, output);
        if (Files.isSymbolicLink(report) || !Files.isDirectory(report.getParent())) {
            throw new IOException("Report needs an existing parent and cannot be a symbolic link");
        }
        Result result = build(current, args[1], cache, args[3], output);
        Path temporary = Files.createTempFile(report.getParent(), ".miru-apk-report-", ".json");
        try {
            Files.writeString(temporary, result.toJson());
            replace(temporary, report);
        } finally { Files.deleteIfExists(temporary); }
        System.out.println("PACKAGED_UNSIGNED: " + result.entries().size() + " entries, " + result.reusedEntries()
                + " reused; " + result.outputBytes() + " bytes; SHA-256 " + result.outputSha256());
    }
}
