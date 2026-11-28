// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_tab.h"

#include <algorithm>
#include <string_view>
#include <utility>
#include <vector>

#include "base/android/jni_android.h"
#include "base/android/jni_array.h"
#include "base/android/jni_string.h"
#include "base/android/scoped_java_ref.h"
#include "base/containers/flat_map.h"
#include "base/containers/span.h"
#include "base/functional/bind.h"
#include "base/functional/callback_helpers.h"
#include "base/json/json_writer.h"
#include "base/memory/ref_counted.h"
#include "base/no_destructor.h"
#include "base/rand_util.h"
#include "base/strings/string_number_conversions.h"
#include "base/strings/string_util.h"
#include "base/strings/utf_string_conversions.h"
#include "base/time/time.h"
#include "base/values.h"
#include "components/viz/common/frame_sinks/copy_output_result.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/context_menu_params.h"
#include "content/public/browser/navigation_controller.h"
#include "content/public/browser/navigation_entry.h"
#include "content/public/browser/navigation_handle.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/render_widget_host_view.h"
#include "content/public/browser/ssl_status.h"
#include "content/public/browser/web_contents.h"
#include "content/public/common/isolated_world_ids.h"
#include "content/public/common/stop_find_action.h"
#include "miru_core.h"
#include "miru_engine/miru_engine_jni/MiruChromiumTab_jni.h"
#include "miru_engine/native/miru_browser_context.h"
#include "miru_engine/native/miru_content_browser_client.h"
#include "miru_engine/native/miru_js_dialog_manager.h"
#include "miru_engine/native/miru_login_delegate.h"
#include "miru_engine/native/miru_media_access_handler.h"
#include "miru_engine/native/miru_tab_helpers.h"
#include "net/cert/cert_status_flags.h"
#include "net/cert/x509_certificate.h"
#include "net/cert/x509_util.h"
#include "services/network/public/mojom/referrer_policy.mojom.h"
#include "third_party/blink/public/mojom/context_menu/context_menu.mojom.h"
#include "third_party/blink/public/mojom/frame/find_in_page.mojom.h"
#include "third_party/jni_zero/jni_zero.h"
#include "ui/gfx/android/java_bitmap.h"
#include "ui/gfx/geometry/rect.h"
#include "ui/gfx/geometry/size.h"

namespace miru {

namespace {

constexpr std::u16string_view kBridgePrefix = u"__MIRU_EVT__:";

base::flat_map<const content::WebContents*, MiruTab*>& TabRegistry() {
  static base::NoDestructor<base::flat_map<const content::WebContents*, MiruTab*>>
      registry;
  return *registry;
}

base::flat_map<int64_t, std::unique_ptr<content::WebContents>>&
PendingAdoptions() {
  static base::NoDestructor<
      base::flat_map<int64_t, std::unique_ptr<content::WebContents>>>
      map;
  return *map;
}

int64_t& NextAdoptId() {
  static int64_t next = 1;
  return next;
}

struct MultiFrameJsCollector
    : public base::RefCounted<MultiFrameJsCollector> {
  base::WeakPtr<MiruTab> tab;
  jlong request_id = 0;
  size_t pending = 0;
  base::ListValue results;

  void OnFrameResult(base::Value val) {
    if (!val.is_none()) {
      results.Append(std::move(val));
    }
    if (pending > 0) {
      --pending;
    }
    if (pending == 0 && tab) {
      tab->NotifyIsolatedJsResult(request_id,
                                  base::WriteJson(results).value_or("[]"));
    }
  }

 private:
  friend class base::RefCounted<MultiFrameJsCollector>;
  ~MultiFrameJsCollector() = default;
};

}  // namespace

// static
MiruTab* MiruTab::FromWebContents(content::WebContents* web_contents) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  auto it = TabRegistry().find(web_contents);
  return it != TabRegistry().end() ? it->second : nullptr;
}

