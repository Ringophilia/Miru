// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_MEDIA_ACCESS_HANDLER_H_
#define MIRU_ENGINE_NATIVE_MIRU_MEDIA_ACCESS_HANDLER_H_

#include <cstdint>

#include "content/public/browser/media_stream_request.h"
#include "content/public/common/content_client.h"

namespace miru {

class MiruTab;

/// Bridges `WebContentsDelegate::RequestMediaAccessPermission` to Kotlin and
/// binds Chromium's `MediaCaptureDevices` hardware list upon consent.
class MiruMediaAccessHandler final {
 public:
  static bool Check(MiruTab* tab,
                    const url::Origin& security_origin,
                    blink::mojom::MediaStreamType type);
  static void Request(MiruTab* tab,
                      const content::MediaStreamRequest& request,
                      content::MediaResponseCallback callback);
  static void Resolve(int64_t request_id, bool allowed);
  static void CancelForTab(MiruTab* tab);
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_MEDIA_ACCESS_HANDLER_H_
