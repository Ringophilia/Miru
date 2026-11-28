// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_content_renderer_client.h"

#include <utility>

#include "components/cdm/renderer/key_system_support_update.h"

namespace miru {

MiruContentRendererClient::MiruContentRendererClient() = default;
MiruContentRendererClient::~MiruContentRendererClient() = default;

std::unique_ptr<media::KeySystemSupportRegistration>
MiruContentRendererClient::GetSupportedKeySystems(
    content::RenderFrame* render_frame,
    media::GetSupportedKeySystemsCB cb) {
  return cdm::GetSupportedKeySystemsUpdates(
      render_frame, /*can_persist_data=*/true, std::move(cb));
}

}  // namespace miru
