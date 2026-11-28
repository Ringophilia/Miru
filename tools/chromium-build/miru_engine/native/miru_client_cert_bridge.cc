// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_client_cert_bridge.h"

#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "base/android/jni_android.h"
#include "base/android/jni_array.h"
#include "base/containers/flat_map.h"
#include "base/functional/bind.h"
#include "base/functional/callback_helpers.h"
#include "base/memory/raw_ptr.h"
#include "base/no_destructor.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/client_certificate_delegate.h"
#include "content/public/browser/web_contents.h"
#include "miru_engine/native/miru_runtime_bridges.h"
#include "miru_engine/native/miru_tab.h"
#include "net/cert/x509_certificate.h"
#include "net/ssl/ssl_cert_request_info.h"
#include "net/ssl/ssl_platform_key_android.h"
#include "net/ssl/ssl_private_key.h"

namespace miru {

namespace {

struct PendingClientCert {
  raw_ptr<MiruTab> tab = nullptr;
  std::unique_ptr<content::ClientCertificateDelegate> delegate;
};

base::flat_map<int64_t, PendingClientCert>& PendingCerts() {
  static base::NoDestructor<base::flat_map<int64_t, PendingClientCert>> map;
  return *map;
}

int64_t& NextClientCertId() {
  static int64_t next = 1;
  return next;
}

void CancelPendingRequest(int64_t request_id) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (PendingCerts().erase(request_id) > 0) {
    CancelClientCertificateBridge(request_id);
  }
}

}  // namespace

// static
base::OnceClosure MiruClientCertBridge::SelectClientCertificate(
    content::WebContents* web_contents,
    net::SSLCertRequestInfo* cert_request_info,
    std::unique_ptr<content::ClientCertificateDelegate> delegate) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!delegate) {
    return base::DoNothing();
  }
  MiruTab* tab = web_contents ? MiruTab::FromWebContents(web_contents) : nullptr;
  if (!tab || !cert_request_info) {
    delegate->ContinueWithCertificate(nullptr, nullptr);
    return base::DoNothing();
  }

  const int64_t request_id = NextClientCertId()++;
  PendingCerts()[request_id] = PendingClientCert{
      .tab = tab,
      .delegate = std::move(delegate),
  };

  SelectClientCertificateBridge(
      request_id, reinterpret_cast<int64_t>(tab),
      cert_request_info->host_and_port.host(),
      cert_request_info->cert_authorities);
  return base::BindOnce(&CancelPendingRequest, request_id);
}

// static
void MiruClientCertBridge::Resolve(
    JNIEnv* env,
    int64_t request_id,
    const base::android::JavaRef<jobjectArray>& encoded_chain_ref,
    const base::android::JavaRef<jobject>& private_key_ref) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  auto it = PendingCerts().find(request_id);
  if (it == PendingCerts().end()) {
    return;
  }
  std::unique_ptr<content::ClientCertificateDelegate> delegate =
      std::move(it->second.delegate);
  PendingCerts().erase(it);
  if (!delegate) {
    return;
  }

  if (!encoded_chain_ref || !private_key_ref) {
    delegate->ContinueWithCertificate(nullptr, nullptr);
    return;
  }

  std::vector<std::string> encoded_chain_strings;
  base::android::JavaArrayOfByteArrayToStringVector(env, encoded_chain_ref,
                                                    &encoded_chain_strings);
  std::vector<std::string_view> encoded_chain;
  encoded_chain.reserve(encoded_chain_strings.size());
  for (const auto& cert_der : encoded_chain_strings) {
    encoded_chain.push_back(cert_der);
  }

  scoped_refptr<net::X509Certificate> client_cert(
      net::X509Certificate::CreateFromDERCertChain(encoded_chain));
  if (!client_cert) {
    delegate->ContinueWithCertificate(nullptr, nullptr);
    return;
  }

  scoped_refptr<net::SSLPrivateKey> private_key =
      net::WrapJavaPrivateKey(client_cert.get(), private_key_ref);
  if (!private_key) {
    delegate->ContinueWithCertificate(nullptr, nullptr);
    return;
  }

  delegate->ContinueWithCertificate(std::move(client_cert),
                                    std::move(private_key));
}

// static
void MiruClientCertBridge::CancelForTab(MiruTab* tab) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!tab) {
    return;
  }
  std::vector<int64_t> ids;
  for (const auto& entry : PendingCerts()) {
    if (entry.second.tab == tab) {
      ids.push_back(entry.first);
    }
  }
  for (int64_t id : ids) {
    auto it = PendingCerts().find(id);
    if (it != PendingCerts().end()) {
      auto delegate = std::move(it->second.delegate);
      PendingCerts().erase(it);
      if (delegate) {
        delegate->ContinueWithCertificate(nullptr, nullptr);
      }
      CancelClientCertificateBridge(id);
    }
  }
}

}  // namespace miru