MiruTab::MiruTab(const base::android::JavaRef<jobject>& java_tab,
                 bool off_the_record)
    : java_tab_(java_tab),
      bridge_nonce_(base::HexEncodeLower(base::RandBytesAsVector(16))) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  auto* client = MiruContentBrowserClient::Get();
  CHECK(client);
  MiruBrowserContext* context = off_the_record
                                    ? client->GetOrCreateOffTheRecordContext()
                                    : client->normal_context();
  CHECK(context);
  content::WebContents::CreateParams params(context);
  web_contents_ = content::WebContents::Create(params);
  AttachWebContents();
}

MiruTab::MiruTab(const base::android::JavaRef<jobject>& java_tab,
                 std::unique_ptr<content::WebContents> adopted_contents)
    : java_tab_(java_tab),
      web_contents_(std::move(adopted_contents)),
      bridge_nonce_(base::HexEncodeLower(base::RandBytesAsVector(16))) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  CHECK(web_contents_);
  AttachWebContents();
}

void MiruTab::AttachWebContents() {
  js_dialog_manager_ = std::make_unique<MiruJavaScriptDialogManager>(this);
  web_contents_->SetDelegate(this);
  Observe(web_contents_.get());
  TabRegistry()[web_contents_.get()] = this;

  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_initWebContents(env, java_tab_,
                                       web_contents_->GetJavaWebContents());
}

MiruTab::~MiruTab() {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  MiruMediaAccessHandler::CancelForTab(this);
  if (web_contents_) {
    TabRegistry().erase(web_contents_.get());
    Observe(nullptr);
    web_contents_->SetDelegate(nullptr);
    web_contents_.reset();
  }
  js_dialog_manager_.reset();
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
                                  int64_t content_length,
                                  const std::string& headers_json) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_onNativeMediaDetected(
      env, java_tab_,
      base::android::ConvertUTF8ToJavaString(env, media_url),
      base::android::ConvertUTF8ToJavaString(env, kind),
      base::android::ConvertUTF8ToJavaString(env, mime_type),
      static_cast<jlong>(content_length),
      base::android::ConvertUTF8ToJavaString(env, headers_json));
}

void MiruTab::NotifyIsolatedJsResult(jlong request_id,
                                     const std::string& result_json) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_onNativeIsolatedJsResult(
      env, java_tab_, request_id,
      base::android::ConvertUTF8ToJavaString(env, result_json));
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

void MiruTab::EvaluateIsolatedJs(JNIEnv* env,
                                 const jni_zero::JavaRef<jstring>& jscript,
                                 bool all_frames,
                                 jlong request_id) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!web_contents_) {
    if (request_id > 0) {
      Java_MiruChromiumTab_onNativeIsolatedJsResult(
          env, java_tab_, request_id,
          base::android::ConvertUTF8ToJavaString(env, all_frames ? "[]" : ""));
    }
    return;
  }
  std::string script = base::android::ConvertJavaStringToUTF8(env, jscript);
  base::ReplaceSubstringsAfterOffset(&script, 0, "__MIRU_NONCE__",
                                     bridge_nonce_);
  const std::u16string script16 = base::UTF8ToUTF16(script);

  if (!all_frames) {
    content::RenderFrameHost* main_rfh = web_contents_->GetPrimaryMainFrame();
    if (!main_rfh || !main_rfh->IsRenderFrameLive()) {
      if (request_id > 0) {
        Java_MiruChromiumTab_onNativeIsolatedJsResult(
            env, java_tab_, request_id,
            base::android::ConvertUTF8ToJavaString(env, ""));
      }
      return;
    }
    if (request_id <= 0) {
      main_rfh->ExecuteJavaScriptInIsolatedWorld(
          script16, base::NullCallback(),
          content::ISOLATED_WORLD_ID_CONTENT_END);
      return;
    }
    main_rfh->ExecuteJavaScriptInIsolatedWorld(
        script16,
        base::BindOnce(
            [](base::WeakPtr<MiruTab> weak_tab, jlong req_id,
               base::Value val) {
              if (!weak_tab) {
                return;
              }
              const std::string json =
                  val.is_none() ? std::string()
                                : base::WriteJson(val).value_or("");
              weak_tab->NotifyIsolatedJsResult(req_id, json);
            },
            AsWeakPtr(), request_id),
        content::ISOLATED_WORLD_ID_CONTENT_END);
    return;
  }

  std::vector<content::RenderFrameHost*> targets;
  web_contents_->ForEachRenderFrameHost([&](content::RenderFrameHost* rfh) {
    if (rfh->IsRenderFrameLive() &&
        rfh->GetLastCommittedURL().SchemeIsHTTPOrHTTPS()) {
      targets.push_back(rfh);
    }
  });
  if (request_id <= 0) {
    for (content::RenderFrameHost* rfh : targets) {
      rfh->ExecuteJavaScriptInIsolatedWorld(
          script16, base::NullCallback(),
          content::ISOLATED_WORLD_ID_CONTENT_END);
    }
    return;
  }
  if (targets.empty()) {
    Java_MiruChromiumTab_onNativeIsolatedJsResult(
        env, java_tab_, request_id,
        base::android::ConvertUTF8ToJavaString(env, "[]"));
    return;
  }
  auto collector = base::MakeRefCounted<MultiFrameJsCollector>();
  collector->tab = AsWeakPtr();
  collector->request_id = request_id;
  collector->pending = targets.size();
  for (content::RenderFrameHost* rfh : targets) {
    rfh->ExecuteJavaScriptInIsolatedWorld(
        script16,
        base::BindOnce(&MultiFrameJsCollector::OnFrameResult, collector),
        content::ISOLATED_WORLD_ID_CONTENT_END);
  }
}

