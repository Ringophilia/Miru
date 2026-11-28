// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_https_only_throttle.h"

#include <utility>

#include "base/functional/bind.h"
#include "base/memory/weak_ptr.h"
#include "content/public/browser/browser_task_traits.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/navigation_controller.h"
#include "content/public/browser/navigation_handle.h"
#include "content/public/browser/web_contents.h"
#include "content/public/common/referrer.h"
#include "net/base/url_util.h"
#include "third_party/blink/public/mojom/loader/referrer.mojom.h"
#include "url/gurl.h"
#include "url/url_constants.h"

namespace miru {

MiruHttpsOnlyNavigationThrottle::MiruHttpsOnlyNavigationThrottle(
    content::NavigationThrottleRegistry& registry)
    : content::NavigationThrottle(registry) {}

MiruHttpsOnlyNavigationThrottle::~MiruHttpsOnlyNavigationThrottle() = default;

content::NavigationThrottle::ThrottleCheckResult
MiruHttpsOnlyNavigationThrottle::WillStartRequest() {
  return MaybeUpgradeToHttps();
}

content::NavigationThrottle::ThrottleCheckResult
MiruHttpsOnlyNavigationThrottle::WillRedirectRequest() {
  return MaybeUpgradeToHttps();
}

const char* MiruHttpsOnlyNavigationThrottle::GetNameForLogging() {
  return "MiruHttpsOnlyNavigationThrottle";
}

content::NavigationThrottle::ThrottleCheckResult
MiruHttpsOnlyNavigationThrottle::MaybeUpgradeToHttps() {
  content::NavigationHandle* handle = navigation_handle();
  if (!handle || !handle->IsInPrimaryMainFrame()) {
    return PROCEED;
  }
  const GURL& url = handle->GetURL();
  if (!url.is_valid() || !url.SchemeIs(url::kHttpScheme) ||
      net::IsLocalhost(url)) {
    return PROCEED;
  }
  GURL::Replacements rep;
  rep.SetSchemeStr(url::kHttpsScheme);
  if (url.IntPort() == 80) {
    rep.ClearPort();
  }
  GURL upgraded = url.ReplaceComponents(rep);
  if (!upgraded.is_valid()) {
    return PROCEED;
  }
  content::WebContents* wc = handle->GetWebContents();
  if (!wc) {
    return PROCEED;
  }
  content::Referrer referrer(handle->GetReferrer().url,
                             handle->GetReferrer().policy);
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE,
      base::BindOnce(
          [](base::WeakPtr<content::WebContents> weak_wc,
             GURL target,
             ui::PageTransition transition,
             content::Referrer ref) {
            if (!weak_wc) {
              return;
            }
            content::NavigationController::LoadURLParams params(target);
            params.transition_type = transition;
            params.referrer = std::move(ref);
            weak_wc->GetController().LoadURLWithParams(params);
          },
          wc->GetWeakPtr(), std::move(upgraded), handle->GetPageTransition(),
          std::move(referrer)));
  return CANCEL_AND_IGNORE;
}

}  // namespace miru
