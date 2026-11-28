// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_platform_notification_service.h"

#include <utility>

#include "base/functional/callback_helpers.h"
#include "base/json/json_writer.h"
#include "base/strings/utf_string_conversions.h"
#include "base/values.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/notification_event_dispatcher.h"
#include "miru_engine/native/miru_browser_context.h"
#include "miru_engine/native/miru_content_browser_client.h"
#include "miru_engine/native/miru_runtime_bridges.h"
#include "third_party/blink/public/common/notifications/notification_resources.h"
#include "third_party/blink/public/common/notifications/platform_notification_data.h"
#include "third_party/blink/public/mojom/notifications/notification.mojom.h"
#include "url/gurl.h"

namespace miru {

namespace {

content::BrowserContext* ResolveContext(bool is_private) {
  auto* client = MiruContentBrowserClient::Get();
  if (!client) {
    return nullptr;
  }
  return is_private ? client->GetOrCreateOffTheRecordContext()
                    : client->normal_context();
}

std::string SerializeActions(
    const std::vector<blink::mojom::NotificationActionPtr>& actions) {
  base::ListValue list;
  for (const auto& action : actions) {
    if (!action) {
      continue;
    }
    base::DictValue item;
    item.Set("action", action->action);
    item.Set("title", base::UTF16ToUTF8(action->title));
    list.Append(std::move(item));
  }
  return base::WriteJson(list).value_or("[]");
}

}  // namespace

// static
void MiruPlatformNotificationService::DispatchClick(
    const std::string& notification_id,
    const std::string& origin,
    bool is_private,
    bool is_persistent,
    int action_index) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  auto* dispatcher = content::NotificationEventDispatcher::GetInstance();
  if (!dispatcher) {
    return;
  }
  if (is_persistent) {
    content::BrowserContext* ctx = ResolveContext(is_private);
    if (!ctx) {
      return;
    }
    std::optional<int> action =
        action_index >= 0 ? std::optional<int>(action_index) : std::nullopt;
    dispatcher->DispatchNotificationClickEvent(
        ctx, notification_id, GURL(origin), action, std::nullopt,
        base::DoNothing());
  } else {
    dispatcher->DispatchNonPersistentClickEvent(notification_id,
                                                base::DoNothing());
  }
}

// static
void MiruPlatformNotificationService::DispatchClose(
    const std::string& notification_id,
    const std::string& origin,
    bool is_private,
    bool is_persistent) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  auto* dispatcher = content::NotificationEventDispatcher::GetInstance();
  if (!dispatcher) {
    return;
  }
  if (is_persistent) {
    content::BrowserContext* ctx = ResolveContext(is_private);
    if (!ctx) {
      return;
    }
    dispatcher->DispatchNotificationCloseEvent(
        ctx, notification_id, GURL(origin), /*by_user=*/true,
        base::DoNothing());
  } else {
    dispatcher->DispatchNonPersistentCloseEvent(notification_id,
                                                base::DoNothing());
  }
}

MiruPlatformNotificationService::MiruPlatformNotificationService(
    MiruBrowserContext* context)
    : context_(context) {}

MiruPlatformNotificationService::~MiruPlatformNotificationService() = default;

void MiruPlatformNotificationService::DisplayNotification(
    const std::string& notification_id,
    const GURL& origin,
    const GURL& /*document_url*/,
    const blink::PlatformNotificationData& notification_data,
    const blink::NotificationResources& /*notification_resources*/) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  ShowCommon(notification_id, origin, /*is_persistent=*/false,
             notification_data);
  if (auto* dispatcher = content::NotificationEventDispatcher::GetInstance()) {
    dispatcher->DispatchNonPersistentShowEvent(notification_id);
  }
}

void MiruPlatformNotificationService::DisplayPersistentNotification(
    const std::string& notification_id,
    const GURL& /*service_worker_origin*/,
    const GURL& origin,
    const blink::PlatformNotificationData& notification_data,
    const blink::NotificationResources& /*notification_resources*/) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  ShowCommon(notification_id, origin, /*is_persistent=*/true,
             notification_data);
}

void MiruPlatformNotificationService::ShowCommon(
    const std::string& notification_id,
    const GURL& origin,
    bool is_persistent,
    const blink::PlatformNotificationData& data) {
  displayed_ids_.insert(notification_id);
  ShowNotificationBridge(
      notification_id, origin.is_valid() ? origin.spec() : std::string(),
      context_ && context_->IsOffTheRecord(), is_persistent,
      base::UTF16ToUTF8(data.title), base::UTF16ToUTF8(data.body), data.tag,
      data.silent, SerializeActions(data.actions));
}

void MiruPlatformNotificationService::CloseNotification(
    const std::string& notification_id) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  displayed_ids_.erase(notification_id);
  CloseNotificationBridge(notification_id);
}

void MiruPlatformNotificationService::ClosePersistentNotification(
    const std::string& notification_id) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  displayed_ids_.erase(notification_id);
  CloseNotificationBridge(notification_id);
}

void MiruPlatformNotificationService::GetDisplayedNotifications(
    DisplayedNotificationsCallback callback) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  std::move(callback).Run(displayed_ids_, /*supports_synchronization=*/true);
}

void MiruPlatformNotificationService::GetDisplayedNotificationsForOrigin(
    const GURL& /*origin*/,
    DisplayedNotificationsCallback callback) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  std::move(callback).Run(displayed_ids_, /*supports_synchronization=*/true);
}

void MiruPlatformNotificationService::ScheduleTrigger(
    base::Time /*timestamp*/) {}

base::Time MiruPlatformNotificationService::ReadNextTriggerTimestamp() {
  return base::Time::Max();
}

int64_t MiruPlatformNotificationService::ReadNextPersistentNotificationId() {
  return next_persistent_id_++;
}

void MiruPlatformNotificationService::RecordNotificationUkmEvent(
    const content::NotificationDatabaseData& /*data*/) {}

}  // namespace miru
