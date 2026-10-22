# Synthetic DASH fixture

Copyright (c) 2026 Ringophilia and xiaomao. SPDX-License-Identifier: MPL-2.0.

This twelve-second static DASH presentation is a stream-copy of the beginning of
the existing synthetic `../fixture.mp4` (FFmpeg testsrc2 plus a 440 Hz sine wave).
It contains separate H.264 video and AAC audio representations, their initialization
fragments and three four-second media fragments each. There are no external media
URLs. The XML namespace/schema identifiers are metadata; playback uses only the
device-loopback HTTP origin supplied by `AdaptivePlaybackIntegrationTest`.

The files are instrumentation assets and are not included in a release APK.
Regenerate using `tools/generate-dash-fixture.ps1`; FFmpeg is a development tool,
not a shipped runtime dependency.
