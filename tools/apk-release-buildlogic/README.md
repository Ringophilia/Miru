# Verified compressed-entry reuse for release APKs

This standalone Java helper avoids recompressing unchanged Gecko native libraries
after an application-only change. It builds a **new unsigned, not yet aligned APK**
from the current AGP APK, using a completed optimized APK as a representation cache.
There is no Gradle wiring, Android build, SDK tool patch, compression, signing,
device access, downloading of runtime assets, or change to application payloads.

## Fixed official API contract

Dependency: Google's `com.android:zipflinger:9.1.1`, obtained from
[Google Maven](https://dl.google.com/dl/android/maven2/com/android/zipflinger/9.1.1/zipflinger-9.1.1.jar).
The official binary SHA-256 is pinned by both the runner and Java implementation:

```text
d2ad6ddec6da2ae142a8fe3af2f2529f3fbf77852e2db7ff261ad7b924b119fa
```

The implementation was written after reading the matching
[official sources JAR](https://dl.google.com/dl/android/maven2/com/android/zipflinger/9.1.1/zipflinger-9.1.1-sources.jar):

- `ZipSource(Path)` reads an existing archive's central directory.
- `ZipSource.select(name, name, ZipSource.COMPRESSION_NO_CHANGE, Source.NO_ALIGNMENT)`
  selects an entry. `COMPRESSION_NO_CHANGE` is `-2`; the actual implementation of
  `newZipSourceEntryFor` chooses `ZipSourceEntry` in this branch.
- `ZipSourceEntry.writeTo` transfers the selected compressed payload's original
  byte range directly from its source channel. It does not inflate or deflate it.
  Do not substitute a numeric compression level: that can select the pipe which
  inflates and deflates, irrespective of an outdated Javadoc sentence.
- `ZipArchive(newPath, Zip64.Policy.FORBID)` creates a fresh ZIP. `add(ZipSource)`
  opens the selected source, writes its entries and closes it in `finally`.
  Archive-level input gaps, APK signing blocks and comments are not imported.
- `Source`'s public attribute getters allow comparison of the selected current
  and cache entries without reflection into AGP or Zipflinger internals.

The upstream [Zipflinger design notes](https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/zipflinger/README.md)
describe this compressed-format-preserving transfer. No SDK or dependency JAR
is modified or copied into this repository.

## Validation and selection

Both input whole-file SHA-256 digests are mandatory. Do not point the helper at
an optimizer which is still writing its output. Pin the digest only after the
external process has completed and its exit status and archive integrity have
been checked.

The helper independently streams all entries using the JDK `ZipFile` reader,
computes their **actual uncompressed SHA-256, length and CRC32**, compares the
length/CRC to ZIP declarations, and cross-checks the path set and compression
metadata against Zipflinger. It also hashes the exact compressed byte range.
Duplicate names, unsafe names, unsupported compression, ZIP symbolic links,
unreadable/truncated archives, invalid digests, and excessive sizes are rejected.
Limits are 512 MiB per archive/entry, 2 GiB total raw size, and 100,000 entries.

For each current non-signature path, a cache representation is selected only if:

1. The cache contains the **same path**.
2. Actual raw SHA-256, length, CRC32 and compression method all match.
3. Creator/version and external attributes match.
4. Its compressed representation is strictly smaller.

Otherwise that entry is copied from the current APK, with its existing compressed
bytes. A changed DEX, JavaScript asset, native library, resource, license, or new
file therefore comes from the current APK. A cache-only path never enters output.
Cache changes with identical length and CRC cannot substitute different payloads
because the actual SHA must also match. The cache is a completed APK with no
special index format; corrupted entries anywhere in it are rejected.

Only standard v1 signature metadata directly inside `META-INF/` is omitted:
`MANIFEST.MF`, `*.SF`, `*.RSA`, `*.DSA`, `*.EC`, and `SIG-*`, case-insensitively.
Nested paths and license files such as `META-INF/AL2.0`, `META-INF/LGPL2.1`,
`META-INF/LICENSE*` and `assets/licenses/**` are retained. As with APK signing
tools, a direct `META-INF/NAME.SF` is classified as signature metadata; do not
use reserved signature names for license content. A new archive also discards
the v2/v3 signing block instead of copying it as a gap before the central directory.

Before publishing, a second full audit verifies the exact current non-signature
path set, actual payloads, metadata and **selected source's compressed bytes**.
The output must have no v1 files or APK signing-block footer. Both source digests
are rechecked after copying to detect concurrent mutation. Input/validation
failure preserves any previous output and removes the helper's temporary file.
Successful replacement is atomic when the filesystem supports it, with a normal
replace fallback otherwise. No recursive deletion is used.

## Compile and run

Requires JDK 21 and the pinned Zipflinger JAR. No Gradle process is started.

```powershell
./tools/apk-release-buildlogic/run-tests.ps1 `
  -ZipflingerJar '<cached zipflinger-9.1.1.jar>' `
  -JdkHome '<JDK 21 home>'
```

The runner prints its unique `artifacts/apk-size-reuse/run-*/` evidence directory,
including `classes/`, fixtures, compile log, tests log, and a fixture JSON report.
To package a real **completed** cache, use those classes or compile only
`src/main/java/app/miru/release/ApkCompressionReuse.java`:

```powershell
& '<JDK 21>/bin/java.exe' -cp '<classes>;<zipflinger-9.1.1.jar>' `
  app.miru.release.ApkCompressionReuse `
  '<current AGP APK>' '<current complete SHA-256>' `
  '<completed optimized cache APK>' '<cache complete SHA-256>' `
  '<new unsigned output APK>' '<report JSON>'
```

Both output parents must exist. Neither output nor report may alias either input
or each other (existing hard links are checked too). Input symbolic-link files
and symbolic-link output files are rejected. The report includes whole-file pins,
per-entry origin/actual payload/compressed SHA, counts and `PACKAGED_UNSIGNED`.
Any failed invocation returns nonzero; do not use an old report without matching
its recorded output digest to the actual output.

Then run the SDK's **ordinary** `zipalign -P 16 4`, followed by the intended
`apksigner` certificate, alignment/signature verification, the independent APK
payload verifier, and release install/runtime validation. Do not rerun `-z` on
the merged APK, which would recompress all entries again. The helper is not a
substitute for those gates or for the `<110,000,000` final signed-file size gate.

## Fixture evidence

The JDK 21.0.12 Microsoft / Windows standalone run completed **74 checks** in
`artifacts/apk-size-reuse/run-20261024-073531-8c4c77de/tests.log`:

- Exact cached compressed bytes selected for unchanged native payloads; changed
  DEX and JavaScript compressed bytes selected from the current source.
- Same-length/same-CRC distinct payloads rejected as cache substitutes by SHA.
- Cache-only paths excluded, new paths included, stored resources and empty
  directories preserved, license paths retained, and both signature forms removed.
- Deterministic output, independent JDK readability, final path/payload/compressed
  byte equality and report pins verified.
- Duplicate names, corruption, truncated cache, traversal/absolute/drive/backslash
  names, symlink entries, wrong pins, metadata changes, aliases and wrong generated
  path sets/payloads rejected; previous outputs survive failures.

These are small synthetic archives using ordinary JDK Deflater fixture levels.
A second clean compilation also passed the 74 checks in
`artifacts/apk-size-reuse/run-20261024-074509-646d4682/`.

## Completed unsigned APK experiment

The independent SDK Zopfli process completed with exit code 0 in **854.184 s**.
Its completed cache `artifacts/apk-size/Miru-0.5.1-zopfli-aligned-unsigned.apk`
is 107,570,406 bytes, SHA-256
`204ad07b3fd782a1c0324567dae33c13e455a74176b9fd2838ae056a55702bcc`.
The independent Python verifier matched all **1516** application payloads against
the original 111,060,934-byte 0.5.1 APK, and SDK alignment checking returned 0.
Evidence: `artifacts/apk-size/zopfli-result.json` and
`zopfli-payload-verification.json`.

The helper then processed that completed cache with the original current APK
SHA `a34b9692d99845b983f90bccc3b045a3d30a9943517d41e5aad5fe2ef8d4939e`.
It reused **1220** smaller compressed entries and selected **296** current entries,
producing 107,569,934 bytes with SHA-256
`74fbe1a46fbed1db57b1697867aa0d14f9303de4af3ec19a9ab4bd9594216f64`.
All 1516 payloads again passed the independent Python verifier.
Evidence: `artifacts/apk-size/reuse-original-report.json` and
`reuse-payload-verification.json`. Both products are **unsigned intermediate
artifacts**; these experiments do not prove a final signed release or runtime gate.

## Optional standalone release pipeline

[`../optimize-release.ps1`](../optimize-release.ps1) requires PowerShell 7 and performs the packaging and
verification sequence without modifying global Gradle configuration:

1. Validate paths, complete input SHA pins and the pinned official Zipflinger JAR.
2. Verify the current APK's SDK signature and obtain its certificate SHA digests.
3. Compile this production Java helper with JDK 21, then raw-copy selected entries.
4. Run ordinary SDK `zipalign -P 16 4` (no `-z`), then SDK `apksigner`.
5. Verify final alignment/signature and require the same signer certificate set
   as the verified current APK.
6. Run the independent `tools/verify_apk_payloads.py` for all payloads with strict
   `<110,000,000` bytes, check its exact result pins, and recheck both input hashes.
7. Move the verified signed candidate to a **new** requested output path. It
   refuses an existing output, including one created during verification.

Example parameter contract (the named password variables must already be present
in the inherited process environment):

```powershell
./tools/optimize-release.ps1 `
  -CurrentApk '<current signed AGP APK>' -CurrentSha256 '<complete SHA-256>' `
  -CacheApk '<completed optimized cache APK>' -CacheSha256 '<complete SHA-256>' `
  -ZipflingerJar '<official zipflinger-9.1.1.jar>' `
  -JdkHome '<JDK 21 home>' -BuildToolsDirectory '<SDK build-tools/37.0.0>' `
  -PythonExecutable '<Python executable>' `
  -KeyStorePath '<existing keystore>' -KeyAlias '<existing key alias>' `
  -StorePasswordEnvName MIRU_STORE_PASSWORD -KeyPasswordEnvName MIRU_KEY_PASSWORD `
  -OutputApk '<new final APK path>'
```

There are no inline password parameters, prompting, environment-value reads or
password logging in the wrapper. It passes only `env:NAME` to SDK `apksigner`,
which consumes the secret itself. Only syntactically valid environment names
are accepted. The script invokes the SDK's `lib/apksigner.jar` directly with
Java and argument arrays, avoiding `.bat`/`cmd.exe` parameter interpretation.
It always supplies `--out` and disables separate v4 `.idsig` generation; Android
manifest defaults determine the applicable embedded signature schemes.

`-ValidateOnly` performs parameter/existing-file/hash checks and creates no
output or evidence directory. It does **not** execute tools, parse APK contents,
open the keystore, or claim executable/certificate/runtime validity. Missing
password environment variables fail in the SDK signer during a real run rather
than causing an interactive prompt; the wrapper does not inspect their values.

Logs and JSON are retained in a unique sibling directory
`<output.apk>.verification-<UTC>-<id>/`. `pipeline.json` records tool/source hashes,
step exit codes, public certificate digests, final size/digest and verification
scope. `reuse.json` and `payload-verification.json` retain per-entry evidence.
Compiled classes and unsigned intermediate APKs are retained too; no recursive
cleanup or input/output overwrite is performed. The verified candidate and output
share a filesystem, and publication uses a no-overwrite file move. The Python
report's candidate path describes the staging file; match its recorded digest
to the final output after publication.

The preflight rejection harness ran **19 checks** in
`artifacts/apk-release-script-tests/run-20261024-075609-b170a586/tests.log`:
wrong/missing pins, missing inputs/keystore, wrong dependency bytes, invalid env
names, invalid/existing output paths and preservation of both inputs/output.
Its deliberately non-executable placeholders prove `-ValidateOnly` launches no
programs. Root repeated these 19 checks and all 74 Java checks on October 24.

The first real run exposed SDK 37's `V2 Signer:` output format. The extracted
`Get-MiruSignerDigests.ps1` now validates the declared signer count and recognizes
legacy numbered and versioned scheme labels, excluding public keys/source stamps.
Run `./tools/apk-release-buildlogic/test-signer-parser.ps1` for 18 parser checks,
including the actual SDK output fixture, conflicting identities and incomplete sets.
Differing key-rotation identities require explicit handling and are rejected.
The second run exposed case-distinct APK resource names in the verifier JSON;
PowerShell 7's `ConvertFrom-Json -AsHashtable` preserves these keys.

The complete pipeline then passed on the preserved, **older browser-core input**:
`artifacts/apk-size/Miru-0.5.1-pipeline-proof-arm64.apk`, **107,696,213 bytes**,
SHA-256 `ace087341183ea48abd826f199d0c6111fc86faff50f2b6eefd78fdc40467c48`.
SDK alignment/signature, the unchanged Android Debug certificate and all 1516
payloads passed, with evidence in the sibling
`.verification-20261024-082012-1fea0657/pipeline.json`.
This proves the packaging pipeline on that pinned input, not runtime behavior or
the latest source build. Rebuild and reverify the latest source before delivery;
the local debug certificate is not a production release identity.
