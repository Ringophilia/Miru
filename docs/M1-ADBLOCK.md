# M1 ad blocking integration

Implementation date: 2026-10-22.

## Pinned package and APIs

The unmodified AMO package is uBlock Origin 1.75.0 (`uBlock0@raymondhill.net`). The XPI and its SHA-256 are pinned in `third_party/ublock/NOTICE.md`. Runtime copy goes to application-private `files/extensions/`; it is checked against the pinned SHA-256 before installation.

GeckoEngine.installWebExtension is the Android Components wrapper around GeckoRuntime.webExtensionController.install, and receives a `file://` URI. **No ensureBuiltIn call installs uBO.** Only Miru's own `bridge@miru.app` cookie bridge uses the built-in extension path.

Installed add-ons persist in the Gecko profile. Startup lists installed extensions, installs the bundled XPI if absent or older, preserves a newer installed version, and enables private browsing permission. Only the known pinned extension ID is silently approved at install; arbitrary website extension installs and new optional/update permissions are denied.

## Readiness and truthful UI

The browser shell and native NTP remain available while Gecko/extension setup runs. Page middleware waits at most 1.5 seconds for native add-on readiness. On timeout, page loading continues and `startupTimedOut` remains in state. Readiness requires the genuine native `onReady` callback plus completed permission/enabled-state setup; populated options-page metadata is not readiness evidence. Installation has a 240-second bound and a retry action; failure is reported as FAILED.

Native add-on readiness is distinct from uBO's internal filter readiness. The pinned uBO source deliberately unsuspends requests during first installation (`onVersionReady(lastVersion=0)`). It reports affected pages with badge `!` (`hasUnprocessedRequest`). Miru also tracks HTTP(S) loads begun before native readiness, including those begun before the extension exists. Either condition sets `pageNeedsReload`; installation finishing alone does not clear the warning. A subsequent load after native readiness clears Miru's flag, while uBO's own `!` remains authoritative for internal filtering gaps. The UI displays a warning and offers reload instead of a misleading protected zero count. A separate force-stop/cold-restoration test passed on the local default EasyList fixture; this is limited evidence, not the complete website/device matrix.

## Optional metadata lookup

`RuntimeConfiguration` writes a fixed, app-private Gecko YAML configuration and passes its absolute path through `GeckoRuntimeSettings.configFilePath`. It disables only `extensions.uBlock0@raymondhill.net.getAddons.cache.enabled`, skipping AddonRepository's optional AMO description/screenshots cache query for this bundled ID. Signature verification, add-on blocklist checks, permission checks and update verification retain Gecko defaults. The configuration contains no arbitrary external/user preferences. This internal per-ID preference is coupled to the pinned Gecko version and must be rechecked on upgrades. It reduced some observed install times but did not eliminate clean-profile timeouts; do not describe this as an offline installer.

Primary sources: [Gecko runtime configuration](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/automation.html) and [AddonRepository](https://github.com/mozilla-firefox/firefox/blob/main/toolkit/mozapps/extensions/internal/AddonRepository.sys.mjs), plus the pinned XPIInstall source retained in local validation artifacts.

Global enable state persists independently of extension startup and uses Gecko's enable/disable APIs. Browser-action defaults and session-specific overrides are merged to obtain the active page's real badge text. The action belongs to the actual Gecko tab marked active by AC's WebExtensionMiddleware.

## R2 / R3 validation approach

R2 must be verified on an actual installed APK: fresh install accepts the local signed XPI, private-browsing permission is set, a deterministic EasyList fixture request is blocked and its badge updates. Current build/runtime test results belong in M1-VALIDATION.md; source inspection alone does not prove this behavior.

R3 uses the unmodified `popup-fenix.html` through the browserAction onClick callback. Gecko supplies its popup EngineSession, which the app renders in a Compose sheet using EngineView. The original uBO power button owns the real site whitelist; Miru does not mirror an unknown whitelist state with a pretend native switch or inject into uBO storage. Dashboard/exception/logger actions use the installed extension's runtime base URL (`dashboard.html`, `dashboard.html#whitelist.html`, `logger-ui.html`). The whitelist hash is verified against the pinned archive's dashboard.html. Tab create/select/update/close calls from the extension are mapped into BrowserStore.

## Download context bridge

AC 158's DownloadState does not retain Cookie; it offers userAgent, referrer, MIME, filename, size and an already authenticated Gecko response. The built-in native-only Miru bridge reads cookies from `firefox-default` or `firefox-private` as appropriate. Header values and the original response stream only travel through an in-memory Channel. They are never written to preference/download metadata or logs. HTTP(S) responses are closed and re-requested by the OkHttp Range engine using those transient headers. Blob/data downloads consume the original Gecko response instead; the body adapter lazily pumps at most 64 KiB and closes its Gecko response when cancelled/finished. Native port access and cookie request bookkeeping are serialized on the main dispatcher, including continuation requests initiated by the download engine's I/O threads.

M2 extends this same built-in package with webRequest observation and isolated content-script ports (`miru_media`) for video detection/handoff. Cookie-query commands still arrive exclusively from native code on the background `miru` port. Content messages are associated with the actual EngineSession and active page; ordinary website scripts are not given a native bridge or credential-returning DOM API. The former cookie-only permission description is historical; the current manifest is the source of truth.

## Remaining work

- Use the Android Components AddonUpdater lifecycle for scheduled AMO add-on updates; current M1 installs newer bundled versions and preserves any newer installed version.
- Extra default Cookie-notice filter activation is not yet applied. The unmodified extension retains its normal defaults and language-selected lists; its original dashboard remains available for configuration.
- Controlled Cookie-jar isolation and authenticated-download tests are included; actual results are recorded in M1-VALIDATION.md.
- Distribution license review and the full d3ward/website matrix remain release acceptance work.

Primary API references: https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/web-extensions.html and the pinned 158.0.2 Android Components source JARs from https://maven.mozilla.org/maven2/ .
