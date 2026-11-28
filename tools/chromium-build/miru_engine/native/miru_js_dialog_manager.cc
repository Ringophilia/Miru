// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_js_dialog_manager.h"

#include <utility>

#include "base/strings/utf_string_conversions.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/web_contents.h"
#include "miru_engine/native/miru_tab.h"
#include "url/origin.h"

namespace miru {

namespace {

int ToMiruDialogType(content::JavaScriptDialogType type) {
  switch (type) {
    case content::JAVASCRIPT_DIALOG_TYPE_ALERT:
      return 0;
    case content::JAVASCRIPT_DIALOG_TYPE_CONFIRM:
      return 1;
    case content::JAVASCRIPT_DIALOG_TYPE_PROMPT:
      return 2;
  }
  return 0;
}

std::string FrameOriginString(content::RenderFrameHost* rfh,
                              content::WebContents* wc) {
  if (rfh && !rfh->GetLastCommittedOrigin().opaque()) {
    return rfh->GetLastCommittedOrigin().Serialize();
  }
  if (wc && wc->GetLastCommittedURL().is_valid()) {
    return url::Origin::Create(wc->GetLastCommittedURL()).Serialize();
  }
  return std::string();
}

}  // namespace

MiruJavaScriptDialogManager::MiruJavaScriptDialogManager(MiruTab* tab)
    : tab_(tab) {}

MiruJavaScriptDialogManager::~MiruJavaScriptDialogManager() {
  CancelPendingOnly();
}

void MiruJavaScriptDialogManager::RunJavaScriptDialog(
    content::WebContents* web_contents,
    content::RenderFrameHost* render_frame_host,
    content::JavaScriptDialogType dialog_type,
    const std::u16string& message_text,
    const std::u16string& default_prompt_text,
    DialogClosedCallback callback,
    bool* did_suppress_message) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  CancelPendingOnly();
  if (did_suppress_message) {
    *did_suppress_message = false;
  }
  const int64_t id = ++active_id_;
  pending_callback_ = std::move(callback);
  if (tab_) {
    tab_->OnJavaScriptDialog(
        id, ToMiruDialogType(dialog_type),
        FrameOriginString(render_frame_host, web_contents),
        base::UTF16ToUTF8(message_text),
        base::UTF16ToUTF8(default_prompt_text));
  } else {
    CancelPendingOnly();
  }
}

void MiruJavaScriptDialogManager::RunBeforeUnloadDialog(
    content::WebContents* web_contents,
    content::RenderFrameHost* render_frame_host,
    bool is_reload,
    DialogClosedCallback callback) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  CancelPendingOnly();
  const int64_t id = ++active_id_;
  pending_callback_ = std::move(callback);
  if (tab_) {
    tab_->OnBeforeUnloadDialog(
        id, FrameOriginString(render_frame_host, web_contents), is_reload);
  } else {
    CancelPendingOnly();
  }
}

bool MiruJavaScriptDialogManager::HandleJavaScriptDialog(
    content::WebContents* /*web_contents*/,
    bool accept,
    const std::u16string* prompt_override) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!pending_callback_) {
    return false;
  }
  const int64_t id = active_id_;
  std::u16string input = prompt_override ? *prompt_override : std::u16string();
  ResolveDialog(id, accept, input);
  if (tab_) {
    tab_->OnDialogsCancelled(id, false);
  }
  return true;
}

void MiruJavaScriptDialogManager::CancelDialogs(
    content::WebContents* /*web_contents*/,
    bool reset_state) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  const int64_t old_id = active_id_;
  CancelPendingOnly();
  if (tab_) {
    tab_->OnDialogsCancelled(old_id, reset_state);
  }
}

void MiruJavaScriptDialogManager::ResolveDialog(int64_t dialog_id,
                                                bool accept,
                                                const std::u16string& input) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (dialog_id != active_id_ || !pending_callback_) {
    return;
  }
  DialogClosedCallback cb = std::move(pending_callback_);
  std::move(cb).Run(accept, input);
}

void MiruJavaScriptDialogManager::CancelPendingOnly() {
  if (pending_callback_) {
    DialogClosedCallback cb = std::move(pending_callback_);
    std::move(cb).Run(false, std::u16string());
  }
}

}  // namespace miru
