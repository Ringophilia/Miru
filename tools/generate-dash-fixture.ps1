# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
$ErrorActionPreference = 'Stop'
$miruRoot = Split-Path -Parent $PSScriptRoot
$miruInput = Join-Path $miruRoot 'app/src/androidTest/assets/media/fixture.mp4'
$miruDash = Join-Path $miruRoot 'app/src/androidTest/assets/media/dash'
New-Item -ItemType Directory -Force -Path $miruDash | Out-Null
& ffmpeg -hide_banner -loglevel error -i $miruInput -t 12 -map 0:v:0 -map 0:a:0 -c copy -f dash -use_template 1 -use_timeline 1 -seg_duration 4 -init_seg_name 'dash-init-$RepresentationID$.m4s' -media_seg_name 'dash-segment-$RepresentationID$-$Number%05d$.m4s' -y (Join-Path $miruDash 'dash-manifest.mpd')
if ($LASTEXITCODE -ne 0) { throw 'Synthetic DASH fixture generation failed.' }
