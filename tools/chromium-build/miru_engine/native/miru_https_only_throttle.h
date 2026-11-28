// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_HTTPS_ONLY_THROTTLE_H_
#define MIRU_ENGINE_NATIVE_MIRU_HTTPS_ONLY_THROTTLE_H_

#include "content/public/browser/navigation_throttle.h"

namespace miru {

/// Upgrades non-localhost top-level HTTP navigations to HTTPS when HTTPS-Only
/// security mode is enabled.
class MiruHttpsOnlyNavigationThrottle final : public content::NavigationThrottle {
 public:
  explicit MiruHttpsOnlyNavigationThrottle(
      content::NavigationThrottleRegistry& registry);
  MiruHttpsOnlyNavigationThrottle(const MiruHttpsOnlyNavigationThrottle&) =
      delete;
  MiruHttpsOnlyNavigationThrottle& operator=(
      const MiruHttpsOnlyNavigationThrottle&) = delete;
  ~MiruHttpsOnlyNavigationThrottle() override;

  ThrottleCheckResult WillStartRequest() override;
  ThrottleCheckResult WillRedirectRequest() override;
  const char* GetNameForLogging() override;

 private:
  ThrottleCheckResult MaybeUpgradeToHttps();
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_HTTPS_ONLY_THROTTLE_H_
