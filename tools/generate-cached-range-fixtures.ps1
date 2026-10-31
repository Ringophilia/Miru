# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
#Requires -Version 7.0
<#
SOURCE ONLY until the device/build owner authorizes fixture generation.
This script invokes FFmpeg/ffprobe when run. It has deliberately not been run
as part of the P11 preparation that introduced it.
#>
param(
    [Parameter(Mandatory = $true)][string]$Ffmpeg,
    [Parameter(Mandatory = $true)][string]$Ffprobe,
    [switch]$ReplaceGenerated
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$miruProject = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$miruFixture = Join-Path $miruProject 'app/src/androidTest/assets/media/cached-ranges'
$miruEvidence = Join-Path $miruProject 'artifacts/m3-cached-ranges'
$miruFfmpegPath = (Resolve-Path -LiteralPath $Ffmpeg).Path
$miruFfprobePath = (Resolve-Path -LiteralPath $Ffprobe).Path
$miruCommands = [Collections.Generic.List[object]]::new()
$miruGeneratedNames = @(
    'head-vbr-bframes.mp4', 'tail-vbr-bframes.mp4', 'edit-list-vbr.mp4',
    'head-vbr-bframes.samples.json', 'tail-vbr-bframes.samples.json',
    'edit-list-vbr.samples.json', 'edit-list-vbr.raw.samples.json', 'complex-manifest.json'
)

# This allowlist never includes baseline references or seek-preview assets.
New-Item -ItemType Directory -Force -Path $miruFixture, $miruEvidence | Out-Null
foreach ($miruName in $miruGeneratedNames) {
    if ((Test-Path -LiteralPath (Join-Path $miruFixture $miruName)) -and -not $ReplaceGenerated) {
        throw "Generated fixture already exists: $miruName. Use -ReplaceGenerated intentionally."
    }
}

function Invoke-FixtureEncoder([string[]]$Arguments) {
    $miruCommands.Add([ordered]@{ executable = $miruFfmpegPath; arguments = $Arguments; workingDirectory = $miruFixture })
    $miruLog = Join-Path $miruEvidence ('generate-' + $miruCommands.Count.ToString('00') + '.log')
    & $miruFfmpegPath @Arguments 2>&1 | Tee-Object -FilePath $miruLog | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Fixture encoder failed; inspect $miruLog" }
}

function Invoke-FixtureProbe([string[]]$Arguments) {
    $miruCommands.Add([ordered]@{ executable = $miruFfprobePath; arguments = $Arguments; workingDirectory = $miruFixture })
    $miruRaw = @(& $miruFfprobePath @Arguments)
    if ($LASTEXITCODE -ne 0) { throw 'Fixture packet/frame probe failed.' }
    return (($miruRaw -join [Environment]::NewLine) | ConvertFrom-Json -AsHashtable)
}

function Read-U32BE([byte[]]$Data, [long]$Offset) {
    if ($Offset -lt 0 -or $Offset + 4 -gt $Data.LongLength) { throw 'Truncated uint32.' }
    return [long]$Data[$Offset] * 16777216 + [long]$Data[$Offset + 1] * 65536 +
        [long]$Data[$Offset + 2] * 256 + [long]$Data[$Offset + 3]
}

function Read-U64BE([byte[]]$Data, [long]$Offset) {
    if ($Offset -lt 0 -or $Offset + 8 -gt $Data.LongLength) { throw 'Truncated uint64.' }
    $miruNumber = [Numerics.BigInteger]::Zero
    for ($miruByte = 0; $miruByte -lt 8; $miruByte++) { $miruNumber = $miruNumber * 256 + $Data[$Offset + $miruByte] }
    return $miruNumber
}

function Read-I32BE([byte[]]$Data, [long]$Offset) {
    $miruUnsigned = Read-U32BE $Data $Offset
    if ($miruUnsigned -ge 2147483648) { return $miruUnsigned - 4294967296 }
    return $miruUnsigned
}

function Read-I64BE([byte[]]$Data, [long]$Offset) {
    $miruUnsigned = Read-U64BE $Data $Offset
    if ($miruUnsigned -ge [Numerics.BigInteger]::Pow(2, 63)) { $miruUnsigned -= [Numerics.BigInteger]::Pow(2, 64) }
    return [long]$miruUnsigned
}

function Read-Boxes([byte[]]$Data, [long]$Start, [long]$End) {
    $miruCursor = $Start
    while ($miruCursor -lt $End) {
        if ($End - $miruCursor -lt 8) { throw 'Truncated BMFF box header.' }
        $miruSize = Read-U32BE $Data $miruCursor
        $miruHeader = 8L
        $miruType = [Text.Encoding]::ASCII.GetString($Data, [int]$miruCursor + 4, 4)
        if ($miruSize -eq 1) {
            $miruLarge = Read-U64BE $Data ($miruCursor + 8)
            if ($miruLarge -gt [long]::MaxValue) { throw 'Oversize BMFF box.' }
            $miruSize = [long]$miruLarge
            $miruHeader = 16L
        } elseif ($miruSize -eq 0) { $miruSize = $End - $miruCursor }
        if ($miruSize -lt $miruHeader -or $miruSize -gt $End - $miruCursor) { throw 'BMFF box exceeds parent bounds.' }
        [pscustomobject]@{ type = $miruType; start = $miruCursor; end = $miruCursor + $miruSize; header = $miruHeader }
        $miruCursor += $miruSize
    }
}

function Find-OneChild([byte[]]$Data, $Parent, [string]$Type) {
    $miruFound = @(Read-Boxes $Data ($Parent.start + $Parent.header) $Parent.end | Where-Object type -EQ $Type)
    if ($miruFound.Count -ne 1) { throw "Expected exactly one $Type child." }
    return $miruFound[0]
}

function Get-BmffFacts([byte[]]$Data) {
    $miruTop = @(Read-Boxes $Data 0 $Data.LongLength)
    if (@($miruTop | Where-Object type -EQ 'moof').Count -ne 0) { throw 'Expected unfragmented MP4.' }
    $miruMoovs = @($miruTop | Where-Object type -EQ 'moov')
    $miruMdats = @($miruTop | Where-Object type -EQ 'mdat')
    if ($miruMoovs.Count -ne 1 -or $miruMdats.Count -ne 1) { throw 'Expected one moov and one mdat.' }
    $miruMoov = $miruMoovs[0]
    $miruMvhd = Find-OneChild $Data $miruMoov 'mvhd'
    $miruFull = $miruMvhd.start + $miruMvhd.header
    $miruVersion = $Data[$miruFull]
    if ($miruVersion -notin @(0, 1)) { throw 'Unsupported mvhd version.' }
    $miruMovieTimescale = Read-U32BE $Data ($miruFull + $(if ($miruVersion -eq 0) { 12 } else { 20 }))
    $miruTracks = [Collections.Generic.List[object]]::new()
    foreach ($miruTrak in @(Read-Boxes $Data ($miruMoov.start + $miruMoov.header) $miruMoov.end | Where-Object type -EQ 'trak')) {
        $miruTkhd = Find-OneChild $Data $miruTrak 'tkhd'
        $miruFull = $miruTkhd.start + $miruTkhd.header
        $miruVersion = $Data[$miruFull]
        if ($miruVersion -notin @(0, 1)) { throw 'Unsupported tkhd version.' }
        $miruTrackId = Read-U32BE $Data ($miruFull + $(if ($miruVersion -eq 0) { 12 } else { 20 }))
        $miruEdits = [Collections.Generic.List[object]]::new()
        $miruEdts = @(Read-Boxes $Data ($miruTrak.start + $miruTrak.header) $miruTrak.end | Where-Object type -EQ 'edts')
        if ($miruEdts.Count -gt 1) { throw 'Multiple edts containers.' }
        if ($miruEdts.Count -eq 1) {
            $miruElst = Find-OneChild $Data $miruEdts[0] 'elst'
            $miruFull = $miruElst.start + $miruElst.header
            $miruVersion = $Data[$miruFull]
            if ($miruVersion -notin @(0, 1)) { throw 'Unsupported elst version.' }
            $miruCount = Read-U32BE $Data ($miruFull + 4)
            $miruWidth = $(if ($miruVersion -eq 0) { 12L } else { 20L })
            if ($miruFull + 8 + $miruCount * $miruWidth -ne $miruElst.end) { throw 'Invalid elst size.' }
            for ($miruEdit = 0; $miruEdit -lt $miruCount; $miruEdit++) {
                $miruOffset = $miruFull + 8 + $miruEdit * $miruWidth
                if ($miruVersion -eq 0) {
                    $miruDuration = Read-U32BE $Data $miruOffset
                    $miruTime = Read-I32BE $Data ($miruOffset + 4)
                } else {
                    $miruDuration = [long](Read-U64BE $Data $miruOffset)
                    $miruTime = Read-I64BE $Data ($miruOffset + 8)
                }
                $miruRate = Read-U32BE $Data ($miruOffset + $miruWidth - 4)
                if ($miruRate -ne 65536) { throw 'Fixtures require edit media rate 1.0.' }
                $miruEdits.Add([ordered]@{ durationMovieTicks = $miruDuration; mediaTimeTicks = $miruTime; mediaRateFixed16 = $miruRate })
            }
        }
        $miruTracks.Add([ordered]@{ id = $miruTrackId; edits = $miruEdits.ToArray() })
    }
    return [ordered]@{
        moovPlacement = $(if ($miruMoov.start -lt $miruMdats[0].start) { 'head' } else { 'tail' })
        movieTimescale = $miruMovieTimescale
        topLevel = @($miruTop | ForEach-Object { [ordered]@{ type = $_.type; offset = $_.start; length = $_.end - $_.start } })
        tracks = $miruTracks.ToArray()
    }
}

function Convert-TicksToUs([long]$Ticks, [long]$Numerator, [long]$Denominator) {
    if ($Denominator -le 0) { throw 'Invalid packet time base.' }
    # Integer rational rescaling truncates towards zero. Never read rounded
    # pts_time/dts_time floating-point fields or estimate from byte proportions.
    $miruScaled = [Numerics.BigInteger]$Ticks * $Numerator * 1000000
    return [long][Numerics.BigInteger]::Divide($miruScaled, $Denominator)
}

function Get-PayloadHash([byte[]]$Data, [long]$Offset, [int]$Length) {
    if ($Offset -lt 0 -or $Length -le 0 -or $Offset + $Length -gt $Data.LongLength) { throw 'Packet extent escapes actual MP4 bytes.' }
    $miruHash = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($miruHash.ComputeHash($Data, [int]$Offset, $Length)).Replace('-', '').ToLowerInvariant() }
    finally { $miruHash.Dispose() }
}

