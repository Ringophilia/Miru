// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_BROWSER_MAIN_PARTS_H_
#define MIRU_ENGINE_NATIVE_MIRU_BROWSER_MAIN_PARTS_H_

#include <memory>

#include "content/public/browser/browser_main_parts.h"

namespace performance_manager {
class PerformanceManagerLifetime;
}  // namespace performance_manager

namespace miru {

class MiruBrowserContext;

class MiruBrowserMainParts : public content::BrowserMainParts {
 public:
  MiruBrowserMainParts();
  MiruBrowserMainParts(const MiruBrowserMainParts&) = delete;
  MiruBrowserMainParts& operator=(const MiruBrowserMainParts&) = delete;
  ~MiruBrowserMainParts() override;

  int PreEarlyInitialization() override;
  int PostCreateThreads() override;
  int PreMainMessageLoopRun() override;
  void PostMainMessageLoopRun() override;

  MiruBrowserContext* normal_context() { return normal_context_.get(); }
  MiruBrowserContext* off_the_record_context() { return otr_context_.get(); }
  MiruBrowserContext* GetOrCreateOffTheRecordContext();
  void DestroyOffTheRecordContext();

 private:
  std::unique_ptr<MiruBrowserContext> normal_context_;
  std::unique_ptr<MiruBrowserContext> otr_context_;
  std::unique_ptr<performance_manager::PerformanceManagerLifetime>
      performance_manager_lifetime_;
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_BROWSER_MAIN_PARTS_H_
