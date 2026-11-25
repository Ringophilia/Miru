// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

use serde::{Deserialize, Serialize};

const MIN_VIDEO_BYTES: u64 = 200 * 1024;
const MIN_AUDIO_BYTES: u64 = 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum MediaKind {
    Hls,
    Dash,
    Video,
    Audio,
}

impl MediaKind {
    pub fn as_str(self) -> &'static str {
        match self {
            MediaKind::Hls => "HLS",
            MediaKind::Dash => "DASH",
            MediaKind::Video => "VIDEO",
            MediaKind::Audio => "AUDIO",
        }
    }
}

/// Returns `scheme://host[:port]/path` (query & fragment stripped) for tab-level media deduplication.
pub fn dedup_key(raw_url: &str) -> Option<String> {
    let (scheme_raw, rest) = raw_url.split_once("://")?;
    let scheme = scheme_raw.to_ascii_lowercase();
    if scheme != "http" && scheme != "https" {
        return None;
    }
    let no_frag = rest.split('#').next()?;
    let no_query = no_frag.split('?').next()?;
    let (authority, path) = match no_query.find('/') {
        Some(idx) => (&no_query[..idx], &no_query[idx..]),
        None => (no_query, "/"),
    };
    if authority.is_empty() {
        return None;
    }
    let host_port = authority.rsplit('@').next()?.to_ascii_lowercase();
    let default_suffix = if scheme == "https" { ":443" } else { ":80" };
    let clean_auth = host_port.strip_suffix(default_suffix).unwrap_or(&host_port);
    if clean_auth.is_empty() {
        return None;
    }
    Some(format!("{scheme}://{clean_auth}{path}"))
}

/// Fast zero-regex classifier called directly on Chromium's C++ network IO thread (`URLLoaderThrottle`).
pub fn classify_media(
    raw_url: &str,
    content_type: Option<&str>,
    content_length: Option<u64>,
) -> Option<MediaKind> {
    let (scheme_raw, rest) = raw_url.split_once("://")?;
    let scheme = scheme_raw.to_ascii_lowercase();
    if scheme != "http" && scheme != "https" {
        return None;
    }
    let no_frag = rest.split('#').next()?;
    let no_query = no_frag.split('?').next()?;
    let (authority, path_raw) = match no_query.find('/') {
        Some(idx) => (&no_query[..idx], &no_query[idx..]),
        None => (no_query, "/"),
    };
    let host_port = authority.rsplit('@').next()?;
    let host = if host_port.starts_with('[') {
        host_port.split(']').next()?.trim_start_matches('[')
    } else {
        host_port.split(':').next()?
    }
    .to_ascii_lowercase();
    if host.is_empty() || is_known_ad_host(&host) {
        return None;
    }

    let path = path_raw.to_ascii_lowercase();
    if has_any_suffix(&path, &[".ts", ".m4s", ".aac", ".cmfv", ".cmfa"]) {
        return None;
    }

    let mime = content_type
        .unwrap_or("")
        .split(';')
        .next()
        .unwrap_or("")
        .trim()
        .to_ascii_lowercase();
    if mime == "application/xml" || mime == "text/xml" {
        return None;
    }

    if path.ends_with(".m3u8")
        || matches!(
            mime.as_str(),
            "application/vnd.apple.mpegurl"
                | "application/x-mpegurl"
                | "audio/mpegurl"
                | "audio/x-mpegurl"
        )
    {
        return Some(MediaKind::Hls);
    }

    if path.ends_with(".mpd") || mime == "application/dash+xml" {
        return Some(MediaKind::Dash);
    }

    let size = content_length.unwrap_or(0);
    if mime.starts_with("video/") || has_any_suffix(&path, &[".mp4", ".webm", ".mkv", ".flv", ".mov"]) {
        if size > 0 && size < MIN_VIDEO_BYTES {
            return None;
        }
        return Some(MediaKind::Video);
    }

    if mime.starts_with("audio/") && size > MIN_AUDIO_BYTES {
        return Some(MediaKind::Audio);
    }

    None
}

pub fn is_vast_payload(content_type: Option<&str>, body_preview: &str) -> bool {
    let mime = content_type
        .unwrap_or("")
        .split(';')
        .next()
        .unwrap_or("")
        .trim()
        .to_ascii_lowercase();
    if !mime.is_empty() && !mime.contains("xml") {
        return false;
    }
    let upper = body_preview.to_ascii_uppercase();
    upper.contains("<VAST") || upper.contains("<VMAP:VMAP")
}

fn has_any_suffix(path: &str, suffixes: &[&str]) -> bool {
    suffixes.iter().any(|suffix| path.ends_with(suffix))
}

fn is_known_ad_host(host: &str) -> bool {
    const AD_DOMAINS: &[&str] = &[
        "doubleclick.net",
        "googlesyndication.com",
        "adnxs.com",
        "imasdk.googleapis.com",
    ];
    AD_DOMAINS
        .iter()
        .any(|d| host == *d || (host.len() > d.len() && host.ends_with(d) && host.as_bytes()[host.len() - d.len() - 1] == b'.'))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies_streams_and_rejects_segments_previews_and_ads() {
        assert_eq!(classify_media("https://cdn.example.com/a/master.m3u8?sign=1", None, None), Some(MediaKind::Hls));
        assert_eq!(classify_media("https://cdn.example.com/stream", Some("application/vnd.apple.mpegurl; charset=utf-8"), None), Some(MediaKind::Hls));
        assert_eq!(classify_media("https://cdn.example.com/v.mpd", None, None), Some(MediaKind::Dash));
        assert_eq!(classify_media("https://cdn.example.com/v.mp4", Some("video/mp4"), Some(5_000_000)), Some(MediaKind::Video));
        assert_eq!(classify_media("https://cdn.example.com/a.mp3", Some("audio/mpeg"), Some(2_000_000)), Some(MediaKind::Audio));

        assert_eq!(classify_media("https://cdn.example.com/seg1.ts", Some("video/mp2t"), Some(1_000_000)), None);
        assert_eq!(classify_media("https://cdn.example.com/preview.mp4", Some("video/mp4"), Some(120_000)), None);
        assert_eq!(classify_media("https://cdn.example.com/click.mp3", Some("audio/mpeg"), Some(50_000)), None);
        assert_eq!(classify_media("https://pubads.doubleclick.net/ad.mp4", Some("video/mp4"), Some(4_000_000)), None);
        assert_eq!(classify_media("blob:https://example.com/123", Some("video/mp4"), Some(4_000_000)), None);
        assert!(is_vast_payload(Some("application/xml"), "<VAST version=\"4.0\">"));
        assert!(!is_vast_payload(Some("video/mp4"), "<VAST"));
        assert_eq!(dedup_key("https://CDN.Example.com:443/v/index.m3u8?token=abc").as_deref(), Some("https://cdn.example.com/v/index.m3u8"));
    }
}