function Export-PacketReference([string]$Name, [string]$ReferenceName, [bool]$IgnoreEdits) {
    $miruPath = Join-Path $miruFixture $Name
    $miruData = [IO.File]::ReadAllBytes($miruPath)
    $miruFacts = Get-BmffFacts $miruData
    $miruMdat = @(Read-Boxes $miruData 0 $miruData.LongLength | Where-Object type -EQ 'mdat')[0]
    $miruNonIdentityEdit = @($miruFacts.tracks | ForEach-Object { $_.edits } | Where-Object { $_.mediaTimeTicks -ne 0 }).Count -gt 0
    $miruArguments = @('-v', 'error', '-ignore_editlist', $(if ($IgnoreEdits) { '1' } else { '0' }),
        '-show_streams', '-show_packets', '-show_entries',
        'stream=index,id,codec_type,time_base,start_pts,duration_ts:packet=stream_index,pos,size,pts,dts,duration,flags',
        '-of', 'json', $Name)
    $miruProbe = Invoke-FixtureProbe $miruArguments
    $miruTracks = [Collections.Generic.List[object]]::new()
    foreach ($miruStream in @($miruProbe.streams)) {
        if ($miruStream.codec_type -ne 'video') { throw 'These fixtures must contain video only.' }
        $miruTimeBase = $miruStream.time_base.Split('/')
        if ($miruTimeBase.Count -ne 2 -or [long]$miruTimeBase[0] -ne 1) { throw 'Expected a 1/timescale time base.' }
        $miruNumerator = [long]$miruTimeBase[0]
        $miruDenominator = [long]$miruTimeBase[1]
        $miruSamples = [Collections.Generic.List[object]]::new()
        $miruLastDts = [long]::MinValue
        foreach ($miruPacket in @($miruProbe.packets | Where-Object { $_.stream_index -eq $miruStream.index })) {
            foreach ($miruField in @('pos', 'size', 'pts', 'dts', 'duration', 'flags')) {
                if (-not $miruPacket.ContainsKey($miruField)) { throw "ffprobe omitted packet $miruField." }
            }
            $miruOffset = [long]$miruPacket.pos
            $miruLength = [int]$miruPacket.size
            if ($miruOffset -lt $miruMdat.start + $miruMdat.header -or $miruOffset + $miruLength -gt $miruMdat.end) {
                throw 'Packet extent is not wholly inside the actual mdat payload.'
            }
            $miruDts = [long]$miruPacket.dts
            if ($miruDts -lt $miruLastDts) { throw 'Packet decode order is not monotonic.' }
            $miruLastDts = $miruDts
            $miruSamples.Add([ordered]@{
                offset = $miruOffset; size = $miruLength
                ptsUs = Convert-TicksToUs ([long]$miruPacket.pts) $miruNumerator $miruDenominator
                dtsUs = Convert-TicksToUs $miruDts $miruNumerator $miruDenominator
                durationUs = Convert-TicksToUs ([long]$miruPacket.duration) $miruNumerator $miruDenominator
                isSync = ([string]$miruPacket.flags).Contains('K')
                sha256 = Get-PayloadHash $miruData $miruOffset $miruLength
                packetFlags = [string]$miruPacket.flags
            })
        }
        if ($miruSamples.Count -eq 0) { throw 'No actual packets in fixture.' }
        $miruIdText = [string]$miruStream.id
        $miruId = $(if ($miruIdText.StartsWith('0x')) { [Convert]::ToInt32($miruIdText.Substring(2), 16) } else { [int]$miruIdText })
        $miruStreamDurationUs = Convert-TicksToUs ([long]$miruStream.duration_ts) $miruNumerator $miruDenominator
        $miruTracks.Add([ordered]@{
            id = $miruId; type = 'video'; timescale = $miruDenominator
            sampleCount = $miruSamples.Count; durationUs = $miruStreamDurationUs
            streamStartPts = [long]$miruStream.start_pts
            streamDurationTicks = [long]$miruStream.duration_ts
            maxPacketEndUs = [long](($miruSamples | ForEach-Object { $_.ptsUs + $_.durationUs } | Measure-Object -Maximum).Maximum)
            samples = $miruSamples.ToArray()
        })
    }
    $miruFrames = Invoke-FixtureProbe @('-v', 'error', '-select_streams', 'v:0',
        '-show_frames', '-show_entries', 'frame=pict_type', '-of', 'json', $Name)
    $miruBCount = @($miruFrames.frames | Where-Object pict_type -EQ 'B').Count
    if ($miruBCount -lt 2) { throw 'Fixture does not actually contain multiple B frames.' }
    $miruAllSamples = @($miruTracks | ForEach-Object { $_.samples })
    $miruSizes = @($miruAllSamples | ForEach-Object { $_.size } | Sort-Object)
    if ($miruSizes.Count -lt 90 -or @($miruSizes | Sort-Object -Unique).Count -lt 20) { throw 'Too few distinct sizes to exercise strong VBR.' }
    $miruMinimum = [long]$miruSizes[0]
    $miruMaximum = [long]$miruSizes[-1]
    if ($miruMaximum -lt 10 * $miruMinimum) { throw 'Sample-size ratio is below the required 10:1 VBR gate.' }
    $miruReordered = @($miruAllSamples | Where-Object { $_.ptsUs -ne $_.dtsUs }).Count
    if ($miruReordered -eq 0) { throw 'B-frame stream has no measured PTS/DTS reordering.' }
    $miruReference = [ordered]@{
        schemaVersion = 1; asset = 'media/cached-ranges/' + $Name
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $miruPath).Hash.ToLowerInvariant()
        bytes = $miruData.LongLength; moovPlacement = $miruFacts.moovPlacement; ignoreEditLists = $IgnoreEdits
        provenance = [ordered]@{
            exporter = 'tools/generate-cached-range-fixtures.ps1'
            method = 'Independent ffprobe integer packet pos/size/PTS/DTS/duration/flags; actual packet-payload SHA-256'
            media3Used = $false
            ffmpegVersion = $miruFfmpegVersion; ffmpegSha256 = $miruFfmpegHash
            ffprobeVersion = $miruFfprobeVersion; ffprobeSha256 = $miruFfprobeHash
            packetProbeArguments = $miruArguments
            nonIdentityEditList = $miruNonIdentityEdit
            editListPolicy = $(if ($IgnoreEdits) { 'Raw sample table; ignore_editlist=1' } else {
                'Demuxer applies edits; retain every packet including discard/preroll. Media3 differences require diagnosis, never silent filtering.'
            })
        }
        observed = [ordered]@{
            decodedBFrames = $miruBCount; reorderedPackets = $miruReordered
            minimumSampleBytes = $miruMinimum; maximumSampleBytes = $miruMaximum; bmff = $miruFacts
        }
        tracks = $miruTracks.ToArray()
    }
    $miruReference | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath (Join-Path $miruFixture $ReferenceName) -Encoding utf8NoBOM
    return $miruReference
}

