# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Apk,
    [string]$OriginalManifest = (Join-Path $PSScriptRoot '../../artifacts/m1-blocklist/standalone-work/original-resource-sha256.tsv'),
    [string]$SnapshotDirectory = (Join-Path $PSScriptRoot '../../third_party/mozilla-blocklist/snapshot'),
    [string]$ExpectedOmniSha256 = '65becbcb9be64a1d50f84248e7a6d95531a15dfab25514dff631dcce3e358939',
    [Nullable[DateTimeOffset]]$MinimumApkWriteTimeUtc,
    [string]$SummaryLog = (Join-Path $PSScriptRoot '../../artifacts/m1-blocklist/verify-apk-summary.json')
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-StreamSha256([IO.Stream]$Stream) {
    $digest = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($digest.ComputeHash($Stream))).Replace('-', '').ToLowerInvariant() }
    finally { $digest.Dispose() }
}
function Assert-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Get-EntrySha256([IO.Compression.ZipArchiveEntry]$Entry) {
    $inputStream = $Entry.Open()
    try { return Get-StreamSha256 $inputStream }
    finally { $inputStream.Dispose() }
}

$summary = [ordered]@{
    status = 'FAILED'
    scope = 'APK packaging / original payload integrity; runtime consumption not tested'
    apk = [IO.Path]::GetFullPath($Apk)
    verifiedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    expectedOmniSha256 = $ExpectedOmniSha256.ToLowerInvariant()
    originalManifest = [IO.Path]::GetFullPath($OriginalManifest)
    snapshotDirectory = [IO.Path]::GetFullPath($SnapshotDirectory)
    originalsVerified = 0
    additionsVerified = 0
}
$outer = $null
$inner = $null
$omniBytes = $null
$failure = $null
try {
    Assert-Condition ($ExpectedOmniSha256 -match '^[a-fA-F0-9]{64}$') 'Expected omni digest must be a SHA-256 hex string.'
    $apkInfo = Get-Item -LiteralPath $summary.apk
    Assert-Condition (-not $apkInfo.PSIsContainer) 'APK path must be a file.'
    $summary.apkBytes = $apkInfo.Length
    $summary.apkLastWriteTimeUtc = $apkInfo.LastWriteTimeUtc.ToString('o')
    if ($null -ne $MinimumApkWriteTimeUtc) {
        $minimumUtc = ([DateTimeOffset]$MinimumApkWriteTimeUtc).UtcDateTime
        $summary.minimumApkWriteTimeUtc = $minimumUtc.ToString('o')
        Assert-Condition ($apkInfo.LastWriteTimeUtc -ge $minimumUtc) 'APK predates the requested build cutoff.'
    }
    $summary.apkSha256 = (Get-FileHash -LiteralPath $summary.apk -Algorithm SHA256).Hash.ToLowerInvariant()
    $summary.originalManifestSha256 = (Get-FileHash -LiteralPath $summary.originalManifest -Algorithm SHA256).Hash.ToLowerInvariant()
    $rows = @(Import-Csv -LiteralPath $summary.originalManifest -Delimiter "`t")
    Assert-Condition ($rows.Count -eq 4079) 'Original payload manifest must contain exactly 4079 records.'
    $originals = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    foreach ($row in $rows) {
        Assert-Condition (-not [string]::IsNullOrEmpty($row.path)) 'Original manifest contains an empty resource path.'
        Assert-Condition ($row.sha256 -cmatch '^[a-f0-9]{64}$') "Invalid original payload digest: $($row.path)"
        Assert-Condition ($row.bytes -match '^[0-9]+$') "Invalid original payload length: $($row.path)"
        Assert-Condition (-not $originals.ContainsKey($row.path)) "Duplicate manifest path: $($row.path)"
        $originals.Add($row.path, $row)
    }
    $pins = @(
        @{ path = 'blocklists/addons-bloomfilters.json'; bytes = 72903L; sha256 = '43dbbe38a70b3350ef8eaf5c2f2f7e300196b7986d12cc639b058eef966ef86a' },
        @{ path = 'blocklists/addons-bloomfilters/addons-mlbf.bin'; bytes = 938855L; sha256 = 'bf59d309000ab016e6fcfb558b2ba61ba1368ad4a4a2de424f9f5012a5e47b5a' },
        @{ path = 'blocklists/addons-bloomfilters/addons-mlbf.bin.meta.json'; bytes = 500L; sha256 = '81078ea0f4e5fd2ee36bd58c44849898d2a12e469ba494af30d22895ce216135' },
        @{ path = 'blocklists/addons-bloomfilters/softblocks-addons-mlbf.bin'; bytes = 218202L; sha256 = 'd778ccd02b613efb8c7ee0d4e4517dcac61b7e12a362dc600890c6c3065772cb' },
        @{ path = 'blocklists/addons-bloomfilters/softblocks-addons-mlbf.bin.meta.json'; bytes = 511L; sha256 = 'c715336c8f9ba1c8845b947cba80d4cef6a689d1d4f4408471287b98c6eca36a' }
    )
    $snapshotFiles = @(Get-ChildItem -LiteralPath $summary.snapshotDirectory -Recurse -File)
    Assert-Condition ($snapshotFiles.Count -eq 5) 'Snapshot directory must contain exactly the five pinned files.'
    $outer = [IO.Compression.ZipFile]::OpenRead($summary.apk)
    $omniEntries = @($outer.Entries | Where-Object { $_.FullName -ceq 'assets/omni.ja' })
    Assert-Condition ($omniEntries.Count -eq 1) 'APK must contain exactly one assets/omni.ja entry.'
    $omniBytes = [IO.MemoryStream]::new()
    $inputStream = $omniEntries[0].Open()
    try { $inputStream.CopyTo($omniBytes) }
    finally { $inputStream.Dispose() }
    $summary.omniBytes = $omniBytes.Length
    $omniBytes.Position = 0
    $summary.omniSha256 = Get-StreamSha256 $omniBytes
    Assert-Condition ($summary.omniSha256 -ceq $summary.expectedOmniSha256) 'Packaged omni digest differs from the expected generated archive.'
    $omniBytes.Position = 0
    # The generated overlay is an ordinary ZIP. The original optimized archive
    # was read/audited by Java in run-tests.ps1; this script consumes its manifest.
    $inner = [IO.Compression.ZipArchive]::new($omniBytes, [IO.Compression.ZipArchiveMode]::Read, $true)
    $entries = [Collections.Generic.Dictionary[string, IO.Compression.ZipArchiveEntry]]::new([StringComparer]::Ordinal)
    foreach ($entry in $inner.Entries) {
        Assert-Condition (-not $entries.ContainsKey($entry.FullName)) "Duplicate packaged resource: $($entry.FullName)"
        $entries.Add($entry.FullName, $entry)
    }
    $summary.omniEntryCount = $entries.Count
    Assert-Condition ($entries.Count -eq 4084) 'Packaged omni must contain exactly 4084 resources.'
    foreach ($name in $originals.Keys) {
        Assert-Condition ($entries.ContainsKey($name)) "Missing original Gecko resource: $name"
        $entry = $entries[$name]
        $row = $originals[$name]
        Assert-Condition ($entry.Length -eq [long]$row.bytes) "Changed original resource length: $name"
        Assert-Condition ((Get-EntrySha256 $entry) -ceq $row.sha256) "Changed original resource payload: $name"
        $summary.originalsVerified++
    }
    Assert-Condition ($originals.ContainsKey('defaults/settings/last_modified.json')) 'Manifest omits the original last_modified index.'
    $additionNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $summary.additions = @()
    foreach ($pin in $pins) {
        $name = 'defaults/settings/' + $pin.path
        Assert-Condition (-not $originals.ContainsKey($name)) "Addition would replace an original resource: $name"
        Assert-Condition ($entries.ContainsKey($name)) "Missing official snapshot resource: $name"
        $snapshotPath = Join-Path $summary.snapshotDirectory $pin.path
        $snapshot = Get-Item -LiteralPath $snapshotPath
        Assert-Condition ($snapshot.Length -eq $pin.bytes) "Pinned snapshot file length changed: $($pin.path)"
        $rawHash = (Get-FileHash -LiteralPath $snapshotPath -Algorithm SHA256).Hash.ToLowerInvariant()
        Assert-Condition ($rawHash -ceq $pin.sha256) "Pinned snapshot file digest changed: $($pin.path)"
        Assert-Condition ($entries[$name].Length -eq $pin.bytes) "Packaged snapshot length differs: $name"
        Assert-Condition ((Get-EntrySha256 $entries[$name]) -ceq $rawHash) "Packaged snapshot payload differs: $name"
        [void]$additionNames.Add($name)
        $summary.additions += [ordered]@{ path = $name; bytes = $pin.bytes; sha256 = $rawHash }
        $summary.additionsVerified++
    }
    foreach ($name in $entries.Keys) {
        Assert-Condition ($originals.ContainsKey($name) -or $additionNames.Contains($name)) "Unexpected packaged resource: $name"
    }
    $after = Get-Item -LiteralPath $summary.apk
    Assert-Condition ($after.Length -eq $apkInfo.Length -and $after.LastWriteTimeUtc -eq $apkInfo.LastWriteTimeUtc) 'APK changed while verification was running.'
    $summary.lastModifiedIndexUnchanged = $true
    $summary.status = 'PASSED'
} catch {
    $failure = $_
    $summary.error = $_.Exception.Message
} finally {
    if ($null -ne $inner) { $inner.Dispose() }
    if ($null -ne $omniBytes) { $omniBytes.Dispose() }
    if ($null -ne $outer) { $outer.Dispose() }
    $logPath = [IO.Path]::GetFullPath($SummaryLog)
    New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($logPath)) | Out-Null
    $summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $logPath -Encoding utf8
}
$summary | ConvertTo-Json -Depth 6
if ($null -ne $failure) { throw $failure }
