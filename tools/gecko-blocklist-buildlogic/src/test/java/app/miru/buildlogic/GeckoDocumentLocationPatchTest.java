// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.buildlogic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Source identity/negative checks, complemented by actual-module JavaScript scenarios. */
public final class GeckoDocumentLocationPatchTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected: corresponding-source-directory");
        System.out.println("PASS: " + run(Path.of(args[0])) + " document location patch checks");
    }

    static int run(Path directory) throws Exception {
        passed = 0;
        byte[] original = Files.readAllBytes(directory.resolve("upstream/GeckoViewNavigation.sys.mjs"));
        byte[] patched = GeckoDocumentLocationPatch.apply(original);
        check(Arrays.equals(patched, Files.readAllBytes(directory.resolve("GeckoViewNavigation.sys.mjs"))),
            "Navigation patch equals the published corresponding source");
        String before = new String(original, StandardCharsets.UTF_8), after = new String(patched, StandardCharsets.UTF_8);
        check(before.startsWith("/* This Source Code Form is subject to the terms of the Mozilla Public\n") &&
            after.substring(0, after.indexOf("import ")).equals(before.substring(0, before.indexOf("import "))),
            "Navigation module retains the original MPL notice byte-for-byte");
        check(after.replace(GeckoDocumentLocationPatch.PATCHED_SIGNATURE, GeckoDocumentLocationPatch.SIGNATURE)
            .replace(GeckoDocumentLocationPatch.NOTIFICATION, "").equals(before),
            "Only the callback flags parameter and preceding notification change");
        check(after.contains(GeckoDocumentLocationPatch.NOTIFICATION + GeckoDocumentLocationPatch.SENDER),
            "Top-level document notification directly precedes the unchanged native location sender");
        byte[] tampered = original.clone(); tampered[0] ^= 1;
        reject("Navigation same-length upstream tampering", () -> GeckoDocumentLocationPatch.apply(tampered));
        reject("Navigation changed upstream length", () -> GeckoDocumentLocationPatch.apply(Arrays.copyOf(original, original.length + 1)));
        reject("Already-patched navigation source", () -> GeckoDocumentLocationPatch.apply(patched));
        for (String anchor : new String[] {GeckoDocumentLocationPatch.SIGNATURE, GeckoDocumentLocationPatch.SENDER}) {
            reject("Missing navigation anchor", () -> GeckoDocumentLocationPatch.replaceAnchors(before.replace(anchor, "")));
            reject("Duplicate navigation anchor", () -> GeckoDocumentLocationPatch.replaceAnchors(before + anchor));
            reject("Whitespace-drifted navigation anchor", () -> GeckoDocumentLocationPatch.replaceAnchors(before.replace(anchor, anchor.substring(1))));
        }
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
