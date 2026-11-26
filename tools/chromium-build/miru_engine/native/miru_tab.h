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

/// Owns one `content::WebContents` and bridges native C++ network/cosmetic hooks to Java `MiruChromiumTab`.
class MiruTab : public content::WebContentsDelegate,
                public content::WebContentsObserver {
 public:
  static MiruTab* FromWebContents(content::WebContents* web_contents);

  MiruTab(const base::android::JavaRef<jobject>& java_tab, bool off_the_record);
  MiruTab(const MiruTab&) = delete;
  MiruTab& operator=(const MiruTab&) = delete;
  ~MiruTab() override;

  base::WeakPtr<MiruTab> AsWeakPtr() { return weak_factory_.GetWeakPtr(); }
  content::WebContents* web_contents() const { return web_contents_.get(); }

  void NotifyAdBlocked(const std::string& blocked_url);
  void NotifyMediaDetected(const std::string& media_url,
                           const std::string& kind,
                           const std::string& mime_type,
                           int64_t content_length);

  void FindAll(JNIEnv* env, const jni_zero::JavaRef<jstring>& jquery, bool forward);
  void FindAll(const std::u16string& query, bool forward);
  void ClearFind();
  void DestroyTab(JNIEnv* env);

  // content::WebContentsObserver:
  void DidFinishNavigation(
      content::NavigationHandle* navigation_handle) override;
  void DidReceiveFindReply(int request_id,
                           int number_of_matches,
                           const gfx::Rect& selection_rect,
                           int active_match_ordinal,
                           bool final_update) override;

  // content::WebContentsDelegate:
  content::WebContents* OpenURLFromTab(
      content::WebContents* source,
      const content::OpenURLParams& params,
      base::OnceCallback<void(content::NavigationHandle&)>
          navigation_handle_callback) override;
  void CloseContents(content::WebContents* source) override;
  void EnterFullscreenModeForTab(
      content::RenderFrameHost* requesting_frame,
      const blink::mojom::FullscreenOptions& options) override;
  void ExitFullscreenModeForTab(content::WebContents* web_contents) override;
  bool IsFullscreenForTabOrPending(
      const content::WebContents* web_contents) override;

 private:
  void InjectCosmeticRules(content::RenderFrameHost* rfh,
                           const std::string& url);

  base::android::ScopedJavaGlobalRef<jobject> java_tab_;
  std::unique_ptr<content::WebContents> web_contents_;
  bool is_fullscreen_ = false;
  std::u16string last_find_query_;
  base::WeakPtrFactory<MiruTab> weak_factory_{this};
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_TAB_H_
