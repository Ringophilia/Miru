// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.buildlogic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Pure pinned-source checks; can be compiled without Gradle or Groovy jars. */
public final class GeckoPermissionPolicyPatchTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected: permission-policy-source-directory");
        System.out.println("PASS: " + run(Path.of(args[0])) + " permission policy checks");
    }

    static int run(Path directory) throws Exception {
        passed = 0;
        byte[] original = Files.readAllBytes(directory.resolve("upstream/GeckoViewPermission.sys.mjs"));
        byte[] correspondingSource = Files.readAllBytes(directory.resolve("GeckoViewPermission.sys.mjs"));
        byte[] patched = GeckoPermissionPolicyPatch.apply(original);
        check(Arrays.equals(patched, correspondingSource), "Patch equals the exact published corresponding source");
        String before = new String(original, StandardCharsets.UTF_8), after = new String(patched, StandardCharsets.UTF_8);
        check(before.startsWith("/* This Source Code Form is subject to the terms of the Mozilla Public\n") &&
            after.substring(0, after.indexOf("import ")).equals(before.substring(0, before.indexOf("import "))),
            "Original Mozilla MPL notice is retained byte-for-byte");
        check(after.replace(GeckoPermissionPolicyPatch.REPLACEMENT, GeckoPermissionPolicyPatch.ANCHOR).equals(before),
            "Only the single reviewed permission persistence block changes");
        check(after.contains("if (perm.type !== \"geolocation\") {\n      Services.perms.addFromPrincipal(") &&
            after.contains("        allowOrDeny,\n        Services.perms.EXPIRE_NEVER\n"),
            "Only geolocation skips the existing native permission write");
        byte[] tampered = original.clone(); tampered[0] ^= 1;
        reject("Same-length upstream tampering is rejected", () -> GeckoPermissionPolicyPatch.apply(tampered));
        reject("Changed upstream length is rejected", () -> GeckoPermissionPolicyPatch.apply(Arrays.copyOf(original, original.length + 1)));
        reject("Already patched source is rejected", () -> GeckoPermissionPolicyPatch.apply(patched));
        reject("Missing persistence anchor is rejected", () -> GeckoPermissionPolicyPatch.replaceSingleAnchor(before.replace(GeckoPermissionPolicyPatch.ANCHOR, "")));
        reject("Duplicate persistence anchors are rejected", () -> GeckoPermissionPolicyPatch.replaceSingleAnchor(before + GeckoPermissionPolicyPatch.ANCHOR));
        reject("Whitespace drift in persistence anchor is rejected", () -> GeckoPermissionPolicyPatch.replaceSingleAnchor(before.replace("    Services.perms.addFromPrincipal(", "   Services.perms.addFromPrincipal(")));
        return passed;
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        passed++; System.out.println("OK " + label);
    }
    private static void reject(String label, Operation operation) throws Exception {
        try { operation.run(); }
        catch (IOException expected) { check(true, label); return; }
        throw new AssertionError(label);
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
}
