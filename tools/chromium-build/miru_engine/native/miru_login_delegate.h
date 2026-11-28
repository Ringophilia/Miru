// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_LOGIN_DELEGATE_H_
#define MIRU_ENGINE_NATIVE_MIRU_LOGIN_DELEGATE_H_

#include <cstdint>
#include <string>

#include "base/memory/weak_ptr.h"
#include "content/public/browser/login_delegate.h"
#include "net/base/auth.h"
#include "url/gurl.h"

namespace miru {

class MiruTab;

/// Bridges HTTP 401/407 `CreateLoginDelegate` challenges asynchronously to Java/Kotlin.
class MiruLoginDelegate final : public content::LoginDelegate {
 public:
  static void Resolve(int64_t auth_id,
                      bool submit,
                      const std::u16string& username,
                      const std::u16string& password);

  MiruLoginDelegate(base::WeakPtr<MiruTab> tab,
                    const net::AuthChallengeInfo& auth_info,
                    const GURL& request_url,
                    bool first_auth_attempt,
                    LoginAuthRequiredCallback callback);
  MiruLoginDelegate(const MiruLoginDelegate&) = delete;
  MiruLoginDelegate& operator=(const MiruLoginDelegate&) = delete;
  ~MiruLoginDelegate() override;

 private:
  void DispatchToTab(std::string url,
                     std::string challenger,
                     std::string realm,
                     std::string scheme,
                     bool is_proxy,
                     bool previous_failed);

  base::WeakPtr<MiruTab> tab_;
  int64_t auth_id_;
  LoginAuthRequiredCallback callback_;
  base::WeakPtrFactory<MiruLoginDelegate> weak_factory_{this};
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_LOGIN_DELEGATE_H_
