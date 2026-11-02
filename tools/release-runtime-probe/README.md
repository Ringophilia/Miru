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

The probe seeks through the public playback slider and requires actual red pixels, destroys the
first Activity before stopped-origin re-open, checks that no new media request occurs, and
requires a heading from a genuinely served Gecko document. It rejects debuggable targets.
These are two controlled runtime checks, not full R8/ARM64/physical performance acceptance.
