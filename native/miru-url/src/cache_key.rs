// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

const EPHEMERAL_PARAMS: &[&str] = &[
    "sign",
    "token",
    "expires",
    "timestamp",
    "t",
    "auth_key",
    "signature",
    "key-pair-id",
    "policy",
];

/// Implements MIRU-SPEC §8.5.1 cache key normalization:
/// For media segments (`.ts`, `.m4s`, `.mp4` segments), if ALL query parameters are known
/// ephemeral signing tokens, strip the query so refreshed signed URLs still hit local disk cache.
/// Playlist manifests (`.m3u8`, `.mpd`) are returned untouched (never cached by key stripping).
pub fn strip_ephemeral_signature_params(url: &str) -> String {
    let Some((base, query_and_frag)) = url.split_once('?') else {
        return url.to_owned();
    };
    let lower_base = base.to_ascii_lowercase();
    if lower_base.ends_with(".m3u8") || lower_base.ends_with(".mpd") {
        return url.to_owned();
    }
    let query = query_and_frag.split('#').next().unwrap_or("");
    if query.is_empty() {
        return base.to_owned();
    }
    let mut has_param = false;
    for pair in query.split('&') {
        if pair.is_empty() {
            continue;
        }
        has_param = true;
        let name = pair.split('=').next().unwrap_or("").to_ascii_lowercase();
        if !EPHEMERAL_PARAMS.contains(&name.as_str()) {
            return url.to_owned();
        }
    }
    if has_param { base.to_owned() } else { url.to_owned() }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strips_only_all_ephemeral_signed_segments_and_keeps_manifests() {
        assert_eq!(
            strip_ephemeral_signature_params("https://cdn.example/seg01.ts?Expires=1700000&Signature=abc&Key-Pair-Id=K1"),
            "https://cdn.example/seg01.ts"
        );
        assert_eq!(
            strip_ephemeral_signature_params("https://cdn.example/video.mp4?id=42&sign=abc"),
            "https://cdn.example/video.mp4?id=42&sign=abc"
        );
        assert_eq!(
            strip_ephemeral_signature_params("https://cdn.example/index.m3u8?token=abc"),
            "https://cdn.example/index.m3u8?token=abc"
        );
    }
}
