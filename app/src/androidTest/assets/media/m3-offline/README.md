# Synthetic offline fixtures

Copyright (c) 2026 Ringophilia and xiaomao. SPDX-License-Identifier: MPL-2.0.

`tools/generate-offline-fixture.ps1` generates the silent low-resolution H.264
160 x 90 variant (12 seconds, three four-second TS segments) from the project's
synthetic `media/fixture.mp4`. `master.m3u8` advertises this variant and a high
320 x 180 variant. The instrumentation HTTP origin maps `high.m3u8`, its video
segments, the two language audio playlists and WebVTT files to the existing
`media/m3-tracks/` assets; no duplicate high-resolution content is necessary.

`live.m3u8` intentionally omits ENDLIST and is rejected by offline preparation.
The DASH tests reuse the existing synthetic twelve-second `media/dash/` assets.
These files contain no third-party film, music or artwork and are only bundled
in instrumentation, never the release application.
