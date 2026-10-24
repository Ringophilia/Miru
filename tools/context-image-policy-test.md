# Context image policy protocol checks

Run from any working directory with Node.js 18 or newer:

```powershell
node G:/Projects/Miru/tools/context-image-policy-test.cjs
```

The test loads the actual `media-classifier.js` and `background.js` shipped in
`browser/engine/src/main/assets/extensions/miru-bridge/`. It replaces only the
browser event APIs and the receiving content-script IPC peer. There is no copied
implementation of the production policy selection, document binding or routing.
All addresses and nonces are synthetic `.test` fixtures; no network, credentials,
Android SDK, device or Gradle invocation is needed.

The original six assertions remain covered: both HTTP-header/document-announce
orders, an observed empty policy, SPA policy/address continuity, rejecting late
headers from an old navigation, and completing the current unknown baseline.
Additional checks cover a previous native gesture being rejected even for the same URL,
HTTP policy lists, trusted iframe routing, explicit image
overrides, response identity, changing press identity, expiration and cleanup.
There are now 16 checks, including synchronous, non-modifying navigation setup
limited to documents and rejecting an intermediate wider header until the final
`onResponseStarted` policy is observed.

These are background protocol checks. They do not prove Gecko event ordering,
DOM/meta behavior, native Port lifecycle, image bytes, URI grants or actual
Referer headers. Those remain covered by the Android instrumentation and runtime
evidence described in `docs/BROWSER-CORE-VALIDATION.md`.
