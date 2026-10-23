# Miru super resolution

The OpenGL renderer and mpv hook parser are adapted from Ringo-Cove:
`packages/video_player_android/android/src/main/kotlin/io/flutter/plugins/videoplayer/superres/`.
The original plugin's BSD-3-Clause license is retained in
`licenses/Flutter-BSD-3-Clause.txt`. Miru adaptations remove Flutter texture
ownership, render to an Android SurfaceView surface, and implement asynchronous
lifecycle, output fitting, and protection policies. Source: G:/Projects/Ringo-Cove.

All 14 GLSL assets are copied unchanged from Ringo-Cove's Android `anime4k/` directory.
Anime4K v4 is copyright (c) 2019-2021 bloc97, MIT licensed; the original full text
is `src/main/assets/anime4k/LICENSE`. AMD FidelityFX FSR 1.0 is copyright (c) 2021
Advanced Micro Devices, Inc., MIT licensed; the full text is
`src/main/assets/anime4k/FSR_LICENSE`. The AMD shader headers also retain the
original copyright and notice. Both MIT notices travel inside the APK assets;
the original plugin's BSD notice is also bundled as `licenses/Flutter-BSD-3-Clause.txt`.

The original synthetic benchmark video is copied from Ringo-Cove
`assets/superres/benchmark.mp4`: H.264, 1280x720, 24 fps, three seconds. It is not
external site content. It is used without modification for real rendering tests.

The policy and integration API are Miru contributions, copyright (c) 2026
Ringophilia and xiaomao, MPL-2.0. Upstream copyright is not replaced.
