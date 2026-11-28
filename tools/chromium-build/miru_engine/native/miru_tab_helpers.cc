// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_tab_helpers.h"

#include <algorithm>
#include <vector>

#include "base/functional/bind.h"
#include "base/functional/callback_helpers.h"
#include "base/json/json_writer.h"
#include "base/memory/ref_counted.h"
#include "base/strings/string_util.h"
#include "base/strings/utf_string_conversions.h"
#include "base/time/time.h"
#include "base/values.h"
#include "components/viz/common/frame_sinks/copy_output_result.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/render_widget_host_view.h"
#include "content/public/browser/web_contents.h"
#include "content/public/common/isolated_world_ids.h"
#include "miru_core.h"
#include "miru_engine/native/miru_content_browser_client.h"
#include "ui/gfx/geometry/rect.h"
#include "ui/gfx/geometry/size.h"

namespace miru {

namespace {

struct MultiFrameJsCollector
    : public base::RefCounted<MultiFrameJsCollector> {
  int64_t request_id = 0;
  size_t pending = 0;
  base::ListValue results;
  base::RepeatingCallback<void(int64_t, const std::string&)> on_result;

  void OnFrameResult(base::Value val) {
    if (!val.is_none()) {
      results.Append(std::move(val));
    }
    if (pending > 0) {
      --pending;
    }
    if (pending == 0 && on_result) {
      on_result.Run(request_id, base::WriteJson(results).value_or("[]"));
    }
  }

 private:
  friend class base::RefCounted<MultiFrameJsCollector>;
  ~MultiFrameJsCollector() = default;
};

}  // namespace

const char* ReferrerPolicyToString(network::mojom::ReferrerPolicy policy) {
  switch (policy) {
    case network::mojom::ReferrerPolicy::kAlways:
      return "unsafe-url";
    case network::mojom::ReferrerPolicy::kNoReferrerWhenDowngrade:
      return "no-referrer-when-downgrade";
    case network::mojom::ReferrerPolicy::kNever:
      return "no-referrer";
    case network::mojom::ReferrerPolicy::kOrigin:
      return "origin";
    case network::mojom::ReferrerPolicy::kOriginWhenCrossOrigin:
      return "origin-when-cross-origin";
    case network::mojom::ReferrerPolicy::kStrictOriginWhenCrossOrigin:
      return "strict-origin-when-cross-origin";
    case network::mojom::ReferrerPolicy::kSameOrigin:
      return "same-origin";
    case network::mojom::ReferrerPolicy::kStrictOrigin:
      return "strict-origin";
    case network::mojom::ReferrerPolicy::kDefault:
      return "";
  }
  return "";
}

std::string SanitizeJsonForJsLiteral(const char* raw_json) {
  std::string out(raw_json);
  // U+2028 / U+2029 are valid JSON but line terminators inside JS literals.
  base::ReplaceSubstringsAfterOffset(&out, 0, "\xe2\x80\xa8", "\\u2028");
  base::ReplaceSubstringsAfterOffset(&out, 0, "\xe2\x80\xa9", "\\u2029");
  return out;
}

void InjectCosmeticRulesInFrame(content::RenderFrameHost* rfh,
                                const std::string& url) {
  if (!rfh || !rfh->IsRenderFrameLive()) {
    return;
  }
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

void EvaluateIsolatedJsInWebContents(
    content::WebContents* web_contents,
    const std::string& bridge_nonce,
    std::string script,
    bool all_frames,
    int64_t request_id,
    base::RepeatingCallback<void(int64_t, const std::string&)> on_result) {
  if (!web_contents) {
    if (request_id > 0 && on_result) {
      on_result.Run(request_id, all_frames ? "[]" : "");
    }
    return;
  }
  base::ReplaceSubstringsAfterOffset(&script, 0, "__MIRU_NONCE__", bridge_nonce);
  const std::u16string script16 = base::UTF8ToUTF16(script);

  if (!all_frames) {
    content::RenderFrameHost* main_rfh = web_contents->GetPrimaryMainFrame();
    if (!main_rfh || !main_rfh->IsRenderFrameLive()) {
      if (request_id > 0 && on_result) {
        on_result.Run(request_id, "");
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
            [](base::RepeatingCallback<void(int64_t, const std::string&)> cb,
               int64_t req_id, base::Value val) {
              if (!cb) {
                return;
              }
              const std::string json =
                  val.is_none() ? std::string()
                                : base::WriteJson(val).value_or("");
              cb.Run(req_id, json);
            },
            on_result, request_id),
        content::ISOLATED_WORLD_ID_CONTENT_END);
    return;
  }

  std::vector<content::RenderFrameHost*> targets;
  web_contents->ForEachRenderFrameHost([&](content::RenderFrameHost* rfh) {
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
    if (on_result) {
      on_result.Run(request_id, "[]");
    }
    return;
  }
  auto collector = base::MakeRefCounted<MultiFrameJsCollector>();
  collector->request_id = request_id;
  collector->pending = targets.size();
  collector->on_result = std::move(on_result);
  for (content::RenderFrameHost* rfh : targets) {
    rfh->ExecuteJavaScriptInIsolatedWorld(
        script16,
        base::BindOnce(&MultiFrameJsCollector::OnFrameResult, collector),
        content::ISOLATED_WORLD_ID_CONTENT_END);
  }
}

void CaptureWebContentsThumbnail(
    content::WebContents* web_contents,
    int max_width,
    int64_t request_id,
    base::OnceCallback<void(int64_t, const SkBitmap*)> on_result) {
  content::RenderWidgetHostView* rwhv =
      web_contents ? web_contents->GetRenderWidgetHostView() : nullptr;
  if (!rwhv) {
    std::move(on_result).Run(request_id, nullptr);
    return;
  }
  const gfx::Size bounds = rwhv->GetViewBounds().size();
  const int w = std::max(64, max_width);
  const int h = bounds.width() > 0
                    ? std::max(64, static_cast<int>(
                                       static_cast<int64_t>(bounds.height()) *
                                       w / bounds.width()))
                    : (w * 16 / 9);
  rwhv->CopyFromSurface(
      gfx::Rect(), gfx::Size(w, h), base::Seconds(2),
      base::BindOnce(
          [](base::OnceCallback<void(int64_t, const SkBitmap*)> cb, int64_t id,
             const content::CopyFromSurfaceResult& result) {
            if (!result.has_value() || result->bitmap.drawsNothing()) {
              std::move(cb).Run(id, nullptr);
              return;
            }
            std::move(cb).Run(id, &result->bitmap);
          },
          std::move(on_result), request_id));
}

}  // namespace miru
