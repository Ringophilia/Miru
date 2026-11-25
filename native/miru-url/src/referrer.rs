// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

use crate::origin::split_host_port;

struct ParsedRefUrl {
    scheme: String,
    host: String,
    port: u16,
    origin: String,
    document: String,
    trustworthy: bool,
}

impl ParsedRefUrl {
    fn parse(value: &str) -> Option<Self> {
        if value.is_empty() || value.bytes().any(|b| b <= 0x20 || b == 0x7f) {
            return None;
        }
        let (scheme_raw, rest) = value.split_once("://")?;
        let scheme = scheme_raw.to_ascii_lowercase();
        if scheme != "http" && scheme != "https" {
            return None;
        }
        let rest_no_frag = rest.split('#').next()?;
        let (authority, path_and_query) = match rest_no_frag.find(['/', '?']) {
            Some(idx) => (&rest_no_frag[..idx], &rest_no_frag[idx..]),
            None => (rest_no_frag, ""),
        };
        let host_port = authority.rsplit('@').next()?;
        let (host, explicit_port) = split_host_port(host_port)?;
        let default_port = if scheme == "https" { 443 } else { 80 };
        let port = explicit_port.unwrap_or(default_port);

        let mut origin = format!("{scheme}://{host}");
        if port != default_port {
            origin.push(':');
            origin.push_str(&port.to_string());
        }
        origin.push('/');

        let normalized_pq = if path_and_query.is_empty() {
            "/".to_owned()
        } else if path_and_query.starts_with('?') {
            format!("/{path_and_query}")
        } else {
            path_and_query.to_owned()
        };
        let full_doc = format!("{}{}", &origin[..origin.len() - 1], normalized_pq);
        let document = if full_doc.len() > 4096 { origin.clone() } else { full_doc };

        let plain = host.trim_start_matches('[').trim_end_matches(']').trim_end_matches('.');
        let is_loopback_v4 = {
            let parts: Vec<&str> = plain.split('.').collect();
            parts.len() == 4
                && parts[0] == "127"
                && parts.iter().all(|p| p.parse::<u8>().is_ok())
        };
        let trustworthy = scheme == "https"
            || plain == "localhost"
            || plain.ends_with(".localhost")
            || plain == "::1"
            || is_loopback_v4;

        Some(Self { scheme, host, port, origin, document, trustworthy })
    }

    fn same_origin(&self, other: &Self) -> bool {
        self.scheme == other.scheme && self.host == other.host && self.port == other.port
    }
}

/// Evaluates Referrer Policy (`no-referrer`, `strict-origin-when-cross-origin`, etc.).
pub fn referrer_for_policy(document_url: &str, target_url: &str, policy: &str) -> Option<String> {
    let source = ParsedRefUrl::parse(document_url)?;
    let target = ParsedRefUrl::parse(target_url)?;
    let same_origin = source.same_origin(&target);
    let downgrade = source.trustworthy && !target.trustworthy;
    let normalized = if policy.is_empty() { "strict-origin-when-cross-origin" } else { policy };
    match normalized {
        "no-referrer" => None,
        "no-referrer-when-downgrade" => (!downgrade).then_some(source.document),
        "same-origin" => same_origin.then_some(source.document),
        "origin" => Some(source.origin),
        "strict-origin" => (!downgrade).then_some(source.origin),
        "origin-when-cross-origin" => Some(if same_origin { source.document } else { source.origin }),
        "strict-origin-when-cross-origin" => {
            if same_origin {
                Some(source.document)
            } else {
                (!downgrade).then_some(source.origin)
            }
        }
        "unsafe-url" => Some(source.document),
        _ => None,
    }
}

/// Bounds a context menu image/media action's Referer by the owning document origin.
pub fn context_referrer(
    page_url: &str,
    target_url: &str,
    captured_referrer: Option<&str>,
) -> Option<String> {
    let page = ParsedRefUrl::parse(page_url)?;
    let target = ParsedRefUrl::parse(target_url)?;
    if page.scheme == "https" && target.scheme != "https" {
        return None;
    }
    let maximum = if page.same_origin(&target) { page.document.clone() } else { page.origin.clone() };
    let Some(captured_raw) = captured_referrer else {
        return Some(maximum);
    };
    if captured_raw.trim().is_empty() {
        return None;
    }
    let captured = ParsedRefUrl::parse(captured_raw)?;
    if !page.same_origin(&captured) {
        return None;
    }
    if captured.document == captured.origin {
        return Some(page.origin);
    }
    if captured.document != page.document {
        return None;
    }
    Some(maximum)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strips_path_on_cross_origin_and_drops_on_https_downgrade() {
        assert_eq!(
            context_referrer("https://a.example/private?token=1", "https://b.example/img.png", None).as_deref(),
            Some("https://a.example/")
        );
        assert_eq!(
            context_referrer("https://a.example/private?token=1", "http://b.example/img.png", None),
            None
        );
        assert_eq!(
            referrer_for_policy("https://a.example/p?x=1", "https://a.example/img", "same-origin").as_deref(),
            Some("https://a.example/p?x=1")
        );
        assert_eq!(
            referrer_for_policy("https://a.example/p?x=1", "https://b.example/img", "no-referrer"),
            None
        );
    }
}
