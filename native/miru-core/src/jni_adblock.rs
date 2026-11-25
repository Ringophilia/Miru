// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use miru_adblock::MiruAdblockEngine;

use crate::jni_util::{read_jstring, to_jstring};

unsafe fn engine_ref<'a>(handle: jlong) -> Option<&'a MiruAdblockEngine> {
    if handle == 0 { None } else { Some(&*(handle as *const MiruAdblockEngine)) }
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeCreateFromRules(
    mut env: JNIEnv, _class: JClass, rules: JString,
) -> jlong {
    let text = read_jstring(&mut env, &rules);
    Box::into_raw(Box::new(MiruAdblockEngine::from_rules(&text))) as jlong
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeCreateFromSnapshot(
    env: JNIEnv, _class: JClass, snapshot: JByteArray,
) -> jlong {
    let Ok(bytes) = env.convert_byte_array(snapshot) else { return 0 };
    match MiruAdblockEngine::from_snapshot(&bytes) {
        Ok(engine) => Box::into_raw(Box::new(engine)) as jlong,
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeSerializeSnapshot(
    env: JNIEnv, _class: JClass, handle: jlong,
) -> jbyteArray {
    let Some(engine) = (unsafe { engine_ref(handle) }) else { return std::ptr::null_mut() };
    env.byte_array_from_slice(&engine.serialize_snapshot())
        .map(|a| a.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeCheckRequestJson(
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
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeCosmeticResourcesJson(
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
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeSetEnabled(
    _env: JNIEnv, _class: JClass, handle: jlong, enabled: jboolean,
) {
    if let Some(engine) = unsafe { engine_ref(handle) } {
        engine.set_enabled(enabled != JNI_FALSE);
    }
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeSetSiteAllowed(
    mut env: JNIEnv, _class: JClass, handle: jlong, host: JString, allowed: jboolean,
) {
    if let Some(engine) = unsafe { engine_ref(handle) } {
        let h = read_jstring(&mut env, &host);
        engine.set_site_allowed(&h, allowed != JNI_FALSE);
    }
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeIsSiteAllowed(
    mut env: JNIEnv, _class: JClass, handle: jlong, host: JString,
) -> jboolean {
    let Some(engine) = (unsafe { engine_ref(handle) }) else { return JNI_FALSE };
    let h = read_jstring(&mut env, &host);
    if engine.is_site_allowed(&h) { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeAddCustomRule(
    mut env: JNIEnv, _class: JClass, handle: jlong, rule: JString,
) -> jboolean {
    let Some(engine) = (unsafe { engine_ref(handle) }) else { return JNI_FALSE };
    let r = read_jstring(&mut env, &rule);
    if engine.add_custom_rule(&r) { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeRemoveCustomRule(
    mut env: JNIEnv, _class: JClass, handle: jlong, rule: JString,
) -> jboolean {
    let Some(engine) = (unsafe { engine_ref(handle) }) else { return JNI_FALSE };
    let r = read_jstring(&mut env, &rule);
    if engine.remove_custom_rule(&r) { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeCustomRulesJson(
    mut env: JNIEnv, _class: JClass, handle: jlong,
) -> jstring {
    let Some(engine) = (unsafe { engine_ref(handle) }) else { return to_jstring(&mut env, "[]") };
    let out = serde_json::to_string(&engine.custom_rules()).unwrap_or_else(|_| "[]".to_owned());
    to_jstring(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeDestroy(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    if handle != 0 {
        unsafe { let _ = Box::from_raw(handle as *mut MiruAdblockEngine); }
    }
}