void MiruTab::CaptureThumbnail(JNIEnv* env, jint max_width, jlong request_id) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  content::RenderWidgetHostView* rwhv =
      web_contents_ ? web_contents_->GetRenderWidgetHostView() : nullptr;
  if (!rwhv) {
    Java_MiruChromiumTab_onNativeThumbnailResult(env, java_tab_, request_id,
                                                 nullptr);
    return;
  }
  const gfx::Size bounds = rwhv->GetViewBounds().size();
  const int w = std::max(64, static_cast<int>(max_width));
  const int h = bounds.width() > 0
                    ? std::max(64, static_cast<int>(
                                       static_cast<int64_t>(bounds.height()) *
                                       w / bounds.width()))
                    : (w * 16 / 9);
  rwhv->CopyFromSurface(
      gfx::Rect(), gfx::Size(w, h), base::Seconds(2),
      base::BindOnce(
          [](base::WeakPtr<MiruTab> weak_tab, jlong id,
             const content::CopyFromSurfaceResult& result) {
            if (!weak_tab) {
              return;
            }
            JNIEnv* cb_env = base::android::AttachCurrentThread();
            if (!result.has_value() || result->bitmap.drawsNothing()) {
              Java_MiruChromiumTab_onNativeThumbnailResult(
                  cb_env, weak_tab->java_tab_, id, nullptr);
              return;
            }
            auto jbmp = gfx::ConvertToJavaBitmap(
                result->bitmap, gfx::OomBehavior::kReturnNullOnOom);
            Java_MiruChromiumTab_onNativeThumbnailResult(
                cb_env, weak_tab->java_tab_, id, jbmp);
          },
          AsWeakPtr(), request_id));
}

void MiruTab::DestroyTab(JNIEnv*) {
  delete this;
}

void MiruTab::OnJavaScriptDialog(int64_t dialog_id,
                                 int dialog_type,
                                 const std::string& origin,
                                 const std::string& message,
                                 const std::string& default_prompt) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_onNativeJavaScriptDialog(
      env, java_tab_, static_cast<jlong>(dialog_id),
      static_cast<jint>(dialog_type),
      base::android::ConvertUTF8ToJavaString(env, origin),
      base::android::ConvertUTF8ToJavaString(env, message),
      base::android::ConvertUTF8ToJavaString(env, default_prompt));
}

void MiruTab::OnBeforeUnloadDialog(int64_t dialog_id,
                                   const std::string& origin,
                                   bool is_reload) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_onNativeBeforeUnloadDialog(
      env, java_tab_, static_cast<jlong>(dialog_id),
      base::android::ConvertUTF8ToJavaString(env, origin),
      is_reload ? JNI_TRUE : JNI_FALSE);
}

