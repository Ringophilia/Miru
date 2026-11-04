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
if($env:MIRU_VALIDATION_MOCK_CALLS) { [IO.File]::AppendAllText($env:MIRU_VALIDATION_MOCK_CALLS, ($args -join ' ') + [Environment]::NewLine) }
switch($args[2]) {
    'logcat' { if($env:MIRU_VALIDATION_MOCK_MODE -eq 'transport-lost') { throw 'Must not query logcat after transport loss' } }
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
                if($env:MIRU_VALIDATION_MOCK_MODE -eq 'transport-lost') { $global:LASTEXITCODE=255 }
                elseif($env:MIRU_VALIDATION_MOCK_MODE -eq 'bad-count') { 'OK (2 tests)' }
                else { 'OK (1 test)' }
            }
        }
    }
}
'@ | Set-Content -LiteralPath $miruMock
$miruEnvironmentNames = @('MIRU_VALIDATION_MOCK_TRACE','MIRU_VALIDATION_MOCK_MODE','MIRU_VALIDATION_MOCK_APK','MIRU_VALIDATION_MOCK_CALLS')
$miruOriginalEnvironment = @{}
foreach($name in $miruEnvironmentNames) { $miruOriginalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
$miruChecks = @()
try {
    foreach($mode in @('success','success-user','bad-pull','bad-count','transport-lost')) {
        $succeeds = $mode -like 'success*'
        $runName = "disk-cleanup-mock-$mode-$miruId"
        $run = Join-Path $miruRepository "artifacts/m3-cached-ranges/$runName"
        $env:MIRU_VALIDATION_MOCK_MODE = $mode
        $env:MIRU_VALIDATION_MOCK_TRACE = Join-Path $miruBuild "pull-$mode.txt"
        $env:MIRU_VALIDATION_MOCK_CALLS = Join-Path $miruBuild "calls-$mode.txt"
        $userArguments = if($mode -eq 'success-user') { @{UserId=10;InstrumentationArguments=@{'miru.requireNoNetwork'='true'}} } else { @{} }
        $rejected = $false
        try {
            $null = & (Join-Path $PSScriptRoot 'run-device-validation.ps1') -BuildName $miruBuildName -RunName $runName -Selector 'fixture#oneTest' -Expected 1 -Adb $miruMock @userArguments
        } catch {
            $rejected = $true
            if($succeeds) { throw }
            if($mode -eq 'bad-pull' -and $_.Exception.Message -notlike '*hash/size mismatch*') { throw }
            if($mode -eq 'bad-count' -and $_.Exception.Message -notlike '*Device regression failed*') { throw }
            if($mode -eq 'transport-lost' -and $_.Exception.Message -notlike '*Device regression failed*') { throw }
        }
        if($rejected -ne (-not $succeeds)) { throw "Unexpected rejection state for $mode" }
        $result = Get-Content -LiteralPath (Join-Path $run 'result.json') -Raw | ConvertFrom-Json
        if($result.success -ne $succeeds) { throw "Incorrect result success for $mode" }
        if($mode -eq 'success-user') {
            $runInput = Get-Content -LiteralPath (Join-Path $run 'input.json') -Raw | ConvertFrom-Json
            $calls = Get-Content -LiteralPath $env:MIRU_VALIDATION_MOCK_CALLS -Raw
            if($runInput.userId -ne 10 -or -not $calls.Contains('install -r --user 10') -or
                -not $calls.Contains('shell pm path --user 10') -or -not $calls.Contains('shell am instrument -w -r --user 10') -or
                -not $calls.Contains('-e miru.requireNoNetwork true')) {
                throw 'Explicit user must be forwarded to installation, installed-path verification and instrumentation'
            }
        }
        if($mode -eq 'transport-lost' -and ($result.adbExit -ne 255 -or $result.terminalOK -or
            (Get-Content -LiteralPath (Join-Path $run 'logcat.log') -Raw) -notlike 'Skipped post-run logcat*')) { throw 'Transport failure was not preserved without another logcat query' }
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
