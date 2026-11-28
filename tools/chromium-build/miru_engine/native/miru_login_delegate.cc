// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_login_delegate.h"

#include <optional>
#include <utility>

#include "base/containers/flat_map.h"
#include "base/functional/bind.h"
#include "base/no_destructor.h"
#include "content/public/browser/browser_task_traits.h"
#include "content/public/browser/browser_thread.h"
#include "miru_engine/native/miru_tab.h"

namespace miru {

namespace {

base::flat_map<int64_t, MiruLoginDelegate*>& ActiveLogins() {
  static base::NoDestructor<base::flat_map<int64_t, MiruLoginDelegate*>> map;
  return *map;
}

int64_t& NextAuthId() {
  static int64_t next = 1;
  return next;
}

}  // namespace

// static
void MiruLoginDelegate::Resolve(int64_t auth_id,
                                bool submit,
                                const std::u16string& username,
                                const std::u16string& password) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  auto it = ActiveLogins().find(auth_id);
  if (it == ActiveLogins().end()) {
    return;
  }
  MiruLoginDelegate* self = it->second;
  ActiveLogins().erase(it);
  LoginAuthRequiredCallback cb = std::move(self->callback_);
  if (!cb) {
    return;
  }
  if (submit) {
    std::move(cb).Run(std::make_optional<net::AuthCredentials>(username, password));
  } else {
    std::move(cb).Run(std::nullopt);
  }
}

MiruLoginDelegate::MiruLoginDelegate(base::WeakPtr<MiruTab> tab,
                                     const net::AuthChallengeInfo& auth_info,
                                     const GURL& request_url,
                                     bool first_auth_attempt,
                                     LoginAuthRequiredCallback callback)
    : tab_(std::move(tab)),
      auth_id_(NextAuthId()++),
      callback_(std::move(callback)) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  ActiveLogins()[auth_id_] = this;
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE,
      base::BindOnce(&MiruLoginDelegate::DispatchToTab,
                     weak_factory_.GetWeakPtr(), request_url.spec(),
                     auth_info.challenger.Serialize(), auth_info.realm,
                     auth_info.scheme, auth_info.is_proxy,
                     !first_auth_attempt));
}

MiruLoginDelegate::~MiruLoginDelegate() {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  const bool was_pending = ActiveLogins().erase(auth_id_) > 0 && callback_;
  // `callback_` must not be run on destruction per LoginDelegate contract.
  callback_.Reset();
  if (was_pending && tab_) {
    tab_->OnHttpAuthCancelled(auth_id_);
  }
}

void MiruLoginDelegate::DispatchToTab(std::string url,
                                      std::string challenger,
                                      std::string realm,
                                      std::string scheme,
                                      bool is_proxy,
                                      bool previous_failed) {
  if (!callback_) {
    return;
  }
  if (!tab_) {
    Resolve(auth_id_, false, std::u16string(), std::u16string());
    return;
  }
  tab_->OnHttpAuthRequest(auth_id_, url, challenger, realm, scheme, is_proxy,
                          previous_failed);
}

}  // namespace miru
