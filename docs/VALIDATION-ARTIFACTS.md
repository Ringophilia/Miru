# Validation artifact retention

The 2026-11-01 inventory found approximately 65.8 GB in this checkout, including 65.6 GB under `artifacts`. Repeated validation runs copied the same roughly 475 MB Debug APK into both build and test directories, then retained another copy pulled from the device. Release optimization also retained complete unsigned intermediate APKs. Source and Git history were a small part of the total.

The cleanup removed 388 superseded generated APKs totaling **63,773,563,973 bytes (63.8 GB decimal)**. The exact paths and byte counts are recorded in `artifacts/disk-cleanup/cleanup-20261101-214747-194.json`; pre/post inventories are beside it. Source, owned media fixtures, downloaded test dependencies, logs, JSON/XML reports and screenshots remain. Historical test reports retain their original hashes and paths, but pruned APK paths no longer denote available binaries.

The retained set is:

- Two most recent successful Debug build snapshots, each with main, app test and player test APKs.
- Two most recent ARM64 release APKs, selected by version.
- The latest published release's stock R8 input, identified by `stock-input.json`.
- Tracked files, third party inputs, and explicitly protected inputs. Live build/test snapshots are protected by a lease whose process ID and start time must both match a running process.

The post-cleanup inventory measured **2.017 GB for the checkout**, including **1.879 GB in artifacts**. This includes a 21 MB host-test placeholder left from an initial failed safety check; direct deletion was rejected by the command approval policy, so it remains at `artifacts/disk-cleanup/test-18c502fc01e84689a5e700020356d05b/case/dependencies/third-party.apk`. Subsequent host checks use `finally` to remove their large placeholder on either outcome.

## Running validation

Set the local JDK/SDK environment as usual. Use a fresh name for each build or run; scripts refuse to overwrite existing evidence.

```powershell
./tools/build-player-validation.ps1 -BuildName mp4-build-08
./tools/run-device-validation.ps1 -RunName mp4-module-08 -BuildName mp4-build-08 `
    -Selector 'app.miru.player.Mp4AvcCachedTimelineTest#aOneByteHoleRevokesAllLaterSamplesInThatGopButNotTheNextIdr' -Expected 1
```

Use `-App` for app instrumentation and `-Routine` for the controlled app regression exclusions. Additional capability flags use `-InstrumentationArguments @{miruMp4NativeSeekCapability='true'}`. Pass `-Adb <path>` and `-Device <serial>` when needed.

The runner checks each APK against the successful build manifest, installs from that path, and compares the installed bytes and SHA-256 using a temporary pull. It deletes that pull on success and failure. Run directories contain evidence and paths/hashes, without another APK copy. `input.json` records the exact instrumentation command; success requires the expected count, no negative status codes, zero ADB exit status, and terminal JUnit OK.

The build helper freezes source hashes, requires successful Gradle/JVM/lint tasks without source drift, snapshots the three APKs once, and applies retention in `finally`. A failed build never accepts previous APKs or reports as current results. Ordinary Gradle outputs/dependencies remain useful incremental build inputs; the retention script does not remove shared caches.

Release optimization deletes generated unsigned and unpublished signed scratch in `finally`, preserving logs, hashes and reports. A deliberate `-KeepScratch` investigation is the exception and must be cleaned afterward. Run retention after publishing a release so the previous stock input can be pruned safely.

## Cleaning

```powershell
./tools/clean-validation-artifacts.ps1                 # Plan only, with JSON ledger
./tools/clean-validation-artifacts.ps1 -Apply          # Delete superseded generated APKs
./tools/clean-validation-artifacts.ps1 -Apply `
    -Keep 'm3-cached-ranges/specific-build/main.apk'    # Explicit additional input
```

The tool only removes known generated APK names inside this repository's `artifacts`, rejects reparse paths, and protects Git-tracked files. It does not infer that an arbitrary large third party APK is disposable. Each deletion ledger records the path, size, reason and actual removed count. Keep the retained artifacts near or below **3 GB** at current package sizes; investigate any new growth before making more full binary copies.

Host safety checks: `./tools/test-validation-artifacts.ps1` and `./tools/test-device-validation.ps1`. These cover retention, dry run, live/stale leases, version ordering, outside-root and junction rejection, idempotence, installed hash mismatch and incorrect JUnit counts. A real device smoke test passed **1/1**, including installed APK SHA-256/size equality, with no retained pull or run-local APK; evidence is under `artifacts/m3-cached-ranges/disk-cleanup-runner-smoke/`. The existing release parameter preflight passed **19 checks**. Real release pipeline checks passed for both successful packaging and deliberate signing failure, retaining reports and deleting both unsigned APKs; evidence is under `artifacts/disk-cleanup/release-smoke-a63547686b2a43c4a4e8c9be20b9afaf/`. The build helper subsequently completed `benchmark-release-build-01`: 602 frozen source files without drift, JVM79/79 and lint0/27. Its automatic retention removed the three APKs of the superseded build-06 (0.511 GB), keeping artifacts near 1.886 GB.