void MiruTab::OnDialogsCancelled(int64_t dialog_id, bool reset_state) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_onNativeDialogsCancelled(
      env, java_tab_, static_cast<jlong>(dialog_id),
      reset_state ? JNI_TRUE : JNI_FALSE);
}

void MiruTab::OnHttpAuthRequest(int64_t auth_id,
                                const std::string& url,
                                const std::string& challenger,
                                const std::string& realm,
                                const std::string& scheme,
                                bool is_proxy,
                                bool previous_failed) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_onNativeHttpAuthRequest(
      env, java_tab_, static_cast<jlong>(auth_id),
      base::android::ConvertUTF8ToJavaString(env, url),
      base::android::ConvertUTF8ToJavaString(env, challenger),
      base::android::ConvertUTF8ToJavaString(env, realm),
      base::android::ConvertUTF8ToJavaString(env, scheme),
      is_proxy ? JNI_TRUE : JNI_FALSE,
      previous_failed ? JNI_TRUE : JNI_FALSE);
}

void MiruTab::OnHttpAuthCancelled(int64_t auth_id) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_onNativeHttpAuthCancelled(
      env, java_tab_, static_cast<jlong>(auth_id));
}

bool MiruTab::CheckPopupAllowed(const std::string& opener_url,
                                const std::string& target_url,
                                bool user_gesture,
                                bool opener_suppressed) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  JNIEnv* env = base::android::AttachCurrentThread();
  jboolean allowed = Java_MiruChromiumTab_onNativeCheckPopupAllowed(
      env, java_tab_,
      base::android::ConvertUTF8ToJavaString(env, opener_url),
      base::android::ConvertUTF8ToJavaString(env, target_url),
      user_gesture ? JNI_TRUE : JNI_FALSE,
      opener_suppressed ? JNI_TRUE : JNI_FALSE);
  return allowed != JNI_FALSE;
}

void MiruTab::OnExternalProtocol(const std::string& url, bool has_user_gesture) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_onNativeExternalProtocol(
      env, java_tab_, base::android::ConvertUTF8ToJavaString(env, url),
      has_user_gesture ? JNI_TRUE : JNI_FALSE);
}

void MiruTab::ResolveJsDialog(JNIEnv* env,
                              jlong dialog_id,
                              jboolean accept,
                              const jni_zero::JavaRef<jstring>& jinput) {
  if (!js_dialog_manager_) {
    return;
  }
  const std::u16string input =
      jinput.is_null() ? std::u16string()
                       : base::android::ConvertJavaStringToUTF16(env, jinput);
  js_dialog_manager_->ResolveDialog(static_cast<int64_t>(dialog_id),
                                    accept != JNI_FALSE, input);
}

void MiruTab::ResolveRepostDialog(JNIEnv*, jboolean resubmit) {
  if (!web_contents_) {
    return;
  }
  if (resubmit != JNI_FALSE) {
    web_contents_->GetController().ContinuePendingReload();
  } else {
    web_contents_->GetController().CancelPendingReload();
  }
}

void MiruTab::NotifyContextMenuClosed(JNIEnv*) {
  if (web_contents_) {
    web_contents_->NotifyContextMenuClosed(GURL());
  }
}

void MiruTab::ReadyToCommitNavigation(
    content::NavigationHandle* navigation_handle) {
  if (navigation_handle && navigation_handle->IsInPrimaryMainFrame() &&
      web_contents_) {
    web_contents_->NotifyPreferencesChanged();
  }
}

void MiruTab::DidFinishNavigation(
    content::NavigationHandle* navigation_handle) {
  if (!navigation_handle->HasCommitted() ||
      navigation_handle->IsErrorPage()) {
    return;
  }
  const GURL& url = navigation_handle->GetURL();
  if (navigation_handle->IsInPrimaryMainFrame()) {
    NotifySecurityState();
  }
  if (!url.SchemeIsHTTPOrHTTPS()) {
    return;
  }
  if (auto* rfh = navigation_handle->GetRenderFrameHost()) {
    InjectCosmeticRules(rfh, url.spec());
  }
}

void MiruTab::DidChangeVisibleSecurityState() {
  NotifySecurityState();
}

