// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

use serde::Serialize;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct InlineDomainMatch {
    pub text: String,
    pub url: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ParsedHttpAuthority {
    pub scheme_lower: String,
    pub host_lower: String,
    pub port: Option<u16>,
}

/// Parses `http://` or `https://` authority without embedded credentials or control chars.
/// Matches Java `URI` host + explicit port semantics used in `InlineDomainCompletion`.
pub(crate) fn parse_http_authority(url: &str) -> Option<ParsedHttpAuthority> {
    if url.is_empty() || url.bytes().any(|b| b <= 0x20 || b == 0x7f) {
        return None;
    }
    let (scheme_raw, rest) = url.split_once("://")?;
    let scheme_lower = scheme_raw.to_ascii_lowercase();
    if scheme_lower != "http" && scheme_lower != "https" {
        return None;
    }
    let authority_end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let authority = &rest[..authority_end];
    if authority.is_empty() || authority.contains('@') || authority.ends_with(':') {
        return None;
    }
    if authority
        .bytes()
        .any(|b| matches!(b, b'<' | b'>' | b'"' | b'{' | b'}' | b'|' | b'\\' | b'^' | b'`'))
    {
        return None;
    }
    let (host_lower, port) = if let Some(stripped) = authority.strip_prefix('[') {
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
        (format!("[{}]", ipv6.to_ascii_lowercase()), port)
    } else {
        let (host_raw, port) = match authority.rsplit_once(':') {
            Some((h, p)) => (h, Some(p.parse::<u16>().ok()?)),
            None => (authority, None),
        };
        if host_raw.is_empty() || host_raw.contains(':') {
            return None;
        }
        (host_raw.to_ascii_lowercase(), port)
    };
    Some(ParsedHttpAuthority { scheme_lower, host_lower, port })
}

fn is_valid_domain_prefix(prefix: &str) -> bool {
    let bytes = prefix.as_bytes();
    if bytes.is_empty() || !bytes[0].is_ascii_alphanumeric() {
        return false;
    }
    bytes.iter().all(|b| b.is_ascii_alphanumeric() || *b == b'.' || *b == b'-')
}

pub(crate) fn extract_typed_scheme_and_prefix(input: &str) -> Option<(&str, &str)> {
    let scheme = if input.len() >= 8 && input[..8].eq_ignore_ascii_case("https://") {
        &input[..8]
    } else if input.len() >= 7 && input[..7].eq_ignore_ascii_case("http://") {
        &input[..7]
    } else {
        ""
    };
    let prefix = &input[scheme.len()..];
    if !is_valid_domain_prefix(prefix) {
        return None;
    }
    Some((scheme, prefix))
}

pub(crate) fn match_authority(
    scheme: &str,
    prefix: &str,
    parsed: &ParsedHttpAuthority,
) -> Option<InlineDomainMatch> {
    let omit_www = parsed.host_lower.starts_with("www.")
        && !prefix.as_bytes().first().is_some_and(|b| *b == b'w' || *b == b'W');
    let display_host = if omit_www {
        &parsed.host_lower[4..]
    } else {
        parsed.host_lower.as_str()
    };
    let port_suffix = match parsed.port {
        Some(p) => format!(":{p}"),
        None => String::new(),
    };
    let display = format!("{display_host}{port_suffix}");
    if display.len() <= prefix.len() || !display[..prefix.len()].eq_ignore_ascii_case(prefix) {
        return None;
    }
    let origin_scheme = if scheme.is_empty() {
        format!("{}://", parsed.scheme_lower)
    } else {
        scheme.to_owned()
    };
    Some(InlineDomainMatch {
        text: format!("{scheme}{prefix}{}", &display[prefix.len()..]),
        url: format!("{origin_scheme}{}{port_suffix}", parsed.host_lower),
    })
}

/// Complete only a domain, never a history path, query, credentials, or non-ASCII composition.
pub fn complete_inline_domain<'a>(
    input: &str,
    history_urls: impl IntoIterator<Item = &'a str>,
) -> Option<InlineDomainMatch> {
    let (scheme, prefix) = extract_typed_scheme_and_prefix(input)?;
    for raw_url in history_urls {
        let Some(parsed) = parse_http_authority(raw_url) else { continue };
        if let Some(matched) = match_authority(scheme, prefix, &parsed) {
            return Some(matched);
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn completes_ranked_history_and_only_origin() {
        assert_eq!(
            complete_inline_domain("git", ["https://github.com/user/repo?token=secret"]),
            Some(InlineDomainMatch {
                text: "github.com".to_owned(),
                url: "https://github.com".to_owned(),
            })
        );
        assert_eq!(
            complete_inline_domain("loc", ["http://localhost:8080/visited"]),
            Some(InlineDomainMatch {
                text: "localhost:8080".to_owned(),
                url: "http://localhost:8080".to_owned(),
            })
        );
        assert_eq!(
            complete_inline_domain("git", ["https://gitlab.com/project", "https://github.com"])
                .map(|m| m.text),
            Some("gitlab.com".to_owned())
        );
    }

    #[test]
    fn omits_www_in_display_and_keeps_typed_scheme() {
        assert_eq!(
            complete_inline_domain("Go", ["https://www.google.com/search?q=x"]),
            Some(InlineDomainMatch {
                text: "Google.com".to_owned(),
                url: "https://www.google.com".to_owned(),
            })
        );
        assert_eq!(
            complete_inline_domain("https://git", ["http://github.com/old"]).map(|m| m.url),
            Some("https://github.com".to_owned())
        );
    }

    #[test]
    fn rejects_queries_paths_credentials_and_exact_host_matches() {
        for bad in ["天气", "git hub", "github.com/", "git?token=", ""] {
            assert_eq!(complete_inline_domain(bad, ["https://github.com"]), None, "input={bad}");
        }
        assert_eq!(complete_inline_domain("git", ["https://name:password@github.com"]), None);
        assert_eq!(complete_inline_domain("github.com", ["https://github.com/path"]), None);
    }
}
