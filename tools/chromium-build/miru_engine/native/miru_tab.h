// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_TAB_H_
#define MIRU_ENGINE_NATIVE_MIRU_TAB_H_

#include <cstdint>
#include <memory>
#include <string>

#include "base/android/scoped_java_ref.h"
#include "base/memory/weak_ptr.h"
#include "content/public/browser/web_contents_delegate.h"
#include "content/public/browser/web_contents_observer.h"
#include "third_party/jni_zero/jni_zero.h"

namespace blink::mojom {
class FullscreenOptions;
}  // namespace blink::mojom

namespace content {
class RenderFrameHost;
class WebContents;
}  // namespace content

namespace miru {

class MiruJavaScriptDialogManager;

/// Owns one `content::WebContents` and bridges native C++ network/cosmetic hooks to Java `MiruChromiumTab`.
class MiruTab : public content::WebContentsDelegate,
                public content::WebContentsObserver {
 public:
  static MiruTab* FromWebContents(content::WebContents* web_contents);

  MiruTab(const base::android::JavaRef<jobject>& java_tab, bool off_the_record);
  MiruTab(const base::android::JavaRef<jobject>& java_tab,
          std::unique_ptr<content::WebContents> adopted_contents);
  MiruTab(const MiruTab&) = delete;
  MiruTab& operator=(const MiruTab&) = delete;
  ~MiruTab() override;

  base::WeakPtr<MiruTab> AsWeakPtr() { return weak_factory_.GetWeakPtr(); }
  content::WebContents* web_contents() const { return web_contents_.get(); }

  void NotifyAdBlocked(const std::string& blocked_url);
  void NotifyMediaDetected(const std::string& media_url,
                           const std::string& kind,
                           const std::string& mime_type,
                           int64_t content_length,
                           const std::string& headers_json);
  void NotifyIsolatedJsResult(jlong request_id, const std::string& result_json);
  void OnJavaScriptDialog(int64_t dialog_id,
                          int dialog_type,
                          const std::string& origin,
                          const std::string& message,
                          const std::string& default_prompt);
  void OnBeforeUnloadDialog(int64_t dialog_id,
                            const std::string& origin,
                            bool is_reload);
  void OnDialogsCancelled(int64_t dialog_id, bool reset_state);
  void OnHttpAuthRequest(int64_t auth_id,
                         const std::string& url,
                         const std::string& challenger,
                         const std::string& realm,
                         const std::string& scheme,
                         bool is_proxy,
                         bool previous_failed);
  void OnHttpAuthCancelled(int64_t auth_id);
  bool CheckPopupAllowed(const std::string& opener_url,
                         const std::string& target_url,
                         bool user_gesture,
                         bool opener_suppressed);
  void OnExternalProtocol(const std::string& url, bool has_user_gesture);

  void FindAll(JNIEnv* env, const jni_zero::JavaRef<jstring>& jquery, bool forward);
  void FindAll(const std::u16string& query, bool forward);
  void ClearFind();
  void EvaluateIsolatedJs(JNIEnv* env,
                          const jni_zero::JavaRef<jstring>& jscript,
                          bool all_frames,
                          jlong request_id);
  void CaptureThumbnail(JNIEnv* env, jint max_width, jlong request_id);
  void ResolveJsDialog(JNIEnv* env,
                       jlong dialog_id,
                       jboolean accept,
                       const jni_zero::JavaRef<jstring>& jinput);
  void ResolveRepostDialog(JNIEnv* env, jboolean resubmit);
  void NotifyContextMenuClosed(JNIEnv* env);
  void DestroyTab(JNIEnv* env);

  // content::WebContentsObserver:
  void DidFinishNavigation(
      content::NavigationHandle* navigation_handle) override;
  void DOMContentLoaded(content::RenderFrameHost* render_frame_host) override;
  void DidChangeVisibleSecurityState() override;
  void DidReceiveFindReply(int request_id,
                           int number_of_matches,
                           const gfx::Rect& selection_rect,
                           int active_match_ordinal,
                           bool final_update) override;

  // content::WebContentsDelegate:
  bool DidAddMessageToConsole(
      content::WebContents* source,
      blink::mojom::ConsoleMessageLevel log_level,
      const std::u16string& message,
      int32_t line_no,
      const std::u16string& source_id) override;
  content::WebContents* OpenURLFromTab(
      content::WebContents* source,
      const content::OpenURLParams& params,
      base::OnceCallback<void(content::NavigationHandle&)>
          navigation_handle_callback) override;
  content::WebContents* AddNewContents(
      content::WebContents* source,
      std::unique_ptr<content::WebContents> new_contents,
      const GURL& target_url,
      WindowOpenDisposition disposition,
      const blink::mojom::WindowFeatures& window_features,
      bool user_gesture,
      bool* was_blocked) override;
  bool HandleContextMenu(content::RenderFrameHost& render_frame_host,
                         const content::ContextMenuParams& params) override;
  void ShowRepostFormWarningDialog(content::WebContents* source) override;
  content::JavaScriptDialogManager* GetJavaScriptDialogManager(
      content::WebContents* source) override;
  void CloseContents(content::WebContents* source) override;
  void EnterFullscreenModeForTab(
      content::RenderFrameHost* requesting_frame,
      const blink::mojom::FullscreenOptions& options) override;
  void ExitFullscreenModeForTab(content::WebContents* web_contents) override;
  bool IsFullscreenForTabOrPending(
      const content::WebContents* web_contents) override;

 private:
  void AttachWebContents();
  void InjectCosmeticRules(content::RenderFrameHost* rfh,
                           const std::string& url);
  void NotifySecurityState();

  base::android::ScopedJavaGlobalRef<jobject> java_tab_;
  std::unique_ptr<content::WebContents> web_contents_;
  std::unique_ptr<MiruJavaScriptDialogManager> js_dialog_manager_;
  std::string bridge_nonce_;
  bool is_fullscreen_ = false;
  std::u16string last_find_query_;
  base::WeakPtrFactory<MiruTab> weak_factory_{this};
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_TAB_H_
