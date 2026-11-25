// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use crate::jni_util::{read_jstring, read_optional_jstring, to_jstring, to_optional_jstring};

fn pack_resolution(res: Option<miru_media::MediaResolution>) -> jlong {
    match res {
        Some(r) => ((r.width as jlong) << 32) | ((r.height as jlong) & 0xffff_ffff),
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeClassifyMedia(
    mut env: JNIEnv, _class: JClass, url: JString, content_type: JString, content_length: jlong,
) -> jstring {
    let u = read_jstring(&mut env, &url);
    let ct = read_optional_jstring(&mut env, &content_type);
    let len_opt = (content_length > 0).then_some(content_length as u64);
    let kind = miru_media::classify_media(&u, ct.as_deref(), len_opt).map(|k| k.as_str());
    to_optional_jstring(&mut env, kind)
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeMediaDedupKey(
    mut env: JNIEnv, _class: JClass, url: JString,
) -> jstring {
    let u = read_jstring(&mut env, &url);
    let key = miru_media::dedup_key(&u);
    to_optional_jstring(&mut env, key.as_deref())
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeProbeMediaBytesJson(
    mut env: JNIEnv, _class: JClass, bytes: JByteArray,
) -> jstring {
    let Ok(raw) = env.convert_byte_array(bytes) else { return std::ptr::null_mut() };
    let json = miru_media::probe_media_bytes(&raw).and_then(|r| serde_json::to_string(&r).ok());
    to_optional_jstring(&mut env, json.as_deref())
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeProbeMediaBytesPacked(
    env: JNIEnv, _class: JClass, bytes: JByteArray,
) -> jlong {
    let Ok(raw) = env.convert_byte_array(bytes) else { return 0 };
    pack_resolution(miru_media::probe_media_bytes(&raw))
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeProbeMp4Packed(
    env: JNIEnv, _class: JClass, bytes: JByteArray,
) -> jlong {
    let Ok(raw) = env.convert_byte_array(bytes) else { return 0 };
    pack_resolution(miru_media::probe_mp4(&raw))
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeProbeHlsPacked(
    mut env: JNIEnv, _class: JClass, playlist: JString,
) -> jlong {
    let text = read_jstring(&mut env, &playlist);
    pack_resolution(miru_media::probe_hls_manifest(&text))
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeNormalizedCacheUrl(
    mut env: JNIEnv, _class: JClass, url: JString, is_range: jboolean,
) -> jstring {
    let u = read_jstring(&mut env, &url);
    to_jstring(&mut env, &miru_url::normalized_cache_url(&u, is_range != JNI_FALSE))
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeStripCacheKeySignatures(
    mut env: JNIEnv, _class: JClass, url: JString,
) -> jstring {
    let u = read_jstring(&mut env, &url);
    to_jstring(&mut env, &miru_url::strip_ephemeral_signature_params(&u))
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeSiteOrigin(
    mut env: JNIEnv, _class: JClass, url: JString,
) -> jstring {
    let u = read_optional_jstring(&mut env, &url);
    let origin = u.as_deref().and_then(miru_url::site_origin);
    to_optional_jstring(&mut env, origin.as_deref())
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeReferrerForPolicy(
    mut env: JNIEnv, _class: JClass, document_url: JString, target_url: JString, policy: JString,
) -> jstring {
    let doc = read_jstring(&mut env, &document_url);
    let target = read_jstring(&mut env, &target_url);
    let pol = read_jstring(&mut env, &policy);
    let result = miru_url::referrer_for_policy(&doc, &target, &pol);
    to_optional_jstring(&mut env, result.as_deref())
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeContextReferrer(
    mut env: JNIEnv, _class: JClass, page_url: JString, target_url: JString, captured_referrer: JString,
) -> jstring {
    let page = read_jstring(&mut env, &page_url);
    let target = read_jstring(&mut env, &target_url);
    let captured = read_optional_jstring(&mut env, &captured_referrer);
    let result = miru_url::context_referrer(&page, &target, captured.as_deref());
    to_optional_jstring(&mut env, result.as_deref())
}

#[no_mangle]
pub extern "system" fn Java_app_miru_nativecore_MiruNativeBridge_nativeExternalDataAllowed(
    mut env: JNIEnv, _class: JClass, url: JString, has_package: jboolean,
) -> jboolean {
    let u = read_jstring(&mut env, &url);
    if miru_url::external_data_allowed(&u, has_package != JNI_FALSE) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
