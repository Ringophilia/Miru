// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_RUNTIME_BRIDGES_H_
#define MIRU_ENGINE_NATIVE_MIRU_RUNTIME_BRIDGES_H_

#include <cstdint>
#include <string>

namespace miru {

/// Queries Kotlin site permission store synchronously on UI thread:
/// 0 = ASK, 1 = GRANTED, 2 = DENIED.
int QuerySitePermissionBridge(const std::string& origin,
                              bool off_the_record,
                              int kind,
                              int64_t tab_ptr);

void RequestSitePermissionsBridge(int64_t request_id,
                                  int64_t tab_ptr,
                                  const std::string& origin,
                                  const std::string& top_origin,
                                  bool off_the_record,
                                  int kinds_mask);

void RequestMediaAccessBridge(int64_t request_id,
                              int64_t tab_ptr,
                              const std::string& origin,
                              const std::string& top_origin,
                              bool off_the_record,
                              int kinds_mask);

/// Queries Kotlin per-site autoplay policy:
/// 0 = BLOCK_AUDIBLE (kDocumentUserActivationRequired),
/// 1 = ALLOW_ALL (kNoUserGestureRequired),
/// 2 = BLOCK_ALL (kUserGestureRequired).
int QueryAutoplayPolicyBridge(const std::string& url, bool off_the_record);

void ShowNotificationBridge(const std::string& notification_id,
                            const std::string& origin,
                            bool is_private,
                            bool is_persistent,
                            const std::string& title,
                            const std::string& body,
                            const std::string& tag,
                            bool silent,
                            const std::string& actions_json);

void CloseNotificationBridge(const std::string& notification_id);

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_RUNTIME_BRIDGES_H_
