// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_file_chooser.h"

#include <utility>

#include "base/containers/flat_map.h"
#include "base/files/file_path.h"
#include "base/memory/raw_ptr.h"
#include "base/no_destructor.h"
#include "base/strings/string_split.h"
#include "base/strings/utf_string_conversions.h"
#include "content/public/browser/browser_thread.h"
#include "miru_engine/native/miru_tab.h"

namespace miru {

namespace {

struct PendingFileChooser {
  raw_ptr<MiruTab> tab = nullptr;
  scoped_refptr<content::FileSelectListener> listener;
  blink::mojom::FileChooserParams::Mode mode =
      blink::mojom::FileChooserParams::Mode::kOpen;
};

base::flat_map<int64_t, PendingFileChooser>& PendingChoosers() {
  static base::NoDestructor<base::flat_map<int64_t, PendingFileChooser>> map;
  return *map;
}

int64_t& NextFileChooserId() {
  static int64_t next = 1;
  return next;
}

int MapCaptureMode(const blink::mojom::FileChooserParams& params) {
  if (!params.use_media_capture) {
    return 0;  // NONE
  }
  if (params.capture_value == "user") {
    return 2;  // FRONT_CAMERA
  }
  if (params.capture_value == "environment") {
    return 3;  // BACK_CAMERA
  }
  return 1;    // ANY
}

}  // namespace

// static
void MiruFileChooser::Run(MiruTab* tab,
                          scoped_refptr<content::FileSelectListener> listener,
                          const blink::mojom::FileChooserParams& params) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!listener) {
    return;
  }
  if (!tab) {
    listener->FileSelectionCanceled();
    return;
  }

  int mode_int = -1;
  switch (params.mode) {
    case blink::mojom::FileChooserParams::Mode::kOpen:
      mode_int = 0;
      break;
    case blink::mojom::FileChooserParams::Mode::kOpenMultiple:
      mode_int = 1;
      break;
    case blink::mojom::FileChooserParams::Mode::kUploadFolder:
      mode_int = 2;
      break;
    case blink::mojom::FileChooserParams::Mode::kOpenDirectory:
    case blink::mojom::FileChooserParams::Mode::kSave:
      listener->FileSelectionCanceled();
      return;
  }

  std::vector<std::string> accept_types;
  accept_types.reserve(params.accept_types.size());
  for (const std::u16string& accept : params.accept_types) {
    std::string utf8 = base::UTF16ToUTF8(accept);
    if (!utf8.empty()) {
      accept_types.push_back(std::move(utf8));
    }
  }

  const int64_t request_id = NextFileChooserId()++;
  PendingChoosers()[request_id] = PendingFileChooser{
      .tab = tab,
      .listener = std::move(listener),
      .mode = params.mode,
  };
  tab->OnFileChooserRequest(request_id, mode_int, accept_types,
                            MapCaptureMode(params));
}

// static
void MiruFileChooser::Resolve(
    int64_t request_id,
    bool accepted,
    const std::vector<std::string>& file_paths,
    const std::vector<std::string>& display_names,
    const std::vector<std::string>& base_subdirs_joined,
    const std::vector<std::string>& mime_types,
    const std::vector<int64_t>& last_modified_ms,
    const std::vector<bool>& has_last_modified,
    const std::string& base_dir) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  auto it = PendingChoosers().find(request_id);
  if (it == PendingChoosers().end()) {
    return;
  }
  PendingFileChooser pending = std::move(it->second);
  PendingChoosers().erase(it);
  if (!pending.listener) {
    return;
  }
  if (!accepted) {
    pending.listener->FileSelectionCanceled();
    return;
  }

  const size_t count = file_paths.size();
  std::vector<blink::mojom::FileChooserFileInfoPtr> files;
  files.reserve(count);
  for (size_t i = 0; i < count; ++i) {
    auto info = blink::mojom::NativeFileInfo::New();
    info->file_path = base::FilePath(file_paths[i]);
    if (i < display_names.size() && !display_names[i].empty()) {
      info->display_name = base::UTF8ToUTF16(display_names[i]);
    }
    if (i < base_subdirs_joined.size() && !base_subdirs_joined[i].empty()) {
      auto parts = base::SplitString(base_subdirs_joined[i], "/",
                                     base::KEEP_WHITESPACE,
                                     base::SPLIT_WANT_NONEMPTY);
      for (const std::string& part : parts) {
        info->base_subdirs.push_back(base::UTF8ToUTF16(part));
      }
    }
    if (i < mime_types.size()) {
      info->explicit_mime_type = mime_types[i];
    }
    if (i < has_last_modified.size() && has_last_modified[i] &&
        i < last_modified_ms.size()) {
      info->has_explicit_last_modified = true;
      info->explicit_last_modified_ms = last_modified_ms[i];
    }
    files.push_back(
        blink::mojom::FileChooserFileInfo::NewNativeFile(std::move(info)));
  }

  pending.listener->FileSelected(std::move(files), base::FilePath(base_dir),
                                 pending.mode);
}

// static
void MiruFileChooser::CancelForTab(MiruTab* tab) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!tab) {
    return;
  }
  std::vector<PendingFileChooser> to_cancel;
  for (auto it = PendingChoosers().begin(); it != PendingChoosers().end();) {
    if (it->second.tab == tab) {
      to_cancel.push_back(std::move(it->second));
      it = PendingChoosers().erase(it);
    } else {
      ++it;
    }
  }
  for (auto& pending : to_cancel) {
    if (pending.listener) {
      pending.listener->FileSelectionCanceled();
    }
  }
}

}  // namespace miru
