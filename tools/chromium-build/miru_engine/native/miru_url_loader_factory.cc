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
#include "mojo/public/cpp/bindings/receiver.h"
#include "net/base/net_errors.h"
#include "net/http/http_response_headers.h"
#include "net/traffic_annotation/network_traffic_annotation.h"
#include "services/network/public/cpp/resource_request.h"
#include "services/network/public/cpp/url_loader_completion_status.h"
#include "services/network/public/mojom/early_hints.mojom.h"
#include "services/network/public/mojom/url_loader.mojom.h"
#include "services/network/public/mojom/url_response_head.mojom.h"

namespace miru {

namespace {

/// Pass-through `URLLoaderClient` observer classifying response headers in C++
/// without copying or buffering any media payload bytes.
class MiruSniffingURLLoaderClient final
    : public network::mojom::URLLoaderClient {
 public:
  static mojo::PendingRemote<network::mojom::URLLoaderClient> Wrap(
      mojo::PendingRemote<network::mojom::URLLoaderClient> target_client,
      base::WeakPtr<MiruTab> tab,
      GURL initial_url,
      std::string headers_json) {
    mojo::PendingRemote<network::mojom::URLLoaderClient> proxy_remote;
    new MiruSniffingURLLoaderClient(
        proxy_remote.InitWithNewPipeAndPassReceiver(), std::move(target_client),
        std::move(tab), std::move(initial_url), std::move(headers_json));
    return proxy_remote;
  }

  MiruSniffingURLLoaderClient(
      mojo::PendingReceiver<network::mojom::URLLoaderClient> receiver,
      mojo::PendingRemote<network::mojom::URLLoaderClient> target_client,
      base::WeakPtr<MiruTab> tab,
      GURL initial_url,
      std::string headers_json)
      : receiver_(this, std::move(receiver)),
        target_client_(std::move(target_client)),
        tab_(std::move(tab)),
        current_url_(std::move(initial_url)),
        headers_json_(std::move(headers_json)) {
    receiver_.set_disconnect_handler(base::BindOnce(
        &MiruSniffingURLLoaderClient::Destroy, base::Unretained(this)));
    target_client_.set_disconnect_handler(base::BindOnce(
        &MiruSniffingURLLoaderClient::Destroy, base::Unretained(this)));
  }

  MiruSniffingURLLoaderClient(const MiruSniffingURLLoaderClient&) = delete;
  MiruSniffingURLLoaderClient& operator=(const MiruSniffingURLLoaderClient&) =
      delete;

  void OnReceiveEarlyHints(
      network::mojom::EarlyHintsPtr early_hints) override {
    target_client_->OnReceiveEarlyHints(std::move(early_hints));
  }

  void OnReceiveResponse(
      network::mojom::URLResponseHeadPtr head,
      mojo::ScopedDataPipeConsumerHandle body,
      std::optional<mojo_base::BigBuffer> cached_metadata) override {
    if (tab_ && head && current_url_.SchemeIsHTTPOrHTTPS()) {
      const int64_t len =
          head->content_length >= 0
              ? head->content_length
              : (head->headers
                     ? head->headers->GetInt64HeaderValue("Content-Length")
                           .value_or(-1)
                     : -1);
      const std::string url_spec = current_url_.spec();
      char* kind_ptr = miru_media_classify(url_spec.c_str(),
                                           head->mime_type.c_str(), len);
      if (kind_ptr) {
        std::string kind(kind_ptr);
        miru_string_free(kind_ptr);
        tab_->NotifyMediaDetected(url_spec, kind, head->mime_type, len,
                                  headers_json_);
      }
    }
    target_client_->OnReceiveResponse(std::move(head), std::move(body),
                                      std::move(cached_metadata));
  }

  void OnReceiveRedirect(const net::RedirectInfo& redirect_info,
                         network::mojom::URLResponseHeadPtr head) override {
    current_url_ = redirect_info.new_url;
    target_client_->OnReceiveRedirect(redirect_info, std::move(head));
  }

  void OnUploadProgress(int64_t current_position,
                        int64_t total_size,
                        OnUploadProgressCallback callback) override {
    target_client_->OnUploadProgress(current_position, total_size,
                                     std::move(callback));
  }

  void OnTransferSizeUpdated(int32_t transfer_size_diff) override {
    target_client_->OnTransferSizeUpdated(transfer_size_diff);
  }

  void OnComplete(const network::URLLoaderCompletionStatus& status) override {
    target_client_->OnComplete(status);
  }

 private:
  ~MiruSniffingURLLoaderClient() override = default;

  void Destroy() { delete this; }

  mojo::Receiver<network::mojom::URLLoaderClient> receiver_;
  mojo::Remote<network::mojom::URLLoaderClient> target_client_;
  base::WeakPtr<MiruTab> tab_;
  GURL current_url_;
  std::string headers_json_;
};

}  // namespace

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

  mojo::PendingRemote<network::mojom::URLLoaderClient> effective_client =
      (tab_ && request.url.SchemeIsHTTPOrHTTPS())
          ? MiruSniffingURLLoaderClient::Wrap(
                std::move(client), tab_, request.url,
                MiruSerializeRequestHeadersJson(request))
          : std::move(client);

  target_factory_->CreateLoaderAndStart(
      std::move(loader), request_id, options, request,
      std::move(effective_client), traffic_annotation);
}

}  // namespace miru
