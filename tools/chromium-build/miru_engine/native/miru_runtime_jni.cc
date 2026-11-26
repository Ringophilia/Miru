// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include <string>
#include <utility>
#include <vector>

#include "base/android/jni_android.h"
#include "base/android/jni_array.h"
#include "base/android/jni_string.h"
#include "base/android/scoped_java_ref.h"
#include "base/functional/bind.h"
#include "miru_engine/miru_engine_jni/MiruChromiumRuntime_jni.h"
#include "miru_engine/native/miru_content_browser_client.h"
#include "third_party/jni_zero/jni_zero.h"

namespace miru {

static void JNI_MiruChromiumRuntime_SetAdblockRules(
    JNIEnv* env,
    const base::android::JavaRef<jstring>& jrules) {
  auto* client = MiruContentBrowserClient::Get();
  if (!client) {
    return;
  }
  client->SetAdblockRules(base::android::ConvertJavaStringToUTF8(env, jrules));
}

static jboolean JNI_MiruChromiumRuntime_SetAdblockSnapshot(
    JNIEnv* env,
    const base::android::JavaRef<jbyteArray>& jsnapshot) {
  auto* client = MiruContentBrowserClient::Get();
  if (!client || jsnapshot.is_null()) {
    return JNI_FALSE;
  }
  std::vector<uint8_t> bytes;
  base::android::JavaByteArrayToByteVector(env, jsnapshot, &bytes);
  return client->SetAdblockSnapshot(bytes.data(), bytes.size()) ? JNI_TRUE
                                                                : JNI_FALSE;
}

static void JNI_MiruChromiumRuntime_SetAdblockEnabled(JNIEnv*,
                                                      jboolean enabled) {
  if (auto* client = MiruContentBrowserClient::Get()) {
    client->SetAdblockEnabled(enabled != JNI_FALSE);
  }
}

static void JNI_MiruChromiumRuntime_SetSiteAdblockAllowed(
    JNIEnv* env,
    const base::android::JavaRef<jstring>& jhost,
    jboolean allowed) {
  if (auto* client = MiruContentBrowserClient::Get()) {
    client->SetSiteAdblockAllowed(
        base::android::ConvertJavaStringToUTF8(env, jhost),
        allowed != JNI_FALSE);
  }
}

static void JNI_MiruChromiumRuntime_DestroyOffTheRecordProfile(JNIEnv*) {
  if (auto* client = MiruContentBrowserClient::Get()) {
    client->DestroyOffTheRecordContext();
  }
}

static void JNI_MiruChromiumRuntime_GetCookiesForUrl(
    JNIEnv* env,
    jboolean off_the_record,
    const base::android::JavaRef<jstring>& jurl,
    jlong request_id) {
  auto* client = MiruContentBrowserClient::Get();
  if (!client) {
    Java_MiruChromiumRuntime_onNativeCookiesResult(
        env, request_id, base::android::ConvertUTF8ToJavaString(env, ""));
    return;
  }
  const std::string url = base::android::ConvertJavaStringToUTF8(env, jurl);
  client->GetCookiesForUrl(
      off_the_record != JNI_FALSE, url,
      base::BindOnce(
          [](jlong id, std::string cookie_line) {
            JNIEnv* cb_env = base::android::AttachCurrentThread();
            Java_MiruChromiumRuntime_onNativeCookiesResult(
                cb_env, id,
                base::android::ConvertUTF8ToJavaString(cb_env, cookie_line));
          },
          request_id));
}

static base::android::ScopedJavaLocalRef<jstring>
JNI_MiruChromiumRuntime_GetUserAgent(JNIEnv* env) {
  auto* client = MiruContentBrowserClient::Get();
  std::string ua = client ? client->GetUserAgent() : std::string();
  return base::android::ConvertUTF8ToJavaString(env, ua);
}

}  // namespace miru

DEFINE_JNI(MiruChromiumRuntime)
