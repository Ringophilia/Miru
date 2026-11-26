// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_tab.h"

#include <utility>

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/android/scoped_java_ref.h"
#include "base/containers/flat_map.h"
#include "base/functional/callback_helpers.h"
#include "base/no_destructor.h"
#include "base/strings/string_util.h"
#include "base/strings/utf_string_conversions.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/navigation_controller.h"
#include "content/public/browser/navigation_handle.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/web_contents.h"
#include "content/public/common/isolated_world_ids.h"
#include "content/public/common/stop_find_action.h"
#include "miru_core.h"
#include "miru_engine/miru_engine_jni/MiruChromiumTab_jni.h"
#include "miru_engine/native/miru_browser_context.h"
#include "miru_engine/native/miru_content_browser_client.h"
#include "third_party/blink/public/mojom/frame/find_in_page.mojom.h"
#include "third_party/jni_zero/jni_zero.h"

namespace miru {

namespace {

base::flat_map<const content::WebContents*, MiruTab*>& TabRegistry() {
  static base::NoDestructor<base::flat_map<const content::WebContents*, MiruTab*>>
      registry;
  return *registry;
}

std::string SanitizeJsonForJsLiteral(const char* raw_json) {
  std::string out(raw_json);
  // U+2028 / U+2029 are valid JSON but line terminators inside JS literals.
  base::ReplaceSubstringsAfterOffset(&out, 0, "\xe2\x80\xa8", "\\u2028");
  base::ReplaceSubstringsAfterOffset(&out, 0, "\xe2\x80\xa9", "\\u2029");
  return out;
}

}  // namespace

// static
MiruTab* MiruTab::FromWebContents(content::WebContents* web_contents) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  auto it = TabRegistry().find(web_contents);
  return it != TabRegistry().end() ? it->second : nullptr;
}

MiruTab::MiruTab(const base::android::JavaRef<jobject>& java_tab,
                 bool off_the_record)
    : java_tab_(java_tab) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  auto* client = MiruContentBrowserClient::Get();
  CHECK(client);
  MiruBrowserContext* context = off_the_record
                                    ? client->GetOrCreateOffTheRecordContext()
                                    : client->normal_context();
  CHECK(context);
  content::WebContents::CreateParams params(context);
  web_contents_ = content::WebContents::Create(params);
  web_contents_->SetDelegate(this);
  Observe(web_contents_.get());
  TabRegistry()[web_contents_.get()] = this;

  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_initWebContents(env, java_tab_,
                                       web_contents_->GetJavaWebContents());
}

MiruTab::~MiruTab() {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (web_contents_) {
    TabRegistry().erase(web_contents_.get());
    Observe(nullptr);
    web_contents_->SetDelegate(nullptr);
    web_contents_.reset();
  }
}

void MiruTab::NotifyAdBlocked(const std::string& blocked_url) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_onNativeAdBlocked(
      env, java_tab_,
      base::android::ConvertUTF8ToJavaString(env, blocked_url));
}

void MiruTab::NotifyMediaDetected(const std::string& media_url,
                                  const std::string& kind,
                                  const std::string& mime_type,
                                  int64_t content_length) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_onNativeMediaDetected(
      env, java_tab_,
      base::android::ConvertUTF8ToJavaString(env, media_url),
      base::android::ConvertUTF8ToJavaString(env, kind),
      base::android::ConvertUTF8ToJavaString(env, mime_type),
      static_cast<jlong>(content_length));
}

void MiruTab::FindAll(JNIEnv* env,
                      const jni_zero::JavaRef<jstring>& jquery,
                      bool forward) {
  FindAll(base::android::ConvertJavaStringToUTF16(env, jquery), forward);
}

void MiruTab::FindAll(const std::u16string& query, bool forward) {
  if (!web_contents_ || query.empty()) {
    return;
  }
  auto options = blink::mojom::FindOptions::New();
  options->forward = forward;
  options->new_session = (last_find_query_ != query);
  last_find_query_ = query;
  web_contents_->Find(query, std::move(options), false,
                      [](int /*request_id*/) {});
}

void MiruTab::ClearFind() {
  last_find_query_.clear();
  if (web_contents_) {
    web_contents_->StopFinding(content::STOP_FIND_ACTION_CLEAR_SELECTION);
  }
}