Push-Location $miruFixture
try {
    $miruCommands.Add([ordered]@{ executable = $miruFfmpegPath; arguments = @('-version') })
    $miruFfmpegVersion = @(& $miruFfmpegPath -version)[0]
    if ($LASTEXITCODE -ne 0) { throw 'Unable to identify FFmpeg.' }
    $miruCommands.Add([ordered]@{ executable = $miruFfprobePath; arguments = @('-version') })
    $miruFfprobeVersion = @(& $miruFfprobePath -version)[0]
    if ($LASTEXITCODE -ne 0) { throw 'Unable to identify ffprobe.' }
    $miruFfmpegHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $miruFfmpegPath).Hash.ToLowerInvariant()
    $miruFfprobeHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $miruFfprobePath).Hash.ToLowerInvariant()

    # Synthetic scenes change coding complexity. Closed GOPs and B-adapt disabled
    # make reordering intentional. Actual packet observations below prove VBR.
    Invoke-FixtureEncoder @('-hide_banner', '-loglevel', 'error', '-nostdin', '-filter_complex_threads', '1',
        '-f', 'lavfi', '-i', 'color=c=red:s=160x90:r=30:d=4',
        '-f', 'lavfi', '-i', 'testsrc2=s=160x90:r=30:d=4',
        '-f', 'lavfi', '-i', 'color=c=blue:s=160x90:r=30:d=4',
        '-filter_complex', '[0:v][1:v][2:v]concat=n=3:v=1:a=0,format=yuv420p[v]',
        '-map', '[v]', '-an', '-c:v', 'libx264', '-profile:v', 'high', '-preset', 'veryfast',
        '-crf', '18', '-bf', '3', '-g', '30', '-keyint_min', '30',
        '-x264-params', 'b-adapt=0:scenecut=0:open-gop=0',
        '-video_track_timescale', '15360', '-movie_timescale', '1000',
        '-use_editlist', '1', '-threads', '2', '-movflags', '+faststart', '-y', 'head-vbr-bframes.mp4')
    Invoke-FixtureEncoder @('-hide_banner', '-loglevel', 'error', '-nostdin',
        '-i', 'head-vbr-bframes.mp4', '-map', '0:v:0', '-an', '-c', 'copy',
        '-video_track_timescale', '15360', '-movie_timescale', '1000',
        '-use_editlist', '1', '-movflags', '-faststart', '-y', 'tail-vbr-bframes.mp4')
    # Non-keyframe trim can retain negative-time decoder preroll marked D. The
    # edited reference deliberately keeps those packets instead of hiding them.
    Invoke-FixtureEncoder @('-hide_banner', '-loglevel', 'error', '-nostdin',
        '-ss', '1.25', '-i', 'head-vbr-bframes.mp4', '-t', '8.5',
        '-map', '0:v:0', '-an', '-c', 'copy', '-avoid_negative_ts', 'disabled',
        '-video_track_timescale', '15360', '-movie_timescale', '1000',
        '-use_editlist', '1', '-movflags', '+faststart', '-y', 'edit-list-vbr.mp4')

    $miruHead = Export-PacketReference 'head-vbr-bframes.mp4' 'head-vbr-bframes.samples.json' $true
    $miruTail = Export-PacketReference 'tail-vbr-bframes.mp4' 'tail-vbr-bframes.samples.json' $true
    $miruEdited = Export-PacketReference 'edit-list-vbr.mp4' 'edit-list-vbr.samples.json' $false
    $miruRawEdited = Export-PacketReference 'edit-list-vbr.mp4' 'edit-list-vbr.raw.samples.json' $true
    # I/P frame-size variation alone also occurs in ordinary near-CBR streams.
    # Require equal-duration static/moving scene windows to differ in real bytes.
    $miruWindowChecks = [Collections.Generic.List[object]]::new()
    foreach ($miruReference in @($miruHead, $miruTail)) {
        $miruQuietPackets = @($miruReference.tracks[0].samples | Where-Object { $_.ptsUs -ge 1000000 -and $_.ptsUs -lt 3000000 })
        $miruBusyPackets = @($miruReference.tracks[0].samples | Where-Object { $_.ptsUs -ge 5000000 -and $_.ptsUs -lt 7000000 })
        $miruQuietBytes = [long](($miruQuietPackets | ForEach-Object { [long]$_.size } | Measure-Object -Sum).Sum)
        $miruBusyBytes = [long](($miruBusyPackets | ForEach-Object { [long]$_.size } | Measure-Object -Sum).Sum)
        if ($miruQuietPackets.Count -ne 60 -or $miruBusyPackets.Count -ne 60 -or $miruQuietBytes -le 0 -or $miruBusyBytes -lt 3 * $miruQuietBytes) {
            throw 'Equal two-second static/moving windows did not prove strong variable byte density (at least 3:1).'
        }
        $miruWindowChecks.Add([ordered]@{ asset = $miruReference.asset; staticTwoSecondBytes = $miruQuietBytes; movingTwoSecondBytes = $miruBusyBytes })
    }
    if ($miruHead.moovPlacement -ne 'head' -or $miruTail.moovPlacement -ne 'tail' -or $miruEdited.moovPlacement -ne 'head') {
        throw 'Top-level boxes did not satisfy requested head/tail placement.'
    }
    if ($miruHead.tracks.Count -ne 1 -or $miruTail.tracks.Count -ne 1) { throw 'Expected single video tracks.' }
    $miruHeadPackets = @($miruHead.tracks[0].samples)
    $miruTailPackets = @($miruTail.tracks[0].samples)
    if ($miruHeadPackets.Count -ne $miruTailPackets.Count) { throw 'Remux changed packet count.' }
    for ($miruPacket = 0; $miruPacket -lt $miruHeadPackets.Count; $miruPacket++) {
        foreach ($miruField in @('size', 'ptsUs', 'dtsUs', 'durationUs', 'isSync', 'sha256')) {
            if ($miruHeadPackets[$miruPacket][$miruField] -cne $miruTailPackets[$miruPacket][$miruField]) {
                throw "Head/tail remux changed packet $miruPacket field $miruField."
            }
        }
    }
    $miruPositiveEdit = @($miruEdited.observed.bmff.tracks | ForEach-Object { $_.edits } | Where-Object { $_.mediaTimeTicks -gt 0 })
    if ($miruPositiveEdit.Count -eq 0) { throw 'Edited fixture has no positive non-identity media-time edit.' }
    $miruHeadEditMaximum = [long](($miruHead.observed.bmff.tracks | ForEach-Object { $_.edits } |
        ForEach-Object { [long]$_.mediaTimeTicks } | Measure-Object -Maximum).Maximum)
    if (@($miruPositiveEdit | Where-Object { $_.mediaTimeTicks -gt $miruHeadEditMaximum }).Count -eq 0) {
        throw 'Trimmed fixture did not add a media-time edit beyond ordinary B-frame compensation.'
    }
    if ($miruRawEdited.tracks[0].sampleCount -ge $miruHead.tracks[0].sampleCount) {
        throw 'Trimmed fixture did not actually remove samples from the twelve-second source.'
    }
    $miruEditPackets = @($miruEdited.tracks[0].samples)
    $miruRawPackets = @($miruRawEdited.tracks[0].samples)
    $miruShiftObserved = $miruEditPackets.Count -ne $miruRawPackets.Count
    if (-not $miruShiftObserved) {
        for ($miruPacket = 0; $miruPacket -lt $miruEditPackets.Count; $miruPacket++) {
            if ($miruEditPackets[$miruPacket].ptsUs -ne $miruRawPackets[$miruPacket].ptsUs) { $miruShiftObserved = $true; break }
        }
    }
    if (-not $miruShiftObserved) { throw 'Applying edits had no measured sample/PTS effect.' }
    $miruManifest = [ordered]@{
        schemaVersion = 1; generatedBy = 'tools/generate-cached-range-fixtures.ps1'
        status = 'generated-and-host-validated; Android BoxParser capability still unverified'
        ffmpegVersion = $miruFfmpegVersion; ffmpegSha256 = $miruFfmpegHash
        ffprobeVersion = $miruFfprobeVersion; ffprobeSha256 = $miruFfprobeHash
        commands = $miruCommands.ToArray()
        variableDensityChecks = $miruWindowChecks.ToArray()
        files = @($miruGeneratedNames | Where-Object { $_ -ne 'complex-manifest.json' } | ForEach-Object {
            $miruFile = Get-Item -LiteralPath (Join-Path $miruFixture $_)
            [ordered]@{ name = $_; bytes = $miruFile.Length; sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $miruFile.FullName).Hash.ToLowerInvariant() }
        })
    }
    $miruManifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $miruFixture 'complex-manifest.json') -Encoding utf8NoBOM
    Write-Host 'Fixture generation/host checks complete. Android public-API results remain unverified.'
} finally {
    # Even a failed generation preserves exact arguments; partial output is never
    # a successful fixture batch. No recursive cleanup of the output tree occurs.
    $miruCommands | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $miruEvidence 'complex-fixture-commands.json') -Encoding utf8NoBOM
    Pop-Location
}
