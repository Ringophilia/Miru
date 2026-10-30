# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
param([Parameter(Mandatory = $true)][string]$Ffmpeg)
$ErrorActionPreference = 'Stop'
$miruProject = Split-Path -Parent $PSScriptRoot
$miruFixture = Join-Path $miruProject 'app/src/androidTest/assets/media/seek-preview'
$miruEvidence = Join-Path $miruProject 'artifacts/m3-thumbnails'
New-Item -ItemType Directory -Force -Path $miruFixture, $miruEvidence | Out-Null
$miruCommands = [System.Collections.Generic.List[object]]::new()
function Invoke-FixtureFfmpeg([string[]]$Arguments) {
    $miruCommands.Add(@{ executable = $Ffmpeg; arguments = $Arguments })
    & $Ffmpeg @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Seek-preview fixture generation failed.' }
}
Push-Location $miruFixture
try {
    # Synthetic images only. Bottom-strip timecode is for human inspection; device
    # assertions read the upper color block and actual decoder presentationTimeMs.
    Invoke-FixtureFfmpeg @('-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
        'color=c=red:s=160x90:r=10:d=4', '-f', 'lavfi', '-i',
        'color=c=lime:s=160x90:r=10:d=4', '-f', 'lavfi', '-i',
        'color=c=blue:s=160x90:r=10:d=4', '-filter_complex',
        "[0:v][1:v][2:v]concat=n=3:v=1:a=0,drawbox=x=0:y=66:w=160:h=24:color=black:t=fill,drawtext=font='Consolas':text='%{pts\:hms}':x=4:y=70:fontsize=12:fontcolor=white[v]",
        '-map', '[v]', '-an', '-c:v', 'libx264', '-profile:v', 'baseline', '-preset',
        'veryfast', '-crf', '18', '-pix_fmt', 'yuv420p', '-g', '10', '-keyint_min',
        '10', '-sc_threshold', '0', '-movflags', '+faststart', '-threads', '2', '-y', 'colors.mp4')
    Invoke-FixtureFfmpeg @('-hide_banner', '-loglevel', 'error', '-i', 'colors.mp4',
        '-c', 'copy', '-f', 'hls', '-hls_time', '4', '-hls_playlist_type', 'vod',
        '-hls_segment_filename', 'ts-%02d.ts', '-y', 'ts.m3u8')
    Invoke-FixtureFfmpeg @('-hide_banner', '-loglevel', 'error', '-i', 'colors.mp4',
        '-c', 'copy', '-f', 'hls', '-hls_time', '4', '-hls_playlist_type', 'vod',
        '-hls_segment_type', 'fmp4', '-hls_fmp4_init_filename', 'fmp4-init.mp4',
        '-hls_segment_filename', 'fmp4-%02d.m4s', '-y', 'fmp4.m3u8')
    # Public deterministic test key, never an application credential.
    [IO.File]::WriteAllBytes((Join-Path $miruFixture 'aes.key'), [byte[]](0..15))
    @('aes.key', (Join-Path $miruFixture 'aes.key'), '00000000000000000000000000000001') |
        Set-Content -LiteralPath 'key-info.tmp' -Encoding utf8NoBOM
    try {
        Invoke-FixtureFfmpeg @('-hide_banner', '-loglevel', 'error', '-i', 'colors.mp4',
            '-c', 'copy', '-f', 'hls', '-hls_time', '4', '-hls_playlist_type', 'vod',
            '-hls_key_info_file', 'key-info.tmp', '-hls_segment_filename', 'aes-%02d.ts',
            '-y', 'aes.m3u8')
    } finally { Remove-Item -LiteralPath 'key-info.tmp' }
    Invoke-FixtureFfmpeg @('-hide_banner', '-loglevel', 'error', '-i', 'colors.mp4',
        '-c', 'copy', '-f', 'dash', '-use_template', '1', '-use_timeline', '1',
        '-seg_duration', '4', '-init_seg_name', 'dash-init-$RepresentationID$.m4s',
        '-media_seg_name', 'dash-$RepresentationID$-$Number%05d$.m4s', '-y', 'dash.mpd')
} finally { Pop-Location }
$miruManifest = [ordered]@{
    generatedBy = 'tools/generate-seek-preview-fixture.ps1'
    ffmpegVersion = (& $Ffmpeg -version | Select-Object -First 1)
    ffmpegSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $Ffmpeg).Hash
    commands = $miruCommands
    files = @(Get-ChildItem -LiteralPath $miruFixture -File | Where-Object Name -NotIn @('README.md', 'manifest.json') |
        Sort-Object Name | ForEach-Object { @{ name = $_.Name; bytes = $_.Length; sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash } })
}
$miruManifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $miruFixture 'manifest.json') -Encoding utf8NoBOM
