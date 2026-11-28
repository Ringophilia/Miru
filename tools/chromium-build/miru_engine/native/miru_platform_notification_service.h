// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_PLATFORM_NOTIFICATION_SERVICE_H_
#define MIRU_ENGINE_NATIVE_MIRU_PLATFORM_NOTIFICATION_SERVICE_H_

#include <cstdint>
#include <set>
#include <string>

#include "base/memory/raw_ptr.h"
#include "base/time/time.h"
#include "content/public/browser/platform_notification_service.h"

namespace miru {

class MiruBrowserContext;

/// Forwards Blink non-persistent and persistent (ServiceWorker) Web
/// Notifications to Kotlin's `ChromiumNotificationsController` and dispatches
/// click / close events back to Chromium's `NotificationEventDispatcher`.
class MiruPlatformNotificationService final
    : public content::PlatformNotificationService {
 public:
  static void DispatchClick(const std::string& notification_id,
                            const std::string& origin,
                            bool is_private,
                            bool is_persistent,
                            int action_index);
  static void DispatchClose(const std::string& notification_id,
                            const std::string& origin,
                            bool is_private,
                            bool is_persistent);

  explicit MiruPlatformNotificationService(MiruBrowserContext* context);
  MiruPlatformNotificationService(const MiruPlatformNotificationService&) =
      delete;
  MiruPlatformNotificationService& operator=(
      const MiruPlatformNotificationService&) = delete;
  ~MiruPlatformNotificationService() override;

  void DisplayNotification(
      const std::string& notification_id,
      const GURL& origin,
      const GURL& document_url,
      const blink::PlatformNotificationData& notification_data,
      const blink::NotificationResources& notification_resources) override;
  void DisplayPersistentNotification(
      const std::string& notification_id,
      const GURL& service_worker_origin,
      const GURL& origin,
      const blink::PlatformNotificationData& notification_data,
      const blink::NotificationResources& notification_resources) override;
  void CloseNotification(const std::string& notification_id) override;
  void ClosePersistentNotification(const std::string& notification_id) override;
  void GetDisplayedNotifications(
      DisplayedNotificationsCallback callback) override;
  void GetDisplayedNotificationsForOrigin(
      const GURL& origin,
      DisplayedNotificationsCallback callback) override;
  void ScheduleTrigger(base::Time timestamp) override;
  base::Time ReadNextTriggerTimestamp() override;
  int64_t ReadNextPersistentNotificationId() override;
  void RecordNotificationUkmEvent(
      const content::NotificationDatabaseData& data) override;

 private:
  void ShowCommon(const std::string& notification_id,
                  const GURL& origin,
                  bool is_persistent,
                  const blink::PlatformNotificationData& data);

  raw_ptr<MiruBrowserContext> context_;
  std::set<std::string> displayed_ids_;
  int64_t next_persistent_id_ = 1;
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_PLATFORM_NOTIFICATION_SERVICE_H_