void MiruTab::NotifySecurityState() {
  if (!web_contents_) {
    return;
  }
  content::NavigationEntry* entry =
      web_contents_->GetController().GetVisibleEntry();
  const GURL url = entry ? entry->GetURL() : GURL();
  const content::SSLStatus* ssl = entry ? &entry->GetSSL() : nullptr;
  const bool is_secure = url.SchemeIsCryptographic() && ssl &&
                         ssl->certificate &&
                         !net::IsCertStatusError(ssl->cert_status);
  JNIEnv* env = base::android::AttachCurrentThread();
  base::android::ScopedJavaLocalRef<jbyteArray> jcert;
  if (ssl && ssl->certificate) {
    std::string_view der =
        net::x509_util::CryptoBufferAsStringPiece(ssl->certificate->cert_buffer());
    if (!der.empty()) {
      jcert = base::android::ToJavaByteArray(env, base::as_byte_span(der));
    }
  }
  Java_MiruChromiumTab_onNativeSecurityStateChanged(
      env, java_tab_, is_secure ? JNI_TRUE : JNI_FALSE, jcert);
}

void MiruTab::DOMContentLoaded(content::RenderFrameHost* render_frame_host) {
  if (!render_frame_host || !render_frame_host->IsRenderFrameLive()) {
    return;
  }
  const GURL& url = render_frame_host->GetLastCommittedURL();
  if (!url.SchemeIsHTTPOrHTTPS()) {
    return;
  }
  InjectCosmeticRules(render_frame_host, url.spec());
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_onNativeFrameDomReady(
      env, java_tab_,
      render_frame_host->IsInPrimaryMainFrame() ? JNI_TRUE : JNI_FALSE,
      base::android::ConvertUTF8ToJavaString(env, url.spec()));
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
      "const apply = () => {"
      "  if (r.hide_selectors && r.hide_selectors.length) {"
      "    const target = document.head || document.documentElement;"
      "    if (!target) return false;"
      "    const css = r.hide_selectors.join(',') + '{display:none!important}';"
      "    let el = document.getElementById('__miru_cosmetic');"
      "    if (!el) {"
      "      el = document.createElement('style');"
      "      el.id = '__miru_cosmetic';"
      "      target.appendChild(el);"
      "    }"
      "    el.textContent = css;"
      "  }"
      "  if (r.injected_script && r.injected_script.length && !globalThis.__miruScriptInjected) {"
      "    globalThis.__miruScriptInjected = true;"
      "    try { (0, eval)(r.injected_script); } catch (_) {}"
      "  }"
      "  return true;"
      "};"
      "if (!apply()) {"
      "  document.addEventListener('DOMContentLoaded', () => apply(), { once: true });"
      "}"
      "})();";
  rfh->ExecuteJavaScriptInIsolatedWorld(
      base::UTF8ToUTF16(script), base::NullCallback(),
      content::ISOLATED_WORLD_ID_CONTENT_END);
}

bool MiruTab::DidAddMessageToConsole(
    content::WebContents* /*source*/,
    blink::mojom::ConsoleMessageLevel /*log_level*/,
    const std::u16string& message,
    int32_t /*line_no*/,
    const std::u16string& /*source_id*/) {
  const std::u16string expected =
      std::u16string(kBridgePrefix) + base::UTF8ToUTF16(bridge_nonce_) + u":";
  if (!message.starts_with(expected)) {
    return false;
  }
  const std::string payload =
      base::UTF16ToUTF8(std::u16string_view(message).substr(expected.size()));
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_onNativeBridgeEvent(
      env, java_tab_, base::android::ConvertUTF8ToJavaString(env, payload));
  return true;
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

content::WebContents* MiruTab::AddNewContents(
    content::WebContents* /*source*/,
    std::unique_ptr<content::WebContents> new_contents,
    const GURL& target_url,
    WindowOpenDisposition /*disposition*/,
    const blink::mojom::WindowFeatures& /*window_features*/,
    bool /*user_gesture*/,
    bool* was_blocked) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!new_contents) {
    if (was_blocked) {
      *was_blocked = true;
    }
    return nullptr;
  }
  content::WebContents* raw_wc = new_contents.get();
  const int64_t adopt_id = NextAdoptId()++;
  PendingAdoptions()[adopt_id] = std::move(new_contents);

  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_onNativeAdoptPopupTab(
      env, java_tab_, static_cast<jlong>(adopt_id),
      base::android::ConvertUTF8ToJavaString(
          env, target_url.is_valid() ? target_url.spec() : std::string()));

  if (PendingAdoptions().erase(adopt_id) > 0) {
    if (was_blocked) {
      *was_blocked = true;
    }
    return nullptr;
  }
  return raw_wc;
}

