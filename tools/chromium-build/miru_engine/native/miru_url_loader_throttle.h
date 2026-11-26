// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_URL_LOADER_THROTTLE_H_
#define MIRU_ENGINE_NATIVE_MIRU_URL_LOADER_THROTTLE_H_

#include <string>

#include "base/memory/scoped_refptr.h"
#include "base/memory/weak_ptr.h"
#include "base/task/sequenced_task_runner.h"
#include "services/network/public/mojom/fetch_api.mojom-shared.h"
#include "third_party/blink/public/common/loader/url_loader_throttle.h"
#include "url/gurl.h"

namespace network {
struct ResourceRequest;
}  // namespace network

namespace miru {

class MiruTab;

const char* MiruDestinationToRequestType(
    network::mojom::RequestDestination dest);

/// Extracts sanitized `Referer`, `Origin`, `User-Agent`, and `Authorization`
/// headers as an in-memory JSON object string for process-only media handoff.
std::string MiruSerializeRequestHeadersJson(
    const network::ResourceRequest& request);

/// Network throttle calling `libmiru_core` directly in C++ (zero per-request JNI).
class MiruUrlLoaderThrottle : public blink::URLLoaderThrottle {
 public:
  MiruUrlLoaderThrottle(
      base::WeakPtr<MiruTab> tab,
      scoped_refptr<base::SequencedTaskRunner> ui_task_runner,
      std::string page_url);
  ~MiruUrlLoaderThrottle() override;

  void WillStartRequest(network::ResourceRequest* request,
                        bool* defer) override;
  void WillProcessResponse(
      const GURL& response_url,
      network::mojom::URLResponseHead* response_head,
      bool* defer) override;

 private:
  base::WeakPtr<MiruTab> tab_;
  scoped_refptr<base::SequencedTaskRunner> ui_task_runner_;
  std::string page_url_;
  std::string headers_json_;
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_URL_LOADER_THROTTLE_H_
