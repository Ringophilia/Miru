// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "base/android/feature_map.h"
#include "base/no_destructor.h"
#include "components/content_settings/core/common/features.h"
#include "components/permissions/android/core_jni/PermissionsAndroidFeatureMap_jni.h"

namespace permissions {

namespace {

const base::Feature* const kFeaturesExposedToJava[] = {
    &content_settings::features::kApproximateGeolocationPermission,
};

base::android::FeatureMap* GetFeatureMap() {
  static base::NoDestructor<base::android::FeatureMap> kFeatureMap(
      kFeaturesExposedToJava);
  return kFeatureMap.get();
}

}  // namespace

static jlong JNI_PermissionsAndroidFeatureMap_GetNativeMap(JNIEnv* env) {
  return reinterpret_cast<jlong>(GetFeatureMap());
}

}  // namespace permissions

DEFINE_JNI(PermissionsAndroidFeatureMap)