bool MiruTab::HandleContextMenu(content::RenderFrameHost& /*render_frame_host*/,
                                const content::ContextMenuParams& params) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  const bool has_link = params.link_url.is_valid();
  const bool is_image =
      params.media_type == blink::mojom::ContextMenuDataMediaType::kImage;
  const bool is_video =
      params.media_type == blink::mojom::ContextMenuDataMediaType::kVideo;
  if (!has_link && !is_image && !is_video) {
    return false;
  }
  const int media_kind = is_image ? 1 : (is_video ? 2 : 0);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_onNativeContextMenu(
      env, java_tab_, static_cast<jint>(media_kind),
      base::android::ConvertUTF8ToJavaString(
          env, has_link ? params.link_url.spec() : std::string()),
      base::android::ConvertUTF16ToJavaString(env, params.link_text),
      base::android::ConvertUTF8ToJavaString(
          env, params.src_url.is_valid() ? params.src_url.spec() : std::string()),
      base::android::ConvertUTF16ToJavaString(
          env, params.title_text.empty() ? params.alt_text : params.title_text),
      base::android::ConvertUTF8ToJavaString(
          env, params.page_url.is_valid() ? params.page_url.spec() : std::string()),
      base::android::ConvertUTF8ToJavaString(
          env, params.frame_url.is_valid() ? params.frame_url.spec() : std::string()),
      base::android::ConvertUTF8ToJavaString(
          env, ReferrerPolicyToString(params.referrer_policy)));
  return true;
}

void MiruTab::ShowRepostFormWarningDialog(content::WebContents* /*source*/) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_MiruChromiumTab_onNativeRepostDialog(env, java_tab_);
}

void MiruTab::RequestMediaAccessPermission(
    content::WebContents*,
    const content::MediaStreamRequest& request,
    content::MediaResponseCallback callback) {
  MiruMediaAccessHandler::Request(this, request, std::move(callback));
}

bool MiruTab::CheckMediaAccessPermission(
    content::RenderFrameHost*,
    const url::Origin& security_origin,
    blink::mojom::MediaStreamType type) {
  return MiruMediaAccessHandler::Check(this, security_origin, type);
}

content::JavaScriptDialogManager* MiruTab::GetJavaScriptDialogManager(
    content::WebContents* /*source*/) {
  return js_dialog_manager_.get();
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

static jlong JNI_MiruChromiumTab_CreateAdoptedTab(
    JNIEnv*,
    const base::android::JavaRef<jobject>& jcaller,
    jlong adopt_id) {
  auto it = PendingAdoptions().find(static_cast<int64_t>(adopt_id));
  CHECK(it != PendingAdoptions().end());
  std::unique_ptr<content::WebContents> wc = std::move(it->second);
  PendingAdoptions().erase(it);
  auto* tab = new MiruTab(jcaller, std::move(wc));
  return reinterpret_cast<jlong>(tab);
}

static void JNI_MiruChromiumTab_ResolveHttpAuth(
    JNIEnv* env,
    jlong auth_id,
    jboolean submit,
    const base::android::JavaRef<jstring>& jusername,
    const base::android::JavaRef<jstring>& jpassword) {
  const std::u16string username =
      jusername.is_null() ? std::u16string()
                          : base::android::ConvertJavaStringToUTF16(env, jusername);
  const std::u16string password =
      jpassword.is_null() ? std::u16string()
                          : base::android::ConvertJavaStringToUTF16(env, jpassword);
  MiruLoginDelegate::Resolve(static_cast<int64_t>(auth_id), submit != JNI_FALSE,
                             username, password);
}

}  // namespace miru

DEFINE_JNI(MiruChromiumTab)
