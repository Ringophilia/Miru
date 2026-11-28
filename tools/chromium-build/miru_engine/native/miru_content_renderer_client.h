// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_CONTENT_RENDERER_CLIENT_H_
#define MIRU_ENGINE_NATIVE_MIRU_CONTENT_RENDERER_CLIENT_H_

#include <memory>

#include "content/public/renderer/content_renderer_client.h"

namespace miru {

/// Registers Android Widevine / ClearKey EME key systems in renderer processes.
class MiruContentRendererClient final : public content::ContentRendererClient {
 public:
  MiruContentRendererClient();
  MiruContentRendererClient(const MiruContentRendererClient&) = delete;
  MiruContentRendererClient& operator=(const MiruContentRendererClient&) =
      delete;
  ~MiruContentRendererClient() override;

  std::unique_ptr<media::KeySystemSupportRegistration> GetSupportedKeySystems(
      content::RenderFrame* render_frame,
      media::GetSupportedKeySystemsCB cb) override;
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_CONTENT_RENDERER_CLIENT_H_
