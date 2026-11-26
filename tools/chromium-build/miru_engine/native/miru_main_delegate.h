// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_MAIN_DELEGATE_H_
#define MIRU_ENGINE_NATIVE_MIRU_MAIN_DELEGATE_H_

#include <memory>
#include <optional>
#include <string>
#include <variant>

#include "components/memory_system/memory_system.h"
#include "content/public/app/content_main_delegate.h"

namespace content {
class ContentGpuClient;
class ContentRendererClient;
class ContentUtilityClient;
}  // namespace content

namespace miru {

class MiruContentBrowserClient;
class MiruContentClient;

class MiruMainDelegate : public content::ContentMainDelegate {
 public:
  MiruMainDelegate();
  MiruMainDelegate(const MiruMainDelegate&) = delete;
  MiruMainDelegate& operator=(const MiruMainDelegate&) = delete;
  ~MiruMainDelegate() override;

  std::optional<int> BasicStartupComplete() override;
  void PreSandboxStartup() override;
  std::optional<int> PostEarlyInitialization(InvokedIn invoked_in) override;
  std::variant<int, content::MainFunctionParams> RunProcess(
      const std::string& process_type,
      content::MainFunctionParams main_function_params) override;

  content::ContentClient* CreateContentClient() override;
  content::ContentBrowserClient* CreateContentBrowserClient() override;
  content::ContentGpuClient* CreateContentGpuClient() override;
  content::ContentRendererClient* CreateContentRendererClient() override;
  content::ContentUtilityClient* CreateContentUtilityClient() override;

 private:
  void InitializeResourceBundle();

  std::unique_ptr<MiruContentClient> content_client_;
  std::unique_ptr<MiruContentBrowserClient> browser_client_;
  std::unique_ptr<content::ContentGpuClient> gpu_client_;
  std::unique_ptr<content::ContentRendererClient> renderer_client_;
  std::unique_ptr<content::ContentUtilityClient> utility_client_;
  memory_system::MemorySystem memory_system_;
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_MAIN_DELEGATE_H_
