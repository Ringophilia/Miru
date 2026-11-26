#!/usr/bin/env bash
# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
#
# Phase C1 reproducible Linux x86_64 / WSL2 build entrypoint for Miru's de-Googled
# Chromium 159.0.8154.33 AAR (`//miru_engine:miru_chromium_engine_aar`) dynamically
# linked against the single unified Rust shared library (`libmiru_core.so`).
#
# Prerequisites (Ubuntu 22.04/24.04 x86_64 or WSL2 Ubuntu-24.04, >= 64 GB RAM, >= 250 GB SSD):
#   1. depot_tools on PATH (or under $CHROMIUM_ROOT/depot_tools)
#   2. Pinned Chromium 159.0.8154.33 checkout at $CHROMIUM_SRC
#   3. Prebuilt libmiru_core.so (DT_SONAME: libmiru_core.so) in native/android-jniLibs/{arm64-v8a,x86_64}/
#      (or rustup + Android NDK toolchain configured to build miru-core)
#
# Usage:
#   bash tools/chromium-build/build-miru-chromium.sh [arm64-v8a|x86_64|dual] [version]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGET_ABI="${1:-dual}"
ENGINE_VERSION="${2:-159.0.8154.33}"
CHROMIUM_ROOT="${CHROMIUM_ROOT:-$HOME/chromium}"
CHROMIUM_SRC="${CHROMIUM_SRC:-$CHROMIUM_ROOT/workspace/src}"

if [[ -d "$CHROMIUM_ROOT/depot_tools" ]]; then
  export PATH="$CHROMIUM_ROOT/depot_tools:$PATH"
fi

if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "x86_64" ]]; then
  echo "ERROR: Chromium Android compilation requires a Linux x86_64 host." >&2
  exit 1
fi

if [[ ! -d "$CHROMIUM_SRC" ]]; then
  echo "ERROR: Set CHROMIUM_SRC to a pinned Chromium 159.0.8154.33 checkout directory (missing: $CHROMIUM_SRC)." >&2
  exit 1
fi

build_one_abi() {
  local abi="$1"
  local gn_cpu="$2"
  local gn_args="$3"
  local rust_target="$4"
  local jni_lib="$REPO_ROOT/native/android-jniLibs/$abi/libmiru_core.so"

  if [[ ! -f "$jni_lib" ]]; then
    echo "[miru-chromium] Missing $jni_lib; building miru-core ($rust_target) via Cargo..."
    cargo build \
      --manifest-path "$REPO_ROOT/native/Cargo.toml" \
      --release \
      -p miru-core \
      --target "$rust_target"
    mkdir -p "$(dirname "$jni_lib")"
    cp -a "$REPO_ROOT/native/target/$rust_target/release/libmiru_core.so" "$jni_lib"
  fi

  local out_dir="$CHROMIUM_SRC/out/miru_${gn_cpu}"
  mkdir -p "$out_dir"
  cp "$gn_args" "$out_dir/args.gn"
  (
    cd "$CHROMIUM_SRC"
    gn gen "out/miru_${gn_cpu}"
    autoninja -C "out/miru_${gn_cpu}" miru_engine:miru_chromium_engine_aar
  )
}

echo "[1/3] Syncing //miru_engine sources and prebuilt libmiru_core.so -> $CHROMIUM_SRC/miru_engine"
bash "$REPO_ROOT/tools/chromium-build/sync-miru-engine.sh"

PKG_ARGS=(--version "$ENGINE_VERSION")

case "$TARGET_ABI" in
  arm64-v8a)
    echo "[2/3] Building Chromium arm64-v8a engine AAR..."
    build_one_abi "arm64-v8a" "arm64" "$REPO_ROOT/tools/chromium-build/args-arm64.gn" "aarch64-linux-android"
    PKG_ARGS+=(--arm64-out "$CHROMIUM_SRC/out/miru_arm64")
    ;;
  x86_64)
    echo "[2/3] Building Chromium x86_64 engine AAR..."
    build_one_abi "x86_64" "x64" "$REPO_ROOT/tools/chromium-build/args-x64.gn" "x86_64-linux-android"
    PKG_ARGS+=(--x86-64-out "$CHROMIUM_SRC/out/miru_x64")
    ;;
  dual|all)
    echo "[2/3] Building Chromium dual-ABI (arm64-v8a + x86_64) engine AAR..."
    build_one_abi "arm64-v8a" "arm64" "$REPO_ROOT/tools/chromium-build/args-arm64.gn" "aarch64-linux-android"
    build_one_abi "x86_64" "x64" "$REPO_ROOT/tools/chromium-build/args-x64.gn" "x86_64-linux-android"
    PKG_ARGS+=(--arm64-out "$CHROMIUM_SRC/out/miru_arm64" --x86-64-out "$CHROMIUM_SRC/out/miru_x64")
    ;;
  *)
    echo "Unsupported ABI target: $TARGET_ABI (expected arm64-v8a, x86_64, or dual)" >&2
    exit 1
    ;;
esac

echo "[3/3] Packaging AAR + uncompressed assets + SHA256SUMS into tools/local-chromium-repo/..."
python3 "$REPO_ROOT/tools/chromium-build/package-aar.py" "${PKG_ARGS[@]}"