void MiruTab::DestroyTab(JNIEnv*) {
  delete this;
}

void MiruTab::DidFinishNavigation(
    content::NavigationHandle* navigation_handle) {
  if (!navigation_handle->IsInPrimaryMainFrame() ||
      !navigation_handle->HasCommitted() ||
      navigation_handle->IsErrorPage()) {
    return;
  }
  const GURL& url = navigation_handle->GetURL();
  if (!url.SchemeIsHTTPOrHTTPS()) {
    return;
  }
  if (auto* rfh = navigation_handle->GetRenderFrameHost()) {
    InjectCosmeticRules(rfh, url.spec());
  }
}

void MiruTab::InjectCosmeticRules(content::RenderFrameHost* rfh,
                                  const std::string& url) {
  auto* client = MiruContentBrowserClient::Get();
  auto adblock_ref = client ? client->GetAdblockRef() : nullptr;
  MiruAdblockHandle* adblock = adblock_ref ? adblock_ref->ptr : nullptr;
  if (!adblock) {
    return;
  }
  char* json_ptr = miru_adblock_cosmetic_json(adblock, url.c_str());
  if (!json_ptr) {
    return;
  }
  const std::string literal = SanitizeJsonForJsLiteral(json_ptr);
  miru_string_free(json_ptr);

  const std::string script =
      "(() => {"
      "const r = " + literal + ";"
      "if (!r) return;"
      "if (r.hide_selectors && r.hide_selectors.length) {"
      "  const css = r.hide_selectors.join(',') + '{display:none!important}';"
      "  let el = document.getElementById('__miru_cosmetic');"
      "  if (!el) {"
      "    el = document.createElement('style');"
      "    el.id = '__miru_cosmetic';"
      "    (document.head || document.documentElement).appendChild(el);"
      "  }"
      "  el.textContent = css;"
      "}"
      "if (r.injected_script && r.injected_script.length) {"
      "  try { (0, eval)(r.injected_script); } catch (_) {}"
      "}"
      "})();";
  rfh->ExecuteJavaScriptInIsolatedWorld(
      base::UTF8ToUTF16(script), base::NullCallback(),
      content::ISOLATED_WORLD_ID_CONTENT_END);
}

content::WebContents* MiruTab::OpenURLFromTab(
    content::WebContents* source,
    const content::OpenURLParams& params,
    base::OnceCallback<void(content::NavigationHandle&)>
        navigation_handle_callback) {
  if (!source) {
    return nullptr;
  }
  source->GetController().LoadURLWithParams(
      content::NavigationController::LoadURLParams(params));
  return source;
}

void MiruTab::CloseContents(content::WebContents* /*source*/) {}

void MiruTab::EnterFullscreenModeForTab(
    content::RenderFrameHost* /*requesting_frame*/,
    const blink::mojom::FullscreenOptions& /*options*/) {
  is_fullscreen_ = true;
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_onNativeFullscreenChanged(env, java_tab_, JNI_TRUE);
}

void MiruTab::ExitFullscreenModeForTab(content::WebContents* /*web_contents*/) {
  is_fullscreen_ = false;
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_onNativeFullscreenChanged(env, java_tab_, JNI_FALSE);
}

bool MiruTab::IsFullscreenForTabOrPending(const content::WebContents*) {
  return is_fullscreen_;
}

void MiruTab::DidReceiveFindReply(int /*request_id*/,
                                  int number_of_matches,
                                  const gfx::Rect&,
                                  int active_match_ordinal,
                                  bool final_update) {
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_onNativeFindReply(
      env, java_tab_, static_cast<jint>(active_match_ordinal),
      static_cast<jint>(number_of_matches),
      final_update ? JNI_TRUE : JNI_FALSE);
}

// --- JNI exports ---

static jlong JNI_MiruChromiumTab_CreateTab(
    JNIEnv*,
    const base::android::JavaRef<jobject>& jcaller,
    jboolean off_the_record) {
  auto* tab = new MiruTab(jcaller, off_the_record != JNI_FALSE);
  return reinterpret_cast<jlong>(tab);
}

}  // namespace miru

DEFINE_JNI(MiruChromiumTab)
