# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$miruRepository = Split-Path $PSScriptRoot -Parent
$miruId = [guid]::NewGuid().ToString('N')
$miruBuildName = "disk-cleanup-mock-build-$miruId"
$miruBuild = Join-Path $miruRepository "artifacts/m3-cached-ranges/$miruBuildName"
New-Item -ItemType Directory -Path $miruBuild -Force | Out-Null
$miruApk = Join-Path $miruBuild 'player-test.apk'
[IO.File]::WriteAllText($miruApk,'pinned mock APK bytes')
$miruHash = (Get-FileHash -LiteralPath $miruApk).Hash.ToLowerInvariant()
[ordered]@{success=$true;apks=@(@{name='player-test.apk';bytes=(Get-Item -LiteralPath $miruApk).Length;sha256=$miruHash})} |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $miruBuild 'result.json')
$miruMock = Join-Path $miruBuild 'mock-adb.ps1'
@'
$global:LASTEXITCODE = 0
switch($args[2]) {
    'install' { $env:MIRU_VALIDATION_MOCK_APK = $args[-1] }
    'pull' {
        $destination = $args[-1]
        [IO.File]::WriteAllText($env:MIRU_VALIDATION_MOCK_TRACE,$destination)
        if($env:MIRU_VALIDATION_MOCK_MODE -eq 'bad-pull') { [IO.File]::WriteAllText($destination,'wrong installed bytes') }
        else { [IO.File]::Copy($env:MIRU_VALIDATION_MOCK_APK,$destination) }
    }
    'shell' {
        switch($args[3]) {
            'pm' { 'package:/data/mock/base.apk' }
            'getprop' { 'mock-device' }
            'am' {
                'INSTRUMENTATION_STATUS_CODE: 0'
                if($env:MIRU_VALIDATION_MOCK_MODE -eq 'bad-count') { 'OK (2 tests)' }
                else { 'OK (1 test)' }
            }
        }
    }
}
'@ | Set-Content -LiteralPath $miruMock
$miruEnvironmentNames = @('MIRU_VALIDATION_MOCK_TRACE','MIRU_VALIDATION_MOCK_MODE','MIRU_VALIDATION_MOCK_APK')
$miruOriginalEnvironment = @{}
foreach($name in $miruEnvironmentNames) { $miruOriginalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
$miruChecks = @()
try {
    foreach($mode in @('success','bad-pull','bad-count')) {
        $runName = "disk-cleanup-mock-$mode-$miruId"
        $run = Join-Path $miruRepository "artifacts/m3-cached-ranges/$runName"
        $env:MIRU_VALIDATION_MOCK_MODE = $mode
        $env:MIRU_VALIDATION_MOCK_TRACE = Join-Path $miruBuild "pull-$mode.txt"
        $rejected = $false
        try {
            $null = & (Join-Path $PSScriptRoot 'run-device-validation.ps1') -BuildName $miruBuildName -RunName $runName -Selector 'fixture#oneTest' -Expected 1 -Adb $miruMock
        } catch {
            $rejected = $true
            if($mode -eq 'success') { throw }
            if($mode -eq 'bad-pull' -and $_.Exception.Message -notlike '*hash/size mismatch*') { throw }
            if($mode -eq 'bad-count' -and $_.Exception.Message -notlike '*Device regression failed*') { throw }
        }
        if($rejected -ne ($mode -ne 'success')) { throw "Unexpected rejection state for $mode" }
        $result = Get-Content -LiteralPath (Join-Path $run 'result.json') -Raw | ConvertFrom-Json
        if($result.success -ne ($mode -eq 'success')) { throw "Incorrect result success for $mode" }
        $pull = Get-Content -LiteralPath $env:MIRU_VALIDATION_MOCK_TRACE -Raw
        if((Test-Path -LiteralPath $pull) -or (Test-Path -LiteralPath (Split-Path $pull -Parent))) { throw "Temporary pull leaked after $mode" }
        if(@(Get-ChildItem -LiteralPath $run -File -Filter '*.apk').Count -ne 0) { throw "Run-local APK copy leaked after $mode" }
        if(@(Get-ChildItem -LiteralPath $miruBuild -File -Filter '.active-validation-*.json').Count -ne 0) { throw "Active lease leaked after $mode" }
        $miruChecks += "PASS: $mode verifies result, no APK copy, no temporary pull and no live lease left behind"
    }
} finally {
    foreach($name in $miruEnvironmentNames) { [Environment]::SetEnvironmentVariable($name,$miruOriginalEnvironment[$name],'Process') }
    $absolute = [IO.Path]::GetFullPath($miruApk)
    if(-not $absolute.StartsWith([IO.Path]::GetFullPath($miruBuild)+[IO.Path]::DirectorySeparatorChar)) { throw 'Mock cleanup escaped build directory' }
    Remove-Item -LiteralPath $absolute -Force
}
$miruChecks | Set-Content -LiteralPath (Join-Path $miruBuild 'tests.log')
$miruChecks
