// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_url_loader_throttle.h"

#include <string>
#include <utility>

#include "base/functional/bind.h"
#include "miru_core.h"
#include "miru_engine/native/miru_content_browser_client.h"
#include "miru_engine/native/miru_tab.h"
#include "net/base/net_errors.h"
#include "services/network/public/cpp/resource_request.h"
#include "services/network/public/mojom/fetch_api.mojom-shared.h"
#include "services/network/public/mojom/url_response_head.mojom.h"

namespace miru {

const char* MiruDestinationToRequestType(
    network::mojom::RequestDestination dest) {
  using Dest = network::mojom::RequestDestination;
  switch (dest) {
    case Dest::kDocument:
      return "document";
    case Dest::kIframe:
    case Dest::kFrame:
    case Dest::kFencedframe:
      return "sub_frame";
    case Dest::kScript:
      return "script";
    case Dest::kStyle:
      return "stylesheet";
    case Dest::kImage:
      return "image";
    case Dest::kFont:
      return "font";
    case Dest::kAudio:
    case Dest::kVideo:
    case Dest::kTrack:
      return "media";
    case Dest::kWorker:
    case Dest::kSharedWorker:
    case Dest::kServiceWorker:
      return "script";
    default:
      return "other";
  }
}

MiruUrlLoaderThrottle::MiruUrlLoaderThrottle(
    base::WeakPtr<MiruTab> tab,
    scoped_refptr<base::SequencedTaskRunner> ui_task_runner,
    std::string page_url)
    : tab_(std::move(tab)),
      ui_task_runner_(std::move(ui_task_runner)),
      page_url_(std::move(page_url)) {}

MiruUrlLoaderThrottle::~MiruUrlLoaderThrottle() = default;

void MiruUrlLoaderThrottle::WillStartRequest(network::ResourceRequest* request,
                                             bool* defer) {
  auto* client = MiruContentBrowserClient::Get();
  auto adblock_ref = client ? client->GetAdblockRef() : nullptr;
  MiruAdblockHandle* adblock = adblock_ref ? adblock_ref->ptr : nullptr;
  if (!adblock || !request->url.SchemeIsHTTPOrHTTPS()) {
    return;
  }

  // Sub-resource requests should evaluate against the owning tab or initiator URL.
  std::string source_url = page_url_;
  if (source_url.empty() && request->request_initiator.has_value()) {
    source_url = request->request_initiator->Serialize();
  }
  const std::string target_url = request->url.spec();
  if (source_url.empty()) {
    source_url = target_url;
  }
  if (request->destination == network::mojom::RequestDestination::kDocument) {
    // Allow top-level document navigations unless strict user rules apply later.
    return;
  }

  const char* req_type = MiruDestinationToRequestType(request->destination);
  if (miru_adblock_should_block(adblock, target_url.c_str(),
                                source_url.c_str(), req_type) != 0) {
    delegate_->CancelWithError(net::ERR_BLOCKED_BY_CLIENT, "MiruAdblock");
    if (ui_task_runner_ && tab_) {
      ui_task_runner_->PostTask(
          FROM_HERE,
          base::BindOnce(&MiruTab::NotifyAdBlocked, tab_, target_url));
    }
  }
}

void MiruUrlLoaderThrottle::WillProcessResponse(
    const GURL& response_url,
    network::mojom::URLResponseHead* response_head,
    bool* defer) {
  if (!response_head || !response_url.SchemeIsHTTPOrHTTPS()) {
    return;
  }
  const std::string url = response_url.spec();
  char* kind_ptr = miru_media_classify(
      url.c_str(), response_head->mime_type.c_str(),
      static_cast<int64_t>(response_head->content_length));
  if (!kind_ptr) {
    return;
  }
  std::string kind(kind_ptr);
  miru_string_free(kind_ptr);

  if (ui_task_runner_ && tab_) {
    ui_task_runner_->PostTask(
        FROM_HERE,
        base::BindOnce(&MiruTab::NotifyMediaDetected, tab_, url, std::move(kind),
                       response_head->mime_type,
                       static_cast<int64_t>(response_head->content_length)));
  }
}

}  // namespace miru
