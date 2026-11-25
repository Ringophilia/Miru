// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

/// Parses an HTTP(S) URL into a normalized origin (`scheme://host[:non-default-port]`).
/// Rejects control chars, opaque URIs, embedded credentials, and invalid ports.
pub fn site_origin(value: &str) -> Option<String> {
    if value.is_empty() || value.bytes().any(|b| b <= 0x20 || b == 0x7f) {
        return None;
    }
    let (scheme_raw, rest) = value.split_once("://")?;
    let scheme = scheme_raw.to_ascii_lowercase();
    if scheme != "http" && scheme != "https" {
        return None;
    }
    let authority_end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let authority = &rest[..authority_end];
    if authority.is_empty() || authority.contains('@') || authority.ends_with(':') {
        return None;
    }
    let (host, port) = split_host_port(authority)?;
    if host.is_empty() {
        return None;
    }
    let default_port = if scheme == "https" { 443 } else { 80 };
    let mut out = String::with_capacity(scheme.len() + 3 + host.len() + 8);
    out.push_str(&scheme);
    out.push_str("://");
    if host.contains(':') && !host.starts_with('[') {
        out.push('[');
        out.push_str(&host);
        out.push(']');
    } else {
        out.push_str(&host);
    }
    if let Some(p) = port {
        if p != default_port {
            out.push(':');
            out.push_str(&p.to_string());
        }
    }
    Some(out)
}

pub(crate) fn split_host_port(authority: &str) -> Option<(String, Option<u16>)> {
    if let Some(stripped) = authority.strip_prefix('[') {
        let close = stripped.find(']')?;
        let ipv6 = &stripped[..close];
        if ipv6.is_empty() {
            return None;
        }
        let after = &stripped[close + 1..];
        let port = if after.is_empty() {
            None
        } else if let Some(port_str) = after.strip_prefix(':') {
            Some(port_str.parse::<u16>().ok()?)
        } else {
            return None;
        };
        Some((format!("[{}]", ipv6.to_ascii_lowercase()), port))
    } else {
        let (host_raw, port) = match authority.rsplit_once(':') {
            Some((h, p)) => (h, Some(p.parse::<u16>().ok()?)),
            None => (authority, None),
        };
        if host_raw.is_empty() || host_raw.contains(':') {
            return None;
        }
        Some((host_raw.to_ascii_lowercase(), port))
    }
}

pub fn link_scheme(value: &str) -> Option<String> {
    if value.len() > 8192 || value.bytes().any(|b| b < 0x20 || b == 0x7f) {
        return None;
    }
    let colon = value.find(':')?;
    if colon == 0 {
        return None;
    }
    let candidate = &value[..colon];
    let bytes = candidate.as_bytes();
    if !bytes[0].is_ascii_alphabetic() {
        return None;
    }
    if bytes[1..]
        .iter()
        .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'+' | b'-' | b'.'))
    {
        Some(candidate.to_ascii_lowercase())
    } else {
        None
    }
}

pub fn is_browser_scheme(scheme: &str) -> bool {
    matches!(
        scheme,
        "http"
            | "https"
            | "about"
            | "data"
            | "blob"
            | "javascript"
            | "resource"
            | "chrome"
            | "moz-safe-about"
            | "moz-extension"
            | "view-source"
    )
}

pub fn web_fallback(value: &str) -> Option<&str> {
    let scheme = link_scheme(value)?;
    if (scheme == "http" || scheme == "https") && site_origin(value).is_some() {
        Some(value)
    } else {
        None
    }
}

pub fn external_data_allowed(value: &str, has_package: bool) -> bool {
    let Some(scheme) = link_scheme(value) else {
        return false;
    };
    if scheme == "http" || scheme == "https" {
        return has_package && web_fallback(value).is_some();
    }
    !matches!(scheme.as_str(), "intent" | "android-app" | "file" | "content" | "miru")
        && !is_browser_scheme(&scheme)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_origins_and_rejects_credentials() {
        assert_eq!(site_origin("HTTPS://Example.COM:443/path?q=1").as_deref(), Some("https://example.com"));
        assert_eq!(site_origin("http://127.0.0.1:8080/a").as_deref(), Some("http://127.0.0.1:8080"));
        assert_eq!(site_origin("https://[::1]:8443/x").as_deref(), Some("https://[::1]:8443"));
        assert_eq!(site_origin("https://user:pass@example.com/"), None);
        assert_eq!(site_origin("javascript:alert(1)"), None);
        assert_eq!(site_origin("https://example.com:/x"), None);
    }

    #[test]
    fn enforces_external_scheme_boundaries() {
        assert!(external_data_allowed("weixin://dl/business", false));
        assert!(!external_data_allowed("intent://evil", false));
        assert!(!external_data_allowed("file:///etc/passwd", false));
        assert!(!external_data_allowed("https://example.com", false));
        assert!(external_data_allowed("https://example.com", true));
    }
}
