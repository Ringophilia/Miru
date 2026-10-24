# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Get-MiruSignerDigests.ps1')
$miruFixture = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'src/test/fixtures/sdk37-current-signature.txt') -Raw
$miruDigest = '5aa1a00e59b8638298dbddcc5a61053ca245249136edb67199e0c9a3e4cb1f84'
$miruOther = '1' * 64
$miruChecks = 0
function Expect-Digests([string]$Text, [string[]]$Expected, [string]$Label) {
    $actual = @(Get-MiruSignerDigests $Text)
    if (($actual -join ',') -cne (($Expected | Sort-Object -Unique) -join ',')) { throw "Wrong signer set: $Label" }
    $script:miruChecks++
    Write-Output "PASS $script:miruChecks`: $Label"
}
function Expect-Rejection([string]$Text, [string]$Label) {
    $rejected = $false
    try { $null = Get-MiruSignerDigests $Text } catch { $rejected = $true }
    if (-not $rejected) { throw "Accepted invalid signer output: $Label" }
    $script:miruChecks++
    Write-Output "PASS $script:miruChecks`: $Label"
}
Expect-Digests $miruFixture @($miruDigest) 'actual SDK 37 V2 output excludes public key digest'
Expect-Digests ($miruFixture -replace 'V2 Signer:', 'Signer #1') @($miruDigest) 'legacy numbered signer output'
foreach ($miruScheme in @('V3', 'V3.1', 'V3.2')) {
    Expect-Digests ($miruFixture -replace 'V2 Signer:', "$miruScheme Signer:") @($miruDigest) "$miruScheme scheme output"
}
Expect-Digests ($miruFixture + "`nV3.1 Signer: certificate SHA-256 digest: $miruDigest") @($miruDigest) 'same certificate across schemes is a set'
Expect-Digests ($miruFixture + "`nSource Stamp Signer certificate SHA-256 digest: $miruOther") @($miruDigest) 'source stamp is not app identity'
Expect-Digests ("Number of signers: 2`nSigner #1 certificate SHA-256 digest: $miruDigest`nSigner #2 certificate SHA-256 digest: $miruOther") @($miruDigest, $miruOther) 'complete numbered multisigner set'
Expect-Rejection ($miruFixture -replace 'Number of signers: 1', 'Number of signers: 2') 'unnumbered multisigner label is ambiguous'
Expect-Rejection ($miruFixture -replace 'Number of signers: 1', 'Number of signers: 0') 'zero signers'
Expect-Rejection ($miruFixture -replace 'Number of signers: 1', '') 'missing count'
Expect-Rejection ($miruFixture + "`nNumber of signers: 1") 'duplicate count'
Expect-Rejection ($miruFixture + "`nV2 Signer: certificate SHA-256 digest: $miruOther") 'conflicting certificate for one label'
Expect-Rejection ($miruFixture + "`nV3 Signer: certificate SHA-256 digest: $miruOther") 'rotation identities require explicit handling'
Expect-Rejection ($miruFixture -replace $miruDigest, 'short') 'malformed certificate digest'
Expect-Rejection ("Number of signers: 1`nV2 Signer: public key SHA-256 digest: $miruDigest") 'public key alone cannot satisfy certificate identity'
Expect-Rejection ("Number of signers: 2`nSigner #1 certificate SHA-256 digest: $miruDigest") 'incomplete numbered set'
Expect-Rejection ("Number of signers: 1`nSigner #2 certificate SHA-256 digest: $miruDigest") 'index outside declared count'
Write-Output "PASS: $miruChecks signer parser checks"
