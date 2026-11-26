// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include <string>
#include <vector>

#include "base/android/jni_android.h"
#include "base/android/jni_array.h"
#include "base/android/jni_string.h"
#include "base/android/scoped_java_ref.h"
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

}  // namespace miru

DEFINE_JNI(MiruChromiumRuntime)
