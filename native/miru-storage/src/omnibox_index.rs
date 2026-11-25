// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

use std::sync::RwLock;

use serde::{Deserialize, Serialize};

use crate::inline_domain::{
    extract_typed_scheme_and_prefix, match_authority, parse_http_authority, InlineDomainMatch,
    ParsedHttpAuthority,
};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HistoryRecord {
    pub url: String,
    pub title: String,
    pub last_visited: i64,
    pub visit_count: i32,
}

#[derive(Debug, Clone)]
struct IndexedEntry {
    record: HistoryRecord,
    url_lower: String,
    title_lower: String,
    authority: Option<ParsedHttpAuthority>,
    host_no_www: String,
}

impl IndexedEntry {
    fn from_record(record: HistoryRecord) -> Self {
        let authority = parse_http_authority(&record.url);
        let host_no_www = authority
            .as_ref()
            .map(|a| a.host_lower.strip_prefix("www.").unwrap_or(&a.host_lower).to_owned())
            .unwrap_or_default();
        let url_lower = record.url.to_lowercase();
        let title_lower = record.title.to_lowercase();
        Self { record, url_lower, title_lower, authority, host_no_www }
    }
}

/// Thread-safe in-memory index for instant omnibox completion & history ranking.
#[derive(Default)]
pub struct OmniboxIndex {
    entries: RwLock<Vec<IndexedEntry>>,
}

impl OmniboxIndex {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn replace_history(&self, mut records: Vec<HistoryRecord>) {
        // Order most recent first so inline completion prefers fresh/frequent history.
        records.sort_by(|a, b| b.last_visited.cmp(&a.last_visited));
        let indexed: Vec<IndexedEntry> = records.into_iter().map(IndexedEntry::from_record).collect();
        if let Ok(mut guard) = self.entries.write() {
            *guard = indexed;
        }
    }

    pub fn record_visit(&self, url: &str, title: &str, now: i64) {
        let Ok(mut guard) = self.entries.write() else { return };
        if let Some(pos) = guard.iter().position(|e| e.record.url == url) {
            let mut existing = guard.remove(pos).record;
            existing.last_visited = now;
            existing.visit_count = existing.visit_count.saturating_add(1);
            if !title.is_empty() {
                existing.title = title.to_owned();
            }
            guard.insert(0, IndexedEntry::from_record(existing));
        } else {
            guard.insert(
                0,
                IndexedEntry::from_record(HistoryRecord {
                    url: url.to_owned(),
                    title: title.to_owned(),
                    last_visited: now,
                    visit_count: 1,
                }),
            );
        }
    }

    pub fn complete_domain(&self, input: &str) -> Option<InlineDomainMatch> {
        let (scheme, prefix) = extract_typed_scheme_and_prefix(input)?;
        let guard = self.entries.read().ok()?;
        for entry in guard.iter() {
            let Some(parsed) = entry.authority.as_ref() else { continue };
            if let Some(matched) = match_authority(scheme, prefix, parsed) {
                return Some(matched);
            }
        }
        None
    }

    pub fn suggest(&self, query: &str, limit: usize) -> Vec<HistoryRecord> {
        if limit == 0 {
            return Vec::new();
        }
        let Ok(guard) = self.entries.read() else { return Vec::new() };
        let trimmed = query.trim();
        if trimmed.is_empty() {
            let mut all: Vec<&IndexedEntry> = guard.iter().collect();
            all.sort_by(|a, b| {
                b.record
                    .visit_count
                    .cmp(&a.record.visit_count)
                    .then_with(|| b.record.last_visited.cmp(&a.record.last_visited))
            });
            return all.into_iter().take(limit).map(|e| e.record.clone()).collect();
        }

        let needle = trimmed.to_lowercase();
        let host_prefix = strip_host_prefix(&needle);
        let mut matches: Vec<(bool, i32, i64, &HistoryRecord)> = Vec::new();
        for entry in guard.iter() {
            if !entry.url_lower.contains(&needle) && !entry.title_lower.contains(&needle) {
                continue;
            }
            let prefix_hit = !host_prefix.is_empty()
                && entry
                    .authority
                    .as_ref()
                    .is_some_and(|a| a.host_lower.starts_with(host_prefix) || entry.host_no_www.starts_with(host_prefix));
            matches.push((
                prefix_hit,
                entry.record.visit_count,
                entry.record.last_visited,
                &entry.record,
            ));
        }

        matches.sort_by(|a, b| {
            b.0.cmp(&a.0)
                .then_with(|| b.1.cmp(&a.1))
                .then_with(|| b.2.cmp(&a.2))
        });
        matches.into_iter().take(limit).map(|m| m.3.clone()).collect()
    }
}

fn strip_host_prefix(lower: &str) -> &str {
    let no_scheme = lower
        .strip_prefix("https://")
        .or_else(|| lower.strip_prefix("http://"))
        .unwrap_or(lower);
    no_scheme.strip_prefix("www.").unwrap_or(no_scheme)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    #[test]
    fn ranks_prefix_hosts_and_supports_cjk_queries() {
        let index = OmniboxIndex::new();
        index.replace_history(vec![
            HistoryRecord {
                url: "https://search.example/q?v=git".to_owned(),
                title: "Web Search".to_owned(),
                last_visited: 300,
                visit_count: 20,
            },
            HistoryRecord {
                url: "https://github.com/miru".to_owned(),
                title: "Miru Repo".to_owned(),
                last_visited: 200,
                visit_count: 5,
            },
            HistoryRecord {
                url: "https://zh.wikipedia.org/wiki/浏览器".to_owned(),
                title: "开源浏览器".to_owned(),
                last_visited: 400,
                visit_count: 3,
            },
        ]);
        let git_hits = index.suggest("git", 3);
        assert_eq!(git_hits[0].url, "https://github.com/miru");
        let cjk_hits = index.suggest("浏览器", 3);
        assert_eq!(cjk_hits.len(), 1);
        assert_eq!(cjk_hits[0].title, "开源浏览器");
    }

    #[test]
    fn scans_5000_entries_quickly_and_updates_visits() {
        let index = OmniboxIndex::new();
        let records: Vec<HistoryRecord> = (0..5000)
            .map(|i| HistoryRecord {
                url: format!("https://site{i}.example.org/page/{i}"),
                title: format!("Site {i} Title"),
                last_visited: i as i64,
                visit_count: (i % 7) as i32 + 1,
            })
            .collect();
        index.replace_history(records);
        index.record_visit("https://rust-lang.org/learn", "Rust Learn", 99999);

        let start = Instant::now();
        let domain = index.complete_domain("rust");
        let hits = index.suggest("site499", 5);
        let elapsed = start.elapsed();

        assert_eq!(domain.map(|d| d.url), Some("https://rust-lang.org".to_owned()));
        assert!(!hits.is_empty());
        assert!(elapsed.as_millis() < 100, "too slow: {:?}", elapsed);
    }
}
