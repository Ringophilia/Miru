# Mozilla upstream and M0 implementation notes

Verified on 2026-10-22 (Asia/Shanghai).

## Reference Browser baseline

Miru follows Mozilla Reference Browser's composition of a shared GeckoRuntime, GeckoEngine, BrowserStore, EngineMiddleware, TabsUseCases, SessionUseCases and SessionStorage. The Compose interface is Miru's own implementation. Reference Browser is still active, so the fallback to the old archived android-components repository is unnecessary.

- Repository: https://github.com/mozilla-mobile/reference-browser
- Inspected commit: `61267efe132591f6f1dfd58185ebd8dbff6aa816`
- Commit date: 2026-10-19
- Latest inspected commit subject: `Update Android Components version to 160.0.20261019073400.`
- License: MPL-2.0, preserved at `third_party/mozilla/MPL-2.0.txt`.
- This upstream commit uses a nightly Android Components build. Miru pins a stable Android Components release in its version catalog instead.
- Current Android Components source is part of Mozilla's source tree: https://github.com/mozilla-firefox/firefox/tree/main/mobile/android/android-components

Relevant pinned reference source:

- [Core.kt](https://github.com/mozilla-mobile/reference-browser/blob/61267efe132591f6f1dfd58185ebd8dbff6aa816/app/src/main/java/org/mozilla/reference/browser/components/Core.kt)
- [EngineProvider.kt](https://github.com/mozilla-mobile/reference-browser/blob/61267efe132591f6f1dfd58185ebd8dbff6aa816/app/src/main/java/org/mozilla/reference/browser/EngineProvider.kt)
- [UseCases.kt](https://github.com/mozilla-mobile/reference-browser/blob/61267efe132591f6f1dfd58185ebd8dbff6aa816/app/src/main/java/org/mozilla/reference/browser/components/UseCases.kt)

Miru deliberately selects browser runtime and session components rather than importing Reference Browser's application, Firefox Accounts, Sync, Firebase, Sentry, or application telemetry integrations. Transitive dependencies still need to be audited before a release; an absence of app telemetry initialization is not proof that no telemetry library is packaged.

## Toolchain checks

The exact versions used by Miru are pinned in `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties`.

Official compatibility checks:

- [AGP 9.1.1](https://developer.android.com/build/releases/agp-9-1-0-release-notes): supports API 37.0, requires Gradle 9.3.1 and JDK 17 or newer.
- [Kotlin Gradle plugin compatibility](https://kotlinlang.org/docs/gradle-configure-project.html): Kotlin 2.4.20 supports AGP 8.5.2–9.3.1 and Gradle 7.6.3–9.7.0.
- [Compose compiler setup](https://developer.android.com/jetpack/androidx/releases/compose-kotlin): Kotlin 2.x uses the matching Compose Compiler Gradle plugin.
- [Android 17 SDK](https://developer.android.com/about/versions/17/setup-sdk): API 37. The SDK repository package is `platforms;android-37.0`, not `platforms;android-37`.

Machine discovered during implementation:

- SDK: `G:/Projects/Ringo-oneghg-ad-free/tools/android-sdk`
- JDK 21: `C:/Program Files/Microsoft/jdk-21.0.12.101-hotspot`
- Android Studio JBR is newer and is not required for the pinned toolchain.
- Installed SDK platform 37.0 revision 2 and Build Tools 37.0.0 for Miru development; platform metadata has no preview codename.
- The PATH `java` resolves to a Java 11 JRE, so builds must use an explicit JDK 17+ via `JAVA_HOME` or IDE Gradle JDK settings.
- Windows Hypervisor Platform acceleration is available.

## Runtime verification boundary

The initial ADB inventory contained only a stale offline emulator connection. `adb reconnect offline` removed it, and no emulator process or physical device was connected at that point. Existing API 35 AVDs are available, but merely discovering these AVDs does not count as running or passing Miru's device acceptance tests.

M0 acceptance still requires a built APK on an emulator or device, tab history restoration after process death, and an interaction recording. Build and test results belong in the root implementation report; this note records only the upstream/toolchain checks.

## Risks for later milestones

- R1: Reference Browser maintenance was verified at the pinned commit. Its runtime/state/session APIs remain suitable as an M0 baseline.
- R2/R3: Bundled signed uBlock Origin installation, extension readiness gating, badges, popup and element picker have not been verified by this toolchain check. Do not label M0 as providing uBO protection.
- R9: Preserve Mozilla source notices in derived files and provide corresponding source/notice information at distribution. uBO redistribution and release compliance remain a separate M1/release task from the specification.
