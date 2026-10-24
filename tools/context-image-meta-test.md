# Context image meta protocol checks

Run with Node.js 18 or newer, without Gradle, Android, credentials or a network:

```powershell
node G:/Projects/Miru/tools/context-image-meta-test.cjs
```

The test loads the shipped `image-policy.js` in a VM. Its small DOM fixture
provides connected elements, attribute old values, ordered mutation records,
observer delivery and the content IPC peer. It implements no referrer policy
selection or native meta processing. Assertions inspect replies from production
code, rather than a second implementation of that code. Synthetic trusted events
exercise the protocol; they do not prove real event trust or native touch binding.

The 21 checks cover an observed empty or unknown HTTP baseline, initial and
inserted metas, direct and subtree insertions, recycled meta nodes, single and
multiple content writes, removal, renaming, explicit image overrides and press
identity. The regression runs both through the observer callback and through the
resolver draining queued records.

## Failure retained before the production edit

On 2026-10-24 the new tests ran against the original production script from
`126fbce`, SHA-256
`48287E7C54A088C1A1F96A4E8C1489AEA829C5438E15F1E14F9AFC9BEFBCEFE8`.
The command exited 1: **21 tests, 17 passed, 4 failed**. Every failed case
returned `found: true, policy: "unsafe-url"` where the contract requires
`found: false, stage: "meta-ambiguous"`:

- Insert a `no-referrer` meta, then make one `content = "invalid"` write in
  the same batch; resolve while the records remain queued.
- The same sequence with the MutationObserver callback delivered first.
- The same sequence with the inserted meta nested in a newly inserted subtree.
- Remove an already processed meta, deliver that removal, change it while
  detached, reinsert it with `no-referrer`, then write `invalid` before delivery.

One exact assertion excerpt from the first failing run was:

```text
AssertionError [ERR_ASSERTION]: Expected meta-ambiguous, got {"found":true,"policy":"unsafe-url","documentNonce":"dad7f3c3-3cac-4821-b155-87b75e932371","pressNonce":"36f09577-56d0-40bd-b946-d8cb08225b3b","documentUrl":"https://page.test/document?private=meta-secret"}
```

## Repair and evidence boundary

The script now associates attributes with meta nodes inserted in the same
mutation batch, including descendants and reused nodes. It conservatively marks
their intermediate state unavailable. It does not invent a policy or fall back
to HTTP when evidence is lost. A separately observed, single change on an
existing meta keeps working, and an explicit live image policy remains usable.

After the repair on 2026-10-24, the same new test command exited 0 with
**21/21 passed**. At that point the existing `node tools/context-image-policy-test.cjs`
background protocol suite also exited 0 with **16/16 passed**. Production script
syntax (`node --check`) and scoped `git diff --check` passed.

The fixture proves the production script's reply for those mutation records.
It does not prove that a particular Gecko build emits those records or sends a
particular HTTP Referer. The parent review separately checked Firefox
`FIREFOX_158_0_RELEASE`: [HTMLMetaElement.cpp](https://raw.githubusercontent.com/mozilla-firefox/firefox/refs/tags/FIREFOX_158_0_RELEASE/dom/html/HTMLMetaElement.cpp)
lines 59–109 bind and process an inserted meta synchronously; lines 141–145 call
`UpdateReferrerInfoFromMeta`.
[Document.cpp](https://raw.githubusercontent.com/mozilla-firefox/firefox/refs/tags/FIREFOX_158_0_RELEASE/dom/base/Document.cpp)
lines 4378–4399 return for an invalid policy without replacing the previous
policy. This supports the native behavior behind the regression but is evidence
for the 158.0 tag, not a claim about the exact 158.0.2 revision in the app. Actual
Gecko request observation remains the Android instrumentation's responsibility.

No Gradle, device, network or commit operation was performed by that Node test task.

## Actual Gecko regression and 0.5.3 evidence

The parent instrumentation work subsequently ran the regression against the
actually installed **0.5.2 / bridge 0.3.7** APK. The installed and local APK
SHA-256 values match in `artifacts/browser-core/meta-repro-installed-052.json`.
The first device attempt, `meta-probe-before-device.log`, passed one test in
11.269 seconds, but did not first establish an available native HTTP baseline.
An unavailable startup baseline could mask the defect, so this pass is not
evidence that the old script was safe.

The strengthened fixture first obtains a real Gecko Cookie reply to prove the
background is answering. It then reloads the document and successfully copies
the image with the wider HTTP Referer, proving that the native baseline is
available before the mutation. After inserting `no-referrer` and writing
`invalid` in the same batch, a fresh actual Gecko image request has no Referer.
The native copy must either report unavailable without a new request or copy
with no Referer and preserve the original PNG bytes.

Against the old APK, `meta-known-baseline-before-device.log` fails that final
native-policy assertion: **1 test, 1 failure, 12.553 seconds**. The native copy
widens the policy established by the actual Gecko probe. This failed evidence
is retained alongside the original four Node failures.

With **0.5.3 / code 11 / bridge 0.3.8**, `053-meta-bridge-device.log` completes
**14/14 tests in 79.341 seconds**, including the strengthened regression, eight
other image-policy cases, three real Cookie bridge cases, the ordinary/private
image menu case and the uBO case. The assertions retain real Referer and PNG
byte observations, sensitive clipboard handling and FLAG_SECURE checks.

The current Node runs are saved as `053-meta-protocol.log` (**21/21**) and
`053-policy-protocol.log` (**25/25**) in `artifacts/browser-core`. The expanded
background suite checks listener registration before native connection/ready,
ready on every replacement connection, stale disconnect/request isolation,
asynchronous Cookie resolve/reject isolation, and ordinary/private jar and
longest-path ordering. These are protocol checks, not device observations.

The 14-test device run preceded the later same-value uBO guard edit. The final
guard has its own `053-idempotent-device.log` pass (**1/1, 23.324 seconds**),
retaining the original uBO assertions and observing that three repeated enabled
settings keep READY and cause no new HTML document load over 1.5 seconds.
The 0.5.3 fresh-profile 79-test run, release/lint/package gates and external
force-stop restart gates still require their own terminal evidence. See the
Chinese [browser core validation record](../docs/BROWSER-CORE-VALIDATION.md)
for the preserved 0.5.2 full-suite failure and the same-value reload confounder.
