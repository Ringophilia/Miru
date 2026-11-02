# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')][string]$RunName,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')][string]$BuildName,
    [string]$Selector,
    [Parameter(Mandatory)][ValidateRange(1,100000)][int]$Expected,
    [switch]$App,
    [switch]$Routine,
    [hashtable]$InstrumentationArguments = @{},
    [string]$Device = 'emulator-5554',
    [string]$Adb
)
$ErrorActionPreference = 'Stop'
if(-not $Routine -and [string]::IsNullOrWhiteSpace($Selector)) { throw 'Selector is required unless Routine is set' }
$miruRepository = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$miruArtifacts = Join-Path $miruRepository 'artifacts/m3-cached-ranges'
$miruRun = Join-Path $miruArtifacts $RunName
$miruBuild = Join-Path $miruArtifacts $BuildName
if(Test-Path -LiteralPath $miruRun) { throw 'Use a fresh RunName; existing validation evidence cannot be overwritten' }
if(-not $Adb) {
    $sdk = if($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
    if($sdk) { $Adb = Join-Path $sdk 'platform-tools/adb.exe' } else { $Adb = (Get-Command adb -ErrorAction Stop).Source }
}
if(-not (Test-Path -LiteralPath $Adb -PathType Leaf)) { throw 'ADB executable not found; pass -Adb or set ANDROID_HOME' }
$miruBuildResult = Get-Content -LiteralPath (Join-Path $miruBuild 'result.json') -Raw | ConvertFrom-Json
if(-not $miruBuildResult.success) { throw 'Refusing artifacts from an unsuccessful build' }
New-Item -ItemType Directory -Path $miruRun | Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $miruRun 'runner.ps1')
$miruProcess = Get-Process -Id $PID
$miruLease = Join-Path $miruBuild ('.active-validation-' + $PID + '-' + [guid]::NewGuid().ToString('N') + '.json')
[ordered]@{pid=$PID;processStartTicks=$miruProcess.StartTime.ToUniversalTime().Ticks;run=$miruRun} |
    ConvertTo-Json | Set-Content -LiteralPath $miruLease -Encoding utf8
