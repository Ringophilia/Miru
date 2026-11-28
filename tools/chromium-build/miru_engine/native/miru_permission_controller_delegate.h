// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_PERMISSION_CONTROLLER_DELEGATE_H_
#define MIRU_ENGINE_NATIVE_MIRU_PERMISSION_CONTROLLER_DELEGATE_H_

#include <cstdint>
#include <vector>

#include "base/containers/flat_map.h"
#include "base/functional/callback.h"
#include "content/public/browser/permission_controller_delegate.h"

namespace miru {

/// Bridges Chromium document/origin permission queries and prompts to Kotlin's
/// `ChromiumPermissionsController` with strict normal vs off-the-record isolation.
class MiruPermissionControllerDelegate final
    : public content::PermissionControllerDelegate {
 public:
  static void Resolve(int64_t request_id, bool allowed);
  static int ToMiruKind(blink::PermissionType permission);

  explicit MiruPermissionControllerDelegate(bool off_the_record);
  MiruPermissionControllerDelegate(const MiruPermissionControllerDelegate&) =
      delete;
  MiruPermissionControllerDelegate& operator=(
      const MiruPermissionControllerDelegate&) = delete;
  ~MiruPermissionControllerDelegate() override;

  int QueryRaw(const GURL& requesting_origin, int kind, int64_t tab_ptr) const;

  void RequestPermissionsFromCurrentDocument(
      content::RenderFrameHost* render_frame_host,
      const content::PermissionRequestDescription& request_description,
      base::OnceCallback<void(const std::vector<content::PermissionResult>&)>
          callback) override;
  blink::mojom::PermissionStatus GetPermissionStatus(
      const blink::mojom::PermissionDescriptorPtr& permission_descriptor,
      const GURL& requesting_origin,
      const GURL& embedding_origin) override;
  content::PermissionResult GetPermissionResultForOriginWithoutContext(
      const blink::mojom::PermissionDescriptorPtr& permission_descriptor,
      const url::Origin& requesting_origin,
      const url::Origin& embedding_origin) override;
  content::PermissionResult GetPermissionResultForCurrentDocument(
      const blink::mojom::PermissionDescriptorPtr& permission_descriptor,
      content::RenderFrameHost* render_frame_host,
      bool should_include_device_status) override;
  content::PermissionResult GetPermissionResultForWorker(
      const blink::mojom::PermissionDescriptorPtr& permission_descriptor,
      content::RenderProcessHost* render_process_host,
      const GURL& worker_origin) override;
  void ResetPermission(blink::PermissionType permission,
                       const GURL& requesting_origin,
                       const GURL& embedding_origin) override;

 private:
  struct PendingRequest {
    PendingRequest();
    PendingRequest(PendingRequest&&);
    PendingRequest& operator=(PendingRequest&&);
    ~PendingRequest();

    std::vector<blink::PermissionType> types;
    base::OnceCallback<void(const std::vector<content::PermissionResult>&)>
        callback;
  };

  content::PermissionResult ToPermissionResult(blink::PermissionType type,
                                                 const GURL& origin,
                                                 int64_t tab_ptr) const;

  const bool off_the_record_;
  std::vector<int64_t> owned_request_ids_;
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_PERMISSION_CONTROLLER_DELEGATE_H_
