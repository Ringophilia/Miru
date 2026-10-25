# Pinned Gecko permission policy and document-location adaptations

This directory contains the exact original and modified source of two MPL-2.0
GeckoView modules. Miru changes geolocation decision persistence in the permission
module and adds a top-level document-boundary notification in the navigation module. Its application coordinator owns document-limited choices, ordinary
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
4,077 byte-identical original resources, the two exact modified modules, and the
five pinned, unchanged Mozilla blocklist additions (4,084 entries in total). No original path is removed.

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

## Navigation module corresponding source and exact event contract

- Same official AAR, original omni digest, Gecko 158.0.2 application version and
  `e4ce606692cd81e6898547e2edd7c552fefcbb04` revision recorded above.
- Packaged resource: `modules/GeckoViewNavigation.sys.mjs`; upstream source
  [`mobile/android/modules/geckoview/GeckoViewNavigation.sys.mjs`](https://hg.mozilla.org/releases/mozilla-release/file/e4ce606692cd81e6898547e2edd7c552fefcbb04/mobile/android/modules/geckoview/GeckoViewNavigation.sys.mjs).
- [Original packaged source](upstream/GeckoViewNavigation.sys.mjs): 22,791 bytes,
  SHA-256 `fb5926acbf81a405929309b39076f1ef86c4e407795f8903963d3f15158f208c`.
- [Miru corresponding source](GeckoViewNavigation.sys.mjs): 23,179 bytes,
  SHA-256 `a5e17b2c1b6253dd531aa69a9f9319f4f090831b2c5b9f9a4c814fdd9b5cb598`.
- Original MPL notice remains byte-identical, and the added block attributes
  Miru's modification. The same [MPL-2.0 license](MPL-2.0.txt) applies.

The fixed original `onLocationChange` names only three callback arguments. The
patch adds the fourth argument `aFlags`, then inserts one guarded send directly
before the unchanged `GeckoView:LocationChange` send. Only top-level progress
emits `Miru:DocumentLocation`; its only payload field is Boolean `sameDocument`,
derived from `Ci.nsIWebProgressListener.LOCATION_CHANGE_SAME_DOCUMENT`.
Subframe progress emits no Miru document notification. The original location
payload, initial about:blank filtering, translation callback, permission handling
and navigation remain unchanged. Same-URL reload is not inferred to be the same
document, and fragment/history changes are not inferred to be new documents.

The sender uses the fixed `modules/Messaging.sys.mjs` signature
`sendRequest(aType, aMsg, aCallback)`, which forwards to `dispatch` synchronously.
Accordingly the call is `sendRequest("Miru:DocumentLocation", { sameDocument })`;
there is no additional `type`, URL, title, file name or document content in the
payload. Source call ordering alone is not proof of native listener delivery or
thread ordering; the session adapter and real navigation tests must verify that
separately.

`GeckoDocumentLocationPatch` rejects any upstream byte/hash drift, missing or
duplicate signature/sender anchor, and any result differing from this exact
corresponding source. Java archive verification preserves all 4,079 original
paths/compression methods and the original index; the APK verifier requires
4,077 unchanged payloads, one exact permission patch, one exact navigation patch,
and five pinned additions. No replacement wildcard is introduced.

The 2026-10-26 standalone run passed 59 Java/archive checks, 22 actual permission
module scenarios and 54 actual navigation module scenarios with local stubs.
Evidence and the exact command are in
`artifacts/b13-upload-lifecycle/gecko-navigation-20261026-011500/retry-1/`;
the generated omni is 16,459,282 bytes, SHA-256
`f0531a43b294533f7929afc74a03d265a1e8c2a93076c0c57dd7a2a2fb0af1f8`.
Both complete builds were byte-identical; independent archive audits verified
4,077 unchanged payloads, the two exact patches, five additions, all original
compression methods and the unchanged index. The first run's overly broad
exposure-error test stub was corrected and its failed JS log retained separately.

Actual-module local-stub tests cover top/subframe flag combinations, reload at an
unchanged URL, initial blank filtering and unchanged original messages/side
effects. Neither those tests nor packaging establishes native runtime consumption,
same-document handling or upload-lease cleanup on a device. No Gradle, APK or
device test was run for this record. The previous one-module overlay's
digest/results remain historical only; fresh APK and device evidence is still
required for this two-module overlay.
