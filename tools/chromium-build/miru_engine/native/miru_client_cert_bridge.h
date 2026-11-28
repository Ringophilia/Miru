// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_CLIENT_CERT_BRIDGE_H_
#define MIRU_ENGINE_NATIVE_MIRU_CLIENT_CERT_BRIDGE_H_

#include <jni.h>
#include <cstdint>
#include <memory>

#include "base/android/scoped_java_ref.h"
#include "base/functional/callback.h"

namespace content {
class ClientCertificateDelegate;
class WebContents;
}  // namespace content

namespace net {
class SSLCertRequestInfo;
}  // namespace net

namespace miru {

class MiruTab;

class MiruClientCertBridge {
 public:
  static base::OnceClosure SelectClientCertificate(
      content::WebContents* web_contents,
      net::SSLCertRequestInfo* cert_request_info,
      std::unique_ptr<content::ClientCertificateDelegate> delegate);

  static void Resolve(JNIEnv* env,
                      int64_t request_id,
                      const base::android::JavaRef<jobjectArray>& encoded_chain_ref,
                      const base::android::JavaRef<jobject>& private_key_ref);

  static void CancelForTab(MiruTab* tab);
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_CLIENT_CERT_BRIDGE_H_
