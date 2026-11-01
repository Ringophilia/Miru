# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')][string]$BuildName)
$ErrorActionPreference = 'Stop'
$miruRepository = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$miruBuild = Join-Path $miruRepository "artifacts/m3-cached-ranges/$BuildName"
if(Test-Path -LiteralPath $miruBuild) { throw 'Use a fresh BuildName; existing build evidence cannot be overwritten' }
New-Item -ItemType Directory -Path $miruBuild | Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $miruBuild 'build.ps1')
function Get-MiruSources {
    $paths = @(& git -c core.quotepath=false ls-files --cached --others --exclude-standard)
    if($LASTEXITCODE -ne 0) { throw 'Cannot inventory source files' }
    @($paths | Sort-Object -Unique | ForEach-Object {
        [ordered]@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
}
$miruResult = [ordered]@{success=$false;acceptedTests=$false;acceptedApks=$false}
$miruLease = Join-Path $miruBuild ('.active-validation-' + $PID + '.json')
[ordered]@{pid=$PID;processStartTicks=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks} | ConvertTo-Json | Set-Content -LiteralPath $miruLease
Push-Location $miruRepository
try {
    $before = Get-MiruSources
    $before | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $miruBuild 'sources-before.json')
    $tasks = @(':feature:player:testDebugUnitTest', ':feature:player:assembleDebugAndroidTest', ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:lintDebug', '--no-daemon', '--console=plain')
    [ordered]@{head=(git rev-parse HEAD);startedAt=(Get-Date -Format o);javaHome=$env:JAVA_HOME;androidHome=$env:ANDROID_HOME;command=@('.\gradlew.bat')+$tasks;sourceFiles=$before.Count} |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $miruBuild 'input.json')
    & .\gradlew.bat @tasks *> (Join-Path $miruBuild 'build.log')
    $code = $LASTEXITCODE
    $after = Get-MiruSources
    $after | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $miruBuild 'sources-after.json')
    $drift = @(Compare-Object ($before | ConvertTo-Json -Depth 5) ($after | ConvertTo-Json -Depth 5))
    $miruResult['gradleExit'] = $code
    $miruResult['sourceFiles'] = $after.Count
    $miruResult['sourceDrift'] = $drift
    if($code -ne 0 -or $drift.Count -ne 0) { throw 'Build failed or source changed; no APK or potentially stale test report accepted' }
    $reports = @(Get-ChildItem -Path 'feature/player/build/test-results/testDebugUnitTest/TEST-*.xml')
    if($reports.Count -eq 0) { throw 'No player JVM test reports found' }
    $reportDirectory = Join-Path $miruBuild 'junit'
    New-Item -ItemType Directory -Path $reportDirectory | Out-Null
    $tests=0; $failures=0; $errors=0; $skipped=0
    foreach($file in $reports) {
        Copy-Item -LiteralPath $file.FullName -Destination $reportDirectory
        [xml]$report = Get-Content -LiteralPath $file.FullName -Raw
        $tests += [int]$report.testsuite.tests; $failures += [int]$report.testsuite.failures
        $errors += [int]$report.testsuite.errors; $skipped += [int]$report.testsuite.skipped
    }
    $miruResult['playerJvm'] = @{tests=$tests;failures=$failures;errors=$errors;skipped=$skipped}
    if($tests -eq 0 -or $failures -ne 0 -or $errors -ne 0) { throw 'Player JVM tests did not pass' }
    $apks = @()
    foreach($entry in @(
        @{name='player-test.apk';path='feature/player/build/outputs/apk/androidTest/debug/player-debug-androidTest.apk'},
        @{name='main.apk';path='app/build/outputs/apk/debug/app-debug.apk'},
        @{name='app-test.apk';path='app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'}
    )) {
        $target = Join-Path $miruBuild $entry.name
        Copy-Item -LiteralPath $entry.path -Destination $target
        $apks += [ordered]@{name=$entry.name;path=$entry.path;bytes=(Get-Item -LiteralPath $target).Length;sha256=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
    Copy-Item -LiteralPath 'app/build/reports/lint-results-debug.xml' -Destination (Join-Path $miruBuild 'lint.xml')
    $miruResult['apks'] = $apks
    $miruResult.acceptedTests = $true; $miruResult.acceptedApks = $true; $miruResult.success = $true
} catch {
    $miruResult['error'] = $_.Exception.Message
    throw
} finally {
    $miruResult['finishedAt'] = Get-Date -Format o
    $miruResult | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $miruBuild 'result.json')
    Remove-Item -LiteralPath $miruLease -Force
    Pop-Location
    # Even failed batches can leave partial APK snapshots. Keep the last two successful builds.
    & (Join-Path $PSScriptRoot 'clean-validation-artifacts.ps1') -Apply | Format-List | Out-Host
}
[pscustomobject]$miruResult