$miruTestPackage = if($App) { 'app.miru.browser.test' } else { 'app.miru.player.test' }
$miruNames = if($App) { @('main.apk','app-test.apk') } else { @('player-test.apk') }
$miruApks = @()
$miruStarted = Get-Date
$miruResult = [ordered]@{success=$false;startedAt=$miruStarted.ToString('o')}
$miruPullDirectory = $null
try {
    # Only one temporary pull directory, with a guaranteed finally cleanup. No run-local APK copies.
    $miruPullDirectory = Join-Path ([IO.Path]::GetTempPath()) ('miru-apk-verify-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $miruPullDirectory | Out-Null
    foreach($name in $miruNames) {
        $apk = Join-Path $miruBuild $name
        $pin = @($miruBuildResult.apks | Where-Object name -EQ $name)
        if($pin.Count -ne 1 -or $pin[0].sha256 -notmatch '^[a-fA-F0-9]{64}$') { throw "Missing unique build pin: $name" }
        $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
        $bytes = (Get-Item -LiteralPath $apk).Length
        if($hash -ine $pin[0].sha256 -or $bytes -ne $pin[0].bytes) { throw "Build APK changed: $name" }
        $record = [ordered]@{name=$name;path=$apk;sha256=$hash;bytes=$bytes;installedVerified=$false}
        $miruApks += $record
        & $Adb -s $Device install -r $apk *> (Join-Path $miruRun "install-$name.log")
        if($LASTEXITCODE -ne 0) { throw "Installation failed: $name" }
        $package = if($name -eq 'main.apk') { 'app.miru.browser' } else { $miruTestPackage }
        $paths = @(& $Adb -s $Device shell pm path $package)
        if($LASTEXITCODE -ne 0 -or $paths.Count -ne 1 -or $paths[0] -notmatch '^package:/') { throw "Expected one installed base APK: $package" }
        $pulled = Join-Path $miruPullDirectory $name
        try {
            & $Adb -s $Device pull ($paths[0] -replace '^package:','').Trim() $pulled *> (Join-Path $miruRun "pull-$name.log")
            if($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $pulled)) { throw "Installed APK pull failed: $name" }
            $record['installedSha256'] = (Get-FileHash -LiteralPath $pulled -Algorithm SHA256).Hash.ToLowerInvariant()
            $record['installedBytes'] = (Get-Item -LiteralPath $pulled).Length
            if($record.installedSha256 -cne $hash -or $record.installedBytes -ne $bytes) { throw "Installed APK hash/size mismatch: $name" }
            $record.installedVerified = $true
        } finally {
            if(Test-Path -LiteralPath $pulled) { Remove-Item -LiteralPath $pulled -Force }
        }
    }
    $instrumentArgs = @('-s',$Device,'shell','am','instrument','-w','-r')
    if($Routine) {
        $excluded = 'app.miru.browser.DownloadRestartTest,app.miru.browser.OfflineRestartTest,app.miru.browser.RuntimeSitePermissionIntegrationTest,app.miru.browser.AudioCaptureIntegrationTest'
        $instrumentArgs += @('-e','notClass',$excluded,'-e','notPackage','app.miru.browser.capability')
    } else { $instrumentArgs += @('-e','class',$Selector) }
    foreach($key in $InstrumentationArguments.Keys | Sort-Object) {
        if($key -notmatch '^[A-Za-z][A-Za-z0-9_]*$' -or $key -in @('class','notClass','notPackage')) { throw "Invalid additional instrumentation key: $key" }
        $instrumentArgs += @('-e',[string]$key,[string]$InstrumentationArguments[$key])
    }
    $instrumentArgs += "$miruTestPackage/androidx.test.runner.AndroidJUnitRunner"
    [ordered]@{
        startedAt=$miruStarted.ToString('o');device=$Device
        sdk=(& $Adb -s $Device shell getprop ro.build.version.sdk)
        abi=(& $Adb -s $Device shell getprop ro.product.cpu.abi)
        apks=$miruApks;command=@($Adb)+$instrumentArgs;expectedTests=$Expected
        scope='Selected controlled regression only; no whole-suite or complete P11 acceptance claim'
        sourceBuild=$miruBuildResult;temporaryPullsRetained=$false
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $miruRun 'input.json') -Encoding utf8
    $started = Get-Date
    & $Adb @instrumentArgs *> (Join-Path $miruRun 'runner.log')
    $exitCode = $LASTEXITCODE
    $raw = Get-Content -LiteralPath (Join-Path $miruRun 'runner.log') -Raw
    $codes = @([regex]::Matches($raw, 'INSTRUMENTATION_STATUS_CODE: (-?\d+)') | ForEach-Object { [int]$_.Groups[1].Value })
    $passed = @($codes | Where-Object { $_ -eq 0 }).Count
    $bad = @($codes | Where-Object { $_ -lt 0 })
    $ok = $raw -match "(?m)^OK \($Expected tests?\)\s*$"
    if($exitCode -eq 0) {
        & $Adb -s $Device logcat -d -v threadtime -t 6000 *> (Join-Path $miruRun 'logcat.log')
    } else {
        "Skipped post-run logcat after instrumentation transport exit $exitCode; retain any live capture separately." |
            Set-Content -LiteralPath (Join-Path $miruRun 'logcat.log')
    }
    $miruResult = [ordered]@{finishedAt=(Get-Date -Format o);elapsedSeconds=((Get-Date)-$started).TotalSeconds;adbExit=$exitCode;passed=$passed;nonPassCodes=$bad;terminalOK=$ok;success=($exitCode -eq 0 -and $passed -eq $Expected -and $bad.Count -eq 0 -and $ok)}
    if(-not $miruResult.success) { throw 'Device regression failed; see retained runner.log and logcat.log' }
} catch {
    $miruResult['success'] = $false
    $miruResult['error'] = $_.Exception.Message
    throw
} finally {
    # This exact private path is created above; remove only its known files, never recurse.
    if($miruPullDirectory -and (Test-Path -LiteralPath $miruPullDirectory)) {
        foreach($name in $miruNames) {
            $temporary = Join-Path $miruPullDirectory $name
            if(Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
        }
        Remove-Item -LiteralPath $miruPullDirectory -Force
    }
    Remove-Item -LiteralPath $miruLease -Force
    $miruResult['apks'] = $miruApks
    $miruResult['temporaryPullsRetained'] = $false
    $miruResult | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $miruRun 'result.json') -Encoding utf8
}
[pscustomobject]$miruResult
