// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_browser_context.h"

#include "base/base_paths.h"
#include "base/files/file_util.h"
#include "base/path_service.h"
#include "components/keyed_service/content/browser_context_dependency_manager.h"
#include "components/keyed_service/core/simple_dependency_manager.h"
#include "components/keyed_service/core/simple_factory_key.h"
#include "components/keyed_service/core/simple_key_map.h"
#include "content/public/browser/zoom_level_delegate.h"
#include "miru_engine/native/miru_download_delegate.h"
#include "miru_engine/native/miru_permission_controller_delegate.h"
#include "miru_engine/native/miru_platform_notification_service.h"

namespace miru {

MiruBrowserContext::MiruBrowserContext(bool off_the_record)
    : off_the_record_(off_the_record) {
  base::FilePath base_dir;
  CHECK(base::PathService::Get(base::DIR_ANDROID_APP_DATA, &base_dir));
  path_ = off_the_record_ ? base_dir.AppendASCII("miru_otr")
                          : base_dir.AppendASCII("miru_profile");
  if (!off_the_record_) {
    base::CreateDirectory(path_);
  }
  key_ = std::make_unique<SimpleFactoryKey>(path_, off_the_record_);
  SimpleKeyMap::GetInstance()->Associate(this, key_.get());
  BrowserContextDependencyManager::GetInstance()->CreateBrowserContextServices(
      this);
}

MiruBrowserContext::~MiruBrowserContext() {
  NotifyWillBeDestroyed();
  DependencyManager::PerformInterlockedTwoPhaseShutdown(
      BrowserContextDependencyManager::GetInstance(), this,
      SimpleDependencyManager::GetInstance(), key_.get());
  SimpleKeyMap::GetInstance()->Dissociate(this);
  ShutdownStoragePartitions();
  notification_service_.reset();
  permission_delegate_.reset();
}

base::FilePath MiruBrowserContext::GetPath() const {
  return path_;
}

std::unique_ptr<content::ZoomLevelDelegate>
MiruBrowserContext::CreateZoomLevelDelegate(const base::FilePath&) {
  return nullptr;
}

bool MiruBrowserContext::IsOffTheRecord() {
  return off_the_record_;
}

content::DownloadManagerDelegate*
MiruBrowserContext::GetDownloadManagerDelegate() {
  if (!download_delegate_) {
    download_delegate_ = std::make_unique<MiruDownloadManagerDelegate>(this);
    download_delegate_->SetDownloadManager(GetDownloadManager());
  }
  return download_delegate_.get();
}

content::BrowserPluginGuestManager* MiruBrowserContext::GetGuestManager() {
  return nullptr;
}

storage::SpecialStoragePolicy* MiruBrowserContext::GetSpecialStoragePolicy() {
  return nullptr;
}

content::PlatformNotificationService*
MiruBrowserContext::GetPlatformNotificationService() {
  if (!notification_service_) {
    notification_service_ =
        std::make_unique<MiruPlatformNotificationService>(this);
  }
  return notification_service_.get();
}

content::PushMessagingService* MiruBrowserContext::GetPushMessagingService() {
  return nullptr;
}

content::StorageNotificationService*
MiruBrowserContext::GetStorageNotificationService() {
  return nullptr;
}

content::SSLHostStateDelegate* MiruBrowserContext::GetSSLHostStateDelegate() {
  return nullptr;
}

content::PermissionControllerDelegate*
MiruBrowserContext::GetPermissionControllerDelegate() {
  if (!permission_delegate_) {
    permission_delegate_ =
        std::make_unique<MiruPermissionControllerDelegate>(off_the_record_);
  }
  return permission_delegate_.get();
}

content::BackgroundFetchDelegate*
MiruBrowserContext::GetBackgroundFetchDelegate() {
  return nullptr;
}

content::BackgroundSyncController*
MiruBrowserContext::GetBackgroundSyncController() {
  return nullptr;
}

content::BrowsingDataRemoverDelegate*
MiruBrowserContext::GetBrowsingDataRemoverDelegate() {
  return nullptr;
}

content::ContentIndexProvider* MiruBrowserContext::GetContentIndexProvider() {
  return nullptr;
}

content::ClientHintsControllerDelegate*
MiruBrowserContext::GetClientHintsControllerDelegate() {
  return nullptr;
}

content::ReduceAcceptLanguageControllerDelegate*
MiruBrowserContext::GetReduceAcceptLanguageControllerDelegate() {
  return nullptr;
}

}  // namespace miru
