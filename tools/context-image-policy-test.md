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
The original 16 checks include synchronous, non-modifying navigation setup
limited to documents and rejecting an intermediate wider header until the final
`onResponseStarted` policy is observed.

The suite now has **25 checks**. Nine additional checks load the same production
background and exercise its native connection protocol: the first connection and
`bridge-ready` follow every listener registration; reconnect sends ready without
duplicating web listeners; stale disconnects and requests cannot affect the new
connection; an old asynchronous Cookie success or failure cannot reply through
the replacement connection; invalid URLs and Cookie API failures remain
unavailable; ordinary/private jar selection and longest-path ordering remain
intact. Connection events and retry timers are fixture controlled, without real
timeouts or native ports. On 2026-10-24 all **25/25 passed** after the startup
handshake change; the separate meta protocol suite also passed **21/21**.

The Kotlin host uses a separate startup attempt for every `start()`. Its final
extension handler accepts ready only for that current attempt, and one 1.5-second
deadline covers both private-permission configuration and ready. Disconnects do
not replace the startup latch. This is the host's implementation contract, not
something the JavaScript test can execute or prove; Kotlin compilation and actual
Gecko startup timing still require separate validation.

These are background protocol checks. They do not prove Gecko event ordering,
DOM/meta behavior, native Port lifecycle, image bytes, URI grants or actual
Referer headers. Those remain covered by the Android instrumentation and runtime
evidence described in `docs/BROWSER-CORE-VALIDATION.md`.
