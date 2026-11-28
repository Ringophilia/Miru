// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_BROWSER_CONTEXT_H_
#define MIRU_ENGINE_NATIVE_MIRU_BROWSER_CONTEXT_H_

#include <memory>

#include "base/files/file_path.h"
#include "content/public/browser/browser_context.h"

class SimpleFactoryKey;

namespace miru {

class MiruDownloadManagerDelegate;
class MiruPermissionControllerDelegate;
class MiruPlatformNotificationService;

class MiruBrowserContext : public content::BrowserContext {
 public:
  explicit MiruBrowserContext(bool off_the_record);
  MiruBrowserContext(const MiruBrowserContext&) = delete;
  MiruBrowserContext& operator=(const MiruBrowserContext&) = delete;
  ~MiruBrowserContext() override;

  base::FilePath GetPath() const override;
  std::unique_ptr<content::ZoomLevelDelegate> CreateZoomLevelDelegate(
      const base::FilePath& partition_path) override;
  bool IsOffTheRecord() override;
  content::DownloadManagerDelegate* GetDownloadManagerDelegate() override;
  content::BrowserPluginGuestManager* GetGuestManager() override;
  storage::SpecialStoragePolicy* GetSpecialStoragePolicy() override;
  content::PlatformNotificationService* GetPlatformNotificationService() override;
  content::PushMessagingService* GetPushMessagingService() override;
  content::StorageNotificationService* GetStorageNotificationService() override;
  content::SSLHostStateDelegate* GetSSLHostStateDelegate() override;
  content::PermissionControllerDelegate* GetPermissionControllerDelegate() override;
  content::BackgroundFetchDelegate* GetBackgroundFetchDelegate() override;
  content::BackgroundSyncController* GetBackgroundSyncController() override;
  content::BrowsingDataRemoverDelegate* GetBrowsingDataRemoverDelegate() override;
  content::ContentIndexProvider* GetContentIndexProvider() override;
  content::ClientHintsControllerDelegate* GetClientHintsControllerDelegate() override;
  content::ReduceAcceptLanguageControllerDelegate*
  GetReduceAcceptLanguageControllerDelegate() override;

 private:
  const bool off_the_record_;
  base::FilePath path_;
  std::unique_ptr<SimpleFactoryKey> key_;
  std::unique_ptr<MiruDownloadManagerDelegate> download_delegate_;
  std::unique_ptr<MiruPermissionControllerDelegate> permission_delegate_;
  std::unique_ptr<MiruPlatformNotificationService> notification_service_;
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_BROWSER_CONTEXT_H_
