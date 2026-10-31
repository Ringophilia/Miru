# P11 byte-span/time-index capability fixtures

This directory supplies preflight input for public Media3 **1.11.1**
BoxParser.parseTraks / TrackSampleTable capabilities. It is not production P11
implementation or evidence that the UI can show disk-cached time ranges.

colors.samples.json independently expands the existing media/seek-preview/colors.mp4.
Its complete-file hash, sample offsets/sizes, integer timestamps, sync flags and
packet-payload hashes bind the fixed asset. The baseline exporter is
tools/export-cached-range-baseline.py; it invokes neither FFmpeg nor Media3.
The baseline Android probe can also move its complete moov to the tail in memory
and adjust chunk offsets without generating new video.

## Host generation completed; edit-list oracle comparison remains failing

The separately authorized 2026-10-31 generation-02 completed the host checks.
Generation-01's PowerShell OrderedDictionary aggregation failure, partial assets,
hashes and exact commands remain under ignored artifacts/m3-cached-ranges/.
Only numeric projection before aggregation changed; every VBR/B-frame/edit-list
threshold remains intact. No Android complex result is claimed by generation.
Subsequent generation slots still belong to the device/build owner.

Head/tail each contain 360 real packets and 252 B frames, with sample sizes from
13 to 3500 bytes. Equal two-second static/moving windows contain 964/52204 bytes.
Their packet payloads/timestamps/sync flags match after remux. The edited file has
265 ffprobe packets, including negative-time preroll; its elst media time is
4864/15360 seconds. The raw and edit-applied references are both retained for
strict comparison and diagnosis, without filtering packets to match Media3.

The script outputs only named generated files in this directory:

| Video | Reference | Required observation |
|---|---|---|
| head-vbr-bframes.mp4 | head-vbr-bframes.samples.json | moov before mdat; strong sample-size variation; actual decoded B frames and PTS/DTS reordering |
| tail-vbr-bframes.mp4 | tail-vbr-bframes.samples.json | moov after mdat; remux preserves every packet payload, size, timestamp and sync flag |
| edit-list-vbr.mp4 | edit-list-vbr.samples.json | positive non-identity elst media time; measured demuxer edit-list effect |
| same edited video | edit-list-vbr.raw.samples.json | same file with edits ignored, for independent diagnosis |

Existing generated outputs cause failure unless -ReplaceGenerated is supplied.
The script never overwrites the existing seek-preview fixture, baseline reference
or this README. It does not delete old cache data, run Gradle or use a device.

All scenes are synthetic: static red, moving testsrc2, static blue. The encoder
uses quality-based CRF, fixed closed GOPs, explicit B frames and two encoder
threads. Options express intent only. Host checks require at least two actual
decoded B frames, non-equal PTS/DTS, at least twenty distinct packet sizes and a
maximum/minimum packet-size ratio of at least 10:1. A failed check rejects the
batch; a filename or option cannot replace those observations.
Head/tail fixtures must additionally show at least a 3:1 total-byte difference
between equal two-second static and moving windows, with sixty actual packets in
each. This tests variation over time beyond ordinary I/P frame-size differences.

References use schemaVersion 1. Each binds the complete video's SHA-256, byte
length, moov placement and edit-list mode. Every sample contains actual ffprobe
packet position, size, integer PTS/DTS/duration converted through the stream time
base, sync flag and SHA-256 of bytes at exactly that packet extent. Neither
byte/time proportions, rounded pts_time, Media3-generated expected tables nor
decoder-returned timestamps form the expected values.

The head/tail references set ignoreEditLists true and pass -ignore_editlist 1,
separating raw B-frame composition offsets from edit trimming. The edited
reference uses false and -ignore_editlist 0. All returned packets remain,
including negative-time decoder preroll/discard packets. FFmpeg and Media3 may
expose different edit/preroll policies: any mismatch must be captured and
diagnosed separately, never fixed by silently dropping packets, relaxing PTS
assertions or declaring the format supported. Extra packetFlags, original stream
timing and maxPacketEndUs are diagnostic fields.
Complex-reference durationUs is ffprobe's stream-duration definition. It can
differ from BoxParser TrackSampleTable.durationUs, especially when edits are
ignored and CTTS shifts presentation time. These two durations remain diagnostic;
neither substitutes for the other's expected value or a precise cached-range
endpoint. Sample count, offset, size, PTS, sync flags and payload hashes remain
strict comparisons.

