# Pinned Gecko geolocation permission policy adaptation

This directory contains the exact original and modified source of one MPL-2.0
GeckoView module. Miru changes only the native persistence of a geolocation
decision. Its application coordinator owns document-limited choices, ordinary
saved site choices, and private in-memory choices. An unchecked “remember” choice
must not become Gecko's permanent origin grant or denial.

## Provenance and corresponding source

- Official artifact: `org.mozilla.geckoview:geckoview-omni:158.0.20261021001945`.
- [Official AAR](https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview-omni/158.0.20261021001945/geckoview-omni-158.0.20261021001945.aar):
  243,952,420 bytes, SHA-256
  `a3af9d5ffb1f898df5b8782d083b69c4274c4f3275e31f3dac33d0d2aa16c692`.
- Original `assets/omni.ja`: SHA-256
  `b6dca3074e505b3d475504c864ba517138e383d6d9f23c6db94de34403c254b5`.
- Application version `158.0.2`; source revision
  [`e4ce606692cd81e6898547e2edd7c552fefcbb04`](https://hg.mozilla.org/releases/mozilla-release/rev/e4ce606692cd81e6898547e2edd7c552fefcbb04).
- Module packaged at `modules/GeckoViewPermission.sys.mjs`; upstream source
  [`mobile/android/modules/geckoview/GeckoViewPermission.sys.mjs`](https://hg.mozilla.org/releases/mozilla-release/file/e4ce606692cd81e6898547e2edd7c552fefcbb04/mobile/android/modules/geckoview/GeckoViewPermission.sys.mjs).
- [Original packaged source](upstream/GeckoViewPermission.sys.mjs): 7,785 bytes,
  SHA-256 `44199b91bf3e62a54b19c2dcf9c57f18141635c99f58dc8236b69fd5d0c2d35b`.
- [Miru corresponding source](GeckoViewPermission.sys.mjs): 8,000 bytes,
  SHA-256 `ba77185def5e1bee64300abc9fd065470cf6758eaaf461ff947c6e8b50db0489`.
- Full [MPL-2.0 license](MPL-2.0.txt); the original module's MPL notice is
  preserved verbatim. Miru's modification is attributed beside the changed block.

The source was copied from the locally cached, digest-verified official artifact;
these records do not claim a new network fetch or native runtime verification.
Keep both source files, the license, this record, and the builder with a binary
release so recipients can obtain the exact modified source. The fixed release
revision also has a [complete upstream source archive](https://hg.mozilla.org/releases/mozilla-release/archive/e4ce606692cd81e6898547e2edd7c552fefcbb04.tar.gz).

## Exact change and lifecycle boundary

The original module's `promptPermission()` awaits the content result, then asks
for Android location permission for an allowed geolocation request. It finally
calls `Services.perms.addFromPrincipal(principal, perm.type, allowOrDeny,
Services.perms.EXPIRE_NEVER)`. Miru wraps only that write in
`if (perm.type !== "geolocation")`. The original content and Android checks,
allow/cancel result, storage-access special case, and persistence behavior of all
other permission types remain unchanged.

The Java patcher accepts only the exact original length/digest and exactly one
matching persistence block, then verifies the exact resulting length/digest.
It supplies no general resource replacement option. The final overlay contains
4,078 byte-identical original resources, this one exact modified module, and the
five pinned, unchanged Mozilla blocklist additions. No original path is removed.

This patch does not remove permissions cached by older Miru builds. Application
startup must enumerate all native permissions, reset only geolocation entries,
and confirm their absence or PROMPT value before creating/restoring sessions.
Site-setting writes must keep native geolocation at PROMPT. A local policy
change alone cannot revoke a previous native ALLOW.

The local JavaScript scenarios run the actual original and modified modules with
stubbed Gecko services to compare outcomes and writes. Archive checks verify the
packaged bytes. Neither establishes native C++ permission-cache behavior, upgrade
migration, document navigation behavior, or private browsing on a device; those
remain separate acceptance work.
