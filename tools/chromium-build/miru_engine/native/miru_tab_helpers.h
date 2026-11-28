// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_TAB_HELPERS_H_
#define MIRU_ENGINE_NATIVE_MIRU_TAB_HELPERS_H_

#include <cstdint>
#include <string>

#include "base/functional/callback.h"
#include "services/network/public/mojom/referrer_policy.mojom.h"
#include "third_party/skia/include/core/SkBitmap.h"

namespace content {
class RenderFrameHost;
class WebContents;
}

namespace miru {

const char* ReferrerPolicyToString(network::mojom::ReferrerPolicy policy);
std::string SanitizeJsonForJsLiteral(const char* raw_json);
void InjectCosmeticRulesInFrame(content::RenderFrameHost* rfh,
                                const std::string& url);

void EvaluateIsolatedJsInWebContents(
    content::WebContents* web_contents,
    const std::string& bridge_nonce,
    std::string script,
    bool all_frames,
    int64_t request_id,
    base::RepeatingCallback<void(int64_t, const std::string&)> on_result);

void CaptureWebContentsThumbnail(
    content::WebContents* web_contents,
    int max_width,
    int64_t request_id,
    base::OnceCallback<void(int64_t, const SkBitmap*)> on_result);

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_TAB_HELPERS_H_
