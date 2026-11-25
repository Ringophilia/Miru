// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

const EPHEMERAL_PARAMS: &[&str] = &[
    "sign",
    "token",
    "expires",
    "timestamp",
    "t",
    "auth_key",
    "Expires",
    "Signature",
    "Key-Pair-Id",
    "Policy",
];

fn decode_query_token(raw: &str) -> Option<String> {
    let bytes = raw.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b'%' => {
                if i + 2 >= bytes.len() {
                    return None;
                }
                let hi = (bytes[i + 1] as char).to_digit(16)?;
                let lo = (bytes[i + 2] as char).to_digit(16)?;
                out.push(((hi << 4) | lo) as u8);
                i += 3;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8(out).ok()
}

/// Implements MIRU-SPEC §8.5.1 / `PlaybackPolicy.normalizedCacheUrl`:
/// For segment URLs (`.ts`, `.m4s`, `.mp4`, or `is_range == true`), if ALL query keys are in the
/// ephemeral signing token whitelist, strip `?query` so refreshed signed URLs reuse local cache.
/// Playlist manifests (`.m3u8`, `.mpd`) and non-segment / content-selecting URLs stay untouched.
pub fn normalized_cache_url(raw: &str, is_range: bool) -> String {
    let Some((base, query_and_frag)) = raw.split_once('?') else {
        return raw.to_owned();
    };
    let lower_path = base.split_once("://")
        .and_then(|(_, rest)| rest.find('/').map(|idx| &rest[idx..]))
        .unwrap_or("")
        .to_ascii_lowercase();
    if lower_path.ends_with(".m3u8") || lower_path.ends_with(".mpd") {
        return raw.to_owned();
    }
    let segment = is_range
        || lower_path.ends_with(".ts")
        || lower_path.ends_with(".m4s")
        || lower_path.ends_with(".mp4");
    if !segment {
        return raw.to_owned();
    }
    let query = query_and_frag.split('#').next().unwrap_or("");
    let mut any_key = false;
    for pair in query.split('&') {
        any_key = true;
        let raw_key = pair.split('=').next().unwrap_or("");
        let Some(decoded) = decode_query_token(raw_key) else {
            return raw.to_owned();
        };
        if !EPHEMERAL_PARAMS.contains(&decoded.as_str()) {
            return raw.to_owned();
        }
    }
    if any_key { base.to_owned() } else { raw.to_owned() }
}

pub fn strip_ephemeral_signature_params(url: &str) -> String {
    normalized_cache_url(url, true)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strips_only_all_ephemeral_signed_segments_and_keeps_manifests() {
        assert_eq!(
            normalized_cache_url("https://cdn.example/a.ts?token=a&expires=1", false),
            "https://cdn.example/a.ts"
        );
        assert_eq!(
            normalized_cache_url("https://cdn.example/movie.mp4?Signature=a&Expires=2", true),
            "https://cdn.example/movie.mp4"
        );
        assert_eq!(
            strip_ephemeral_signature_params(
                "https://cdn.example/seg01.ts?Expires=1700000&Signature=abc&Key-Pair-Id=K1"
            ),
            "https://cdn.example/seg01.ts"
        );
        for kept in [
            "https://cdn.example/a.m3u8?token=a",
            "https://cdn.example/a.mpd?token=a",
            "https://cdn.example/a.ts?quality=hd&token=a",
            "https://cdn.example/stream?token=a",
            "https://cdn.example/video.mp4?id=42&sign=abc",
        ] {
            assert_eq!(normalized_cache_url(kept, false), kept);
        }
    }
}