complex-manifest.json is emitted only after all host gates pass, recording tool
versions/hashes, exact argument arrays and all output hashes. Encoder logs and
command arrays are retained under ignored artifacts/m3-cached-ranges/, including
on partial failure. Host generation alone still does not establish Android API
compatibility, partial-cache mapping, network-free seeking, HLS/DASH coverage,
private-mode behavior or P11 acceptance.

## Separate Android results, 2026-10-31

The exact three-method baseline invocation passed 3/3, skip 0, in 0.166s on the
fixed API35 x86_64 emulator. A separate complex invocation completed three methods
in 0.281s: strong-VBR/B-frame head and tail moov passed; the non-identity edit-list
comparison failed at sample0 PTS (ffprobe -266666us versus Media3 -250000us).
Both invocations had adb exit 0; the failed test batch's validation wrapper
correctly returned failure. Do not combine these into an invented six-test pass.

A further single-edit diagnostic invocation retained the original assertion and
failed again in 0.218s. Optional miruCachedRangeTrace=true emitted all 265 public
table entries before that unchanged assertion. Exact byte offsets, sizes, flags
and encoded payload hashes agreed for all 265 entries. All 265 actual Media3 PTS
matched an independent expansion of stts/ctts/elst. All differed from ffprobe by
one half-frame in source ticks (256/15360 seconds, displayed as 16666 or 16667us
after integer truncation). This diagnostic is not an additional passing test.

The file has movie timescale 1000, media timescale 15360, stts 265*512,
first raw composition PTS 1024 and elst media_time 4864. Direct edit application
therefore yields (1024-4864)/15360 = -0.25s. Fixed FFmpeg mov_fix_index retains
eight preroll/discard packets and normalizes the first included presentation
point to zero. Its trace records Offset DTS by 2560; independent simulation of
that control flow reproduces all 265 ffprobe PTS without altering references.

These are distinct edit/presentation policies, not a microsecond rounding
explanation for the half-frame shift. See fixed
[FFmpeg source](https://github.com/FFmpeg/FFmpeg/blob/27b8d1a017/libavformat/mov.c)
and [Media3 BoxParser](https://github.com/androidx/media/blob/1.11.1/libraries/extractor/src/main/java/androidx/media3/extractor/mp4/BoxParser.java).
The strict original ffprobe-comparison gate remains red. A production mapper
must follow actual Media3 presentation semantics and still verify decoder PTS/RGB
and cached-only seek behavior; no decoder, GL, disk-span tracker or P11 UI was
exercised by these parser probes.

Immutable inputs, command arrays, APK/source hashes, raw runner/logcat, the
preserved generation failure and full table diagnostics are retained under
ignored artifacts/m3-cached-ranges/. Main APK SHA stayed
496f5b0ceb274de8bfa2ca14bc7b2dac1b09709b99ae5ff4ed321f53875b5634 throughout.

## Explicit Android selectors (documentation only)

The exact test class is
app.miru.browser.capability.CachedRangeMp4IndexCapabilityTest.
An intentional baseline run requires both the class selector and argument
miruCachedRangeCapability=true. Ordinary broad instrumentation runs should exclude
the capability package, as for the existing P6 probes.

Without miruCachedRangeComplex=true, the class contains three baseline methods
and three complex methods skipped by assumption. Such a result must never be
reported as six-of-six capability success. For baseline-only terminal counts,
select these three methods explicitly:

- headMoovPublicTablesMatchAllFixedSourceSamples
- relocatedTailMoovPublicTablesMatchEveryRelocatedSourceSample
- omittingSampleArraysCannotSupplyAByteTimeIndex

Complex cases additionally require miruCachedRangeComplex=true and the generated
assets/references in the exact installed APK. Once complex mode is selected,
missing assets must fail rather than become an assumption skip. Full evidence
must identify each invocation's class/method selectors, both opt-in arguments,
APK/source hashes, counts and terminal result. These selectors describe repeatable
commands; the actual separate results are recorded above. Adding this document
does not execute adb.
