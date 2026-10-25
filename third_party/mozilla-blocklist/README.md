# Pinned Mozilla add-on blocklist resources

Version boundary: through Miru 0.5.3, the generated overlay preserved all 4,079
original payloads. Starting with the 0.5.4 development batch, the same generator
also applies one separately reviewed [geolocation policy patch](../mozilla-permission-policy/README.md).
The five blocklist resources remain unmodified; the combined output now preserves
4,078 originals and changes exactly that one pinned module. The historical
4,079-preserved results do not describe the new combined output.

These five files are unmodified Mozilla Remote Settings dump resources from the
same release source revision as Miru's GeckoView dependency. They are licensed
under the Mozilla Public License 2.0; the complete license is retained in
[MPL-2.0.txt](MPL-2.0.txt). Mozilla's upstream source and notices remain the
authority for the Gecko distribution and its other third-party components.

## Provenance

- Artifact: `org.mozilla.geckoview:geckoview-omni:158.0.20261021001945`.
- Official repository: <https://maven.mozilla.org/maven2>.
- [Pinned AAR](https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview-omni/158.0.20261021001945/geckoview-omni-158.0.20261021001945.aar):
  243,952,420 bytes, SHA-256
  `a3af9d5ffb1f898df5b8782d083b69c4274c4f3275e31f3dac33d0d2aa16c692`.
- The original AAR's `assets/omni.ja` has SHA-256
  `b6dca3074e505b3d475504c864ba517138e383d6d9f23c6db94de34403c254b5`
  and 4,079 entries.
