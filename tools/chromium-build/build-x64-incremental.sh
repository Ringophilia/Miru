#!/usr/bin/env bash
# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
set -euo pipefail

CHROMIUM_ROOT="${CHROMIUM_ROOT:-$HOME/chromium}"
CHROMIUM_SRC="${CHROMIUM_SRC:-$CHROMIUM_ROOT/workspace/src}"
CHROMIUM_EXT4_IMG="${CHROMIUM_EXT4_IMG:-}"

export PATH="$CHROMIUM_ROOT/depot_tools:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
if [[ -n "$CHROMIUM_EXT4_IMG" ]] && ! mountpoint -q "$CHROMIUM_ROOT"; then
  if [[ -n "${MIRU_WSL_SUDO_PASS:-}" ]]; then
    printf '%s\n' "$MIRU_WSL_SUDO_PASS" | sudo -S mount -o loop,noatime "$CHROMIUM_EXT4_IMG" "$CHROMIUM_ROOT"
  else
    mount -o loop,noatime "$CHROMIUM_EXT4_IMG" "$CHROMIUM_ROOT"
  fi
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
bash "$REPO_ROOT/tools/chromium-build/sync-miru-engine.sh"
cd "$CHROMIUM_SRC"
gn gen out/miru_x64
autoninja -C out/miru_x64 miru_engine:miru_chromium_engine_aar
python3 "$REPO_ROOT/tools/chromium-build/package-aar.py" \
  --version 159.0.8154.33 \
  --x86-64-out "$CHROMIUM_SRC/out/miru_x64"
