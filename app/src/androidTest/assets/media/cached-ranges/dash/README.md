# DASH exact cache-range fixtures

Copyright (c) 2026 Ringophilia and xiaomao. SPDX-License-Identifier: MPL-2.0.

Generated from the project's owned twelve-second `seek-preview/colors.mp4`, without
third-party audiovisual material. Regenerate using
`python tools/generate-dash-cached-range-fixtures.py` and the pinned FFmpeg/FFprobe
recorded in `manifest.json`. FFmpeg is a development tool, not shipped in Miru.

`base.mp4` is fragmented MP4 with a global sidx. `base.mpd` uses SegmentBase with
explicit initialization/index ranges; `list.mpd` references the same physical
fragments using SegmentList; `pto.mpd` applies a two-second presentationTimeOffset
and has a ten-second presentation. Initialization ends at byte 795, sidx occupies
796..871. Three media ranges begin at 872, 8406 and 15384.

All original sidx fields are retained. The fixed FFmpeg writes starts_with_SAP=1
but SAP_type=0. The derived MPDs' startWithSAP=1 declaration is independently
supported by per-fragment first-packet key flags and three independent decodes
with the original red/green/blue RGB threshold (45/channel), not by changing sidx.
The first failed generator assumption and original output are retained under
`artifacts/m3-cached-ranges/dash-fixture-generation/failed-01/`.

The generator records exact atom/sample-reference byte positions and sizes, raw
SAP fields, tools/source/output SHA-256 and independent decoder commands/results.
This does not itself prove Android runtime, cached seek, UI or full P11 acceptance.
