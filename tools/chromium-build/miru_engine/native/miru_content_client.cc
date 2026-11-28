// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_content_client.h"

#include "base/no_destructor.h"
#include "components/cdm/common/android_cdm_registration.h"
#include "components/cdm/common/clearkey_drm_delegate_android.h"
#include "components/cdm/common/widevine_drm_delegate_android.h"
#include "media/base/android/media_drm_bridge_client.h"
#include "third_party/widevine/cdm/buildflags.h"
#include "ui/base/l10n/l10n_util.h"
#include "ui/base/resource/resource_bundle.h"
#include "url/url_constants.h"

namespace miru {

namespace {

class MiruMediaDrmBridgeClient final : public media::MediaDrmBridgeClient {
 public:
  MiruMediaDrmBridgeClient() = default;
  ~MiruMediaDrmBridgeClient() override = default;

  media::MediaDrmBridgeDelegate* GetMediaDrmBridgeDelegate(
      const media::UUID& scheme_uuid) override {
    if (scheme_uuid == widevine_delegate_.GetUUID()) {
      return &widevine_delegate_;
    }
    if (scheme_uuid == clearkey_delegate_.GetUUID()) {
      return &clearkey_delegate_;
    }
    return nullptr;
  }

 private:
  cdm::WidevineDrmDelegateAndroid widevine_delegate_;
  cdm::ClearKeyDrmDelegateAndroid clearkey_delegate_;
};

}  // namespace

MiruContentClient::MiruContentClient() = default;
MiruContentClient::~MiruContentClient() = default;

std::u16string MiruContentClient::GetLocalizedString(int message_id) {
  return l10n_util::GetStringUTF16(message_id);
}

std::string_view MiruContentClient::GetDataResource(
    int resource_id,
    ui::ResourceScaleFactor scale_factor) {
  return ui::ResourceBundle::GetSharedInstance().GetRawDataResourceForScale(
      resource_id, scale_factor);
}

scoped_refptr<base::RefCountedMemory> MiruContentClient::GetDataResourceBytes(
    int resource_id) {
  return ui::ResourceBundle::GetSharedInstance().LoadDataResourceBytes(
      resource_id);
}

std::string MiruContentClient::GetDataResourceString(int resource_id) {
  return ui::ResourceBundle::GetSharedInstance().LoadDataResourceString(
      resource_id);
}

gfx::Image& MiruContentClient::GetNativeImageNamed(int resource_id) {
  return ui::ResourceBundle::GetSharedInstance().GetNativeImageNamed(
      resource_id);
}

void MiruContentClient::AddAdditionalSchemes(Schemes* schemes) {
  schemes->local_schemes.push_back(url::kContentScheme);
}

void MiruContentClient::AddContentDecryptionModules(
    std::vector<content::CdmInfo>* cdms,
    std::vector<media::CdmHostFilePath>* /*cdm_host_file_paths*/) {
#if BUILDFLAG(ENABLE_WIDEVINE)
  cdm::AddAndroidWidevineCdm(cdms);
#endif
  cdm::AddOtherAndroidCdms(cdms);
}

media::MediaDrmBridgeClient* MiruContentClient::GetMediaDrmBridgeClient() {
  static base::NoDestructor<MiruMediaDrmBridgeClient> client;
  return client.get();
}

}  // namespace miru
