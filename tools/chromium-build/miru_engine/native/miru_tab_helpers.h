// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_TAB_HELPERS_H_
#define MIRU_ENGINE_NATIVE_MIRU_TAB_HELPERS_H_

#include <string>

#include "services/network/public/mojom/referrer_policy.mojom.h"

namespace miru {

const char* ReferrerPolicyToString(network::mojom::ReferrerPolicy policy);
std::string SanitizeJsonForJsLiteral(const char* raw_json);

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_TAB_HELPERS_H_
