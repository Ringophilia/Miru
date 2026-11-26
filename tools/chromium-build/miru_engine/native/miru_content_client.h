// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_CONTENT_CLIENT_H_
#define MIRU_ENGINE_NATIVE_MIRU_CONTENT_CLIENT_H_

#include <string>
#include <string_view>

#include "base/memory/ref_counted_memory.h"
#include "content/public/common/content_client.h"

namespace miru {

class MiruContentClient : public content::ContentClient {
 public:
  MiruContentClient();
  ~MiruContentClient() override;

  std::u16string GetLocalizedString(int message_id) override;
  std::string_view GetDataResource(
      int resource_id,
      ui::ResourceScaleFactor scale_factor) override;
  scoped_refptr<base::RefCountedMemory> GetDataResourceBytes(
      int resource_id) override;
  std::string GetDataResourceString(int resource_id) override;
  gfx::Image& GetNativeImageNamed(int resource_id) override;
  void AddAdditionalSchemes(Schemes* schemes) override;
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_CONTENT_CLIENT_H_
