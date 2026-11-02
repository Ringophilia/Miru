# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
#requires -Version 7.0
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')][string]$RunName,
    [string]$Device='emulator-5554',
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*\.json$')][string]$HarnessManifest,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')][string]$BuildDirectory,
    [string]$Adb
)
$ErrorActionPreference='Stop'
if($RunName -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') { throw 'Invalid run name' }
$repository=Split-Path $PSScriptRoot -Parent
$evidence=Join-Path $repository 'artifacts/r8-device-smoke'
$run=Join-Path $evidence $RunName
if(Test-Path -LiteralPath $run) { throw 'Run evidence already exists' }
New-Item -ItemType Directory -Path $run | Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $run 'runner.ps1')
if(-not $Adb) {
    if($env:ANDROID_HOME) { $Adb=Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe' }
    else { $Adb=(Get-Command adb -ErrorAction Stop).Source }
}
$comparison=Get-Content -LiteralPath (Join-Path $evidence "$BuildDirectory/arm64-dex-comparison.json") -Raw | ConvertFrom-Json
$harness=Get-Content -LiteralPath (Join-Path $evidence $HarnessManifest) -Raw | ConvertFrom-Json
if($comparison.status -ne 'PASSED') { throw 'Exact R8 DEX/ABI comparison failed' }
if($harness.noRuntimeDependencies -ne $true) { throw 'A release probe must have no runtime dependencies that can override optimized target classes' }
$lease=Join-Path $evidence ($BuildDirectory+'/.active-validation-'+$PID+'-runtime.json')
[ordered]@{pid=$PID;processStartTicks=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks} | ConvertTo-Json | Set-Content -LiteralPath $lease
$temporary=Join-Path ([IO.Path]::GetTempPath()) ('miru-r8-apk-verify-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary | Out-Null
$records=@();$result=[ordered]@{success=$false};$started=Get-Date
try {
    foreach($entry in @(
        @{name='main.apk';path=$comparison.x86.path;sha256=$comparison.x86.sha256;bytes=$comparison.x86.bytes;package='app.miru.browser'},
        @{name='harness.apk';path=$harness.apk;sha256=$harness.sha256;bytes=$harness.bytes;package='app.miru.releaseprobe'}
    )) {
        if((Get-FileHash -LiteralPath $entry.path).Hash.ToLowerInvariant() -cne $entry.sha256 -or (Get-Item -LiteralPath $entry.path).Length -ne $entry.bytes) { throw 'Runtime input pin changed' }
        & $adb -s $Device install -r $entry.path *> (Join-Path $run ('install-'+$entry.name+'.log'))
        if($LASTEXITCODE -ne 0) { throw 'Runtime installation failed' }
        $remote=@(& $adb -s $Device shell pm path $entry.package)
        if($LASTEXITCODE -ne 0 -or $remote.Count -ne 1 -or $remote[0] -notmatch '^package:/') { throw 'Expected one installed base APK' }
        $pulled=Join-Path $temporary $entry.name
        try {
            & $adb -s $Device pull ($remote[0] -replace '^package:','').Trim() $pulled *> (Join-Path $run ('pull-'+$entry.name+'.log'))
            if($LASTEXITCODE -ne 0 -or (Get-FileHash -LiteralPath $pulled).Hash.ToLowerInvariant() -cne $entry.sha256 -or (Get-Item -LiteralPath $pulled).Length -ne $entry.bytes) { throw 'Installed runtime APK differs from its input pin' }
            $records += [ordered]@{path=$entry.path;package=$entry.package;sha256=$entry.sha256;bytes=$entry.bytes;installedVerified=$true}
        } finally { if(Test-Path -LiteralPath $pulled) { Remove-Item -LiteralPath $pulled -Force } }
    }
    $graphics=@(& $adb -s $Device shell dumpsys SurfaceFlinger | Select-String '^GLES:' | ForEach-Object Line)
    [ordered]@{startedAt=$started.ToString('o');device=$Device;sdk=(& $adb -s $Device shell getprop ro.build.version.sdk);abi=(& $adb -s $Device shell getprop ro.product.cpu.abi);graphics=$graphics;apks=$records;dexComparison=$comparison;command=@($adb,'-s',$Device,'shell','am','instrument','-w','-r','app.miru.releaseprobe/app.miru.releaseprobe.ReleaseRuntimeProbe');scope='Exact ARM64 release DEX executed in x86_64 package; native ARM64 and full release matrix not tested'} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $run 'input.json')
    & $adb -s $Device shell am instrument -w -r app.miru.releaseprobe/app.miru.releaseprobe.ReleaseRuntimeProbe *> (Join-Path $run 'runner.log')
    $exitCode=$LASTEXITCODE
    $raw=Get-Content -LiteralPath (Join-Path $run 'runner.log') -Raw
    $codes=@([regex]::Matches($raw,'INSTRUMENTATION_STATUS_CODE: (-?\d+)') | ForEach-Object { [int]$_.Groups[1].Value })
    $passed=@($codes | Where-Object { $_ -eq 0 }).Count
    $bad=@($codes | Where-Object { $_ -lt 0 })
    $ok=$raw -match '(?m)^OK \(2 tests\)\s*$'
    $result=[ordered]@{finishedAt=(Get-Date -Format o);elapsedSeconds=((Get-Date)-$started).TotalSeconds;adbExit=$exitCode;passed=$passed;nonPassCodes=$bad;terminalOK=$ok;success=($exitCode -eq 0 -and $passed -eq 2 -and $bad.Count -eq 0 -and $ok);apks=$records}
    $reportLine=[regex]::Match($raw,'(?m)^INSTRUMENTATION_RESULT: report=(.+)$')
    if($reportLine.Success) {
        $report=$reportLine.Groups[1].Value.Trim() | ConvertFrom-Json
        foreach($frame in $report.frames) {
            if($frame.name -notin @('first-red.png','cached-red.png')) { throw 'Unknown proof image name' }
            [IO.File]::WriteAllBytes((Join-Path $run $frame.name),[Convert]::FromBase64String($frame.pngBase64))
            if((Get-FileHash -LiteralPath (Join-Path $run $frame.name)).Hash.ToLowerInvariant() -cne $frame.sha256) { throw 'Proof image transport hash differs' }
        }
        $report | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $run 'report.json')
        if($report.status -ne 'PASSED') { $result.success=$false }
    } else { $result.success=$false }
    & $adb -s $Device logcat -d -v threadtime -t 4000 *> (Join-Path $run 'logcat.log')
    if(-not $result.success) { throw 'R8 runtime probe failed; original runner/report/logcat retained' }
} catch { $result['success']=$false;$result['error']=$_.Exception.Message;throw }
finally {
    foreach($name in @('main.apk','harness.apk')) { $path=Join-Path $temporary $name;if(Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force } }
    Remove-Item -LiteralPath $temporary -Force
    Remove-Item -LiteralPath $lease -Force
    $result['temporaryPullsRetained']=$false
    $result | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $run 'result.json')
}
[pscustomobject]$result
