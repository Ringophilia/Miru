// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use miru_adblock::MiruAdblockEngine;

unsafe fn engine_ref<'a>(handle: jlong) -> Option<&'a MiruAdblockEngine> {
    if handle == 0 { None } else { Some(&*(handle as *const MiruAdblockEngine)) }
}

fn read_jstring(env: &mut JNIEnv, input: &JString) -> String {
    if input.is_null() {
        return String::new();
    }
    env.get_string(input).map(|s| s.into()).unwrap_or_default()
}

fn to_jstring(env: &mut JNIEnv, text: &str) -> jstring {
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

// --- Adblock JNI ---

#[no_mangle]
pub extern "system" fn Java_app_miru_adblock_rust_NativeAdblockBridge_nativeCreateFromRules(
    mut env: JNIEnv, _class: JClass, rules: JString,
) -> jlong {
    let text = read_jstring(&mut env, &rules);
    Box::into_raw(Box::new(MiruAdblockEngine::from_rules(&text))) as jlong
}

#[no_mangle]
pub extern "system" fn Java_app_miru_adblock_rust_NativeAdblockBridge_nativeCreateFromSnapshot(
    env: JNIEnv, _class: JClass, snapshot: JByteArray,
) -> jlong {
    let Ok(bytes) = env.convert_byte_array(snapshot) else { return 0 };
    match MiruAdblockEngine::from_snapshot(&bytes) {
        Ok(engine) => Box::into_raw(Box::new(engine)) as jlong,
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_miru_adblock_rust_NativeAdblockBridge_nativeSerializeSnapshot(
    env: JNIEnv, _class: JClass, handle: jlong,
) -> jbyteArray {
    let Some(engine) = (unsafe { engine_ref(handle) }) else { return std::ptr::null_mut() };
    env.byte_array_from_slice(&engine.serialize_snapshot())
        .map(|a| a.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_app_miru_adblock_rust_NativeAdblockBridge_nativeCheckRequestJson(
    mut env: JNIEnv, _class: JClass, handle: jlong, url: JString, source_url: JString, request_type: JString,
) -> jstring {
    let Some(engine) = (unsafe { engine_ref(handle) }) else {
        return to_jstring(&mut env, "{\"blocked\":false,\"important\":false}");
    };
    let u = read_jstring(&mut env, &url);
    let s = read_jstring(&mut env, &source_url);
    let r = read_jstring(&mut env, &request_type);
    let out = serde_json::to_string(&engine.check_network_request(&u, &s, &r))
        .unwrap_or_else(|_| "{\"blocked\":false,\"important\":false}".to_owned());
    to_jstring(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_app_miru_adblock_rust_NativeAdblockBridge_nativeCosmeticResourcesJson(
    mut env: JNIEnv, _class: JClass, handle: jlong, url: JString,
) -> jstring {
    let Some(engine) = (unsafe { engine_ref(handle) }) else {
        return to_jstring(&mut env, "{\"hide_selectors\":[],\"injected_script\":\"\",\"generic_hide\":false}");
    };
    let u = read_jstring(&mut env, &url);
    let out = serde_json::to_string(&engine.url_cosmetic_resources(&u))
        .unwrap_or_else(|_| "{\"hide_selectors\":[],\"injected_script\":\"\",\"generic_hide\":false}".to_owned());
    to_jstring(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_app_miru_adblock_rust_NativeAdblockBridge_nativeSetEnabled(
    _env: JNIEnv, _class: JClass, handle: jlong, enabled: jboolean,
) {
    if let Some(engine) = unsafe { engine_ref(handle) } { engine.set_enabled(enabled != JNI_FALSE); }
}

#[no_mangle]
pub extern "system" fn Java_app_miru_adblock_rust_NativeAdblockBridge_nativeSetSiteAllowed(
    mut env: JNIEnv, _class: JClass, handle: jlong, host: JString, allowed: jboolean,
) {
    if let Some(engine) = unsafe { engine_ref(handle) } {
        let h = read_jstring(&mut env, &host);
        engine.set_site_allowed(&h, allowed != JNI_FALSE);
    }
}

#[no_mangle]
pub extern "system" fn Java_app_miru_adblock_rust_NativeAdblockBridge_nativeIsSiteAllowed(
    mut env: JNIEnv, _class: JClass, handle: jlong, host: JString,
) -> jboolean {
    let Some(engine) = (unsafe { engine_ref(handle) }) else { return JNI_FALSE };
    let h = read_jstring(&mut env, &host);
    if engine.is_site_allowed(&h) { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_app_miru_adblock_rust_NativeAdblockBridge_nativeAddCustomRule(
    mut env: JNIEnv, _class: JClass, handle: jlong, rule: JString,
) -> jboolean {
    let Some(engine) = (unsafe { engine_ref(handle) }) else { return JNI_FALSE };
    let r = read_jstring(&mut env, &rule);
    if engine.add_custom_rule(&r) { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_app_miru_adblock_rust_NativeAdblockBridge_nativeRemoveCustomRule(
    mut env: JNIEnv, _class: JClass, handle: jlong, rule: JString,
) -> jboolean {
    let Some(engine) = (unsafe { engine_ref(handle) }) else { return JNI_FALSE };
    let r = read_jstring(&mut env, &rule);
    if engine.remove_custom_rule(&r) { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_app_miru_adblock_rust_NativeAdblockBridge_nativeCustomRulesJson(
    mut env: JNIEnv, _class: JClass, handle: jlong,
) -> jstring {
    let Some(engine) = (unsafe { engine_ref(handle) }) else { return to_jstring(&mut env, "[]") };
    let out = serde_json::to_string(&engine.custom_rules()).unwrap_or_else(|_| "[]".to_owned());
    to_jstring(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_app_miru_adblock_rust_NativeAdblockBridge_nativeDestroy(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    if handle != 0 { unsafe { let _ = Box::from_raw(handle as *mut MiruAdblockEngine); } }
}

// --- Media & URL JNI (unified libmiru_core) ---

#[no_mangle]
pub extern "system" fn Java_app_miru_adblock_rust_NativeAdblockBridge_nativeClassifyMedia(
    mut env: JNIEnv, _class: JClass, url: JString, content_type: JString, content_length: jlong,
) -> jstring {
    let u = read_jstring(&mut env, &url);
    let ct = read_jstring(&mut env, &content_type);
    let ct_opt = (!ct.is_empty()).then_some(ct.as_str());
    let len_opt = (content_length > 0).then_some(content_length as u64);
    match miru_media::classify_media(&u, ct_opt, len_opt) {
        Some(kind) => to_jstring(&mut env, kind.as_str()),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_miru_adblock_rust_NativeAdblockBridge_nativeProbeMediaBytesJson(
    mut env: JNIEnv, _class: JClass, bytes: JByteArray,
) -> jstring {
    let Ok(raw) = env.convert_byte_array(bytes) else { return std::ptr::null_mut() };
    match miru_media::probe_media_bytes(&raw) {
        Some(res) => {
            let json = serde_json::to_string(&res).unwrap_or_default();
            to_jstring(&mut env, &json)
        }
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_miru_adblock_rust_NativeAdblockBridge_nativeStripCacheKeySignatures(
    mut env: JNIEnv, _class: JClass, url: JString,
) -> jstring {
    let u = read_jstring(&mut env, &url);
    to_jstring(&mut env, &miru_url::strip_ephemeral_signature_params(&u))
}

#[no_mangle]
pub extern "system" fn Java_app_miru_adblock_rust_NativeAdblockBridge_nativeSiteOrigin(
    mut env: JNIEnv, _class: JClass, url: JString,
) -> jstring {
    let u = read_jstring(&mut env, &url);
    match miru_url::site_origin(&u) {
        Some(origin) => to_jstring(&mut env, &origin),
        None => std::ptr::null_mut(),
    }
}
