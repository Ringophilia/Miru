#!/usr/bin/env bash
# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
# Syncs Miru's tracked //miru_engine sources and unified Rust shared library (libmiru_core.so, DT_SONAME: libmiru_core.so)
# into the Chromium checkout so GN builds hermetically on ext4.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHROMIUM_SRC="${CHROMIUM_SRC:-$HOME/chromium/workspace/src}"
ENGINE_DST="$CHROMIUM_SRC/miru_engine"

if [[ ! -d "$CHROMIUM_SRC" ]]; then
  echo "ERROR: CHROMIUM_SRC directory does not exist: $CHROMIUM_SRC" >&2
  exit 1
fi

rm -rf "$ENGINE_DST"
mkdir -p "$ENGINE_DST/prebuilt/include" "$ENGINE_DST/prebuilt/x86_64" "$ENGINE_DST/prebuilt/arm64-v8a"
cp -a "$REPO_ROOT/tools/chromium-build/miru_engine/." "$ENGINE_DST/"
cp -a "$REPO_ROOT/native/miru-core/include/miru_core.h" "$ENGINE_DST/prebuilt/include/miru_core.h"

for abi in "x86_64" "arm64-v8a"; do
  lib_path="$REPO_ROOT/native/android-jniLibs/$abi/libmiru_core.so"
  if [[ -f "$lib_path" ]]; then
    cp -a "$lib_path" "$ENGINE_DST/prebuilt/$abi/libmiru_core.so"
    echo "[sync-miru-engine] Copied $abi libmiru_core.so ($(stat -c%s "$lib_path") B)"
  fi
done

# Register //miru_engine:miru_chromium_engine_aar in root //BUILD.gn once.
if ! grep -q "miru_all" "$CHROMIUM_SRC/BUILD.gn"; then
  cat >> "$CHROMIUM_SRC/BUILD.gn" << 'EOF'

if (is_android) {
  group("miru_all") {
    deps = [ "//miru_engine:miru_chromium_engine_aar" ]
  }
}
EOF
  echo "[sync-miru-engine] Registered //:miru_all in $CHROMIUM_SRC/BUILD.gn"
fi

echo "[sync-miru-engine] Done -> $ENGINE_DST"
