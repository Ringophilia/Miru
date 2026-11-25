# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
[CmdletBinding()]
param(
    [switch]$SkipHost,
    [switch]$SkipAndroid,
    [string[]]$Abis = @("arm64-v8a", "x86_64")
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$manifestPath = Join-Path $repoRoot "native\Cargo.toml"
$outJniLibs = Join-Path $repoRoot "native\android-jniLibs"

function Resolve-SdkDir {
    $localProps = Join-Path $repoRoot "local.properties"
    if (Test-Path $localProps) {
        foreach ($line in Get-Content $localProps) {
            if ($line -match '^\s*sdk\.dir\s*=\s*(.+?)\s*$') {
                $raw = $Matches[1].Replace('\:', ':').Replace('\\', '\')
                if (Test-Path $raw) { return $raw }
            }
        }
    }
    foreach ($envName in @("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
        $candidate = [Environment]::GetEnvironmentVariable($envName)
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    throw "Android SDK directory not found in local.properties, ANDROID_HOME, or ANDROID_SDK_ROOT."
}

function Resolve-NdkLlvmBin {
    param([string]$SdkDir)
    $ndkRoot = Join-Path $SdkDir "ndk"
    if (-not (Test-Path $ndkRoot)) {
        throw "No NDK installed under $ndkRoot"
    }
    $dirs = Get-ChildItem $ndkRoot -Directory | Sort-Object Name -Descending
    $preferred = $dirs | Where-Object { $_.Name -like "28.*" } | Select-Object -First 1
    if (-not $preferred) { $preferred = $dirs | Select-Object -First 1 }
    if (-not $preferred) {
        throw "No NDK version directories found under $ndkRoot"
    }
    $llvmBin = Join-Path $preferred.FullName "toolchains\llvm\prebuilt\windows-x86_64\bin"
    if (-not (Test-Path $llvmBin)) {
        throw "NDK LLVM bin directory missing: $llvmBin"
    }
    Write-Host "[miru-rust] Using NDK $($preferred.Name) ($llvmBin)"
    return $llvmBin
}

function Verify-16KbElfAlignment {
    param(
        [string]$LlvmReadelf,
        [string]$SoPath
    )
    $lines = & $LlvmReadelf -W -l $SoPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "llvm-readelf failed on $SoPath"
    }
    $loadCount = 0
    foreach ($line in $lines) {
        if ($line -match '^\s*LOAD\s+') {
            $loadCount++
            if ($line -match '(0x[0-9a-fA-F]+)\s*$') {
                $align = [Convert]::ToUInt64($Matches[1], 16)
                if ($align -lt 16384) {
                    throw "16 KB alignment check failed for $SoPath (LOAD Align=$($Matches[1]))"
                }
            } else {
                throw "Unable to parse LOAD alignment from line: $line"
            }
        }
    }
    if ($loadCount -eq 0) {
        throw "No PT_LOAD segments found in $SoPath"
    }
    $sizeBytes = (Get-Item $SoPath).Length
    $sizeMiB = [Math]::Round($sizeBytes / 1MB, 2)
    Write-Host "[miru-rust] Verified $SoPath ($sizeBytes B / $sizeMiB MiB, $loadCount LOAD segments >= 16 KB aligned)"
}

if (-not $SkipHost) {
    $winLibsBin = "C:\Users\tocml\AppData\Local\Microsoft\WinGet\Packages\BrechtSanders.WinLibs.POSIX.UCRT_Microsoft.Winget.Source_8wekyb3d8bbwe\mingw64\bin"
    if (Test-Path $winLibsBin) {
        $env:PATH = "$winLibsBin;$env:PATH"
    }
    Write-Host "[miru-rust] Building host library (miru_core)..."
    & cargo build --manifest-path $manifestPath -p miru-core --release
    if ($LASTEXITCODE -ne 0) {
        throw "Host cargo build failed with exit code $LASTEXITCODE"
    }
}

if (-not $SkipAndroid) {
    $sdkDir = Resolve-SdkDir
    $llvmBin = Resolve-NdkLlvmBin -SdkDir $sdkDir
    $llvmAr = Join-Path $llvmBin "llvm-ar.exe"
    $llvmReadelf = Join-Path $llvmBin "llvm-readelf.exe"

    $abiTargets = @{
        "arm64-v8a" = @{
            Target = "aarch64-linux-android"
            EnvPrefix = "AARCH64_LINUX_ANDROID"
            Clang = Join-Path $llvmBin "aarch64-linux-android26-clang.cmd"
        }
        "x86_64" = @{
            Target = "x86_64-linux-android"
            EnvPrefix = "X86_64_LINUX_ANDROID"
            Clang = Join-Path $llvmBin "x86_64-linux-android26-clang.cmd"
        }
    }

    foreach ($abi in $Abis) {
        if (-not $abiTargets.ContainsKey($abi)) {
            throw "Unsupported ABI '$abi'. Supported: $($abiTargets.Keys -join ', ')"
        }
        $cfg = $abiTargets[$abi]
        if (-not (Test-Path $cfg.Clang)) {
            throw "NDK clang wrapper missing: $($cfg.Clang)"
        }

        [Environment]::SetEnvironmentVariable("CARGO_TARGET_$($cfg.EnvPrefix)_LINKER", $cfg.Clang)
        [Environment]::SetEnvironmentVariable("CARGO_TARGET_$($cfg.EnvPrefix)_AR", $llvmAr)
        [Environment]::SetEnvironmentVariable("CARGO_TARGET_$($cfg.EnvPrefix)_RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")

        Write-Host "[miru-rust] Building Android ABI=$abi target=$($cfg.Target)..."
        & cargo build --manifest-path $manifestPath -p miru-core --release --target $cfg.Target
        if ($LASTEXITCODE -ne 0) {
            throw "Android cargo build failed for $($cfg.Target) with exit code $LASTEXITCODE"
        }

        $targetReleaseDir = Join-Path $repoRoot "native\target\$($cfg.Target)\release"
        $builtSo = Join-Path $targetReleaseDir "libmiru_core.so"
        $builtStatic = Join-Path $targetReleaseDir "libmiru_core.a"
        $destDir = Join-Path $outJniLibs $abi
        New-Item -ItemType Directory -Force -Path $destDir | Out-Null
        $destSo = Join-Path $destDir "libmiru_core.so"
        Copy-Item -Force $builtSo $destSo
        Verify-16KbElfAlignment -LlvmReadelf $llvmReadelf -SoPath $destSo
        if (Test-Path $builtStatic) {
            $staticMiB = [Math]::Round((Get-Item $builtStatic).Length / 1MB, 2)
            Write-Host "[miru-rust] Staticlib ready for C++ link: $builtStatic ($staticMiB MiB)"
        }
    }
}
