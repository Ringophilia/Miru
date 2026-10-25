# Gecko blocklist and exact permission/navigation asset overlay build logic

This build-time Java code takes one pinned official GeckoView AAR and the five
unmodified resources documented in
[`third_party/mozilla-blocklist/README.md`](../../third_party/mozilla-blocklist/README.md).
It writes a generated `omni.ja` without altering the dependency cache. Exactly
4,077 original payloads remain unchanged; exactly two original modules receive
the pinned geolocation policy and top-level document notification changes documented in
[`third_party/mozilla-permission-policy/README.md`](../../third_party/mozilla-permission-policy/README.md).
The sources are kept in this directory and
compiled by `buildSrc` through its Java source directory configuration. Running
the standalone tests does not configure/build the Miru Android project or use a
device.

## What is validated

`GeckoBlocklistOverlay.build()` rejects an unexpected artifact version, AAR size
or SHA-256, extracted omni digest, source revision/version, original entry count,
snapshot file set/digests, collection/index timestamp, and mismatched attachment
metadata. It limits archive input sizes, rejects duplicate/unsafe names, and
checks entry CRCs. It verifies 4,077 original resources' uncompressed hashes and
lengths, all 4,079 original compression methods, and the two exact patched modules
after writing, then atomically replaces the output
when the filesystem supports that operation. An existing output survives input
validation failure.

Only these five paths are added:

```text
defaults/settings/blocklists/addons-bloomfilters.json
defaults/settings/blocklists/addons-bloomfilters/addons-mlbf.bin
defaults/settings/blocklists/addons-bloomfilters/addons-mlbf.bin.meta.json
defaults/settings/blocklists/addons-bloomfilters/softblocks-addons-mlbf.bin
defaults/settings/blocklists/addons-bloomfilters/softblocks-addons-mlbf.bin.meta.json
```

Every original path must be absent from this addition set; overriding even an
existing blocklist resource fails. In particular, the original
`defaults/settings/last_modified.json` remains byte-identical.

Only `modules/GeckoViewPermission.sys.mjs` and `modules/GeckoViewNavigation.sys.mjs` may change. The separate
`GeckoPermissionPolicyPatch` checks the original 7,785-byte SHA-256, replaces one
exact persistence block, and checks the resulting 8,000-byte SHA-256. It wraps
only the native permission write in `if (perm.type !== "geolocation")`; other
permission behavior and the original MPL notice remain unchanged. There is no
wildcard or caller-configurable replacement mechanism. Application startup must
separately clear and verify old native geolocation decisions before sessions load.

`GeckoDocumentLocationPatch` checks the original 22,791-byte navigation module
SHA-256, adds the fourth web-progress callback argument `aFlags`, and sends a
`Miru:DocumentLocation` notification only when `aWebProgress.isTopLevel` is true,
immediately before the original `GeckoView:LocationChange` sender. The payload
contains only Boolean `sameDocument`, derived from Gecko's
`LOCATION_CHANGE_SAME_DOCUMENT` flag; URL equality is not used. The fixed
`Messaging.sys.mjs` API is `sendRequest(type, payload)`, so the event name is its
first argument rather than a `type` field in the payload. The original location
message, translations, navigation and permission logic remain byte-identical.
The resulting 23,179-byte module has its own exact SHA-256 pin. No subframe
notification is emitted. See the corresponding source/provenance above.

The archive is rebuilt as an ordinary ZIP with sorted paths and fixed entry
timestamps. Two runs on the same ZIP writer must match byte-for-byte. Repacking
changes container layout/compressed bytes, so the original whole-omni digest is
not claimed for the output. `GenerateGeckoBlocklistAssets` includes the executing
JVM vendor/runtime and OS/architecture in its task inputs because Deflater's
compressed output can differ across writers.

