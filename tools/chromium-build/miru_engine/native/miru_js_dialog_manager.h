// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_JS_DIALOG_MANAGER_H_
#define MIRU_ENGINE_NATIVE_MIRU_JS_DIALOG_MANAGER_H_

#include <cstdint>
#include <string>

#include "base/functional/callback.h"
#include "base/memory/raw_ptr.h"
#include "content/public/browser/javascript_dialog_manager.h"

namespace miru {

class MiruTab;

/// Forwards `alert`/`confirm`/`prompt`/`beforeunload` requests for one `MiruTab`
/// to Miru's application-lifetime prompt controller and ensures every native
/// callback is resolved at most once on UI thread.
class MiruJavaScriptDialogManager final
    : public content::JavaScriptDialogManager {
 public:
  explicit MiruJavaScriptDialogManager(MiruTab* tab);
  MiruJavaScriptDialogManager(const MiruJavaScriptDialogManager&) = delete;
  MiruJavaScriptDialogManager& operator=(const MiruJavaScriptDialogManager&) =
      delete;
  ~MiruJavaScriptDialogManager() override;

  void RunJavaScriptDialog(content::WebContents* web_contents,
                           content::RenderFrameHost* render_frame_host,
                           content::JavaScriptDialogType dialog_type,
                           const std::u16string& message_text,
                           const std::u16string& default_prompt_text,
                           DialogClosedCallback callback,
                           bool* did_suppress_message) override;

  void RunBeforeUnloadDialog(content::WebContents* web_contents,
                             content::RenderFrameHost* render_frame_host,
                             bool is_reload,
                             DialogClosedCallback callback) override;

  bool HandleJavaScriptDialog(content::WebContents* web_contents,
                              bool accept,
                              const std::u16string* prompt_override) override;

  void CancelDialogs(content::WebContents* web_contents,
                     bool reset_state) override;

  void ResolveDialog(int64_t dialog_id,
                     bool accept,
                     const std::u16string& input);

 private:
  void CancelPendingOnly();

  raw_ptr<MiruTab> tab_;
  int64_t active_id_ = 0;
  DialogClosedCallback pending_callback_;
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_JS_DIALOG_MANAGER_H_
