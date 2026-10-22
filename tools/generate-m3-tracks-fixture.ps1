# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
$ErrorActionPreference = 'Stop'
$miruRoot = Split-Path -Parent $PSScriptRoot
$miruInput = Join-Path $miruRoot 'app/src/androidTest/assets/media/fixture.mp4'
$miruTracks = Join-Path $miruRoot 'app/src/androidTest/assets/media/m3-tracks'
New-Item -ItemType Directory -Force -Path $miruTracks | Out-Null
& ffmpeg -hide_banner -loglevel error -i $miruInput -t 12 -map 0:v:0 -an -c:v copy -f hls -hls_time 4 -hls_playlist_type vod -hls_segment_filename (Join-Path $miruTracks 'video-%02d.ts') -y (Join-Path $miruTracks 'video.m3u8')
if ($LASTEXITCODE -ne 0) { throw 'Synthetic HLS video fixture generation failed.' }
foreach ($miruAudio in @(@{Name='english';Frequency=440}, @{Name='japanese';Frequency=880})) {
    & ffmpeg -hide_banner -loglevel error -f lavfi -i "sine=frequency=$($miruAudio.Frequency):sample_rate=44100:duration=12" -c:a aac -b:a 48k -f hls -hls_time 4 -hls_playlist_type vod -hls_segment_filename (Join-Path $miruTracks "$($miruAudio.Name)-%02d.ts") -y (Join-Path $miruTracks "$($miruAudio.Name).m3u8")
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic HLS audio fixture generation failed.' }
}
@'
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="english.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Japanese",LANGUAGE="ja",DEFAULT=NO,AUTOSELECT=YES,URI="japanese.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subtitles",NAME="Fixture English",LANGUAGE="en",DEFAULT=NO,AUTOSELECT=YES,URI="subtitles.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=600000,AVERAGE-BANDWIDTH=450000,RESOLUTION=320x180,CODECS="avc1.42C00C,mp4a.40.2",AUDIO="audio",SUBTITLES="subtitles"
video.m3u8
'@ | Set-Content -LiteralPath (Join-Path $miruTracks 'master.m3u8') -Encoding utf8NoBOM
@'
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:12
#EXT-X-MEDIA-SEQUENCE:0
#EXT-X-PLAYLIST-TYPE:VOD
#EXTINF:12.000000,
embedded.vtt
#EXT-X-ENDLIST
'@ | Set-Content -LiteralPath (Join-Path $miruTracks 'subtitles.m3u8') -Encoding utf8NoBOM
@'
WEBVTT
X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:126000

00:00:01.000 --> 00:00:11.000
Miru embedded subtitle
'@ | Set-Content -LiteralPath (Join-Path $miruTracks 'embedded.vtt') -Encoding utf8NoBOM
@'
1
00:00:02,000 --> 00:00:09,000
Miru local SRT
'@ | Set-Content -LiteralPath (Join-Path $miruTracks 'local.srt') -Encoding utf8NoBOM
@'
WEBVTT

00:00:02.000 --> 00:00:09.000
Miru local VTT
'@ | Set-Content -LiteralPath (Join-Path $miruTracks 'local.vtt') -Encoding utf8NoBOM
@'
[Script Info]
ScriptType: v4.00+
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,22,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,10,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:02.00,0:00:09.00,Default,,0,0,0,,{\i1}Miru local ASS{\i0}
'@ | Set-Content -LiteralPath (Join-Path $miruTracks 'local.ass') -Encoding utf8NoBOM
