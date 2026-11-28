// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_proxy_config_monitor.h"

#include <utility>

#include "base/notreached.h"
#include "base/task/single_thread_task_runner.h"
#include "content/public/browser/browser_thread.h"
#include "mojo/public/cpp/bindings/pending_remote.h"

namespace miru {

MiruProxyConfigMonitor::MiruProxyConfigMonitor() {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  proxy_config_service_android_ =
      std::make_unique<net::ProxyConfigServiceAndroid>(
          base::SingleThreadTaskRunner::GetCurrentDefault(),
          base::SingleThreadTaskRunner::GetCurrentDefault());
  proxy_config_service_android_->set_exclude_pac_url(true);
  proxy_config_service_android_->AddObserver(this);
}

MiruProxyConfigMonitor::~MiruProxyConfigMonitor() {
  proxy_config_service_android_->RemoveObserver(this);
}

// static
MiruProxyConfigMonitor* MiruProxyConfigMonitor::GetInstance() {
  static base::NoDestructor<MiruProxyConfigMonitor> instance;
  return instance.get();
}

void MiruProxyConfigMonitor::AddProxyToNetworkContextParams(
    network::mojom::NetworkContextParams* network_context_params) {
  if (!network_context_params) {
    return;
  }
  mojo::PendingRemote<network::mojom::ProxyConfigClient> proxy_config_client;
  network_context_params->proxy_config_client_receiver =
      proxy_config_client.InitWithNewPipeAndPassReceiver();
  proxy_config_client_set_.Add(std::move(proxy_config_client));

  net::ProxyConfigWithAnnotation proxy_config;
  const net::ProxyConfigService::ConfigAvailability availability =
      proxy_config_service_android_->GetLatestProxyConfig(&proxy_config);
  if (availability == net::ProxyConfigService::CONFIG_VALID) {
    network_context_params->initial_proxy_config = proxy_config;
  }
}

void MiruProxyConfigMonitor::OnProxyConfigChanged(
    const net::ProxyConfigWithAnnotation& config,
    net::ProxyConfigService::ConfigAvailability availability) {
  for (const auto& proxy_config_client : proxy_config_client_set_) {
    switch (availability) {
      case net::ProxyConfigService::CONFIG_VALID:
        proxy_config_client->OnProxyConfigUpdated(config);
        break;
      case net::ProxyConfigService::CONFIG_UNSET:
        proxy_config_client->OnProxyConfigUpdated(
            net::ProxyConfigWithAnnotation::CreateDirect());
        break;
      case net::ProxyConfigService::CONFIG_PENDING:
        NOTREACHED();
    }
  }
}

}  // namespace miru
