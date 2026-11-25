// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::ptr;
use std::slice;

use miru_adblock::MiruAdblockEngine;

/// Opaque C handle owned by Chromium C++ (Phase C2 URLLoaderThrottle / NavigationThrottle).
pub struct MiruAdblockHandle(MiruAdblockEngine);

#[repr(C)]
pub struct MiruBytes {
    pub data: *mut u8,
    pub len: usize,
    pub cap: usize,
}

#[repr(C)]
pub struct MiruResolution {
    pub width: u32,
    pub height: u32,
    pub valid: c_int,
}

unsafe fn str_from_ptr<'a>(ptr: *const c_char) -> Option<&'a str> {
    if ptr.is_null() { None } else { CStr::from_ptr(ptr).to_str().ok() }
}

fn alloc_cstring(value: &str) -> *mut c_char {
    CString::new(value).map(CString::into_raw).unwrap_or(ptr::null_mut())
}

// --- Adblock C ABI ---

#[no_mangle]
pub unsafe extern "C" fn miru_adblock_create_from_rules(rules_utf8: *const c_char) -> *mut MiruAdblockHandle {
    let rules = str_from_ptr(rules_utf8).unwrap_or("");
    Box::into_raw(Box::new(MiruAdblockHandle(MiruAdblockEngine::from_rules(rules))))
}

#[no_mangle]
pub unsafe extern "C" fn miru_adblock_create_from_snapshot(data: *const u8, len: usize) -> *mut MiruAdblockHandle {
    if data.is_null() || len == 0 {
        return ptr::null_mut();
    }
    match MiruAdblockEngine::from_snapshot(slice::from_raw_parts(data, len)) {
        Ok(engine) => Box::into_raw(Box::new(MiruAdblockHandle(engine))),
        Err(_) => ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "C" fn miru_adblock_serialize_snapshot(handle: *const MiruAdblockHandle) -> MiruBytes {
    if handle.is_null() {
        return MiruBytes { data: ptr::null_mut(), len: 0, cap: 0 };
    }
    let mut bytes = (*handle).0.serialize_snapshot();
    let out = MiruBytes { data: bytes.as_mut_ptr(), len: bytes.len(), cap: bytes.capacity() };
    std::mem::forget(bytes);
    out
}

#[no_mangle]
pub unsafe extern "C" fn miru_bytes_free(bytes: MiruBytes) {
    if !bytes.data.is_null() && bytes.cap > 0 {
        let _ = Vec::from_raw_parts(bytes.data, bytes.len, bytes.cap);
    }
}

#[no_mangle]
pub unsafe extern "C" fn miru_string_free(ptr: *mut c_char) {
    if !ptr.is_null() {
        let _ = CString::from_raw(ptr);
    }
}

/// Fast path for Chromium URLLoaderThrottle: returns 1 if blocked, 0 if allowed.
#[no_mangle]
pub unsafe extern "C" fn miru_adblock_should_block(
    handle: *const MiruAdblockHandle,
    url_utf8: *const c_char,
    source_url_utf8: *const c_char,
    request_type_utf8: *const c_char,
) -> c_int {
    if handle.is_null() { return 0; }
    let Some(url) = str_from_ptr(url_utf8) else { return 0 };
    let source = str_from_ptr(source_url_utf8).unwrap_or("");
    let rtype = str_from_ptr(request_type_utf8).unwrap_or("other");
    if (*handle).0.check_network_request(url, source, rtype).blocked { 1 } else { 0 }
}

#[no_mangle]
pub unsafe extern "C" fn miru_adblock_cosmetic_json(
    handle: *const MiruAdblockHandle,
    url_utf8: *const c_char,
) -> *mut c_char {
    if handle.is_null() { return ptr::null_mut(); }
    let Some(url) = str_from_ptr(url_utf8) else { return ptr::null_mut() };
    let res = (*handle).0.url_cosmetic_resources(url);
    serde_json::to_string(&res).ok().map(|s| alloc_cstring(&s)).unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn miru_adblock_set_enabled(handle: *const MiruAdblockHandle, enabled: c_int) {
    if !handle.is_null() { (*handle).0.set_enabled(enabled != 0); }
}

#[no_mangle]
pub unsafe extern "C" fn miru_adblock_set_site_allowed(
    handle: *const MiruAdblockHandle,
    host_utf8: *const c_char,
    allowed: c_int,
) {
    if !handle.is_null() {
        if let Some(host) = str_from_ptr(host_utf8) {
            (*handle).0.set_site_allowed(host, allowed != 0);
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn miru_adblock_destroy(handle: *mut MiruAdblockHandle) {
    if !handle.is_null() { let _ = Box::from_raw(handle); }
}

// --- Media Sniffer & Resolution C ABI (for Chromium C++ URLLoaderThrottle) ---

/// Returns newly allocated kind string ("HLS" / "DASH" / "VIDEO" / "AUDIO") or null if ignored.
#[no_mangle]
pub unsafe extern "C" fn miru_media_classify(
    url_utf8: *const c_char,
    content_type_utf8: *const c_char,
    content_length: i64,
) -> *mut c_char {
    let Some(url) = str_from_ptr(url_utf8) else { return ptr::null_mut() };
    let ct = str_from_ptr(content_type_utf8);
    let len = if content_length > 0 { Some(content_length as u64) } else { None };
    match miru_media::classify_media(url, ct, len) {
        Some(kind) => alloc_cstring(kind.as_str()),
        None => ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "C" fn miru_media_probe_bytes(data: *const u8, len: usize) -> MiruResolution {
    if data.is_null() || len == 0 {
        return MiruResolution { width: 0, height: 0, valid: 0 };
    }
    match miru_media::probe_media_bytes(slice::from_raw_parts(data, len)) {
        Some(r) => MiruResolution { width: r.width, height: r.height, valid: 1 },
        None => MiruResolution { width: 0, height: 0, valid: 0 },
    }
}

// --- URL & Cache Key C ABI ---

#[no_mangle]
pub unsafe extern "C" fn miru_url_strip_signature_params(url_utf8: *const c_char) -> *mut c_char {
    let Some(url) = str_from_ptr(url_utf8) else { return ptr::null_mut() };
    alloc_cstring(&miru_url::strip_ephemeral_signature_params(url))
}
