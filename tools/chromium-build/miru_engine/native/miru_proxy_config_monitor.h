// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_PROXY_CONFIG_MONITOR_H_
#define MIRU_ENGINE_NATIVE_MIRU_PROXY_CONFIG_MONITOR_H_

#include <memory>

#include "base/no_destructor.h"
#include "mojo/public/cpp/bindings/remote_set.h"
#include "net/proxy_resolution/proxy_config_service_android.h"
#include "services/network/public/mojom/network_context.mojom.h"
#include "services/network/public/mojom/proxy_config_with_annotation.mojom.h"

namespace miru {

/// Bridges Android system proxy configuration (Wi-Fi proxy / Clash) into Chromium `NetworkContext`.
class MiruProxyConfigMonitor final : public net::ProxyConfigService::Observer {
 public:
  MiruProxyConfigMonitor(const MiruProxyConfigMonitor&) = delete;
  MiruProxyConfigMonitor& operator=(const MiruProxyConfigMonitor&) = delete;

  static MiruProxyConfigMonitor* GetInstance();

  void AddProxyToNetworkContextParams(
      network::mojom::NetworkContextParams* network_context_params);

 private:
  friend class base::NoDestructor<MiruProxyConfigMonitor>;

  MiruProxyConfigMonitor();
  ~MiruProxyConfigMonitor() override;

  // net::ProxyConfigService::Observer:
  void OnProxyConfigChanged(
      const net::ProxyConfigWithAnnotation& config,
      net::ProxyConfigService::ConfigAvailability availability) override;

  std::unique_ptr<net::ProxyConfigServiceAndroid> proxy_config_service_android_;
  mojo::RemoteSet<network::mojom::ProxyConfigClient> proxy_config_client_set_;
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_PROXY_CONFIG_MONITOR_H_
