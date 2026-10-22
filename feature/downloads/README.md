# Ordinary-file downloads (M1)

`DownloadCoordinator.get(context)` owns a Room database, in-memory request credentials, foreground service, settings and all transfer jobs. The application wires Gecko requests into `DownloadRequest` and displays `DownloadConfirmSheet`; the module has no dependency on another feature.

Implemented paths:

- HEAD metadata (GET range probe when HEAD is unsupported), 4-way ranges above 4 MiB, 1–8 configurable threads, three retries with 1/2/4 second delays for transient failures.
- Persisted segment offsets, ETag / Last-Modified revalidation, safe restart after representation changes or missing validators, 3 concurrent jobs by default.
- Pause / resume / cancel, network-wait state and online resumption, process-start recovery of pending jobs.
- File assembly in the app's private files directory; MediaStore `Download/Miru/` publishing on API 29+, or persisted SAF tree URI on all supported versions. API 26–28 requires a user-selected directory.
- Credential headers remain exclusively in process memory. After process death, authenticated normal downloads can call the app's `headerResolver`; authenticated private downloads require returning to their original page. Private downloads remain visible because the product specification explicitly retains downloaded files.
- Cross-origin redirects strip manually attached Cookie, Authorization and Origin headers. Range responses validate their start/end/total and available representation validators before writing.
- Gecko `blob:` / `data:` response streams are copied from memory; their original stream cannot survive process death or resume after pause, and an expired source is reported explicitly.
- Foreground grouped notifications with pause/cancel and completion opening. Android dataSync timeout pauses downloads.
- Confirmation with rename/folder selection; categories/search/date groups/multi-select/share/rename/swipe delete; application-owned six-second deletion undo, including after leaving the page. Deleting an existing file requires explicit confirmation.

Meaningful unit tests cover ranges, naming, validator changes, safe rejection of ignored ranges, resumed byte assembly and cross-origin credential stripping. Device integration tests are maintained under the application module and exercise the real Room/service/MediaStore path.

A 500,000,000-byte transfer passed a real force-stop/foreground-relaunch test on API 35: the same Room record automatically resumed from persisted offsets and the published file matched SHA-256. This does not prove phone reboot or power-loss recovery. After HTTP 401/403 the coordinator drops stale in-memory credentials and normal retries read Gecko's current cookies again. APK opening requests the system's per-app unknown-source permission when needed; actual installer acceptance still needs a device check.

Remaining specification details include download-arrow animation, a file-manager “show in folder” action, localized Today/Yesterday date labels, rename collision handling for existing published files, storage usage display (P1), SAF picker UX on a physical API 26–28 device, and phone-reboot/network-switch validation. Video offline downloads use Media3 in a later milestone.

Sources used for integration: [Room releases](https://developer.android.com/jetpack/androidx/releases/room), [KSP releases](https://github.com/google/ksp/releases), [foreground service dataSync type](https://developer.android.com/develop/background-work/services/fgs/service-types#data-sync), [MediaStore downloads](https://developer.android.com/training/data-storage/shared/media#downloads).
