# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
$ErrorActionPreference = 'Stop'
$miruProject = Split-Path -Parent $PSScriptRoot
$miruSource = Join-Path $miruProject 'app/src/androidTest/assets/media/fixture.mp4'
$miruOffline = Join-Path $miruProject 'app/src/androidTest/assets/media/m3-offline'
New-Item -ItemType Directory -Force -Path $miruOffline | Out-Null
& ffmpeg -hide_banner -loglevel error -i $miruSource -t 12 -map 0:v:0 -an -vf 'scale=160:90' -c:v libx264 -profile:v baseline -preset veryfast -crf 28 -pix_fmt yuv420p -g 96 -keyint_min 96 -sc_threshold 0 -f hls -hls_time 4 -hls_playlist_type vod -hls_segment_filename (Join-Path $miruOffline 'low-%02d.ts') -y (Join-Path $miruOffline 'low.m3u8')
if ($LASTEXITCODE -ne 0) { throw 'Synthetic offline low-resolution HLS generation failed.' }
@'
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="english.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Japanese",LANGUAGE="ja",DEFAULT=NO,AUTOSELECT=YES,URI="japanese.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subtitles",NAME="Fixture English",LANGUAGE="en",DEFAULT=NO,AUTOSELECT=YES,URI="subtitles.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=250000,AVERAGE-BANDWIDTH=160000,RESOLUTION=160x90,CODECS="avc1.42C00B,mp4a.40.2",AUDIO="audio",SUBTITLES="subtitles"
low.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=600000,AVERAGE-BANDWIDTH=450000,RESOLUTION=320x180,CODECS="avc1.42C00C,mp4a.40.2",AUDIO="audio",SUBTITLES="subtitles"
high.m3u8
'@ | Set-Content -LiteralPath (Join-Path $miruOffline 'master.m3u8') -Encoding utf8NoBOM
@'
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:0
#EXTINF:4.000000,
low-00.ts
#EXTINF:4.000000,
low-01.ts
#EXTINF:4.000000,
low-02.ts
'@ | Set-Content -LiteralPath (Join-Path $miruOffline 'live.m3u8') -Encoding utf8NoBOM
