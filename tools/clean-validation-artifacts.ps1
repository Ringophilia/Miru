# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
#requires -Version 7.0
param(
    [string]$ArtifactRoot,
    [ValidateRange(1,10)][int]$KeepBuilds = 2,
    [ValidateRange(1,10)][int]$KeepReleases = 2,
    [string[]]$Keep = @(),
    [switch]$Apply
)
$ErrorActionPreference = 'Stop'
$miruRepository = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$miruAllowed = Join-Path $miruRepository 'artifacts'
if(-not $ArtifactRoot) { $ArtifactRoot = $miruAllowed }
$miruRoot = [IO.Path]::GetFullPath($ArtifactRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)
if($miruRoot -ine $miruAllowed -and -not $miruRoot.StartsWith($miruAllowed + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Cleanup target must stay inside this repository artifacts directory' }
if(-not (Test-Path -LiteralPath $miruRoot -PathType Container)) { throw 'Artifact root not found' }
$miruAncestor = Get-Item -LiteralPath $miruRoot -Force
while($miruAncestor) {
    if($miruAncestor.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing reparse cleanup root or ancestor' }
    if($miruAncestor.FullName -ieq $miruAllowed) { break }
    $miruAncestor = $miruAncestor.Parent
}
if(-not $miruAncestor) { throw 'Cleanup root escaped repository artifacts' }
function Assert-MiruArtifactPath([string]$Path) {
    $absolute = [IO.Path]::GetFullPath($Path)
    if(-not $absolute.StartsWith($miruRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw "Path escaped cleanup root: $absolute" }
    $item = Get-Item -LiteralPath $absolute -Force
    while($item -and $item.FullName -ine $miruRoot) {
        if($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Refusing reparse path: $absolute" }
        $item = if($item -is [IO.DirectoryInfo]) { $item.Parent } else { $item.Directory }
    }
    if(-not $item -or $item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Unsafe cleanup root' }
    return $absolute
}
$miruKeep = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach($relative in $Keep) { $null = $miruKeep.Add((Assert-MiruArtifactPath (Join-Path $miruRoot $relative))) }
$miruSnapshots = @()
$miruRuns = Join-Path $miruRoot 'm3-cached-ranges'
if(Test-Path -LiteralPath $miruRuns) {
    foreach($directory in Get-ChildItem -LiteralPath $miruRuns -Directory) {
        $result = Join-Path $directory.FullName 'result.json'
        if(-not (Test-Path -LiteralPath $result)) { continue }
        try {
            $data = Get-Content -LiteralPath $result -Raw | ConvertFrom-Json
            if($data.success -and ($data.playerJvm -or $data.jvm) -and $data.apks -and (Test-Path -LiteralPath (Join-Path $directory.FullName 'main.apk'))) {
                $miruSnapshots += [pscustomobject]@{directory=$directory.FullName;time=(Get-Item -LiteralPath $result).LastWriteTimeUtc;apks=$data.apks}
            }
        } catch { Write-Warning "Ignoring unreadable build manifest: $result" }
    }
}
foreach($snapshot in $miruSnapshots | Sort-Object time -Descending | Select-Object -First $KeepBuilds) {
    foreach($apk in $snapshot.apks) {
        $path = Join-Path $snapshot.directory $apk.name
        if(Test-Path -LiteralPath $path) { $null = $miruKeep.Add((Assert-MiruArtifactPath $path)) }
    }
}
# A lock file is only a hint: verify both the live process and its start time.
function Test-MiruLeaseOwner([int]$LeasePid,[long]$LeaseTicks) {
    if($LeasePid -le 0 -or $LeaseTicks -lt 0 -or $LeaseTicks -gt [datetime]::MaxValue.Ticks) { throw 'Invalid lease process identity' }
    $process=Get-Process -Id $LeasePid -ErrorAction SilentlyContinue
    if(-not $process) { return $false }
    try { return $process.StartTime.ToUniversalTime().Ticks -eq $LeaseTicks }
    catch {
        # A process may exit after Get-Process, or a reused PID may now belong
        # to an inaccessible process. CIM may prove staleness, never ownership:
        # a possibly matching but unreadable start time still fails closed.
        if($IsWindows) {
            $identity=Get-CimInstance -ClassName Win32_Process -Filter "ProcessId=$LeasePid" -ErrorAction Stop
            if(-not $identity) { return $false }
            if($identity.CreationDate -and [math]::Abs($identity.CreationDate.ToUniversalTime().Ticks-$LeaseTicks) -ge [timespan]::TicksPerMillisecond) { return $false }
        } else {
            if(-not (Get-Process -Id $LeasePid -ErrorAction SilentlyContinue)) { return $false }
        }
        throw
    }
}
foreach($lease in Get-ChildItem -LiteralPath $miruRoot -Recurse -File -Filter '.active-validation-*.json') {
    try {
        $data = Get-Content -LiteralPath $lease.FullName -Raw | ConvertFrom-Json
        if(Test-MiruLeaseOwner ([int]$data.pid) ([long]$data.processStartTicks)) {
            foreach($apk in Get-ChildItem -LiteralPath $lease.DirectoryName -File -Filter '*.apk') { $null = $miruKeep.Add((Assert-MiruArtifactPath $apk.FullName)) }
        }
    } catch { throw "Cannot safely verify active validation lease: $($lease.FullName)" }
}
$miruReleaseRoot = Join-Path $miruRoot 'releases'
$miruVersions = @()
if(Test-Path -LiteralPath $miruReleaseRoot) {
    $miruVersions = @(Get-ChildItem -LiteralPath $miruReleaseRoot -File -Filter '*.apk' | Where-Object Name -Match '^Miru-(\d+\.\d+\.\d+)-arm64\.apk$' |
        ForEach-Object { [pscustomobject]@{file=$_;version=[version]([regex]::Match($_.Name,'\d+\.\d+\.\d+').Value)} } | Sort-Object version -Descending | Select-Object -First $KeepReleases)
    foreach($release in $miruVersions) { $null = $miruKeep.Add((Assert-MiruArtifactPath $release.file.FullName)) }
}
# Keep the latest final package's actual R8 input, but not unsigned/recompression scratch.
if($miruVersions.Count -gt 0) {
    $latest = $miruVersions[0].version.ToString()
    foreach($manifest in Get-ChildItem -LiteralPath $miruRoot -Recurse -File -Filter 'stock-input.json') {
        try {
            $data = Get-Content -LiteralPath $manifest.FullName -Raw | ConvertFrom-Json
            $stock = Join-Path $manifest.DirectoryName 'stock-arm64-release.apk'
            if($data.version -like "$latest/*" -and (Test-Path -LiteralPath $stock)) { $null = $miruKeep.Add((Assert-MiruArtifactPath $stock)) }
        } catch { Write-Warning "Unreadable stock manifest: $($manifest.FullName)" }
    }
}
$miruTracked = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach($relative in (& git -C $miruRepository -c core.quotepath=false ls-files --cached -- artifacts)) { $null = $miruTracked.Add([IO.Path]::GetFullPath((Join-Path $miruRepository $relative))) }
if($LASTEXITCODE -ne 0) { throw 'Could not establish tracked-file protection' }
$miruCandidates = @()
foreach($file in Get-ChildItem -LiteralPath $miruRoot -Recurse -File -Filter '*.apk') {
    # Tiny synthetic fixtures and separately downloaded test apps are inputs, not build snapshots.
    $generated = $file.Name -match '^(main|installed(?:-main|-player-test|-app-test)?|player-test|app-test|debug|androidTest.*|stock-arm64-release|arm64-release-stock|release-stock|reused-unsigned|aligned-unsigned|candidate-signed)\.apk$' -or $file.Name -like 'Miru-*.apk'
    if(-not $generated -or $miruKeep.Contains($file.FullName) -or $miruTracked.Contains($file.FullName)) { continue }
    $absolute = Assert-MiruArtifactPath $file.FullName
    $miruCandidates += [ordered]@{path=[IO.Path]::GetRelativePath($miruRoot,$absolute);bytes=$file.Length;lastWriteUtc=$file.LastWriteTimeUtc.ToString('o');reason='Superseded generated APK/scratch; original logs, hashes, source manifests and screenshots retained'}
}
$miruEvidence = Join-Path $miruRoot 'disk-cleanup'
New-Item -ItemType Directory -Path $miruEvidence -Force | Out-Null
$miruReport = Join-Path $miruEvidence ('cleanup-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.json')
$miruPlannedBytes = 0L
foreach($entry in $miruCandidates) { $miruPlannedBytes += [long]$entry.bytes }
$miruPlan = [ordered]@{root=$miruRoot;apply=[bool]$Apply;keepBuilds=$KeepBuilds;keepReleases=$KeepReleases;kept=@($miruKeep);plannedBytes=$miruPlannedBytes;files=$miruCandidates;removedBytes=0L;removedFiles=0;status='PLANNED'}
$miruPlan | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $miruReport -Encoding utf8
if($Apply) {
    try {
        foreach($entry in $miruCandidates) {
            $absolute = Assert-MiruArtifactPath (Join-Path $miruRoot $entry.path)
            if((Get-Item -LiteralPath $absolute).Length -ne $entry.bytes) { throw "File changed after plan: $absolute" }
            Remove-Item -LiteralPath $absolute -Force
            $miruPlan.removedBytes += [long]$entry.bytes; $miruPlan.removedFiles++
        }
        $miruPlan.status = 'COMPLETED'
    } catch { $miruPlan.status = 'FAILED'; $miruPlan['error'] = $_.Exception.Message; throw }
    finally { $miruPlan | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $miruReport -Encoding utf8 }
}
$miruRemainingBytes = 0L
foreach($file in Get-ChildItem -LiteralPath $miruRoot -Recurse -File) { $miruRemainingBytes += $file.Length }
if($miruRemainingBytes -gt 3000000000L) { Write-Warning 'Artifacts exceed the 3 GB retention target; inspect large inputs/logs before generating more binary copies.' }
[pscustomobject]@{status=$miruPlan.status;plannedFiles=$miruCandidates.Count;plannedGB=[math]::Round($miruPlan.plannedBytes/1e9,3);removedFiles=$miruPlan.removedFiles;removedGB=[math]::Round($miruPlan.removedBytes/1e9,3);remainingGB=[math]::Round($miruRemainingBytes/1e9,3);report=$miruReport;keptApks=$miruKeep.Count}
