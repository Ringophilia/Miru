// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_URL_LOADER_FACTORY_H_
#define MIRU_ENGINE_NATIVE_MIRU_URL_LOADER_FACTORY_H_

#include <cstdint>
#include <string>

#include "base/memory/self_deleting.h"
#include "base/memory/weak_ptr.h"
#include "mojo/public/cpp/bindings/pending_receiver.h"
#include "mojo/public/cpp/bindings/pending_remote.h"
#include "mojo/public/cpp/bindings/remote.h"
#include "services/network/public/cpp/self_deleting_url_loader_factory.h"
#include "services/network/public/mojom/url_loader_factory.mojom.h"

namespace miru {

class MiruTab;

/// Browser-process `URLLoaderFactory` proxy enforcing synchronous `adblock-rust`
/// filtering on renderer-initiated subresources without per-request JNI or IPC rule sync.
class MiruURLLoaderFactory final : public network::SelfDeletingURLLoaderFactory {
 public:
  static void Create(
      mojo::PendingReceiver<network::mojom::URLLoaderFactory> proxy_receiver,
      mojo::PendingRemote<network::mojom::URLLoaderFactory> target_factory,
      base::WeakPtr<MiruTab> tab,
      std::string page_url);

  MiruURLLoaderFactory(
      mojo::PendingReceiver<network::mojom::URLLoaderFactory> factory_receiver,
      mojo::PendingRemote<network::mojom::URLLoaderFactory> target_factory,
      base::WeakPtr<MiruTab> tab,
      std::string page_url,
      base::SelfDeletingPassKey key);

  MiruURLLoaderFactory(const MiruURLLoaderFactory&) = delete;
  MiruURLLoaderFactory& operator=(const MiruURLLoaderFactory&) = delete;

  void CreateLoaderAndStart(
      mojo::PendingReceiver<network::mojom::URLLoader> loader,
      int32_t request_id,
      uint32_t options,
      const network::ResourceRequest& request,
      mojo::PendingRemote<network::mojom::URLLoaderClient> client,
      const net::MutableNetworkTrafficAnnotationTag& traffic_annotation)
      override;

 private:
  ~MiruURLLoaderFactory() override;

  mojo::Remote<network::mojom::URLLoaderFactory> target_factory_;
  base::WeakPtr<MiruTab> tab_;
  std::string page_url_;
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_URL_LOADER_FACTORY_H_
