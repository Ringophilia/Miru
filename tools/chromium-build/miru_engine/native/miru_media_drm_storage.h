// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_MEDIA_DRM_STORAGE_H_
#define MIRU_ENGINE_NATIVE_MIRU_MEDIA_DRM_STORAGE_H_

#include <string>

#include "base/unguessable_token.h"
#include "media/mojo/mojom/media_drm_storage.mojom.h"
#include "mojo/public/cpp/bindings/pending_receiver.h"

namespace content {
class RenderFrameHost;
}

namespace miru {

class MiruMediaDrmStorage final : public media::mojom::MediaDrmStorage {
 public:
  static void Create(
      content::RenderFrameHost* render_frame_host,
      mojo::PendingReceiver<media::mojom::MediaDrmStorage> receiver);
  static void ClearOffTheRecord();

  MiruMediaDrmStorage(std::string origin_spec, bool off_the_record);
  MiruMediaDrmStorage(const MiruMediaDrmStorage&) = delete;
  MiruMediaDrmStorage& operator=(const MiruMediaDrmStorage&) = delete;
  ~MiruMediaDrmStorage() override;

  // media::mojom::MediaDrmStorage:
  void Initialize(InitializeCallback callback) override;
  void OnProvisioned(OnProvisionedCallback callback) override;
  void SavePersistentSession(const std::string& session_id,
                             media::mojom::SessionDataPtr session_data,
                             SavePersistentSessionCallback callback) override;
  void LoadPersistentSession(const std::string& session_id,
                             LoadPersistentSessionCallback callback) override;
  void RemovePersistentSession(
      const std::string& session_id,
      RemovePersistentSessionCallback callback) override;

 private:
  const std::string origin_spec_;
  const bool off_the_record_;
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_MEDIA_DRM_STORAGE_H_
