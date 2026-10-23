# Gecko blocklist asset overlay build logic

This build-time Java code takes one pinned official GeckoView AAR and the five
unmodified resources documented in
[`third_party/mozilla-blocklist/README.md`](../../third_party/mozilla-blocklist/README.md).
It writes a generated `omni.ja` without altering the dependency cache or any of
the original 4,079 resource payloads. The sources are kept in this directory and
compiled by `buildSrc` through its Java source directory configuration. Running
the standalone tests does not configure/build the Miru Android project or use a
device.

## What is validated

`GeckoBlocklistOverlay.build()` rejects an unexpected artifact version, AAR size
or SHA-256, extracted omni digest, source revision/version, original entry count,
snapshot file set/digests, collection/index timestamp, and mismatched attachment
metadata. It limits archive input sizes, rejects duplicate/unsafe names, and
checks entry CRCs. It verifies every original resource's uncompressed hash,
length, and compression method after writing, then atomically replaces the output
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

The runner compiles both production classes (including the Gradle task) and the
standalone test harness with `javac`. It then runs 36 checks:

- Two complete builds produce identical output.
- An independent `ZipFile`/digest audit compares all 4,079 original payloads and
  compression methods, verifies the unchanged original index, and checks exactly
  five additions against their original raw files.
- Different version, wrong-length/same-length corrupt AAR, missing/extra/tampered
  snapshot files, invalid JSON, inconsistent timestamps/records, invalid attachment
  hashes/sizes/types/key formats/locations, and unsafe archive paths are rejected.
- Invalid input preserves the previous output and temporary extraction files are
  cleaned.

Evidence is written to `artifacts/m1-blocklist/standalone-compile.log`,
`standalone-tests.log`, and `standalone-work/original-resource-sha256.tsv`.
The verified JDK 21.0.12 Microsoft / Gradle 9.3.1 Windows run produced:

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

The current debug APK's actual merged asset has been verified with
`verify-apk.ps1`, independently of the production archive verifier. It finds
exactly one `assets/omni.ja`, checks its expected generated digest, compares all
4,079 original payload hashes/lengths against the standalone manifest, and
checks exactly five pinned additions against the raw snapshot files:

```powershell
./tools/gecko-blocklist-buildlogic/verify-apk.ps1 `
  -Apk app/build/outputs/apk/debug/app-debug.apk `
  -MinimumApkWriteTimeUtc '2026-10-23T13:06:08Z'
```

The cutoff above identifies the verified development build; supply the cutoff
for a later completed build when repeating the check. The default expected omni
digest identifies the verified JDK writer. If the writer legitimately changes,
pass `-ExpectedOmniSha256` from a newly verified generated archive and repeat
the standalone integrity/determinism checks. The JSON summary is written to
`artifacts/m1-blocklist/verify-apk-summary.json`. Deliberately wrong expected
digests and stale APK cutoffs were also confirmed to fail.

Fresh-profile runtime acceptance remains separate: prove signed uBO installation
and actual blocking under unchanged signature, TLS, blocklist, and update
policies; repeat offline and after a cold start; test normal online blocklist
updates. Package contents do not establish runtime consumption or a fix for the
historical first-install timeout. See
[`docs/M1-BLOCKLIST-VALIDATION.md`](../../docs/M1-BLOCKLIST-VALIDATION.md) for the
verified package snapshot and remaining acceptance work. Preserve Mozilla's
license/provenance and corresponding-source availability when publishing a
binary/source release.