- Gecko application version `158.0.2`, build ID `20261021001945`, release source
  revision [`e4ce606692cd81e6898547e2edd7c552fefcbb04`](https://hg.mozilla.org/releases/mozilla-release/rev/e4ce606692cd81e6898547e2edd7c552fefcbb04).
  These values are recorded in the AAR POM and in the original omni's
  `modules/AppConstants.sys.mjs`.
- Original source directory:
  [`services/settings/dumps/blocklists/`](https://hg.mozilla.org/releases/mozilla-release/file/e4ce606692cd81e6898547e2edd7c552fefcbb04/services/settings/dumps/blocklists).
  The five relative paths in the table below are copied under `snapshot/blocklists/`.
- Original `defaults/settings/last_modified.json` already indexes
  `blocklists/addons-bloomfilters` at timestamp `1792413409072`. The collection's
  own `timestamp` matches. Miru does not alter the index or the collection.

The official revision's
[`moz.build`](https://hg.mozilla.org/releases/mozilla-release/raw-file/e4ce606692cd81e6898547e2edd7c552fefcbb04/services/settings/dumps/blocklists/moz.build)
explicitly excludes these five files for `mobile/` builds. The overlay restores
these resources at the same `defaults/settings/` paths used by desktop builds.
It preserves every existing resource payload, including executable JavaScript,
prefs, and the original index.

| Relative upstream path | Bytes | SHA-256 |
| --- | ---: | --- |
| [`addons-bloomfilters.json`](https://hg.mozilla.org/releases/mozilla-release/raw-file/e4ce606692cd81e6898547e2edd7c552fefcbb04/services/settings/dumps/blocklists/addons-bloomfilters.json) | 72,903 | `43dbbe38a70b3350ef8eaf5c2f2f7e300196b7986d12cc639b058eef966ef86a` |
| [`addons-bloomfilters/addons-mlbf.bin`](https://hg.mozilla.org/releases/mozilla-release/raw-file/e4ce606692cd81e6898547e2edd7c552fefcbb04/services/settings/dumps/blocklists/addons-bloomfilters/addons-mlbf.bin) | 938,855 | `bf59d309000ab016e6fcfb558b2ba61ba1368ad4a4a2de424f9f5012a5e47b5a` |
| [`addons-bloomfilters/addons-mlbf.bin.meta.json`](https://hg.mozilla.org/releases/mozilla-release/raw-file/e4ce606692cd81e6898547e2edd7c552fefcbb04/services/settings/dumps/blocklists/addons-bloomfilters/addons-mlbf.bin.meta.json) | 500 | `81078ea0f4e5fd2ee36bd58c44849898d2a12e469ba494af30d22895ce216135` |
| [`addons-bloomfilters/softblocks-addons-mlbf.bin`](https://hg.mozilla.org/releases/mozilla-release/raw-file/e4ce606692cd81e6898547e2edd7c552fefcbb04/services/settings/dumps/blocklists/addons-bloomfilters/softblocks-addons-mlbf.bin) | 218,202 | `d778ccd02b613efb8c7ee0d4e4517dcac61b7e12a362dc600890c6c3065772cb` |
| [`addons-bloomfilters/softblocks-addons-mlbf.bin.meta.json`](https://hg.mozilla.org/releases/mozilla-release/raw-file/e4ce606692cd81e6898547e2edd7c552fefcbb04/services/settings/dumps/blocklists/addons-bloomfilters/softblocks-addons-mlbf.bin.meta.json) | 511 | `c715336c8f9ba1c8845b947cba80d4cef6a689d1d4f4408471287b98c6eca36a` |

The `.meta.json` objects must equal their records in the collection. The builder
also verifies their attachment hash, size, type, and key format. Both attachments
have `generation_time` `1792002909147`. These original values are retained;
generation time is distinct from collection `timestamp` and record
`last_modified`.

Mozilla Remote Settings exposes its attachment base URL through
<https://firefox.settings.services.mozilla.com/v1/>, under
`capabilities.attachments.base_url`. Gecko's `Utils.baseAttachmentsURL()` discovers
that value dynamically. For a provenance recheck, the service returned
`https://firefox-settings-attachments.cdn.mozilla.net/`; the two original metadata
`attachment.location` fields produced these successfully downloaded, hash-matching
URLs:

- [Hard block MLBF](https://firefox-settings-attachments.cdn.mozilla.net/staging/addons-bloomfilters/20261014184032--f78ae858-ff21-4701-af04-9f82b3c76300--c4cc77ea-4e48-4a1a-a59e-5e57f92349cd.bin)
- [Soft block MLBF](https://firefox-settings-attachments.cdn.mozilla.net/staging/addons-bloomfilters/20261014184032--1b92c543-1ec2-471f-b10d-643691cf292f--e9dea8b2-3414-4713-bc7c-aa4a5db4003e.bin)

These CDN links document a check of the pinned data; they are not a hardcoded
runtime server override. The collection JSON and soft block binary were also
downloaded again from the fixed HG revision and matched their pins. HG requests
for the other paths intermittently failed at transport level; no replacement
content was accepted. Local recheck outputs live under `artifacts/m1-blocklist/`
and are development evidence, not shipped application assets.

## Source distribution and changes

When distributing a binary that contains this overlay, retain the MPL license,
this provenance record, these exact source resources, and the Miru overlay
builder source. Recipients must be able to obtain the corresponding source
revision and the actual Miru changes. The fixed Mozilla revision above provides
the upstream source, with a [full source archive](https://hg.mozilla.org/releases/mozilla-release/archive/e4ce606692cd81e6898547e2edd7c552fefcbb04.tar.gz)
as an alternative to browsing individual files. Keep a corresponding-source
copy available with a published Miru source release; a link to an upstream latest
branch does not identify this build.

The five Mozilla files are unchanged. Miru's change is a generated archive that
adds them to `defaults/settings/` and repacks the existing entries. The builder
and tests are in [`tools/gecko-blocklist-buildlogic`](../../tools/gecko-blocklist-buildlogic/README.md).
There is no change to extension signatures, blocklist/update checks, security
preferences, or TLS policy.

Updating Gecko requires reviewing the new official revision and all pins
together. Do not copy a newer collection into this version's index or bypass a
digest mismatch. Resource presence in a ZIP does not prove that the running
Gecko consumed it; runtime acceptance is tracked separately.
