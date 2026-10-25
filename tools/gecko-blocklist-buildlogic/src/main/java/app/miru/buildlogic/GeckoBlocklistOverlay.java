// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.buildlogic;

import groovy.json.JsonSlurper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Adds pinned blocklists and exactly two reviewed permission/navigation module patches. */
public final class GeckoBlocklistOverlay {
    public static final String GECKO_ARTIFACT_VERSION = "158.0.20261021001945";
    public static final String GECKO_AAR_SHA256 = "a3af9d5ffb1f898df5b8782d083b69c4274c4f3275e31f3dac33d0d2aa16c692";
    public static final long GECKO_AAR_BYTES = 243952420L;
    public static final String GECKO_OMNI_SHA256 = "b6dca3074e505b3d475504c864ba517138e383d6d9f23c6db94de34403c254b5";
    public static final String GECKO_REVISION = "e4ce606692cd81e6898547e2edd7c552fefcbb04";
    public static final int ORIGINAL_ENTRY_COUNT = 4079;
    public static final int UNCHANGED_ENTRY_COUNT = ORIGINAL_ENTRY_COUNT - 2;
    static final String INDEX = "defaults/settings/last_modified.json";
    static final String SNAPSHOT = "blocklists/addons-bloomfilters.json";
    static final String HARD = "blocklists/addons-bloomfilters/addons-mlbf.bin";
    static final String SOFT = "blocklists/addons-bloomfilters/softblocks-addons-mlbf.bin";
    static final long SNAPSHOT_TIMESTAMP = 1792413409072L;
    private static final long MAX_OMNI_BYTES = 32L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 32L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 128L * 1024 * 1024;
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);
    private static final Map<String, Pin> PINS = pins();

    private GeckoBlocklistOverlay() {}

    private static Map<String, Pin> pins() {
        Map<String, Pin> pins = new LinkedHashMap<>();
        pins.put(SNAPSHOT, new Pin(72903, "43dbbe38a70b3350ef8eaf5c2f2f7e300196b7986d12cc639b058eef966ef86a"));
        pins.put(HARD, new Pin(938855, "bf59d309000ab016e6fcfb558b2ba61ba1368ad4a4a2de424f9f5012a5e47b5a"));
        pins.put(HARD + ".meta.json", new Pin(500, "81078ea0f4e5fd2ee36bd58c44849898d2a12e469ba494af30d22895ce216135"));
        pins.put(SOFT, new Pin(218202, "d778ccd02b613efb8c7ee0d4e4517dcac61b7e12a362dc600890c6c3065772cb"));
        pins.put(SOFT + ".meta.json", new Pin(511, "c715336c8f9ba1c8845b947cba80d4cef6a689d1d4f4408471287b98c6eca36a"));
        return Collections.unmodifiableMap(pins);
    }

    /** The version and byte pins deliberately cannot be overridden through task properties. */
    public static void build(Path aar, String version, Path snapshotDirectory, Path output, Path temporaryDirectory) throws IOException {
        require(GECKO_ARTIFACT_VERSION.equals(version), "Unexpected Gecko artifact version: " + version);
        require(Files.isRegularFile(aar) && Files.size(aar) == GECKO_AAR_BYTES, "Gecko AAR length does not match the pinned official artifact");
        require(GECKO_AAR_SHA256.equals(sha256(aar)), "Gecko AAR SHA-256 does not match the pinned official artifact");
        Map<String, byte[]> additions = readPinnedSnapshots(snapshotDirectory);
        Files.createDirectories(temporaryDirectory);
        Path work = Files.createTempDirectory(temporaryDirectory, "blocklist-overlay-");
        Path original = work.resolve("original-omni.ja");
        Path staged = work.resolve("overlay-omni.ja");
        try {
            extractOmni(aar, original);
            require(GECKO_OMNI_SHA256.equals(sha256(original)), "Extracted omni.ja SHA-256 mismatch");
            try (ZipFile source = new ZipFile(original.toFile(), StandardCharsets.UTF_8)) {
                Map<String, Entry> entries = inventory(source);
                require(entries.size() == ORIGINAL_ENTRY_COUNT, "Unexpected original omni entry count: " + entries.size());
                for (String addition : additions.keySet()) require(!entries.containsKey(addition), "Overlay would replace an original Gecko entry: " + addition);
                byte[] index = readEntry(source, INDEX);
                validateSnapshotIndex(index, additions);
                String constants = new String(readEntry(source, "modules/AppConstants.sys.mjs"), StandardCharsets.UTF_8);
                require(constants.contains("MOZ_APP_VERSION: \"158.0.2\""), "Gecko application version mismatch");
                require(constants.contains("/rev/" + GECKO_REVISION), "Gecko source revision mismatch");
                byte[] permissionPolicy = GeckoPermissionPolicyPatch.apply(readEntry(source, GeckoPermissionPolicyPatch.RESOURCE));
                byte[] documentLocation = GeckoDocumentLocationPatch.apply(readEntry(source, GeckoDocumentLocationPatch.RESOURCE));
                writeOverlay(source, entries, additions, permissionPolicy, documentLocation, staged);
                verifyOverlay(source, entries, additions, staged);
            }
            Files.createDirectories(output.toAbsolutePath().getParent());
            try {
                Files.move(staged, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(staged, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
            Files.deleteIfExists(original);
            Files.deleteIfExists(work);
        }
    }

    static Map<String, byte[]> readPinnedSnapshots(Path directory) throws IOException {
        require(Files.isDirectory(directory) && !Files.isSymbolicLink(directory), "Snapshot input must be a regular directory");
        Set<String> actualFiles = new HashSet<>();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.toList()) {
                require(!Files.isSymbolicLink(path), "Symbolic links are not accepted in the snapshot input");
                if (Files.isDirectory(path)) continue;
                require(Files.isRegularFile(path), "Snapshot input contains a non-regular file");
                actualFiles.add(directory.relativize(path).toString().replace('\\', '/'));
            }
        }
        require(actualFiles.equals(PINS.keySet()), "Snapshot input must contain exactly the five pinned files");
        Map<String, byte[]> result = new TreeMap<>();
        for (var input : PINS.entrySet()) {
            Path file = directory.resolve(input.getKey());
            Pin pin = input.getValue();
            require(Files.size(file) == pin.length(), "Snapshot length mismatch: " + input.getKey());
            byte[] data = Files.readAllBytes(file);
            require(pin.sha256().equals(sha256(data)), "Snapshot SHA-256 mismatch: " + input.getKey());
            result.put("defaults/settings/" + input.getKey(), data);
        }
        return result;
    }

    static void validateSnapshotIndex(byte[] indexBytes, Map<String, byte[]> additions) throws IOException {
        Map<?, ?> index = object(parse(indexBytes), "original last_modified.json");
        byte[] snapshotBytes = additions.get("defaults/settings/" + SNAPSHOT);
        require(snapshotBytes != null, "Missing snapshot collection");
        Map<?, ?> snapshot = object(parse(snapshotBytes), "snapshot collection");
        require(integer(snapshot.get("timestamp")) == SNAPSHOT_TIMESTAMP, "Unexpected snapshot timestamp");
        require(integer(index.get("blocklists/addons-bloomfilters")) == SNAPSHOT_TIMESTAMP, "Original Gecko index does not match the snapshot timestamp");
        Object data = snapshot.get("data");
        require(data instanceof List<?>, "Snapshot data must be an array");
        Map<String, Map<?, ?>> records = new HashMap<>();
        for (Object value : (List<?>) data) {
            Map<?, ?> record = object(value, "snapshot record");
            String id = string(record.get("id"));
            require(records.put(id, record) == null, "Duplicate snapshot record ID");
        }
        validateAttachment(additions, records, HARD, "bloomfilter-base");
        validateAttachment(additions, records, SOFT, "softblocks-bloomfilter-base");
    }

    private static void validateAttachment(Map<String, byte[]> additions, Map<String, Map<?, ?>> records, String path, String type) throws IOException {
        String resource = "defaults/settings/" + path;
        byte[] metaBytes = additions.get(resource + ".meta.json");
        byte[] bytes = additions.get(resource);
        require(metaBytes != null && bytes != null, "Missing blocklist attachment");
        Map<?, ?> meta = object(parse(metaBytes), "attachment metadata");
        require(meta.equals(records.get(string(meta.get("id")))), "Attachment metadata differs from the snapshot record");
        require(type.equals(meta.get("attachment_type")), "Incorrect blocklist attachment type");
        require("{guid}:{version}".equals(meta.get("key_format")), "Incorrect blocklist key format");
        Map<?, ?> attachment = object(meta.get("attachment"), "attachment");
        require(integer(attachment.get("size")) == bytes.length, "Attachment metadata length mismatch");
        require(sha256(bytes).equals(string(attachment.get("hash"))), "Attachment metadata SHA-256 mismatch");
        require(string(attachment.get("location")).startsWith("staging/addons-bloomfilters/"), "Unexpected upstream attachment location");
    }

    private static Object parse(byte[] bytes) throws IOException {
        try { return new JsonSlurper().parseText(new String(bytes, StandardCharsets.UTF_8)); }
        catch (RuntimeException error) { throw new IOException("Invalid pinned snapshot JSON", error); }
    }

    private static Map<?, ?> object(Object value, String name) throws IOException {
        require(value instanceof Map<?, ?>, name + " must be an object");
        return (Map<?, ?>) value;
    }

    private static String string(Object value) throws IOException {
        require(value instanceof String && !((String) value).isEmpty(), "Expected a nonempty JSON string");
        return (String) value;
    }

    private static long integer(Object value) throws IOException {
        require(value instanceof Number && value.toString().matches("[0-9]+"), "Expected an unsigned JSON integer");
        try { return Long.parseLong(value.toString()); }
        catch (NumberFormatException error) { throw new IOException("JSON integer out of range", error); }
    }

    private static void extractOmni(Path aar, Path target) throws IOException {
        try (ZipFile archive = new ZipFile(aar.toFile(), StandardCharsets.UTF_8)) {
            Set<String> names = new HashSet<>();
            for (Enumeration<? extends ZipEntry> all = archive.entries(); all.hasMoreElements();) {
                ZipEntry entry = all.nextElement();
                validatePath(entry.getName());
                require(names.add(entry.getName()), "Duplicate AAR path");
            }
            ZipEntry omni = archive.getEntry("assets/omni.ja");
            require(omni != null && !omni.isDirectory() && omni.getSize() > 0 && omni.getSize() <= MAX_OMNI_BYTES, "Missing or oversized original omni.ja");
            try (InputStream input = archive.getInputStream(omni); OutputStream output = Files.newOutputStream(target)) {
                transferBounded(input, output, MAX_OMNI_BYTES);
            }
        }
    }

    static Map<String, Entry> inventory(ZipFile archive) throws IOException {
        Map<String, Entry> entries = new TreeMap<>();
        long expanded = 0;
        for (Enumeration<? extends ZipEntry> all = archive.entries(); all.hasMoreElements();) {
            ZipEntry entry = all.nextElement();
            validatePath(entry.getName());
            require(entry.getMethod() == ZipEntry.STORED || entry.getMethod() == ZipEntry.DEFLATED, "Unsupported Gecko ZIP compression method");
            require(entry.getSize() >= 0 && entry.getSize() <= MAX_ENTRY_BYTES, "Missing or oversized ZIP entry length");
            expanded += entry.getSize();
            require(expanded <= MAX_EXPANDED_BYTES, "Expanded Gecko ZIP exceeds the limit");
            byte[] bytes = readEntry(archive, entry.getName());
            require(bytes.length == entry.getSize(), "Gecko ZIP entry length mismatch");
            CRC32 crc = new CRC32(); crc.update(bytes);
            require(crc.getValue() == entry.getCrc(), "Gecko ZIP entry CRC mismatch");
            Entry value = new Entry(entry.getMethod(), bytes.length, entry.getCrc(), sha256(bytes));
            require(entries.put(entry.getName(), value) == null, "Duplicate Gecko ZIP entry: " + entry.getName());
            require(entries.size() <= ORIGINAL_ENTRY_COUNT + PINS.size(), "Unexpected Gecko ZIP entry count");
        }
        return entries;
    }

    static void validatePath(String name) throws IOException {
        require(name != null && !name.isEmpty() && !name.startsWith("/") && !name.contains("\\") && !name.contains(":") && !name.contains("\u0000"), "Unsafe ZIP path");
        String path = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String segment : path.split("/", -1)) require(!segment.isEmpty() && !segment.equals(".") && !segment.equals(".."), "Unsafe ZIP path component");
    }

    private static byte[] readEntry(ZipFile archive, String name) throws IOException {
        ZipEntry entry = archive.getEntry(name);
        require(entry != null, "Missing Gecko resource: " + name);
        try (InputStream input = archive.getInputStream(entry); var output = new java.io.ByteArrayOutputStream()) {
            transferBounded(input, output, MAX_ENTRY_BYTES);
            return output.toByteArray();
        }
    }

    private static void transferBounded(InputStream input, OutputStream output, long maximum) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            require(total <= maximum, "ZIP data exceeds the declared safety limit");
            output.write(buffer, 0, read);
        }
    }

    private static void writeOverlay(ZipFile source, Map<String, Entry> entries, Map<String, byte[]> additions, byte[] permissionPolicy, byte[] documentLocation, Path target) throws IOException {
        List<String> names = new ArrayList<>(entries.keySet());
        names.addAll(additions.keySet());
        Collections.sort(names);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            output.setLevel(9);
            for (String name : names) {
                byte[] added = additions.get(name);
                Entry original = entries.get(name);
                byte[] bytes = name.equals(GeckoPermissionPolicyPatch.RESOURCE) ? permissionPolicy
                    : name.equals(GeckoDocumentLocationPatch.RESOURCE) ? documentLocation
                    : added == null ? readEntry(source, name) : added;
                ZipEntry entry = new ZipEntry(name);
                entry.setTimeLocal(ZIP_TIME);
                entry.setMethod(original == null ? ZipEntry.DEFLATED : original.method());
                if (entry.getMethod() == ZipEntry.STORED) {
                    entry.setSize(bytes.length);
                    entry.setCompressedSize(bytes.length);
                    CRC32 crc = new CRC32(); crc.update(bytes);
                    entry.setCrc(crc.getValue());
                }
                output.putNextEntry(entry);
                output.write(bytes);
                output.closeEntry();
            }
        }
    }

    static void verifyOverlay(ZipFile source, Map<String, Entry> originals, Map<String, byte[]> additions, Path output) throws IOException {
        try (ZipFile result = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            Map<String, Entry> actual = inventory(result);
            Set<String> expectedNames = new HashSet<>(originals.keySet());
            expectedNames.addAll(additions.keySet());
            require(actual.keySet().equals(expectedNames) && actual.size() == originals.size() + PINS.size(), "Overlay changed the Gecko resource set");
            int unchanged = 0, permissionPatches = 0, navigationPatches = 0;
            for (var original : originals.entrySet()) {
                Entry copy = actual.get(original.getKey());
                if (original.getKey().equals(GeckoPermissionPolicyPatch.RESOURCE)) {
                    byte[] expected = GeckoPermissionPolicyPatch.apply(readEntry(source, original.getKey()));
                    require(java.util.Arrays.equals(expected, readEntry(result, original.getKey())), "Overlay differs from the exact geolocation policy patch");
                    permissionPatches++;
                } else if (original.getKey().equals(GeckoDocumentLocationPatch.RESOURCE)) {
                    byte[] expected = GeckoDocumentLocationPatch.apply(readEntry(source, original.getKey()));
                    require(java.util.Arrays.equals(expected, readEntry(result, original.getKey())), "Overlay differs from the exact document location patch");
                    navigationPatches++;
                } else {
                    require(copy.sha256().equals(original.getValue().sha256()), "Overlay modified original Gecko bytes: " + original.getKey());
                    require(copy.length() == original.getValue().length(), "Overlay changed original Gecko resource length");
                    unchanged++;
                }
                require(copy.method() == original.getValue().method(), "Overlay changed original Gecko compression method");
            }
            require(unchanged == UNCHANGED_ENTRY_COUNT && permissionPatches == 1 && navigationPatches == 1,
                "Overlay must preserve 4077 resources and apply exactly the permission and navigation patches");
            for (var addition : additions.entrySet()) require(sha256(addition.getValue()).equals(actual.get(addition.getKey()).sha256()), "Overlay changed blocklist bytes");
            require(java.util.Arrays.equals(readEntry(source, INDEX), readEntry(result, INDEX)), "Overlay changed the original last_modified index");
        }
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024]; int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) { return java.util.HexFormat.of().formatHex(digest().digest(bytes)); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void require(boolean condition, String message) throws IOException { if (!condition) throw new IOException(message); }
    private record Pin(long length, String sha256) {}
    record Entry(int method, long length, long crc, String sha256) {}
}
