// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_permission_controller_delegate.h"

#include <utility>

#include "base/feature_list.h"
#include "base/no_destructor.h"
#include "components/content_settings/core/common/content_settings.h"
#include "components/content_settings/core/common/features.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/permission_request_description.h"
#include "content/public/browser/permission_result.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/web_contents.h"
#include "miru_engine/native/miru_runtime_bridges.h"
#include "miru_engine/native/miru_tab.h"
#include "third_party/blink/public/common/permissions/permission_utils.h"
#include "url/gurl.h"
#include "url/origin.h"

namespace miru {

namespace {

using PermissionCallback =
    base::OnceCallback<void(const std::vector<content::PermissionResult>&)>;

struct PendingEntry {
  std::vector<blink::PermissionType> types;
  PermissionCallback callback;
};

base::flat_map<int64_t, PendingEntry>& PendingPermissions() {
  static base::NoDestructor<base::flat_map<int64_t, PendingEntry>> map;
  return *map;
}

int64_t& NextPermissionRequestId() {
  static int64_t next = 1;
  return next;
}

content::PermissionResult BuildResult(blink::PermissionType type,
                                      blink::mojom::PermissionStatus status) {
  if (type == blink::PermissionType::GEOLOCATION &&
      base::FeatureList::IsEnabled(
          content_settings::features::kApproximateGeolocationPermission)) {
    PermissionOption opt = PermissionOption::kAsk;
    if (status == blink::mojom::PermissionStatus::GRANTED) {
      opt = PermissionOption::kAllowed;
    } else if (status == blink::mojom::PermissionStatus::DENIED) {
      opt = PermissionOption::kDenied;
    }
    GeolocationSetting setting{opt, opt};
    return content::PermissionResult(
        status, content::PermissionStatusSource::UNSPECIFIED, setting);
  }
  return content::PermissionResult(status);
}

std::vector<content::PermissionResult> BuildDeniedResults(
    const std::vector<blink::PermissionType>& types) {
  std::vector<content::PermissionResult> results;
  results.reserve(types.size());
  for (blink::PermissionType t : types) {
    results.push_back(BuildResult(t, blink::mojom::PermissionStatus::DENIED));
  }
  return results;
}

}  // namespace

MiruPermissionControllerDelegate::PendingRequest::PendingRequest() = default;
MiruPermissionControllerDelegate::PendingRequest::PendingRequest(
    PendingRequest&&) = default;
MiruPermissionControllerDelegate::PendingRequest&
MiruPermissionControllerDelegate::PendingRequest::operator=(PendingRequest&&) =
    default;
MiruPermissionControllerDelegate::PendingRequest::~PendingRequest() = default;

// static
int MiruPermissionControllerDelegate::ToMiruKind(
    blink::PermissionType permission) {
  switch (permission) {
    case blink::PermissionType::VIDEO_CAPTURE:
      return 0;
    case blink::PermissionType::AUDIO_CAPTURE:
      return 1;
    case blink::PermissionType::GEOLOCATION:
    case blink::PermissionType::GEOLOCATION_APPROXIMATE:
      return 2;
    case blink::PermissionType::NOTIFICATIONS:
      return 3;
    case blink::PermissionType::PROTECTED_MEDIA_IDENTIFIER:
      return 4;
    default:
      return -1;
  }
}

// static
void MiruPermissionControllerDelegate::Resolve(int64_t request_id,
                                               bool allowed) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  auto it = PendingPermissions().find(request_id);
  if (it == PendingPermissions().end()) {
    return;
  }
  PendingEntry entry = std::move(it->second);
  PendingPermissions().erase(it);
  std::vector<content::PermissionResult> results;
  results.reserve(entry.types.size());
  for (blink::PermissionType type : entry.types) {
    const int kind = ToMiruKind(type);
    const auto status =
        (allowed && kind >= 0) ? blink::mojom::PermissionStatus::GRANTED
                               : blink::mojom::PermissionStatus::DENIED;
    results.push_back(BuildResult(type, status));
  }
  std::move(entry.callback).Run(results);
}

MiruPermissionControllerDelegate::MiruPermissionControllerDelegate(
    bool off_the_record)
    : off_the_record_(off_the_record) {}

MiruPermissionControllerDelegate::~MiruPermissionControllerDelegate() {
  for (int64_t id : owned_request_ids_) {
    auto it = PendingPermissions().find(id);
    if (it != PendingPermissions().end()) {
      PendingEntry entry = std::move(it->second);
      PendingPermissions().erase(it);
      std::move(entry.callback).Run(BuildDeniedResults(entry.types));
    }
  }
}

int MiruPermissionControllerDelegate::QueryRaw(const GURL& requesting_origin,
                                               int kind,
                                               int64_t tab_ptr) const {
  if (kind < 0 || !requesting_origin.is_valid()) {
    return 2;
  }
  return QuerySitePermissionBridge(requesting_origin.spec(), off_the_record_,
                                   kind, tab_ptr);
}

