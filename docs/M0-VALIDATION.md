# M0 validation

Date: 2026-10-22 (Asia/Shanghai).

## Result and scope

The M0 browser shell ran successfully on the API 35 emulator. Manual smoke checks covered the native new-tab page, actual Gecko page navigation, menu/settings, captured tab thumbnails, incognito tab isolation at restart, persisted navigation history, and closing/undoing a tab.

The smoke APK was `app/build/outputs/apk/debug/app-debug.apk`, built at **2026-10-22 02:49:19 Asia/Shanghai**, size 469,385,055 bytes. Root's later build includes additional fixes and instrumentation tests; its final automated results should be recorded separately below. Do not treat this smoke run as proof that every P0 requirement in the product specification is complete.

## Environment

- Windows host with Microsoft JDK 21.0.12, Gradle 9.3.1, AGP 9.1.1 and Kotlin 2.4.20.
- Android SDK Platform 37.0 revision 2 and Build Tools 37.0.0 installed.
- Headless emulator `emulator-5570`, Android API 35, Pixel 5 profile, 1080 × 2340, density 440.
- Emulator uses `-read-only -no-snapshot-save`; existing AVD data is preserved.
- Local deterministic two-page fixture: `artifacts/m0/server.cjs`, host port 8743, Android origin `http://10.0.2.2:8743`.
- The initial NTP screenshot uses Chinese. UI navigation checks switched the test application's locale to English to avoid Gboard's Chinese punctuation transformation of ADB key events. Both resource sets rendered correctly.

## Observed device checks

| Check | Result | Evidence under `artifacts/m0/` |
| --- | --- | --- |
| Native NTP and eight shortcuts | Passed | `01-ntp.png`, `01-ntp.xml` |
| Address input opens an actual Gecko page | Passed | `02-page-one.png`, `02-page-one.xml`; fixture UA reports Gecko/158.0 Firefox/158.0. |
| Follow a hyperlink to a second page | Passed | `03-page-two.png`, `03-page-two.xml` |
| Menu and settings open and return to browsing | Passed | `04-menu.png`, `05-settings.png` and matching XML. |
| Tab tray displays a captured website thumbnail | Passed | `06-tabs.png`, `06-tabs.xml` |
| Incognito NTP has no ordinary shortcuts | Passed | `07-incognito.xml` |
| Incognito screen capture prevention | Passed | `07-incognito.png` is black because of `FLAG_SECURE`, while UI hierarchy confirms the private screen. |
| Normal tab survives process death | Passed | From page two, create a private NTP, press Home, force-stop the app, and relaunch. `08-restored.png/xml` shows the normal page two. |
| Navigation stack survives process death | Passed | System Back after relaunch returns to page one: `09-restored-back.png/xml`. |
| Private tab is omitted from restoration | Passed | `10-private-after-restart.xml` shows “No incognito tabs yet”. This check covers private **tab persistence**, not an independent Cookie/database forensic audit. |
| Close tab and undo | Passed | Two ordinary NTP tabs, close the right card, activate Undo after 500 ms, and observe both cards again: `13-two-tabs.xml`, `14-undo-confirmed.xml`. |
| Runtime errors after the fixed smoke build | None observed | `adb logcat -d -s AndroidRuntime:E MiruEngine:E` produced no entries throughout the fixed run. |

The earlier `11-closed-tab.xml`/`12-undo-tab.xml` attempt missed the five-second undo window while the UI hierarchy was being inspected. It is not counted as an application failure or as a successful undo test. The controlled 500 ms check above verified the behavior.

A screen recording from the smoke session is saved as `artifacts/m0/miru-m0.mp4` (approximately 3 MB). It includes keyboard-locale setup and the browsing/menu portion of the session; the restart and privacy assertions are captured by the numbered XML/screenshots.

## Automated checks

