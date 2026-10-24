# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$CurrentApk,
    [Parameter(Mandatory)][string]$CurrentSha256,
    [Parameter(Mandatory)][string]$CacheApk,
    [Parameter(Mandatory)][string]$CacheSha256,
    [Parameter(Mandatory)][string]$ZipflingerJar,
    [Parameter(Mandatory)][string]$JdkHome,
    [Parameter(Mandatory)][string]$BuildToolsDirectory,
    [Parameter(Mandatory)][string]$PythonExecutable,
    [Parameter(Mandatory)][string]$KeyStorePath,
    [Parameter(Mandatory)][string]$KeyAlias,
    [Parameter(Mandatory)][string]$StorePasswordEnvName,
    [Parameter(Mandatory)][string]$KeyPasswordEnvName,
    [Parameter(Mandatory)][string]$OutputApk,
    [switch]$ValidateOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$miruPinnedZipflinger = 'd2ad6ddec6da2ae142a8fe3af2f2529f3fbf77852e2db7ff261ad7b924b119fa'
$miruStrictMaximum = 110000000L
$miruUtf8 = New-Object Text.UTF8Encoding($false)

function Get-MiruRegularFile([string]$Path, [string]$Label) {
    $resolved = [IO.Path]::GetFullPath($Path)
    if (-not [IO.File]::Exists($resolved)) { throw "$Label must be an existing regular file." }
    $item = Get-Item -LiteralPath $resolved -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "$Label cannot be a symbolic link or reparse point." }
    return $resolved
}

function Get-MiruHash([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-MiruDigest([string]$Path, [string]$Expected, [string]$Label) {
    if ($Expected -notmatch '^[a-fA-F0-9]{64}$') { throw "$Label requires a complete SHA-256 digest." }
    if ((Get-MiruHash $Path) -ne $Expected.ToLowerInvariant()) { throw "$Label SHA-256 does not match its supplied complete input pin." }
}

function Resolve-MiruExecutable([string]$Path) {
    if ([IO.Path]::IsPathRooted($Path) -or $Path.Contains('\') -or $Path.Contains('/')) {
        return Get-MiruRegularFile $Path 'Python executable'
    }
    $command = Get-Command $Path -CommandType Application -ErrorAction Stop | Select-Object -First 1
    return Get-MiruRegularFile $command.Source 'Python executable'
}

function Write-MiruJson([object]$Value, [string]$Path) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 12) + [Environment]::NewLine, $miruUtf8)
}

# Preflight performs no external commands and does not inspect password values.
$miruCurrent = Get-MiruRegularFile $CurrentApk 'Current APK'
$miruCache = Get-MiruRegularFile $CacheApk 'Completed cache APK'
$miruJar = Get-MiruRegularFile $ZipflingerJar 'Zipflinger JAR'
$miruKeyStore = Get-MiruRegularFile $KeyStorePath 'Keystore'
$miruJava = Get-MiruRegularFile (Join-Path $JdkHome 'bin/java.exe') 'JDK java executable'
$miruJavac = Get-MiruRegularFile (Join-Path $JdkHome 'bin/javac.exe') 'JDK javac executable'
$miruZipalign = Get-MiruRegularFile (Join-Path $BuildToolsDirectory 'zipalign.exe') 'SDK zipalign executable'
# Use the SDK's JAR directly; no cmd.exe/.bat shell receives aliases or paths.
$miruApksigner = Get-MiruRegularFile (Join-Path $BuildToolsDirectory 'lib/apksigner.jar') 'SDK apksigner JAR'
$miruPython = Resolve-MiruExecutable $PythonExecutable
$miruSource = Get-MiruRegularFile (Join-Path $PSScriptRoot 'apk-release-buildlogic/src/main/java/app/miru/release/ApkCompressionReuse.java') 'Helper Java source'
$miruVerifier = Get-MiruRegularFile (Join-Path $PSScriptRoot 'verify_apk_payloads.py') 'Independent payload verifier'
$miruSignerParser = Get-MiruRegularFile (Join-Path $PSScriptRoot 'apk-release-buildlogic/Get-MiruSignerDigests.ps1') 'Signer certificate parser'
. $miruSignerParser
$miruOutput = [IO.Path]::GetFullPath($OutputApk)
if ([IO.Path]::GetExtension($miruOutput) -ine '.apk') { throw 'Output must have the .apk extension.' }
if (Test-Path -LiteralPath $miruOutput) { throw 'Output already exists; refusing to overwrite it.' }
$miruParent = [IO.Path]::GetDirectoryName($miruOutput)
if (-not [IO.Directory]::Exists($miruParent)) { throw 'Output parent must already exist.' }
if ($miruOutput -ieq $miruCurrent -or $miruOutput -ieq $miruCache) { throw 'Output must differ from both inputs.' }
if ([string]::IsNullOrWhiteSpace($KeyAlias)) { throw 'A nonempty keystore alias is required.' }
if ($StorePasswordEnvName -notmatch '^[A-Za-z_][A-Za-z0-9_]*$' -or $KeyPasswordEnvName -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
    throw 'Password arguments must be environment variable names, using letters, digits and underscore only.'
}
Assert-MiruDigest $miruCurrent $CurrentSha256 'Current APK'
Assert-MiruDigest $miruCache $CacheSha256 'Completed cache APK'
Assert-MiruDigest $miruJar $miruPinnedZipflinger 'Official Zipflinger 9.1.1'