content::PermissionResult
MiruPermissionControllerDelegate::ToPermissionResult(blink::PermissionType type,
                                                       const GURL& origin,
                                                       int64_t tab_ptr) const {
  const int kind = ToMiruKind(type);
  if (kind < 0) {
    return BuildResult(type, blink::mojom::PermissionStatus::DENIED);
  }
  const int raw = QueryRaw(origin, kind, tab_ptr);
  blink::mojom::PermissionStatus status = blink::mojom::PermissionStatus::ASK;
  if (raw == 1) {
    status = blink::mojom::PermissionStatus::GRANTED;
  } else if (raw == 2) {
    status = blink::mojom::PermissionStatus::DENIED;
  }
  return BuildResult(type, status);
}

void MiruPermissionControllerDelegate::RequestPermissionsFromCurrentDocument(
    content::RenderFrameHost* render_frame_host,
    const content::PermissionRequestDescription& request_description,
    base::OnceCallback<void(const std::vector<content::PermissionResult>&)>
        callback) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  std::vector<blink::PermissionType> types;
  types.reserve(request_description.permissions.size());
  int kinds_mask = 0;
  for (const auto& descriptor : request_description.permissions) {
    auto opt_type = blink::MaybePermissionDescriptorToPermissionType(descriptor);
    blink::PermissionType type =
        opt_type.value_or(blink::PermissionType::NUM);
    types.push_back(type);
    const int kind = ToMiruKind(type);
    if (kind >= 0) {
      kinds_mask |= (1 << kind);
    }
  }

  content::WebContents* wc =
      render_frame_host
          ? content::WebContents::FromRenderFrameHost(render_frame_host)
          : nullptr;
  MiruTab* tab = wc ? MiruTab::FromWebContents(wc) : nullptr;
  if (!render_frame_host || render_frame_host->IsNestedWithinFencedFrame() ||
      !tab || kinds_mask == 0) {
    std::move(callback).Run(BuildDeniedResults(types));
    return;
  }

  const GURL origin_url =
      render_frame_host->GetLastCommittedOrigin().GetURL();
  const GURL top_url = wc->GetLastCommittedURL();
  const int64_t request_id = NextPermissionRequestId()++;
  PendingPermissions().emplace(
      request_id, PendingEntry{std::move(types), std::move(callback)});
  owned_request_ids_.push_back(request_id);

  RequestSitePermissionsBridge(
      request_id, reinterpret_cast<int64_t>(tab),
      origin_url.is_valid() ? origin_url.spec() : top_url.spec(),
      top_url.is_valid() ? top_url.spec() : std::string(), off_the_record_,
      kinds_mask);
}

blink::mojom::PermissionStatus
MiruPermissionControllerDelegate::GetPermissionStatus(
    const blink::mojom::PermissionDescriptorPtr& permission_descriptor,
    const GURL& requesting_origin,
    const GURL& /*embedding_origin*/) {
  auto opt =
      blink::MaybePermissionDescriptorToPermissionType(permission_descriptor);
  if (!opt) {
    return blink::mojom::PermissionStatus::DENIED;
  }
  return ToPermissionResult(*opt, requesting_origin, 0).status;
}

content::PermissionResult
MiruPermissionControllerDelegate::GetPermissionResultForOriginWithoutContext(
    const blink::mojom::PermissionDescriptorPtr& permission_descriptor,
    const url::Origin& requesting_origin,
    const url::Origin& /*embedding_origin*/) {
  auto opt =
      blink::MaybePermissionDescriptorToPermissionType(permission_descriptor);
  if (!opt) {
    return content::PermissionResult(blink::mojom::PermissionStatus::DENIED);
  }
  return ToPermissionResult(*opt, requesting_origin.GetURL(), 0);
}

content::PermissionResult
MiruPermissionControllerDelegate::GetPermissionResultForCurrentDocument(
    const blink::mojom::PermissionDescriptorPtr& permission_descriptor,
    content::RenderFrameHost* render_frame_host,
    bool /*should_include_device_status*/) {
  auto opt =
      blink::MaybePermissionDescriptorToPermissionType(permission_descriptor);
  if (!opt || !render_frame_host ||
      render_frame_host->IsNestedWithinFencedFrame()) {
    return content::PermissionResult(blink::mojom::PermissionStatus::DENIED);
  }
  content::WebContents* wc =
      content::WebContents::FromRenderFrameHost(render_frame_host);
  MiruTab* tab = wc ? MiruTab::FromWebContents(wc) : nullptr;
  const GURL origin = render_frame_host->GetLastCommittedOrigin().GetURL();
  return ToPermissionResult(*opt, origin, reinterpret_cast<int64_t>(tab));
}

content::PermissionResult
MiruPermissionControllerDelegate::GetPermissionResultForWorker(
    const blink::mojom::PermissionDescriptorPtr& permission_descriptor,
    content::RenderProcessHost* /*render_process_host*/,
    const GURL& worker_origin) {
  auto opt =
      blink::MaybePermissionDescriptorToPermissionType(permission_descriptor);
  if (!opt) {
    return content::PermissionResult(blink::mojom::PermissionStatus::DENIED);
  }
  return ToPermissionResult(*opt, worker_origin, 0);
}

void MiruPermissionControllerDelegate::ResetPermission(
    blink::PermissionType /*permission*/,
    const GURL& /*requesting_origin*/,
    const GURL& /*embedding_origin*/) {}

}  // namespace miru
