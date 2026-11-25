// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use miru_storage::{complete_inline_domain, HistoryRecord, OmniboxIndex};

use crate::jni_util::{read_jstring, to_jstring, to_optional_jstring};

unsafe fn index_ref<'a>(handle: jlong) -> Option<&'a OmniboxIndex> {
    if handle == 0 { None } else { Some(&*(handle as *const OmniboxIndex)) }
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeCompleteInlineDomainJson(
    mut env: JNIEnv, _class: JClass, input: JString, joined_urls: JString,
) -> jstring {
    let query = read_jstring(&mut env, &input);
    let joined = read_jstring(&mut env, &joined_urls);
    let matched = complete_inline_domain(&query, joined.lines());
    let json = matched.and_then(|m| serde_json::to_string(&m).ok());
    to_optional_jstring(&mut env, json.as_deref())
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeOmniboxCreate(
    _env: JNIEnv, _class: JClass,
) -> jlong {
    Box::into_raw(Box::new(OmniboxIndex::new())) as jlong
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeOmniboxReplaceHistoryJson(
    mut env: JNIEnv, _class: JClass, handle: jlong, records_json: JString,
) {
    let Some(index) = (unsafe { index_ref(handle) }) else { return };
    let raw = read_jstring(&mut env, &records_json);
    if let Ok(records) = serde_json::from_str::<Vec<HistoryRecord>>(&raw) {
        index.replace_history(records);
    }
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeOmniboxRecordVisit(
    mut env: JNIEnv, _class: JClass, handle: jlong, url: JString, title: JString, now_ms: jlong,
) {
    let Some(index) = (unsafe { index_ref(handle) }) else { return };
    let u = read_jstring(&mut env, &url);
    let t = read_jstring(&mut env, &title);
    index.record_visit(&u, &t, now_ms);
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeOmniboxCompleteDomainJson(
    mut env: JNIEnv, _class: JClass, handle: jlong, input: JString,
) -> jstring {
    let Some(index) = (unsafe { index_ref(handle) }) else { return std::ptr::null_mut() };
    let q = read_jstring(&mut env, &input);
    let json = index.complete_domain(&q).and_then(|m| serde_json::to_string(&m).ok());
    to_optional_jstring(&mut env, json.as_deref())
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeOmniboxSuggestJson(
    mut env: JNIEnv, _class: JClass, handle: jlong, query: JString, limit: jint,
) -> jstring {
    let Some(index) = (unsafe { index_ref(handle) }) else { return to_jstring(&mut env, "[]") };
    let q = read_jstring(&mut env, &query);
    let hits = index.suggest(&q, limit.max(0) as usize);
    let json = serde_json::to_string(&hits).unwrap_or_else(|_| "[]".to_owned());
    to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeOmniboxDestroy(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    if handle != 0 {
        unsafe { let _ = Box::from_raw(handle as *mut OmniboxIndex); }
    }
}
