// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

use std::collections::BTreeSet;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::RwLock;

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct NetworkMatchResult {
    pub blocked: bool,
    pub redirect: Option<String>,
    pub matched_rule: Option<String>,
    pub exception_rule: Option<String>,
    pub important: bool,
}

impl NetworkMatchResult {
    pub fn allow() -> Self {
        Self {
            blocked: false,
            redirect: None,
            matched_rule: None,
            exception_rule: None,
            important: false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct CosmeticMatchResult {
    pub hide_selectors: Vec<String>,
    pub injected_script: String,
    pub generic_hide: bool,
}

impl CosmeticMatchResult {
    pub fn empty() -> Self {
        Self {
            hide_selectors: Vec::new(),
            injected_script: String::new(),
            generic_hide: false,
        }
    }
}

struct EngineState {
    engine: Engine,
    base_rules: String,
    custom_rules: Vec<String>,
    allowed_hosts: BTreeSet<String>,
}

/// Thread-safe native adblock engine designed for Chromium's IO thread (`URLLoaderThrottle`)
/// and Android/JVM bridges. Supports millisecond snapshot deserialization, per-site allowlist,
/// and user custom rules (including element picker CSS selectors).
pub struct MiruAdblockEngine {
    enabled: AtomicBool,
    blocked_total: AtomicU64,
    state: RwLock<EngineState>,
}

impl MiruAdblockEngine {
    pub fn from_rules(rules_text: &str) -> Self {
        let engine = compile_engine(rules_text, &[]);
        Self {
            enabled: AtomicBool::new(true),
            blocked_total: AtomicU64::new(0),
            state: RwLock::new(EngineState {
                engine,
                base_rules: rules_text.to_owned(),
                custom_rules: Vec::new(),
                allowed_hosts: BTreeSet::new(),
            }),
        }
    }

    pub fn from_snapshot(bytes: &[u8]) -> Result<Self, String> {
        let mut engine = Engine::default();
        engine
            .deserialize(bytes)
            .map_err(|err| format!("Failed to deserialize adblock snapshot: {err:?}"))?;
        Ok(Self {
            enabled: AtomicBool::new(true),
            blocked_total: AtomicU64::new(0),
            state: RwLock::new(EngineState {
                engine,
                base_rules: String::new(),
                custom_rules: Vec::new(),
                allowed_hosts: BTreeSet::new(),
            }),
        })
    }

    pub fn serialize_snapshot(&self) -> Vec<u8> {
        let guard = self.state.read().expect("adblock engine lock poisoned");
        guard.engine.serialize()
    }

    pub fn set_enabled(&self, enabled: bool) {
        self.enabled.store(enabled, Ordering::Release);
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled.load(Ordering::Acquire)
    }

    pub fn total_blocked(&self) -> u64 {
        self.blocked_total.load(Ordering::Relaxed)
    }

    pub fn set_site_allowed(&self, host: &str, allowed: bool) {
        if let Some(normalized) = normalize_host(host) {
            let mut guard = self.state.write().expect("adblock engine lock poisoned");
            if allowed {
                guard.allowed_hosts.insert(normalized);
            } else {
                guard.allowed_hosts.remove(&normalized);
            }
        }
    }

    pub fn is_site_allowed(&self, host: &str) -> bool {
        let Some(normalized) = normalize_host(host) else {
            return false;
        };
        let guard = self.state.read().expect("adblock engine lock poisoned");
        host_matches_allowlist(&normalized, &guard.allowed_hosts)
    }

    pub fn add_custom_rule(&self, rule: &str) -> bool {
        let trimmed = rule.trim();
        if trimmed.is_empty() || trimmed.starts_with('!') {
            return false;
        }
        let mut guard = self.state.write().expect("adblock engine lock poisoned");
        if guard.custom_rules.iter().any(|existing| existing == trimmed) {
            return true;
        }
        guard.custom_rules.push(trimmed.to_owned());
        guard.engine = compile_engine(&guard.base_rules, &guard.custom_rules);
        true
    }

    pub fn remove_custom_rule(&self, rule: &str) -> bool {
        let trimmed = rule.trim();
        let mut guard = self.state.write().expect("adblock engine lock poisoned");
        let before = guard.custom_rules.len();
        guard.custom_rules.retain(|existing| existing != trimmed);
        if guard.custom_rules.len() == before {
            return false;
        }
        guard.engine = compile_engine(&guard.base_rules, &guard.custom_rules);
        true
    }

    pub fn custom_rules(&self) -> Vec<String> {
        let guard = self.state.read().expect("adblock engine lock poisoned");
        guard.custom_rules.clone()
    }

    pub fn check_network_request(
        &self,
        url: &str,
        source_url: &str,
        request_type: &str,
    ) -> NetworkMatchResult {
        if !self.is_enabled() {
            return NetworkMatchResult::allow();
        }
        let guard = self.state.read().expect("adblock engine lock poisoned");
        if let Some(source_host) = extract_host(source_url) {
            if host_matches_allowlist(&source_host, &guard.allowed_hosts) {
                return NetworkMatchResult::allow();
            }
        }
        let Ok(request) = Request::new(url, source_url, request_type, "get") else {
            return NetworkMatchResult::allow();
        };
        let outcome = guard.engine.check_network_request(&request);
        let blocked = outcome.should_block();
        if blocked {
            self.blocked_total.fetch_add(1, Ordering::Relaxed);
        }
        NetworkMatchResult {
            blocked,
            redirect: outcome.redirect,
            matched_rule: outcome.filter.and_then(|info| info.raw_line),
            exception_rule: outcome.exception.and_then(|info| info.raw_line),
            important: outcome.important,
        }
    }

    pub fn url_cosmetic_resources(&self, url: &str) -> CosmeticMatchResult {
        if !self.is_enabled() {
            return CosmeticMatchResult::empty();
        }
        let guard = self.state.read().expect("adblock engine lock poisoned");
        if let Some(host) = extract_host(url) {
            if host_matches_allowlist(&host, &guard.allowed_hosts) {
                return CosmeticMatchResult::empty();
            }
        }
        let resources = guard.engine.url_cosmetic_resources(url);
        let mut selectors: Vec<String> = resources.hide_selectors.into_iter().collect();
        selectors.sort();
        CosmeticMatchResult {
            hide_selectors: selectors,
            injected_script: resources.injected_script,
            generic_hide: resources.generichide,
        }
    }
}

fn compile_engine(base_rules: &str, custom_rules: &[String]) -> Engine {
    let mut filter_set = FilterSet::new(true);
    let options = ParseOptions::default();
    if !base_rules.is_empty() {
        filter_set.add_filter_list(base_rules.to_owned(), options);
    }
    if !custom_rules.is_empty() {
        filter_set.add_filter_list(custom_rules.join("\n"), options);
    }
    Engine::new_with_filter_set(filter_set)
}

pub fn normalize_host(raw: &str) -> Option<String> {
    let trimmed = raw.trim().trim_end_matches('.').to_ascii_lowercase();
    if trimmed.is_empty() {
        return None;
    }
    if trimmed.contains("://") {
        extract_host(&trimmed)
    } else {
        Some(trimmed.split('/').next()?.split(':').next()?.to_owned())
    }
}

fn extract_host(url: &str) -> Option<String> {
    let after_scheme = url.split_once("://")?.1;
    let authority = after_scheme.split('/').next()?;
    let host_port = authority.rsplit('@').next()?;
    let host = if host_port.starts_with('[') {
        host_port.split(']').next()?.trim_start_matches('[')
    } else {
        host_port.split(':').next()?
    };
    let clean = host.trim_end_matches('.').to_ascii_lowercase();
    if clean.is_empty() { None } else { Some(clean) }
}

fn host_matches_allowlist(host: &str, allowlist: &BTreeSet<String>) -> bool {
    if allowlist.contains(host) {
        return true;
    }
    let mut rest = host;
    while let Some((_, parent)) = rest.split_once('.') {
        if allowlist.contains(parent) {
            return true;
        }
        rest = parent;
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    const SAMPLE_RULES: &str = r#"
! Title: Miru Test Filter List (EasyList + Chinese + Privacy)
||doubleclick.net^
||googlesyndication.com^
||ads.example.com^$third-party
||tracker.cn/beacon.js^$script
||important-ad.example.org^$important
@@||ads.example.com/allowed.js^
example.com##.ad-banner
example.com###popup-promo
news.cn##.floating-ad
"#;

    #[test]
    fn blocks_network_ads_and_honors_exceptions() {
        let engine = MiruAdblockEngine::from_rules(SAMPLE_RULES);
        let ad = engine.check_network_request(
            "https://ads.example.com/banner.js",
            "https://news.site/article",
            "script",
        );
        assert!(ad.blocked);

        let allowed = engine.check_network_request(
            "https://ads.example.com/allowed.js",
            "https://news.site/article",
            "script",
        );
        assert!(!allowed.blocked);

        let cn_tracker = engine.check_network_request(
            "https://tracker.cn/beacon.js?v=1",
            "https://portal.cn/",
            "script",
        );
        assert!(cn_tracker.blocked);

        let important = engine.check_network_request(
            "https://important-ad.example.org/pixel.gif",
            "https://site.org/",
            "image",
        );
        assert!(important.blocked);
        assert!(important.important);
        assert_eq!(engine.total_blocked(), 3);
    }

    #[test]
    fn per_site_allowlist_disables_network_and_cosmetic_for_host_and_subdomains() {
        let engine = MiruAdblockEngine::from_rules(SAMPLE_RULES);
        assert!(engine
            .check_network_request("https://doubleclick.net/ad.js", "https://m.example.com/watch", "script")
            .blocked);
        assert!(engine
            .url_cosmetic_resources("https://example.com/home")
            .hide_selectors
            .contains(&".ad-banner".to_owned()));

        engine.set_site_allowed("example.com", true);
        assert!(engine.is_site_allowed("example.com"));
        assert!(engine.is_site_allowed("m.example.com"));
        assert!(!engine
            .check_network_request("https://doubleclick.net/ad.js", "https://m.example.com/watch", "script")
            .blocked);
        assert!(engine
            .url_cosmetic_resources("https://example.com/home")
            .hide_selectors
            .is_empty());

        engine.set_site_allowed("example.com", false);
        assert!(engine
            .check_network_request("https://doubleclick.net/ad.js", "https://m.example.com/watch", "script")
            .blocked);
    }

    #[test]
    fn custom_element_picker_rule_adds_and_removes_selector() {
        let engine = MiruAdblockEngine::from_rules(SAMPLE_RULES);
        assert!(engine.add_custom_rule("video.example.org##.paywall-overlay"));
        let cosmetic = engine.url_cosmetic_resources("https://video.example.org/ep1");
        assert!(cosmetic.hide_selectors.contains(&".paywall-overlay".to_owned()));

        assert!(engine.remove_custom_rule("video.example.org##.paywall-overlay"));
        let after = engine.url_cosmetic_resources("https://video.example.org/ep1");
        assert!(!after.hide_selectors.contains(&".paywall-overlay".to_owned()));
    }

    #[test]
    fn snapshot_roundtrip_and_cold_start_benchmark() {
        let mut synthetic_rules = String::from(SAMPLE_RULES);
        for i in 0..5_000 {
            synthetic_rules.push_str(&format!("||ad-domain-{i}.example-tracker.test^$third-party\n"));
        }
        let compile_start = Instant::now();
        let engine = MiruAdblockEngine::from_rules(&synthetic_rules);
        let compile_ms = compile_start.elapsed().as_secs_f64() * 1000.0;

        let snapshot = engine.serialize_snapshot();
        assert!(!snapshot.is_empty());

        let deser_start = Instant::now();
        let restored = MiruAdblockEngine::from_snapshot(&snapshot).expect("snapshot deserialize");
        let deser_ms = deser_start.elapsed().as_secs_f64() * 1000.0;

        let match_start = Instant::now();
        let iterations = 1_000;
        let mut hits = 0usize;
        for i in 0..iterations {
            let target = if i % 2 == 0 {
                format!("https://ad-domain-{}.example-tracker.test/a.js", i % 5_000)
            } else {
                format!("https://cdn.clean-site.test/chunk-{i}.js")
            };
            if restored.check_network_request(&target, "https://example.com/", "script").blocked {
                hits += 1;
            }
        }
        let avg_match_us = match_start.elapsed().as_secs_f64() * 1_000_000.0 / (iterations as f64);
        assert_eq!(hits, iterations / 2);
        eprintln!(
            "adblock-rust 5k-rule prototype: compile={compile_ms:.2}ms snapshot={}B deserialize={deser_ms:.2}ms avg_match={avg_match_us:.2}us",
            snapshot.len()
        );
    }
}
