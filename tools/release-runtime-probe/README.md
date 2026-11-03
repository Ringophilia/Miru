# Release runtime probe

This standalone Java APK instruments the ordinary Miru package through Android platform APIs.
It defines no application classes, adds no keep rules to the target, and must have no runtime
dependencies. A Kotlin standard library inside an instrumentation APK can shadow an optimized
target's same-named interfaces. `verify_release_runtime_probe.py` rejects foreign runtime classes;
the sole allowed compiler metadata type is D8's `LambdaMethod`, verified to be an annotation.

Set `JAVA_HOME` and `ANDROID_HOME`, then from the repository root:

```powershell
./tools/build-release-runtime-probe.ps1 -RunName harness-01
./tools/run-release-runtime-probe.ps1 -RunName runtime-01 `
    -BuildDirectory snakeyaml-x86-build -HarnessManifest harness-01.json
```

Use fresh run names. The build directory is below `artifacts/r8-device-smoke` and must contain
`arm64-dex-comparison.json`: status PASSED, arm64/x86 package path/bytes/SHA-256, identicalDex
and nativeLibraries. Establish the DEX comparison from the exact signed inputs before using it;
a matching source revision alone does not prove byte equality. The fixed 0.5.16 record is an example.

`x86-release.init.gradle` changes only release ABI packaging for emulator execution. It does not
disable R8, resource shrinking or production signing configuration. Run it with a frozen source
manifest, retain the exact init script hash, and independently verify x86_64 ELF metadata and
DEX equality against the ARM64 package. Avoid changing application sources during either build.

The runner installs directly from pinned paths, verifies installed bytes using temporary pulls,
and deletes pulls in finally. It protects the release input with a live process/start-time lease.
Evidence is logs, native PNGs and JSON, without APK copies in each run directory.

The probe uses the public playback slider and actual PixelCopy output. It destroys the first
Activity before each stopped-origin re-open and checks for zero new source requests. The three
checks cover single-video red frames, a genuinely served Gecko document heading, and muxed
AVC/dual-AAC input whose 9-second blue frame is decoded again from cache. It rejects debuggable
targets. The muxed check does not measure PCM identity or prove the separate disk-layer pixels.

The build freezes both owned media inputs as well as harness source and records `testCount: 3`
in its manifest. The runner uses that count, validates all four native PNG names/hashes, and
still accepts the earlier two-test manifests with their two PNGs. Missing terminal output or
incomplete counts cannot pass. These are controlled runtime checks; full R8, ARM64 native,
disk-layer geometry and physical performance acceptance remain separate.