if ($ValidateOnly) {
    # Parameter/file/pin validation only: no ZIP, certificate or executable validity claim.
    Write-Output 'VALIDATED_PARAMETERS: no external tools executed, no evidence directory or output created.'
    return
}

$miruEvidence = $miruOutput + '.verification-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
if (Test-Path -LiteralPath $miruEvidence) { throw 'Evidence directory already exists.' }
New-Item -ItemType Directory -Path $miruEvidence | Out-Null
$miruClasses = Join-Path $miruEvidence 'classes'
New-Item -ItemType Directory -Path $miruClasses | Out-Null
$miruRaw = Join-Path $miruEvidence 'reused-unsigned.apk'
$miruAligned = Join-Path $miruEvidence 'aligned-unsigned.apk'
$miruSigned = Join-Path $miruEvidence 'candidate-signed.apk'
$miruReuseReport = Join-Path $miruEvidence 'reuse.json'
$miruPayloadReport = Join-Path $miruEvidence 'payload-verification.json'
$miruSummary = Join-Path $miruEvidence 'pipeline.json'
$miruSteps = New-Object 'Collections.Generic.List[object]'
$miruState = [ordered]@{
    status = 'RUNNING'
    scope = 'Packaging, alignment, signature/certificate equality and complete payload/size verification; runtime not tested'
    startedAtUtc = [DateTime]::UtcNow.ToString('o')
    currentApk = $miruCurrent
    currentSha256 = $CurrentSha256.ToLowerInvariant()
    cacheApk = $miruCache
    cacheSha256 = $CacheSha256.ToLowerInvariant()
    outputApk = $miruOutput
    strictMaximumBytes = $miruStrictMaximum
    zipflingerVersion = '9.1.1'
    zipflingerSha256 = $miruPinnedZipflinger
    javaSha256 = (Get-MiruHash $miruJava)
    javacSha256 = (Get-MiruHash $miruJavac)
    zipalignSha256 = (Get-MiruHash $miruZipalign)
    apksignerSha256 = (Get-MiruHash $miruApksigner)
    helperSourceSha256 = (Get-MiruHash $miruSource)
    verifierSourceSha256 = (Get-MiruHash $miruVerifier)
    signerParserSourceSha256 = (Get-MiruHash $miruSignerParser)
    steps = @()
}

