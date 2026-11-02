# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
#requires -Version 7.0
param([Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')][string]$RunName)
$ErrorActionPreference='Stop'
$repository=Split-Path $PSScriptRoot -Parent
$project=Join-Path $PSScriptRoot 'release-runtime-probe'
$evidence=Join-Path $repository 'artifacts/r8-device-smoke'
$run=Join-Path $evidence $RunName
if(Test-Path -LiteralPath $run) { throw 'Use a fresh probe build name' }
New-Item -ItemType Directory -Path $run -Force | Out-Null
function Get-ProbeSources {
    @('settings.gradle.kts','build.gradle.kts','gradle.properties','src/main/AndroidManifest.xml','src/main/java/app/miru/releaseprobe/ReleaseRuntimeProbe.java') | ForEach-Object {
        [ordered]@{path=$_;sha256=(Get-FileHash -LiteralPath (Join-Path $project $_)).Hash.ToLowerInvariant()}
    }
}
$before=@(Get-ProbeSources)
$before | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'sources-before.json')
$arguments=@('--project-dir',$project,'--no-daemon','--console=plain',('-PmiruRoot='+$repository),'assembleDebug','dependencies','--configuration','debugRuntimeClasspath')
Push-Location $repository
try {
    & (Join-Path $repository 'gradlew.bat') @arguments *> (Join-Path $run 'build.log')
    if($LASTEXITCODE -ne 0) { throw 'Probe build failed; see retained build.log' }
    $after=@(Get-ProbeSources)
    $after | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'sources-after.json')
    if(($before | ConvertTo-Json -Compress) -cne ($after | ConvertTo-Json -Compress)) { throw 'Probe source drift' }
    $apk=Join-Path $project 'build/outputs/apk/debug/MiruReleaseRuntimeProbe-debug.apk'
    $manifest=Join-Path $evidence ($RunName+'.json')
    & python (Join-Path $PSScriptRoot 'verify_release_runtime_probe.py') --apk $apk --report $manifest
    if($LASTEXITCODE -ne 0) { throw 'Probe defines foreign runtime classes; refusing it' }
    Write-Output "Probe manifest: $manifest"
} finally { Pop-Location }
