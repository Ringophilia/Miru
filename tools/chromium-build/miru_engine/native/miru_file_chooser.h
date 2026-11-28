// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_FILE_CHOOSER_H_
#define MIRU_ENGINE_NATIVE_MIRU_FILE_CHOOSER_H_

#include <cstdint>
#include <string>
#include <vector>

#include "base/memory/scoped_refptr.h"
#include "content/public/browser/file_select_listener.h"
#include "third_party/blink/public/mojom/choosers/file_chooser.mojom.h"

namespace miru {

class MiruTab;

class MiruFileChooser {
 public:
  static void Run(MiruTab* tab,
                  scoped_refptr<content::FileSelectListener> listener,
                  const blink::mojom::FileChooserParams& params);

  static void Resolve(int64_t request_id,
                      bool accepted,
                      const std::vector<std::string>& file_paths,
                      const std::vector<std::string>& display_names,
                      const std::vector<std::string>& base_subdirs_joined,
                      const std::vector<std::string>& mime_types,
                      const std::vector<int64_t>& last_modified_ms,
                      const std::vector<bool>& has_last_modified,
                      const std::string& base_dir);

  static void CancelForTab(MiruTab* tab);
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_FILE_CHOOSER_H_
