// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_browser_main_parts.h"

#include "base/functional/callback_helpers.h"
#include "components/performance_manager/embedder/graph_features.h"
#include "components/performance_manager/embedder/performance_manager_lifetime.h"
#include "content/public/common/result_codes.h"
#include "miru_engine/native/miru_browser_context.h"
#include "miru_engine/native/miru_form_popups.h"
#include "net/android/network_change_notifier_factory_android.h"
#include "net/base/network_change_notifier.h"

namespace miru {

MiruBrowserMainParts::MiruBrowserMainParts() = default;
MiruBrowserMainParts::~MiruBrowserMainParts() = default;

int MiruBrowserMainParts::PreEarlyInitialization() {
  net::NetworkChangeNotifier::SetFactory(
      new net::NetworkChangeNotifierFactoryAndroid());
  return content::RESULT_CODE_NORMAL_EXIT;
}

int MiruBrowserMainParts::PostCreateThreads() {
  performance_manager_lifetime_ =
      std::make_unique<performance_manager::PerformanceManagerLifetime>(
          performance_manager::GraphFeatures::WithMinimal(), base::DoNothing());
  return content::RESULT_CODE_NORMAL_EXIT;
}

int MiruBrowserMainParts::PreMainMessageLoopRun() {
  MiruFormPopups::InstallDateTimeHooks();
  normal_context_ = std::make_unique<MiruBrowserContext>(false);
  return 0;
}

void MiruBrowserMainParts::PostMainMessageLoopRun() {
  otr_context_.reset();
  normal_context_.reset();
  performance_manager_lifetime_.reset();
}

MiruBrowserContext* MiruBrowserMainParts::GetOrCreateOffTheRecordContext() {
  if (!otr_context_) {
    otr_context_ = std::make_unique<MiruBrowserContext>(true);
  }
  return otr_context_.get();
}

void MiruBrowserMainParts::DestroyOffTheRecordContext() {
  otr_context_.reset();
}

}  // namespace miru
