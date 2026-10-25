// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.buildlogic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** One reviewed change to one pinned Gecko module; no general resource override mechanism. */
public final class GeckoPermissionPolicyPatch {
    public static final String RESOURCE = "modules/GeckoViewPermission.sys.mjs";
    public static final int ORIGINAL_BYTES = 7785;
    public static final String ORIGINAL_SHA256 = "44199b91bf3e62a54b19c2dcf9c57f18141635c99f58dc8236b69fd5d0c2d35b";
    // Reviewed corresponding source: third_party/mozilla-permission-policy/GeckoViewPermission.sys.mjs.
    public static final int PATCHED_BYTES = 8000;
    public static final String PATCHED_SHA256 = "ba77185def5e1bee64300abc9fd065470cf6758eaaf461ff947c6e8b50db0489";
    static final String ANCHOR = """
            Services.perms.addFromPrincipal(
              principal,
              perm.type,
              allowOrDeny,
              Services.perms.EXPIRE_NEVER
            );
        """;
    static final String REPLACEMENT = """
            // Miru owns geolocation decisions, including choices limited to this document.
            // Modifications Copyright (c) 2026 Ringophilia and xiaomao; MPL-2.0.
            if (perm.type !== "geolocation") {
              Services.perms.addFromPrincipal(
                principal,
                perm.type,
                allowOrDeny,
                Services.perms.EXPIRE_NEVER
              );
            }
        """;

    private GeckoPermissionPolicyPatch() {}

    public static byte[] apply(byte[] original) throws IOException {
        require(original.length == ORIGINAL_BYTES && ORIGINAL_SHA256.equals(sha256(original)),
            "Gecko permission module differs from the pinned official source");
        String patched = replaceSingleAnchor(new String(original, StandardCharsets.UTF_8));
        byte[] result = patched.getBytes(StandardCharsets.UTF_8);
        require(result.length == PATCHED_BYTES && PATCHED_SHA256.equals(sha256(result)),
            "Gecko permission patch differs from the reviewed corresponding source");
        return result;
    }

    static String replaceSingleAnchor(String source) throws IOException {
        int first = source.indexOf(ANCHOR);
        require(first >= 0 && source.indexOf(ANCHOR, first + ANCHOR.length()) == -1,
            "Expected exactly one Gecko permission persistence anchor");
        return source.substring(0, first) + REPLACEMENT + source.substring(first + ANCHOR.length());
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
