// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_form_popups.h"

#include <cstdio>
#include <utility>
#include <vector>

#include "base/containers/flat_map.h"
#include "base/functional/bind.h"
#include "base/memory/raw_ptr.h"
#include "base/no_destructor.h"
#include "content/browser/date_time_chooser/android/date_time_chooser_android.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/color_chooser.h"
#include "content/public/browser/web_contents.h"
#include "miru_engine/native/miru_tab.h"
#include "third_party/blink/public/mojom/choosers/date_time_chooser.mojom.h"
#include "ui/base/ime/text_input_type.h"

namespace miru {

namespace {

using DateReplyCallback = base::OnceCallback<void(bool success,
                                                  bool use_string,
                                                  const std::string& value)>;

struct PendingDateTime {
  raw_ptr<MiruTab> tab = nullptr;
  DateReplyCallback callback;
};

base::flat_map<int64_t, PendingDateTime>& PendingDateTimes() {
  static base::NoDestructor<base::flat_map<int64_t, PendingDateTime>> map;
  return *map;
}

class MiruColorChooser;

struct PendingColor {
  raw_ptr<MiruTab> tab = nullptr;
  raw_ptr<content::WebContents> web_contents = nullptr;
  raw_ptr<MiruColorChooser> chooser = nullptr;
};

base::flat_map<int64_t, PendingColor>& PendingColors() {
  static base::NoDestructor<base::flat_map<int64_t, PendingColor>> map;
  return *map;
}

int64_t& NextFormPopupId() {
  static int64_t next = 1;
  return next;
}

int MapDateType(ui::TextInputType type) {
  switch (type) {
    case ui::TEXT_INPUT_TYPE_MONTH:
      return 1;
    case ui::TEXT_INPUT_TYPE_WEEK:
      return 2;
    case ui::TEXT_INPUT_TYPE_TIME:
      return 3;
    case ui::TEXT_INPUT_TYPE_DATE_TIME:
    case ui::TEXT_INPUT_TYPE_DATE_TIME_LOCAL:
      return 4;
    case ui::TEXT_INPUT_TYPE_DATE:
    default:
      return 0;
  }
}

std::string FormatRgbHex(SkColor color) {
  char buf[8];
  std::snprintf(buf, sizeof(buf), "#%02x%02x%02x", SkColorGetR(color),
                SkColorGetG(color), SkColorGetB(color));
  return std::string(buf);
}

class MiruColorChooser final : public content::ColorChooser {
 public:
  MiruColorChooser(int64_t request_id, MiruTab* tab)
      : request_id_(request_id), tab_(tab) {}

  ~MiruColorChooser() override { End(); }

  void End() override {
    if (ended_) {
      return;
    }
    ended_ = true;
    auto it = PendingColors().find(request_id_);
    if (it != PendingColors().end()) {
      PendingColors().erase(it);
      if (tab_) {
        tab_->OnColorPromptClosed(request_id_);
      }
    }
  }

  void SetSelectedColor(SkColor /*color*/) override {}

  void MarkResolved() { ended_ = true; }

 private:
  const int64_t request_id_;
  raw_ptr<MiruTab> tab_ = nullptr;
  bool ended_ = false;
};

void CancelDateTimeForTab(MiruTab* tab) {
  std::vector<std::pair<int64_t, PendingDateTime>> removed;
  for (auto it = PendingDateTimes().begin(); it != PendingDateTimes().end();) {
    if (it->second.tab == tab) {
      removed.emplace_back(it->first, std::move(it->second));
      it = PendingDateTimes().erase(it);
    } else {
      ++it;
    }
  }
  for (auto& entry : removed) {
    if (entry.second.callback) {
      std::move(entry.second.callback).Run(false, false, std::string());
    }
    if (tab) {
      tab->OnDateTimePromptClosed(entry.first);
    }
  }
}

bool HandleOpenDateTime(content::WebContents* wc,
                        const blink::mojom::DateTimeDialogValue& value,
                        DateReplyCallback callback) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  MiruTab* tab = wc ? MiruTab::FromWebContents(wc) : nullptr;
  if (!tab) {
    return false;
  }
  CancelDateTimeForTab(tab);

  const int64_t request_id = NextFormPopupId()++;
  PendingDateTimes()[request_id] = PendingDateTime{
      .tab = tab,
      .callback = std::move(callback),
  };
  tab->OnDateTimePrompt(request_id, MapDateType(value.dialog_type),
                        value.raw_value, value.raw_min, value.raw_max,
                        value.raw_step);
  return true;
}

void HandleCloseDateTime(content::WebContents* wc) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  MiruTab* tab = wc ? MiruTab::FromWebContents(wc) : nullptr;
  if (!tab) {
    return;
  }
  std::vector<int64_t> ids;
  for (const auto& entry : PendingDateTimes()) {
    if (entry.second.tab == tab) {
      ids.push_back(entry.first);
    }
  }
  for (int64_t id : ids) {
    PendingDateTimes().erase(id);
    tab->OnDateTimePromptClosed(id);
  }
}

}  // namespace

// static
void MiruFormPopups::InstallDateTimeHooks() {
  content::DateTimeChooserAndroid::SetCustomCallbacksForEmbedder(
      base::BindRepeating(&HandleOpenDateTime),
      base::BindRepeating(&HandleCloseDateTime));
}

// static
void MiruFormPopups::ResolveDateTime(int64_t request_id,
                                     bool accepted,
                                     const std::string& value) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  auto it = PendingDateTimes().find(request_id);
  if (it == PendingDateTimes().end()) {
    return;
  }
  DateReplyCallback callback = std::move(it->second.callback);
  PendingDateTimes().erase(it);
  if (callback) {
    std::move(callback).Run(accepted, /*use_string=*/true, value);
  }
}

// static
std::unique_ptr<content::ColorChooser> MiruFormPopups::OpenColorChooser(
    MiruTab* tab,
    content::WebContents* web_contents,
    SkColor initial_color) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!tab || !web_contents) {
    return nullptr;
  }
  const int64_t request_id = NextFormPopupId()++;
  auto chooser = std::make_unique<MiruColorChooser>(request_id, tab);
  PendingColors()[request_id] = PendingColor{
      .tab = tab,
      .web_contents = web_contents,
      .chooser = chooser.get(),
  };
  tab->OnColorPrompt(request_id, FormatRgbHex(initial_color));
  return chooser;
}

// static
void MiruFormPopups::ResolveColor(int64_t request_id,
                                  bool accepted,
                                  uint32_t argb) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  auto it = PendingColors().find(request_id);
  if (it == PendingColors().end()) {
    return;
  }
  PendingColor pending = it->second;
  PendingColors().erase(it);
  if (pending.chooser) {
    pending.chooser->MarkResolved();
  }
  if (pending.web_contents) {
    if (accepted) {
      pending.web_contents->DidChooseColorInColorChooser(
          static_cast<SkColor>(argb));
    }
    pending.web_contents->DidEndColorChooser();
  }
}

// static
void MiruFormPopups::CancelForTab(MiruTab* tab) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!tab) {
    return;
  }
  CancelDateTimeForTab(tab);
  std::vector<int64_t> color_ids;
  for (const auto& entry : PendingColors()) {
    if (entry.second.tab == tab) {
      color_ids.push_back(entry.first);
    }
  }
  for (int64_t id : color_ids) {
    auto it = PendingColors().find(id);
    if (it != PendingColors().end()) {
      if (it->second.chooser) {
        it->second.chooser->MarkResolved();
      }
      PendingColors().erase(it);
    }
  }
}

}  // namespace miru
