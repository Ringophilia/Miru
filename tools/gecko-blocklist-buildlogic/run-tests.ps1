# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$GeckoAar,
    [Parameter(Mandatory)][string]$GradleHome,
    [Parameter(Mandatory)][string]$JdkHome,
    [string]$NodePath = 'node',
    [string]$ArtifactsDirectory = (Join-Path $PSScriptRoot '../../artifacts/b13-policy-overlay')
)
$ErrorActionPreference = 'Stop'
$projectDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$evidenceDirectory = [IO.Path]::GetFullPath($ArtifactsDirectory)
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
# Compile with installed Gradle API / Groovy JSON jars, without running Gradle or the Android project.
$classesDirectory = Join-Path $evidenceDirectory 'standalone-classes'
New-Item -ItemType Directory -Force -Path $classesDirectory | Out-Null
$dependencyClasspath = "$(Join-Path $GradleHome 'lib/*');$(Join-Path $GradleHome 'lib/plugins/*')"
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
$compileLog = Join-Path $evidenceDirectory 'standalone-compile.log'
# Tee-Object does not truncate a previous log when a successful javac emits no output.
Set-Content -LiteralPath $compileLog -Encoding utf8 -Value 'Compiling all production and test Java sources with JDK 21 / installed Gradle API jars.'
& (Join-Path $JdkHome 'bin/javac.exe') --release 21 -encoding UTF-8 -Xlint:all,-path -cp $dependencyClasspath -d $classesDirectory @sources 2>&1 | Tee-Object -FilePath $compileLog -Append
if ($LASTEXITCODE -ne 0) { throw 'Standalone Java compilation failed' }
Add-Content -LiteralPath $compileLog -Encoding utf8 -Value 'Compilation succeeded.'
& (Join-Path $JdkHome 'bin/java.exe') -Xmx768m -cp "$classesDirectory;$dependencyClasspath" app.miru.buildlogic.GeckoBlocklistOverlayTest `
    ([IO.Path]::GetFullPath($GeckoAar)) (Join-Path $projectDirectory 'third_party/mozilla-blocklist/snapshot') (Join-Path $evidenceDirectory 'standalone-work') 2>&1 | Tee-Object -FilePath (Join-Path $evidenceDirectory 'standalone-tests.log')
if ($LASTEXITCODE -ne 0) { throw 'Standalone Java tests failed' }
& $NodePath (Join-Path $PSScriptRoot 'test-permission-policy.mjs') (Join-Path $projectDirectory 'third_party/mozilla-permission-policy') 2>&1 | Tee-Object -FilePath (Join-Path $evidenceDirectory 'permission-policy-js-tests.log')
if ($LASTEXITCODE -ne 0) { throw 'Permission module JavaScript policy scenarios failed' }
& $NodePath (Join-Path $PSScriptRoot 'test-document-location.mjs') (Join-Path $projectDirectory 'third_party/mozilla-permission-policy') 2>&1 | Tee-Object -FilePath (Join-Path $evidenceDirectory 'document-location-js-tests.log')
if ($LASTEXITCODE -ne 0) { throw 'Navigation module JavaScript document-location scenarios failed' }