Pinned Gecko's
[`nsZipArchive::BuildFileList`](https://hg.mozilla.org/releases/mozilla-release/file/e4ce606692cd81e6898547e2edd7c552fefcbb04/modules/libjar/nsZipArchive.cpp#l657)
first detects the optimized central-directory layout. Its alternate branch
scans for the normal ZIP end record and reads the central-directory offset;
this is source evidence for reading an ordinary ZIP. It is not runtime evidence
for successful Gecko startup or consumption of these resources.

## Standalone verification

Use JDK 21 and an installed Gradle distribution (only its API and Groovy JSON
JARs are used). Pass the official cached AAR path explicitly:

```powershell
./tools/gecko-blocklist-buildlogic/run-tests.ps1 `
  -GeckoAar '<cached geckoview-omni-158.0.20261021001945.aar>' `
  -GradleHome '<installed gradle-9.3.1 directory>' `
  -JdkHome '<installed JDK 21 directory>'
```

The runner compiles all production classes (including the Gradle task) and the
standalone test harness with `javac`, then runs the archive and pure patch checks.
An installed Node.js runs the actual original and patched modules against local
stubs; use `-NodePath` when Node is not on PATH.

- Two complete builds produce identical output.
- An independent `ZipFile`/digest audit compares 4,077 original payloads and all
  original compression methods, verifies the exact two module patches and the
  unchanged original index, and checks exactly five additions against raw files.
- Pure Java patch checks reject upstream tampering, changed length, an already
  patched module, missing/duplicate/drifted anchors, and compare the generated
  patch with the published corresponding source while retaining the MPL notice.
- Twenty-two actual-module JavaScript scenarios compare allow/cancel outcomes,
  content/Android permission requests, and unchanged non-geolocation writes;
  geolocation grant, denial, PROMPT, and Android denial produce no native write.
- Actual original/patched navigation modules run against local stubs for top-level
  and subframe progress, same-document flag combinations, reload to the same URL,
  initial about:blank filtering, gesture/private variants, and permission lookup
  failure. Tests require the new Boolean-only notification before the unchanged
  original message and no subframe notifications. These are source/module tests,
  not native Gecko/device acceptance.
- Different version, wrong-length/same-length corrupt AAR, missing/extra/tampered
  snapshot files, invalid JSON, inconsistent timestamps/records, invalid attachment
  hashes/sizes/types/key formats/locations, and unsafe archive paths are rejected.
- Invalid input preserves the previous output and temporary extraction files are
  cleaned.

Evidence is written to `artifacts/b13-policy-overlay/standalone-compile.log`,
`standalone-tests.log`, `permission-policy-js-tests.log`,
`document-location-js-tests.log`, and
`standalone-work/original-resource-sha256.tsv`.
The two-module standalone run on 2026-10-26 (Asia/Shanghai) passed with JDK
21.0.12.1 Microsoft, installed Gradle 9.3.1 API jars and Node.js 24.19.0:

```text
PASS: 59 checks
overlayBytes=16459282
overlaySha256=f0531a43b294533f7929afc74a03d265a1e8c2a93076c0c57dd7a2a2fb0af1f8
PASS: 22 actual-module permission policy scenarios with local stubs; native runtime not tested
PASS: 54 actual-module document-location scenarios with local stubs; native runtime not tested
```

Fresh evidence is under
`artifacts/b13-upload-lifecycle/gecko-navigation-20261026-011500/retry-1/`:
`command.txt`, compile/test logs, JVM/Node versions, the original-resource TSV,
both deterministic archive builds and `verification-summary.json`. The latter
records the original AAR/omni pins, 4,077 unchanged resources, both exact patches,
five additions, preserved compression methods and the unchanged index SHA-256.
The first attempt's archive/Java checks passed but its navigation JS exposure-error
stub also threw during permission URI serialization. Only that stub was narrowed
to the location URI; the entire suite was rerun successfully. First-attempt logs
remain in the parent directory. No Gradle or device was used; APK verification
and native listener/lifecycle acceptance remain separate.

No historical digest below is valid as the new expected output.

The historical one-module permission-policy JDK 21.0.12 Microsoft / Gradle 9.3.1
jar-only Windows run on 2026-10-25 passed (4,078 unchanged + one exact module +
five additions). It does not verify the new navigation patch:

```text
PASS: 46 checks
overlayBytes=16459125
overlaySha256=2a8da1a2c185d24f11e385e794b18b5d169a4e5afa64ce7f8882f8af33b14cb1
PASS: 22 actual-module permission policy scenarios with local stubs; native runtime not tested
```

The earlier blocklist-only JDK 21.0.12 Microsoft / Gradle 9.3.1 Windows run
produced the following historical result. It does not verify this permission
patch or identify the new generated archive:

```text
PASS: 36 checks
overlayBytes=16459034
overlaySha256=65becbcb9be64a1d50f84248e7a6d95531a15dfab25514dff631dcce3e358939
```

These tests establish archive generation and input rejection. They do not claim
Android packaging or runtime acceptance.

## App wiring and acceptance

`GenerateGeckoBlocklistAssets` is a cacheable Gradle task with explicit AAR,
artifact-version and snapshot-directory inputs, plus an output asset directory.
Task registration itself performs no configuration-time file I/O. The current
`buildSrc/build.gradle.kts` makes the task type available to the app build using
`java-library`, `gradleApi()`, `localGroovy()`, and JDK 21. It compiles the Java
sources in this directory; none of the build logic is application runtime code.
The current `app/build.gradle.kts` wiring works as follows:

1. Each variant's actual `${variant.name}RuntimeClasspath` provides an
   `artifactView` with `artifactType=aar`. A `ModuleComponentIdentifier` filter
   selects `org.mozilla.geckoview:geckoview-omni` at the pinned version. This
   supplies the original selected AAR, whose length/digest the task validates.
   Empty or multiple matches fail at `singleFile`; a changed/repacked AAR fails
   the immutable pins. Resolution remains lazy through the task input provider.
2. The task's `snapshotDirectory` is
   `third_party/mozilla-blocklist/snapshot`; `outputAssets` is
   `app/build/generated/gecko-blocklist/<variant>`.
3. `variant.sources.assets.addGeneratedSourceDirectory` attaches that generated
   directory. Its `omni.ja` becomes the app's `assets/omni.ja`, with app-asset
   precedence over the library's resource. APK ZIP resource options do not
   perform Android assets merging.

`verify-apk.ps1` independently verifies the actual merged asset. It requires
exactly one `assets/omni.ja`, checks the supplied freshly verified generated
digest, compares 4,077 original payload hashes/lengths against the standalone
manifest, verifies exactly the pinned permission and navigation module patches, and checks
exactly five pinned additions against the raw snapshot files:

```powershell
./tools/gecko-blocklist-buildlogic/verify-apk.ps1 `
  -Apk app/build/outputs/apk/debug/app-debug.apk `
  -MinimumApkWriteTimeUtc '<completed build cutoff in UTC>' `
  -ExpectedOmniSha256 '<digest from the new verified standalone archive>'
```

Supply the cutoff for a completed build. `-ExpectedOmniSha256` is mandatory;
there is no stale blocklist-only default. If the writer changes, repeat the
standalone integrity/determinism checks. The JSON summary is written to
`artifacts/b13-policy-overlay/verify-apk-summary.json`. Deliberately wrong expected
digests and stale APK cutoffs were confirmed to fail in the earlier blocklist-only
verification; repeat those checks for the new two-module build. Old one-module APKs must fail
the new navigation-module pin even when their original resource manifest is valid.

Fresh-profile runtime acceptance remains separate: prove signed uBO installation
and actual blocking under unchanged signature, TLS, blocklist, and update
policies; repeat offline and after a cold start; test normal online blocklist
updates. Package contents do not establish runtime consumption or a fix for the
historical first-install timeout. See
[`docs/M1-BLOCKLIST-VALIDATION.md`](../../docs/M1-BLOCKLIST-VALIDATION.md) for the
verified package snapshot and remaining acceptance work. Preserve Mozilla's
license/provenance and corresponding-source availability when publishing a
binary/source release.
