// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_main_delegate.h"

#include <unistd.h>

#include <tuple>
#include <utility>

#include "base/android/apk_assets.h"
#include "base/base_switches.h"
#include "base/command_line.h"
#include "base/files/file.h"
#include "base/posix/global_descriptors.h"
#include "base/process/current_process.h"
#include "components/memory_system/initializer.h"
#include "components/memory_system/parameters.h"
#include "content/public/browser/android/compositor.h"
#include "content/public/browser/browser_main_runner.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/common/content_switches.h"
#include "content/public/gpu/content_gpu_client.h"
#include "content/public/renderer/content_renderer_client.h"
#include "content/public/utility/content_utility_client.h"
#include "miru_engine/native/miru_content_browser_client.h"
#include "miru_engine/native/miru_content_client.h"
#include "miru_engine/native/miru_content_renderer_client.h"
#include "miru_engine/native/miru_descriptors.h"
#include "ui/base/resource/resource_bundle.h"

namespace miru {

MiruMainDelegate::MiruMainDelegate() = default;
MiruMainDelegate::~MiruMainDelegate() = default;

std::optional<int> MiruMainDelegate::BasicStartupComplete() {
  content::Compositor::Initialize();
  content::RenderFrameHost::AllowInjectingJavaScript();
  auto* cmd = base::CommandLine::ForCurrentProcess();
  std::string disabled = cmd->GetSwitchValueASCII(switches::kDisableFeatures);
  for (const char* feat : {"ApproximateGeolocationPermission",
                           "MediaDrmQueryInSeparateProcess"}) {
    if (disabled.find(feat) == std::string::npos) {
      if (!disabled.empty()) {
        disabled += ',';
      }
      disabled += feat;
    }
  }
  cmd->AppendSwitchASCII(switches::kDisableFeatures, disabled);
  return std::nullopt;
}

void MiruMainDelegate::PreSandboxStartup() {
  InitializeResourceBundle();
}

void MiruMainDelegate::InitializeResourceBundle() {
  auto* global_descriptors = base::GlobalDescriptors::GetInstance();
  int pak_fd = global_descriptors->MaybeGet(kMiruPakDescriptor);
  base::MemoryMappedFile::Region pak_region;
  if (pak_fd >= 0) {
    pak_region = global_descriptors->GetRegion(kMiruPakDescriptor);
  } else {
    pak_fd = base::android::OpenApkAsset("assets/miru_chromium.pak", &pak_region);
    CHECK_GE(pak_fd, 0) << "Missing assets/miru_chromium.pak";
    global_descriptors->Set(kMiruPakDescriptor, pak_fd, pak_region);
  }
  ui::ResourceBundle::InitSharedInstanceWithPakFileRegion(
      base::File(dup(pak_fd)), pak_region);
  ui::ResourceBundle::GetSharedInstance().AddDataPackFromFileRegion(
      base::File(dup(pak_fd)), pak_region, ui::k100Percent);
}

std::optional<int> MiruMainDelegate::PostEarlyInitialization(
    InvokedIn /*invoked_in*/) {
  const std::string process_type =
      base::CommandLine::ForCurrentProcess()->GetSwitchValueASCII(
          switches::kProcessType);
  memory_system::Initializer()
      .SetDispatcherParameters(
          memory_system::DispatcherParameters::PoissonAllocationSamplerInclusion::
              kDynamic,
          memory_system::DispatcherParameters::
              AllocationTraceRecorderInclusion::kIgnore,
          process_type)
      .Initialize(memory_system_);
  return std::nullopt;
}

std::variant<int, content::MainFunctionParams> MiruMainDelegate::RunProcess(
    const std::string& process_type,
    content::MainFunctionParams main_function_params) {
  if (!process_type.empty()) {
    return std::move(main_function_params);
  }
  base::CurrentProcess::GetInstance().SetProcessType(
      base::CurrentProcessType::PROCESS_BROWSER);
  auto main_runner = content::BrowserMainRunner::Create();
  const int exit_code = main_runner->Initialize(std::move(main_function_params));
  DCHECK_LT(exit_code, 0) << "BrowserMainRunner::Initialize failed";
  std::ignore = main_runner.release();
  return 0;
}

content::ContentClient* MiruMainDelegate::CreateContentClient() {
  content_client_ = std::make_unique<MiruContentClient>();
  return content_client_.get();
}

content::ContentBrowserClient*
MiruMainDelegate::CreateContentBrowserClient() {
  browser_client_ = std::make_unique<MiruContentBrowserClient>();
  return browser_client_.get();
}

content::ContentGpuClient* MiruMainDelegate::CreateContentGpuClient() {
  gpu_client_ = std::make_unique<content::ContentGpuClient>();
  return gpu_client_.get();
}

content::ContentRendererClient*
MiruMainDelegate::CreateContentRendererClient() {
  renderer_client_ = std::make_unique<MiruContentRendererClient>();
  return renderer_client_.get();
}

content::ContentUtilityClient* MiruMainDelegate::CreateContentUtilityClient() {
  utility_client_ = std::make_unique<content::ContentUtilityClient>();
  return utility_client_.get();
}

}  // namespace miru
