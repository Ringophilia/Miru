# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$ZipflingerJar)
$ErrorActionPreference = 'Stop'
$miruRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$miruWorkParent = Join-Path $miruRepository 'artifacts/apk-release-script-tests'
New-Item -ItemType Directory -Path $miruWorkParent -Force | Out-Null
$miruWork = Join-Path $miruWorkParent ('run-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $miruWork | Out-Null
$miruChecks = 0
$miruLines = New-Object 'Collections.Generic.List[string]'

function Confirm-Miru([bool]$Condition, [string]$Label) {
    if (-not $Condition) { throw "Test failed: $Label" }
    $script:miruChecks++
    $line = 'PASS ' + $script:miruChecks + ': ' + $Label
    $script:miruLines.Add($line)
    Write-Output $line
}

function Expect-MiruRejection([hashtable]$Arguments, [string]$Label, [string]$ExpectedMessage) {
    $rejected = $false
    try { $null = & (Join-Path $PSScriptRoot 'optimize-release.ps1') @Arguments -ValidateOnly }
    catch {
        if ($_.Exception.Message -notlike ('*' + $ExpectedMessage + '*')) { throw }
        $rejected = $true
    }
    Confirm-Miru $rejected $Label
}

# Deliberately non-executable placeholders: ValidateOnly must never invoke them.
$miruJdk = Join-Path $miruWork 'fake-jdk'
$miruSdk = Join-Path $miruWork 'fake-sdk'
New-Item -ItemType Directory -Path (Join-Path $miruJdk 'bin') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $miruSdk 'lib') -Force | Out-Null
foreach ($miruRelative in @('java.exe', 'javac.exe')) { [IO.File]::WriteAllText((Join-Path $miruJdk ('bin/' + $miruRelative)), 'not executable') }
[IO.File]::WriteAllText((Join-Path $miruSdk 'zipalign.exe'), 'not executable')
[IO.File]::WriteAllText((Join-Path $miruSdk 'lib/apksigner.jar'), 'not a signer')
$miruPython = Join-Path $miruWork 'fake-python.exe'
[IO.File]::WriteAllText($miruPython, 'not executable')
$miruKeystore = Join-Path $miruWork 'dummy-keystore.jks'
[IO.File]::WriteAllText($miruKeystore, 'not a keystore; never opened by preflight')
$miruCurrent = Join-Path $miruWork 'current.apk'
$miruCache = Join-Path $miruWork 'cache.apk'
Add-Type -AssemblyName System.IO.Compression
$miruFile = [IO.File]::Create($miruCurrent)
try {
    $miruZip = [IO.Compression.ZipArchive]::new($miruFile, [IO.Compression.ZipArchiveMode]::Create, $true)
    try {
        $miruEntry = $miruZip.CreateEntry('assets/LICENSE')
        $miruWriter = [IO.StreamWriter]::new($miruEntry.Open())
        try { $miruWriter.Write('test license') } finally { $miruWriter.Dispose() }
    } finally { $miruZip.Dispose() }
} finally { $miruFile.Dispose() }
[IO.File]::Copy($miruCurrent, $miruCache)
$miruHash = (Get-FileHash -LiteralPath $miruCurrent -Algorithm SHA256).Hash.ToLowerInvariant()
$miruOutput = Join-Path $miruWork 'new-output.apk'
$miruBase = @{
    CurrentApk = $miruCurrent; CurrentSha256 = $miruHash
    CacheApk = $miruCache; CacheSha256 = $miruHash
    ZipflingerJar = $ZipflingerJar; JdkHome = $miruJdk
    BuildToolsDirectory = $miruSdk; PythonExecutable = $miruPython
    KeyStorePath = $miruKeystore; KeyAlias = 'test-alias'
    StorePasswordEnvName = 'MIRU_TEST_STORE_PASS'; KeyPasswordEnvName = 'MIRU_TEST_KEY_PASS'
    OutputApk = $miruOutput
}
$miruValidation = @(& (Join-Path $PSScriptRoot 'optimize-release.ps1') @miruBase -ValidateOnly)
Confirm-Miru (($miruValidation -join '') -like 'VALIDATED_PARAMETERS:*') 'parameter-only preflight executes no placeholder programs'
Confirm-Miru (-not (Test-Path -LiteralPath $miruOutput)) 'parameter-only preflight creates no final APK'
Confirm-Miru (@(Get-ChildItem -LiteralPath $miruWork -Directory -Filter '*.verification-*').Count -eq 0) 'parameter-only preflight creates no evidence/staging directory'
$miruCase = $miruBase.Clone(); $miruCase.CurrentApk = Join-Path $miruWork 'missing.apk'
Expect-MiruRejection $miruCase 'missing current input rejected before execution' 'Current APK must be an existing'
$miruCase = $miruBase.Clone(); $miruCase.CacheApk = Join-Path $miruWork 'missing-cache.apk'
Expect-MiruRejection $miruCase 'missing cache rejected before execution' 'Completed cache APK must be an existing'
$miruCase = $miruBase.Clone(); $miruCase.CurrentSha256 = '0' * 64
Expect-MiruRejection $miruCase 'wrong current pin rejected before execution' 'Current APK SHA-256 does not match'
$miruCase = $miruBase.Clone(); $miruCase.CacheSha256 = 'f' * 64
Expect-MiruRejection $miruCase 'wrong cache pin rejected before execution' 'Completed cache APK SHA-256 does not match'
$miruCase = $miruBase.Clone(); $miruCase.CurrentSha256 = 'short'
Expect-MiruRejection $miruCase 'incomplete pin rejected' 'requires a complete SHA-256'
$miruWrongJar = Join-Path $miruWork 'untrusted-zipflinger.jar'
[IO.File]::WriteAllText($miruWrongJar, 'not the official pinned jar')
$miruCase = $miruBase.Clone(); $miruCase.ZipflingerJar = $miruWrongJar
Expect-MiruRejection $miruCase 'untrusted Zipflinger bytes rejected' 'Official Zipflinger 9.1.1 SHA-256 does not match'
$miruCase = $miruBase.Clone(); $miruCase.KeyStorePath = Join-Path $miruWork 'missing-key.jks'
Expect-MiruRejection $miruCase 'missing keystore rejected' 'Keystore must be an existing'
$miruCase = $miruBase.Clone(); $miruCase.StorePasswordEnvName = 'not-an-env-name'
Expect-MiruRejection $miruCase 'inline/invalid store password argument rejected' 'must be environment variable names'
$miruCase = $miruBase.Clone(); $miruCase.KeyPasswordEnvName = 'env:NAME'
Expect-MiruRejection $miruCase 'invalid key password environment name rejected' 'must be environment variable names'
$miruCase = $miruBase.Clone(); $miruCase.OutputApk = Join-Path $miruWork 'wrong-extension.zip'
Expect-MiruRejection $miruCase 'non-APK output rejected' 'must have the .apk extension'
$miruCase = $miruBase.Clone(); $miruCase.OutputApk = Join-Path $miruWork 'missing-parent/output.apk'
Expect-MiruRejection $miruCase 'nonexistent output parent rejected' 'Output parent must already exist'
$miruCase = $miruBase.Clone(); $miruCase.OutputApk = $miruCurrent
Expect-MiruRejection $miruCase 'input overwrite rejected' 'refusing to overwrite'
[IO.File]::WriteAllText($miruOutput, 'existing final output sentinel')
Expect-MiruRejection $miruBase 'existing final output rejected' 'refusing to overwrite'
Confirm-Miru ([IO.File]::ReadAllText($miruOutput) -ceq 'existing final output sentinel') 'existing final output survives rejection'
Confirm-Miru ((Get-FileHash -LiteralPath $miruCurrent -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $miruHash) 'current input survives all preflight tests'
Confirm-Miru ((Get-FileHash -LiteralPath $miruCache -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $miruHash) 'cache input survives all preflight tests'
$miruLines.Add('PASS: ' + $miruChecks + ' preflight checks; no signing, compression, build or device tools executed')
[IO.File]::WriteAllLines((Join-Path $miruWork 'tests.log'), $miruLines, [Text.UTF8Encoding]::new($false))
Write-Output ('PASS: ' + $miruChecks + ' preflight checks')
Write-Output ('Evidence: ' + $miruWork)
