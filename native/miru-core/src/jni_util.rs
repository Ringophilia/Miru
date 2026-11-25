// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

use jni::objects::JString;
use jni::sys::jstring;
use jni::JNIEnv;

pub(crate) fn read_jstring(env: &mut JNIEnv, input: &JString) -> String {
    if input.is_null() {
        return String::new();
    }
    env.get_string(input).map(|s| s.into()).unwrap_or_default()
}

pub(crate) fn read_optional_jstring(env: &mut JNIEnv, input: &JString) -> Option<String> {
    if input.is_null() {
        None
    } else {
        Some(env.get_string(input).map(|s| s.into()).unwrap_or_default())
    }
}

pub(crate) fn to_jstring(env: &mut JNIEnv, text: &str) -> jstring {
    env.new_string(text).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

pub(crate) fn to_optional_jstring(env: &mut JNIEnv, value: Option<&str>) -> jstring {
    match value {
        Some(text) => to_jstring(env, text),
        None => std::ptr::null_mut(),
    }
}
