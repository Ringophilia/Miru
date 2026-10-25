// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.buildlogic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Exact, pinned top-level document notification; never changes Gecko's location payload. */
public final class GeckoDocumentLocationPatch {
    public static final String RESOURCE = "modules/GeckoViewNavigation.sys.mjs";
    public static final int ORIGINAL_BYTES = 22791;
    public static final String ORIGINAL_SHA256 = "fb5926acbf81a405929309b39076f1ef86c4e407795f8903963d3f15158f208c";
    public static final int PATCHED_BYTES = 23179;
    public static final String PATCHED_SHA256 = "a5e17b2c1b6253dd531aa69a9f9319f4f090831b2c5b9f9a4c814fdd9b5cb598";
    static final String SIGNATURE = "  onLocationChange(aWebProgress, aRequest, aLocationURI) {\n";
    static final String PATCHED_SIGNATURE = "  onLocationChange(aWebProgress, aRequest, aLocationURI, aFlags) {\n";
    static final String SENDER = "    this.eventDispatcher.sendRequest(\"GeckoView:LocationChange\", message);\n";
    static final String NOTIFICATION = """
            // Miru observes the native document boundary before the unchanged location event.
            // Modifications Copyright (c) 2026 Ringophilia and xiaomao; MPL-2.0.
            if (aWebProgress.isTopLevel) {
              this.eventDispatcher.sendRequest("Miru:DocumentLocation", {
                sameDocument:
                  !!(aFlags & Ci.nsIWebProgressListener.LOCATION_CHANGE_SAME_DOCUMENT),
              });
            }
        """;

    private GeckoDocumentLocationPatch() {}

    public static byte[] apply(byte[] original) throws IOException {
        require(original.length == ORIGINAL_BYTES && ORIGINAL_SHA256.equals(sha256(original)),
            "Gecko navigation module differs from the pinned official source");
        byte[] patched = replaceAnchors(new String(original, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        require(patched.length == PATCHED_BYTES && PATCHED_SHA256.equals(sha256(patched)),
            "Gecko document notification differs from the reviewed corresponding source");
        return patched;
    }

    static String replaceAnchors(String source) throws IOException {
        String withFlags = replaceOnce(source, SIGNATURE, PATCHED_SIGNATURE);
        return replaceOnce(withFlags, SENDER, NOTIFICATION + SENDER);
    }

    private static String replaceOnce(String source, String anchor, String replacement) throws IOException {
        int first = source.indexOf(anchor);
        require(first >= 0 && source.indexOf(anchor, first + anchor.length()) == -1,
            "Expected exactly one reviewed Gecko navigation anchor");
        return source.substring(0, first) + replacement + source.substring(first + anchor.length());
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
