# Seek-preview capability fixtures

Copyright (c) 2026 Ringophilia and xiaomao. SPDX-License-Identifier: MPL-2.0.

Entirely generated locally, with no third-party video/audio. This is a new fixture;
existing `media/fixture.mp4` is a 44-second FFmpeg testsrc2 source and does not provide
the unambiguous temporal color oracle needed here. No media was copied from artifacts
or another checkout. FFmpeg is a development tool and is not shipped in Miru.

`tools/generate-seek-preview-fixture.ps1 -Ffmpeg <absolute-ffmpeg.exe>` generates one
12-second H.264 baseline, 160x90, 10 fps video with one-second keyframes and no audio.
The bottom 24 pixels show the actual encoded PTS timecode. The upper region is red at
0..4 seconds, green at 4..8, and blue at 8..12. The same bitstream is stream-copied
into TS HLS, fMP4 HLS, AES-128 TS HLS, and static DASH. `aes.key` is the public test
key 00..0f; the fixed IV is 00000000000000000000000000000001. It is not a credential.

`manifest.json` records the exact generation command arguments, FFmpeg version and
executable SHA-256, and every media file's byte count and SHA-256. Generation used
FFmpeg n8.0.1-66-g27b8d1a017-20260228. The manifests use relative URLs and end-of-list/
static duration; no external origin is involved. The manifests are not edited to
fake successful source or decoder results.

Device probes request 9000, 1000, then 5000 ms. Each checks the **returned decoder
timestamp** within 150 ms, bitmap 160x90, and mean RGB of the upper block (x55..95,
y15..35) within 45/channel of blue/red/green respectively. Bitmaps are inspected
immediately and never recycled because FrameExtractor may reuse a returned object.
All assets and the fixture content provider exist only in the Android test APK.
