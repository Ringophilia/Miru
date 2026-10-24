# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
# Pure parser: no process execution, file access, signing or secret values.
function Get-MiruSignerDigests([string]$VerifyOutput) {
    $lines = @($VerifyOutput -split '\r?\n')
    $numberLines = @($lines | Where-Object { $_.Trim() -match '^Number of signers:' })
    if ($numberLines.Count -ne 1) { throw 'Exactly one Number of signers declaration is required.' }
    $numberMatch = [regex]::Match($numberLines[0].Trim(), '^Number of signers:[ \t]*([0-9]+)[ \t]*$')
    $number = 0
    if (-not $numberMatch.Success -or -not [int]::TryParse($numberMatch.Groups[1].Value, [ref]$number) -or $number -lt 1) {
        throw 'Number of signers must be a positive supported integer.'
    }

    # SDK 37 prints "V2 Signer: certificate ...". Older tools print "Signer #1 ...".
    # Numbered scheme labels and dotted version names (V3.1, V3.2) are also accepted.
    $certificatePattern = '^(?:Signer[ \t]+#(?<legacyIndex>[1-9][0-9]*)|(?<scheme>V[1-9][0-9]*(?:\.[0-9]+)*)[ \t]+Signer(?:[ \t]+#(?<schemeIndex>[1-9][0-9]*))?):?[ \t]+certificate[ \t]+SHA-256[ \t]+digest:[ \t]*(?<digest>[a-fA-F0-9]{64})[ \t]*$'
    $digests = New-Object 'Collections.Generic.SortedSet[string]' ([StringComparer]::Ordinal)
    $groups = @{}
    foreach ($line in $lines) {
        $text = $line.Trim()
        # A source stamp is a different signer role; it must not enter app identity.
        if ($text -match '^Source[ -]Stamp(?:[ \t]|:)') { continue }
        if ($text -notmatch 'certificate[ \t]+SHA-256[ \t]+digest:') { continue }
        $match = [regex]::Match($text, $certificatePattern)
        if (-not $match.Success) { throw 'Unrecognized or malformed app signer certificate SHA-256 line.' }
        $scheme = 'legacy'
        $indexText = $match.Groups['legacyIndex'].Value
        if ($match.Groups['scheme'].Success) {
            $scheme = $match.Groups['scheme'].Value
            $indexText = $match.Groups['schemeIndex'].Value
            if ([string]::IsNullOrEmpty($indexText)) {
                if ($number -ne 1) { throw 'An unnumbered scheme signer is ambiguous when Number of signers exceeds one.' }
                $indexText = '1'
            }
        }
        $index = 0
        if (-not [int]::TryParse($indexText, [ref]$index) -or $index -lt 1 -or $index -gt $number) {
            throw 'Signer index is outside the declared Number of signers.'
        }
        $digest = $match.Groups['digest'].Value.ToLowerInvariant()
        if (-not $groups.ContainsKey($scheme)) { $groups[$scheme] = @{} }
        $schemeSigners = $groups[$scheme]
        if ($schemeSigners.ContainsKey($index) -and $schemeSigners[$index] -cne $digest) {
            throw 'One signer label refers to conflicting certificate digests.'
        }
        $schemeSigners[$index] = $digest
        $null = $digests.Add($digest)
    }
    if ($groups.Count -eq 0) { throw 'Verified signer certificate SHA-256 digest was not found.' }
    foreach ($scheme in $groups.Keys) {
        if ($groups[$scheme].Count -ne $number) { throw 'Signer labels are incomplete for the declared Number of signers.' }
    }
    if ($digests.Count -ne $number) {
        # This wrapper has no key-rotation/lineage input. Different historical
        # scheme certificates must fail closed rather than hiding extra identities.
        throw 'Distinct app signer certificates do not match Number of signers; differing rotation identities require explicit handling.'
    }
    return @($digests)
}
