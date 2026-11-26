// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "base/android/jni_android.h"
#include "base/android/library_loader/library_loader_hooks.h"
#include "content/public/app/content_jni_onload.h"
#include "content/public/app/content_main.h"
#include "miru_engine/native/miru_main_delegate.h"

namespace {

bool MiruNativeInitHook(
    base::android::LibraryProcessType library_process_type) {
  if (!content::android::OnJNIOnLoadInit()) {
    return false;
  }
  content::SetContentMainDelegate(new miru::MiruMainDelegate());
  return true;
}

}  // namespace

JNI_EXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  base::android::InitVM(vm);
  base::android::SetNativeInitializationHook(MiruNativeInitHook);
  return JNI_VERSION_1_4;
}