- `:core:common:test` passed: **5 tests, 0 failures/errors**, report `core/common/build/test-results/test/TEST-app.miru.common.AddressResolverTest.xml`.
- All included Gradle projects configured successfully using the pinned toolchain.
- Final integration build: **passed** `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:core:common:test`, `:browser:engine:testDebugUnitTest`, and `:app:lintDebug` in one Gradle invocation. The final APK was built at **2026-10-22 02:55:15 Asia/Shanghai**.
- Engine JVM tests: **3 tests passed**, including pre-initialization commands and refusing Gecko session allocation for the native NTP. Together with the 5 address tests, **8 JVM tests passed**.
- Lint: **0 errors, 10 warnings**. Warnings concern pinned dependency update suggestions and a redundant resource SDK qualifier; no lint baseline or disabled error checks were used.
- Final device automation: **3 tests passed, 0 skipped, 0 failed**, `:app:connectedDebugAndroidTest`, API 35. The tests exercise the real Activity/BrowserStore: menu-to-private-tab and tab groups; shared omnibox input and internal settings route; an on-device loopback HTTP fixture through real Gecko, clean initial navigation stack, back navigation and find-in-page results.
- Instrumentation report: `app/build/reports/androidTests/connected/debug/index.html`; XML under `app/build/outputs/androidTest-results/connected/debug/`.

### History, bookmarks and UI fixes (2026-10-22, later run)

- Same API 35 emulator (`WfmAdApi35`, read-only). `:core:common:test`, `:browser:engine:testDebugUnitTest`, `:app:lintDebug` (**0 errors, 10 warnings**, unchanged) and `:app:connectedDebugAndroidTest` (**9 passed, 0 failed**).
- New device tests: `BrowsingLibraryTest` (in-memory SQLite: revisit counting, host-prefix suggestions, delete / clear undo, literal LIKE wildcards, bookmark toggle / restore / edit); `regularVisitsReachHistoryAndPrivateVisitsDoNot` (real Gecko through the loopback fixture: a regular visit is stored with its title, a private-tab visit is not); `starredPageIsListedInBookmarksAndVisitInHistory` (menu star, Bookmarks and History screens).
- Manual screenshots (not committed): menu without unavailable icons, bookmark star and snackbar, bookmarks and history screens, focused find bar, app-forced dark theme with light status-bar icons on a light system theme, bottom toolbar on the NTP, history / bookmark / tab suggestions.
- Not covered: predictive back, TalkBack pass, physical devices, the first-launch window colour when the app theme differs from the system theme (the splash follows the system).

## Issues found and corrected during integration

The first APK initialized the browser from Gecko's helper process as well as the main process, causing the app to close and the helper process to report `GeckoRuntime is shutting down`. The application now creates the container only in the main process and starts Gecko after the first Compose frame. The fixed smoke APK remained stable, including across a force-stop/relaunch cycle. The original diagnostic log is `artifacts/m0/first-launch-logcat.txt`.

## Privacy observation boundary

The merged manifest did not declare Firebase, Sentry, or Glean collection/initialization providers. Source wiring does not initialize the application telemetry SDK, Firefox Accounts or Sync and sends the Gecko telemetry preference `false`. The resolved runtime dependency report (`artifacts/m0/dependencies.txt`) contains `service-nimbus`, required by the Gecko component, but no separate Glean, FxA client or Sync component match. These static and log checks are **not** a packet-capture audit and must not be presented as proof that every possible network request was inspected.

## Remaining acceptance work

- Physical-device startup/drawing performance, TalkBack, font scaling, reduced animations and the full website/login matrix need dedicated testing. Debug emulator `am start -W` times are not release performance measurements.
- Private Cookie/form-data cleanup needs a dedicated integration/forensic check beyond omitting private tabs from session storage.
- uBlock Origin, downloads, media detection and the native player are later milestones and are outside this M0 smoke run.
- The two-ABI debug APK is approximately 448 MiB with uncompressed native libraries; the specification's single-ABI release size target remains unverified. Release shrinking, AAB per-ABI distribution and release-device measurements belong to M4.
