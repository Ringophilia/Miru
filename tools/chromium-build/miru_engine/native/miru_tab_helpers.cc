// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_tab_helpers.h"

#include "base/strings/string_util.h"

namespace miru {

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

}  // namespace miru
