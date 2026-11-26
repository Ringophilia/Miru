// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_url_loader_factory.h"

#include <utility>

#include "base/functional/bind.h"
#include "content/public/browser/web_contents.h"
#include "miru_core.h"
#include "miru_engine/native/miru_content_browser_client.h"
#include "miru_engine/native/miru_tab.h"
#include "miru_engine/native/miru_url_loader_throttle.h"
#include "net/base/net_errors.h"
#include "net/traffic_annotation/network_traffic_annotation.h"
#include "services/network/public/cpp/resource_request.h"
#include "services/network/public/cpp/url_loader_completion_status.h"
#include "services/network/public/mojom/url_loader.mojom.h"

namespace miru {

// static
void MiruURLLoaderFactory::Create(
    mojo::PendingReceiver<network::mojom::URLLoaderFactory> proxy_receiver,
    mojo::PendingRemote<network::mojom::URLLoaderFactory> target_factory,
    base::WeakPtr<MiruTab> tab,
    std::string page_url) {
  base::MakeSelfDeleting<MiruURLLoaderFactory>(
      std::move(proxy_receiver), std::move(target_factory), std::move(tab),
      std::move(page_url));
}

MiruURLLoaderFactory::MiruURLLoaderFactory(
    mojo::PendingReceiver<network::mojom::URLLoaderFactory> factory_receiver,
    mojo::PendingRemote<network::mojom::URLLoaderFactory> target_factory,
    base::WeakPtr<MiruTab> tab,
    std::string page_url,
    base::SelfDeletingPassKey key)
    : network::SelfDeletingURLLoaderFactory(std::move(factory_receiver), key),
      target_factory_(std::move(target_factory)),
      tab_(std::move(tab)),
      page_url_(std::move(page_url)) {
  target_factory_.set_disconnect_handler(base::BindOnce(
      &MiruURLLoaderFactory::DisconnectReceiversAndDestroy,
      base::Unretained(this)));
}

MiruURLLoaderFactory::~MiruURLLoaderFactory() = default;

void MiruURLLoaderFactory::CreateLoaderAndStart(
    mojo::PendingReceiver<network::mojom::URLLoader> loader,
    int32_t request_id,
    uint32_t options,
    const network::ResourceRequest& request,
    mojo::PendingRemote<network::mojom::URLLoaderClient> client,
    const net::MutableNetworkTrafficAnnotationTag& traffic_annotation) {
  auto* browser_client = MiruContentBrowserClient::Get();
  auto adblock_ref = browser_client ? browser_client->GetAdblockRef() : nullptr;
  MiruAdblockHandle* adblock = adblock_ref ? adblock_ref->ptr : nullptr;
  if (adblock && request.url.SchemeIsHTTPOrHTTPS()) {
    std::string source_url;
    if (tab_ && tab_->web_contents()) {
      source_url = tab_->web_contents()->GetLastCommittedURL().spec();
    }
    if (source_url.empty()) {
      source_url = page_url_;
    }
    if (source_url.empty() && request.request_initiator.has_value()) {
      source_url = request.request_initiator->Serialize();
    }
    const std::string target_url = request.url.spec();
    if (source_url.empty()) {
      source_url = target_url;
    }
    const char* req_type = MiruDestinationToRequestType(request.destination);
    if (miru_adblock_should_block(adblock, target_url.c_str(),
                                  source_url.c_str(), req_type) != 0) {
      mojo::Remote<network::mojom::URLLoaderClient> client_remote(
          std::move(client));
      client_remote->OnComplete(
          network::URLLoaderCompletionStatus(net::ERR_BLOCKED_BY_CLIENT));
      if (tab_) {
        tab_->NotifyAdBlocked(target_url);
      }
      return;
    }
  }
  target_factory_->CreateLoaderAndStart(
      std::move(loader), request_id, options, request, std::move(client),
      traffic_annotation);
}

}  // namespace miru
