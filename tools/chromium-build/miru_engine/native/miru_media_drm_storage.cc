// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_media_drm_storage.h"

#include <memory>
#include <utility>

#include "base/containers/flat_map.h"
#include "base/no_destructor.h"
#include "content/public/browser/browser_context.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/render_frame_host.h"
#include "mojo/public/cpp/bindings/self_owned_receiver.h"

namespace miru {

namespace {

struct DrmBucket {
  base::flat_map<std::string, base::UnguessableToken> origin_ids;
  base::flat_map<std::string, media::mojom::SessionDataPtr> sessions;
};

DrmBucket& NormalBucket() {
  static base::NoDestructor<DrmBucket> bucket;
  return *bucket;
}

DrmBucket& PrivateBucket() {
  static base::NoDestructor<DrmBucket> bucket;
  return *bucket;
}

DrmBucket& Bucket(bool off_the_record) {
  return off_the_record ? PrivateBucket() : NormalBucket();
}

}  // namespace

// static
void MiruMediaDrmStorage::Create(
    content::RenderFrameHost* render_frame_host,
    mojo::PendingReceiver<media::mojom::MediaDrmStorage> receiver) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!render_frame_host ||
      render_frame_host->GetLastCommittedOrigin().opaque()) {
    return;
  }
  const bool off_the_record =
      render_frame_host->GetBrowserContext() &&
      render_frame_host->GetBrowserContext()->IsOffTheRecord();
  const std::string origin_spec =
      render_frame_host->GetLastCommittedOrigin().Serialize();
  mojo::MakeSelfOwnedReceiver(
      std::make_unique<MiruMediaDrmStorage>(origin_spec, off_the_record),
      std::move(receiver));
}

// static
void MiruMediaDrmStorage::ClearOffTheRecord() {
  PrivateBucket().origin_ids.clear();
  PrivateBucket().sessions.clear();
}

MiruMediaDrmStorage::MiruMediaDrmStorage(std::string origin_spec,
                                         bool off_the_record)
    : origin_spec_(std::move(origin_spec)), off_the_record_(off_the_record) {}

MiruMediaDrmStorage::~MiruMediaDrmStorage() = default;

void MiruMediaDrmStorage::Initialize(InitializeCallback callback) {
  DrmBucket& bucket = Bucket(off_the_record_);
  auto [it, inserted] = bucket.origin_ids.try_emplace(
      origin_spec_, base::UnguessableToken::Create());
  std::move(callback).Run(true, it->second);
}

void MiruMediaDrmStorage::OnProvisioned(OnProvisionedCallback callback) {
  std::move(callback).Run(true);
}

void MiruMediaDrmStorage::SavePersistentSession(
    const std::string& session_id,
    media::mojom::SessionDataPtr session_data,
    SavePersistentSessionCallback callback) {
  if (off_the_record_ || !session_data) {
    std::move(callback).Run(false);
    return;
  }
  Bucket(false).sessions[origin_spec_ + "|" + session_id] =
      std::move(session_data);
  std::move(callback).Run(true);
}

void MiruMediaDrmStorage::LoadPersistentSession(
    const std::string& session_id,
    LoadPersistentSessionCallback callback) {
  if (off_the_record_) {
    std::move(callback).Run(nullptr);
    return;
  }
  auto& sessions = Bucket(false).sessions;
  auto it = sessions.find(origin_spec_ + "|" + session_id);
  std::move(callback).Run(it != sessions.end() && it->second
                              ? it->second.Clone()
                              : nullptr);
}

void MiruMediaDrmStorage::RemovePersistentSession(
    const std::string& session_id,
    RemovePersistentSessionCallback callback) {
  if (!off_the_record_) {
    Bucket(false).sessions.erase(origin_spec_ + "|" + session_id);
  }
  std::move(callback).Run(true);
}

}  // namespace miru
