// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

pub mod bitstream;
pub mod classifier;
pub mod resolution;

pub use classifier::{classify_media, dedup_key, is_vast_payload, MediaKind};
pub use resolution::{probe_hls_manifest, probe_media_bytes, probe_mp4, MediaResolution};
