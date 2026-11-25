// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

pub mod cache_key;
pub mod origin;
pub mod referrer;

pub use cache_key::{normalized_cache_url, strip_ephemeral_signature_params};
pub use origin::{external_data_allowed, is_browser_scheme, link_scheme, site_origin, web_fallback};
pub use referrer::{context_referrer, referrer_for_policy};
