// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_FORM_POPUPS_H_
#define MIRU_ENGINE_NATIVE_MIRU_FORM_POPUPS_H_

#include <cstdint>
#include <memory>
#include <string>

#include "third_party/skia/include/core/SkColor.h"

namespace content {
class ColorChooser;
class WebContents;
}  // namespace content

namespace miru {

class MiruTab;

class MiruFormPopups {
 public:
  static void InstallDateTimeHooks();

  static void ResolveDateTime(int64_t request_id,
                              bool accepted,
                              const std::string& value);

  static std::unique_ptr<content::ColorChooser> OpenColorChooser(
      MiruTab* tab,
      content::WebContents* web_contents,
      SkColor initial_color);

  static void ResolveColor(int64_t request_id,
                           bool accepted,
                           uint32_t argb);

  static void CancelForTab(MiruTab* tab);
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_FORM_POPUPS_H_