function Invoke-MiruTool([string]$Step, [string]$Executable, [string[]]$Arguments) {
    $log = Join-Path $miruEvidence ($Step + '.log')
    Write-Host ("Release verification: " + $Step)
    $started = [DateTime]::UtcNow
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        # Passwords are never expanded here: Arguments contain only env:NAME.
        $lines = @(& $Executable @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    [IO.File]::WriteAllLines($log, [string[]]$lines, $miruUtf8)
    $miruSteps.Add([ordered]@{ step = $Step; exitCode = $code; seconds = ([DateTime]::UtcNow - $started).TotalSeconds; log = $log })
    $miruState.steps = @($miruSteps.ToArray())
    Write-MiruJson $miruState $miruSummary
    if ($code -ne 0) { throw "Step $Step failed with exit code $code; see its retained log." }
    return [string]::Join([Environment]::NewLine, $lines)
}

try {
    Write-MiruJson $miruState $miruSummary
    $miruCurrentSignature = Invoke-MiruTool '01-current-signature' $miruJava @('-jar', $miruApksigner, 'verify', '--verbose', '--print-certs', $miruCurrent)
    $miruCurrentCertificates = @(Get-MiruSignerDigests $miruCurrentSignature)
    $null = Invoke-MiruTool '02-compile-helper' $miruJavac @('--release', '21', '-encoding', 'UTF-8', '-cp', $miruJar, '-d', $miruClasses, $miruSource)
    Assert-MiruDigest $miruSource $miruState.helperSourceSha256 'Compiled helper source'
    $null = Invoke-MiruTool '03-reuse-compressed-entries' $miruJava @('-cp', ($miruClasses + [IO.Path]::PathSeparator + $miruJar),
        'app.miru.release.ApkCompressionReuse', $miruCurrent, $CurrentSha256, $miruCache, $CacheSha256, $miruRaw, $miruReuseReport)
    $null = Invoke-MiruTool '04-align-unsigned' $miruZipalign @('-P', '16', '4', $miruRaw, $miruAligned)
    $null = Invoke-MiruTool '05-sign' $miruJava @('-jar', $miruApksigner, 'sign', '--ks', $miruKeyStore,
        '--ks-key-alias', $KeyAlias, '--ks-pass', ('env:' + $StorePasswordEnvName), '--key-pass', ('env:' + $KeyPasswordEnvName),
        '--v4-signing-enabled', 'false', '--out', $miruSigned, $miruAligned)
    $null = Invoke-MiruTool '06-check-final-alignment' $miruZipalign @('-c', '-P', '16', '4', $miruSigned)
    $miruFinalSignature = Invoke-MiruTool '07-check-final-signature' $miruJava @('-jar', $miruApksigner, 'verify', '--verbose', '--print-certs', $miruSigned)
    $miruFinalCertificates = @(Get-MiruSignerDigests $miruFinalSignature)
    if (($miruCurrentCertificates -join ',') -cne ($miruFinalCertificates -join ',')) {
        throw 'Optimized APK signer certificate set differs from the current signed APK.'
    }
    $null = Invoke-MiruTool '08-independent-payload-size-verification' $miruPython @($miruVerifier, '--original', $miruCurrent,
        '--candidate', $miruSigned, '--max-bytes', [string]$miruStrictMaximum, '--report', $miruPayloadReport)
    Assert-MiruDigest $miruVerifier $miruState.verifierSourceSha256 'Independent verifier source'
    # ZIP paths are case-sensitive; resource names such as 9N and 9n are distinct.
    # PSCustomObject conversion rejects these otherwise valid per-entry JSON keys.
    $miruPayload = Get-Content -LiteralPath $miruPayloadReport -Raw | ConvertFrom-Json -AsHashtable
    $miruFinalHash = Get-MiruHash $miruSigned
    $miruFinalBytes = (Get-Item -LiteralPath $miruSigned).Length
    if ($miruPayload.status -cne 'PASSED' -or $miruPayload.originalSha256 -cne $CurrentSha256.ToLowerInvariant() -or
        $miruPayload.candidateSha256 -cne $miruFinalHash -or $miruPayload.candidateBytes -ne $miruFinalBytes -or
        $miruPayload.strictMaximumBytes -ne $miruStrictMaximum -or $miruFinalBytes -ge $miruStrictMaximum -or
        $miruPayload.verifiedPayloadEntries -le 0) { throw 'Independent verification report does not match the candidate and strict size gate.' }
    Assert-MiruDigest $miruCurrent $CurrentSha256 'Current APK before publication'
    Assert-MiruDigest $miruCache $CacheSha256 'Completed cache APK before publication'
    Assert-MiruDigest $miruSignerParser $miruState.signerParserSourceSha256 'Signer certificate parser source'
    if (Test-Path -LiteralPath $miruOutput) { throw 'Output appeared during verification; refusing to overwrite it.' }
    $miruState.status = 'PASSED'
    $miruState.completedAtUtc = [DateTime]::UtcNow.ToString('o')
    $miruState.outputSha256 = $miruFinalHash
    $miruState.outputBytes = $miruFinalBytes
    $miruState.signerCertificateSha256 = $miruFinalCertificates
    $miruState.verifiedPayloadEntries = $miruPayload.verifiedPayloadEntries
    $miruState.payloadReport = $miruPayloadReport
    $miruState.reuseReport = $miruReuseReport
    Write-MiruJson $miruState $miruSummary
    # Evidence and final output share a parent/filesystem. Two-argument Move refuses
    # overwrite, including a target created after the final existence check.
    [IO.File]::Move($miruSigned, $miruOutput)
    Write-Output ("PUBLISHED_VERIFIED: {0} bytes; SHA-256 {1}" -f $miruFinalBytes, $miruFinalHash)
    Write-Output ("APK: " + $miruOutput)
    Write-Output ("Evidence: " + $miruEvidence)
} catch {
    $miruState.status = 'FAILED'
    $miruState.failedAtUtc = [DateTime]::UtcNow.ToString('o')
    $miruState.error = $_.Exception.Message
    Write-MiruJson $miruState $miruSummary
    throw
}
# Logs, JSON, classes and unsigned intermediate APKs are retained for review.
# No recursive cleanup or mutation of the original/cache inputs is performed.
