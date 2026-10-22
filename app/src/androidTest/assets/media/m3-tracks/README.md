Copyright (c) 2026 Ringophilia and xiaomao. SPDX-License-Identifier: MPL-2.0.

Synthetic twelve-second track-selection fixture. The H.264 video is the existing
Miru test pattern; two independent mono AAC tracks contain 440 Hz and 880 Hz sine
waves and are labelled English and Japanese. The HLS master also advertises an
external WebVTT subtitle rendition. Local SRT, WebVTT and ASS files contain matching
plain test captions. No third-party audiovisual material or website is used.

Regenerate with `tools/generate-m3-tracks-fixture.ps1` and FFmpeg on PATH. Segment
and manifest timestamps are generated together; keep the embedded WebVTT
X-TIMESTAMP-MAP aligned to FFmpeg's 1.4 second MPEG-TS timestamp origin.
