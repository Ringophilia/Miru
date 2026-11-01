# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$miruRepository = Split-Path $PSScriptRoot -Parent
$miruCleaner = Join-Path $PSScriptRoot 'clean-validation-artifacts.ps1'
$miruWork = Join-Path $miruRepository ('artifacts/disk-cleanup/test-' + [guid]::NewGuid().ToString('N'))
$miruCase = Join-Path $miruWork 'case'
New-Item -ItemType Directory -Path $miruCase -Force | Out-Null
$miruChecks = [Collections.Generic.List[string]]::new()
function Confirm-Miru([bool]$Condition, [string]$Label) {
    if(-not $Condition) { throw "Failed: $Label" }
    $miruChecks.Add($Label)
    Write-Host "PASS: $Label"
}
function New-MiruFile([string]$Relative, [string]$Content='fixture') {
    $path = Join-Path $miruCase $Relative
    New-Item -ItemType Directory -Path (Split-Path $path -Parent) -Force | Out-Null
    [IO.File]::WriteAllText($path,$Content)
    return $path
}
foreach($number in 1..4) {
    $dir = "m3-cached-ranges/build-$number"
    foreach($name in @('main.apk','player-test.apk','app-test.apk')) { $null=New-MiruFile "$dir/$name" }
    $manifest = New-MiruFile "$dir/result.json" ([ordered]@{success=($number -ne 4);playerJvm=@{tests=1};apks=@(@{name='main.apk'},@{name='player-test.apk'},@{name='app-test.apk'})} | ConvertTo-Json -Depth 5)
    (Get-Item -LiteralPath $manifest).LastWriteTimeUtc = [datetime]::UtcNow.AddHours(-10+$number)
}
$live = New-MiruFile 'm3-cached-ranges/live/main.apk'
$null = New-MiruFile 'm3-cached-ranges/live/.active-validation-test.json' (@{pid=$PID;processStartTicks=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks} | ConvertTo-Json)
$stale = New-MiruFile 'm3-cached-ranges/stale/main.apk'
$null = New-MiruFile 'm3-cached-ranges/stale/.active-validation-test.json' (@{pid=$PID;processStartTicks=0} | ConvertTo-Json)
foreach($version in @('1.0.1','1.0.2','1.0.3')) { $null=New-MiruFile "releases/Miru-$version-arm64.apk" }
foreach($version in @('1.0.2','1.0.3')) {
    $null=New-MiruFile "stock-$version/stock-arm64-release.apk"
    $null=New-MiruFile "stock-$version/stock-input.json" (@{version="$version/code1"} | ConvertTo-Json)
}
$pinned = New-MiruFile 'investigation/main.apk'
$null = New-MiruFile 'scratch/aligned-unsigned.apk'
$report = New-MiruFile 'logs/report.json' '{"tests":1}'
$thirdParty = New-MiruFile 'dependencies/third-party.apk'
try {
$stream=[IO.File]::OpenWrite($thirdParty)
try { $stream.SetLength(21000000) } finally { $stream.Dispose() }
$dry = & $miruCleaner -ArtifactRoot $miruCase -Keep 'investigation/main.apk'
Confirm-Miru ($dry.status -eq 'PLANNED' -and (Test-Path -LiteralPath $stale)) 'dry run leaves deletion candidates intact'
$outsideRejected=$false
try { $null=& $miruCleaner -ArtifactRoot $miruRepository -Apply } catch { $outsideRejected=$_.Exception.Message -like '*must stay inside*' }
Confirm-Miru $outsideRejected 'target outside repository artifacts is rejected'
$target = Join-Path $miruWork 'junction-target'
New-Item -ItemType Directory -Path $target | Out-Null
[IO.File]::WriteAllText((Join-Path $target 'main.apk'),'outside case sentinel')
$link = Join-Path $miruWork 'junction-root'
New-Item -ItemType Junction -Path $link -Target $target | Out-Null
try {
    $linkRejected=$false
    try { $null=& $miruCleaner -ArtifactRoot $link -Apply } catch { $linkRejected=$_.Exception.Message -like '*reparse*' -or $_.Exception.Message -like '*Unsafe cleanup root*' }
    Confirm-Miru ($linkRejected -and (Test-Path -LiteralPath (Join-Path $target 'main.apk'))) 'junction cleanup root is rejected without deleting its target'
    New-Item -ItemType Directory -Path (Join-Path $target 'child') | Out-Null
    $ancestorRejected=$false
    try { $null=& $miruCleaner -ArtifactRoot (Join-Path $link 'child') -Apply } catch { $ancestorRejected=$_.Exception.Message -like '*reparse*' }
    Confirm-Miru $ancestorRejected 'cleanup root beneath a junction ancestor is rejected'
} finally { Remove-Item -LiteralPath $link -Force }
$applied = & $miruCleaner -ArtifactRoot $miruCase -Keep 'investigation/main.apk' -Apply
Confirm-Miru ($applied.status -eq 'COMPLETED' -and $applied.removedFiles -eq 10) 'apply removes only the ten planned generated files'
Confirm-Miru (-not (Test-Path -LiteralPath (Join-Path $miruCase 'm3-cached-ranges/build-1/main.apk'))) 'superseded successful build is pruned'
Confirm-Miru (-not (Test-Path -LiteralPath (Join-Path $miruCase 'm3-cached-ranges/build-4/main.apk'))) 'failed build never displaces a successful snapshot'
Confirm-Miru ((Test-Path -LiteralPath (Join-Path $miruCase 'm3-cached-ranges/build-2/main.apk')) -and (Test-Path -LiteralPath (Join-Path $miruCase 'm3-cached-ranges/build-3/main.apk'))) 'two newest successful builds are retained'
Confirm-Miru ((Test-Path -LiteralPath $live) -and -not (Test-Path -LiteralPath $stale)) 'live process/start-time lease protects input; stale lease does not'
Confirm-Miru ((Test-Path -LiteralPath (Join-Path $miruCase 'releases/Miru-1.0.2-arm64.apk')) -and (Test-Path -LiteralPath (Join-Path $miruCase 'releases/Miru-1.0.3-arm64.apk')) -and -not (Test-Path -LiteralPath (Join-Path $miruCase 'releases/Miru-1.0.1-arm64.apk'))) 'release retention uses version order'
Confirm-Miru ((Test-Path -LiteralPath (Join-Path $miruCase 'stock-1.0.3/stock-arm64-release.apk')) -and -not (Test-Path -LiteralPath (Join-Path $miruCase 'stock-1.0.2/stock-arm64-release.apk'))) 'only current release stock input is retained'
Confirm-Miru ((Test-Path -LiteralPath $thirdParty) -and (Test-Path -LiteralPath $pinned) -and (Test-Path -LiteralPath $report)) 'large third party input, explicit keep and reports survive'
$second = & $miruCleaner -ArtifactRoot $miruCase -Keep 'investigation/main.apk' -Apply
Confirm-Miru ($second.removedFiles -eq 0) 'second cleanup is idempotent'
$ledger = Get-Content -LiteralPath $applied.report -Raw | ConvertFrom-Json
Confirm-Miru ($ledger.files.Count -eq 10 -and $ledger.removedBytes -gt 0) 'deletion ledger records exact paths and removed bytes'
} finally {
# Do not leave the intentionally large test dependency behind.
$absolute = [IO.Path]::GetFullPath($thirdParty)
if(-not $absolute.StartsWith([IO.Path]::GetFullPath($miruWork)+[IO.Path]::DirectorySeparatorChar)) { throw 'Test cleanup escaped its private workspace' }
Remove-Item -LiteralPath $absolute -Force
}
[ordered]@{success=$true;checks=@($miruChecks);count=$miruChecks.Count} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $miruWork 'tests.json')
Write-Output "PASS: $($miruChecks.Count) artifact retention safety checks; evidence: $miruWork"
