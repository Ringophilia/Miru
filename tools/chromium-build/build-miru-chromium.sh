#!/usr/bin/env bash
# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
#
# Phase C1 reproducible Linux x86_64 build entrypoint for Miru's de-Googled Chromium AAR
# and unified Rust core staticlib (`libmiru_core.a`).
#
# Prerequisites (Ubuntu 22.04/24.04 x86_64, >= 64 GB RAM, >= 250 GB NVMe SSD):
#   1. depot_tools on PATH
#   2. rustup + target aarch64-linux-android (+ x86_64-linux-android if building emulator AAR)
#   3. Android NDK r27+ exported via ANDROID_NDK_HOME
#   4. Pinned Chromium checkout at $CHROMIUM_SRC with Cromite de-Googled patchset applied

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGET_ABI="${1:-arm64-v8a}"
OUT_DIR="${MIRU_AAR_OUT_DIR:-$REPO_ROOT/build/chromium-aar}"

if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "x86_64" ]]; then
  echo "ERROR: Chromium Android compilation requires a Linux x86_64 host." >&2
  exit 1
fi

case "$TARGET_ABI" in
  arm64-v8a)
    RUST_TARGET="aarch64-linux-android"
    GN_CPU="arm64"
    GN_ARGS_FILE="$REPO_ROOT/tools/chromium-build/args-arm64.gn"
    ;;
  x86_64)
    RUST_TARGET="x86_64-linux-android"
    GN_CPU="x64"
    GN_ARGS_FILE="$REPO_ROOT/tools/chromium-build/args-arm64.gn"
    ;;
  *)
    echo "Unsupported ABI: $TARGET_ABI (expected arm64-v8a or x86_64)" >&2
    exit 1
    ;;
esac

echo "[1/3] Building unified Rust staticlib/cdylib (libmiru_core) for $RUST_TARGET..."
cargo build \
  --manifest-path "$REPO_ROOT/native/Cargo.toml" \
  --release \
  -p miru-core \
  --target "$RUST_TARGET"

if [[ -z "${CHROMIUM_SRC:-}" || ! -d "$CHROMIUM_SRC" ]]; then
  echo "ERROR: Set CHROMIUM_SRC to a pinned Chromium + Cromite checkout directory." >&2
  exit 1
fi

BUILD_DIR="$CHROMIUM_SRC/out/miru_${GN_CPU}"
mkdir -p "$BUILD_DIR" "$OUT_DIR"
cp "$GN_ARGS_FILE" "$BUILD_DIR/args.gn"
if [[ "$GN_CPU" != "arm64" ]]; then
  sed -i "s/target_cpu = \"arm64\"/target_cpu = \"$GN_CPU\"/" "$BUILD_DIR/args.gn"
fi

echo "[2/3] Generating Ninja build files in $BUILD_DIR..."
(cd "$CHROMIUM_SRC" && gn gen "$BUILD_DIR")

echo "[3/3] Building Chromium Android embedding target..."
echo "NOTE: Wire the final Miru Chromium AAR target (e.g. miru_chromium_engine_aar) in Phase C1 once Chromium checkout is initialized."
(cd "$CHROMIUM_SRC" && autoninja -C "$BUILD_DIR" chrome_public_apk)

echo "Build complete. Generate SHA-256 manifest before publishing prebuilt .aar to Windows Gradle cache."
