// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_media_access_handler.h"

#include <string>
#include <utility>
#include <vector>

#include "base/containers/flat_map.h"
#include "base/memory/raw_ptr.h"
#include "base/no_destructor.h"
#include "content/public/browser/browser_context.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/media_capture_devices.h"
#include "content/public/browser/web_contents.h"
#include "miru_engine/native/miru_permission_controller_delegate.h"
#include "miru_engine/native/miru_runtime_bridges.h"
#include "miru_engine/native/miru_tab.h"
#include "third_party/blink/public/mojom/mediastream/media_stream.mojom.h"

namespace miru {

namespace {

struct PendingMediaRequest {
  raw_ptr<MiruTab> tab;
  content::MediaStreamRequest request;
  content::MediaResponseCallback callback;
};

base::flat_map<int64_t, PendingMediaRequest>& PendingMedia() {
  static base::NoDestructor<base::flat_map<int64_t, PendingMediaRequest>> map;
  return *map;
}

int64_t& NextMediaRequestId() {
  static int64_t next = 1;
  return next;
}

const blink::MediaStreamDevice* PickDevice(
    const blink::MediaStreamDevices& devices,
    const std::vector<std::string>& requested_ids) {
  if (devices.empty()) {
    return nullptr;
  }
  if (!requested_ids.empty()) {
    for (const auto& device : devices) {
      if (device.id == requested_ids.front()) {
        return &device;
      }
    }
  }
  return &devices.front();
}

void CompleteMediaRequest(PendingMediaRequest entry, bool allowed) {
  if (!allowed) {
    std::move(entry.callback)
        .Run(blink::mojom::StreamDevicesSet(),
             blink::mojom::MediaStreamRequestResult::PERMISSION_DENIED,
             nullptr);
    return;
  }

  blink::mojom::StreamDevicesSet devices_set;
  devices_set.stream_devices.emplace_back(blink::mojom::StreamDevices::New());
  blink::mojom::StreamDevices& devices = *devices_set.stream_devices[0];

  if (entry.request.audio_type ==
      blink::mojom::MediaStreamType::DEVICE_AUDIO_CAPTURE) {
    const auto& audio_devices =
        content::MediaCaptureDevices::GetInstance()->GetAudioCaptureDevices();
    if (const auto* dev =
            PickDevice(audio_devices, entry.request.requested_audio_device_ids)) {
      devices.audio_device = *dev;
    }
  }
  if (entry.request.video_type ==
      blink::mojom::MediaStreamType::DEVICE_VIDEO_CAPTURE) {
    const auto& video_devices =
        content::MediaCaptureDevices::GetInstance()->GetVideoCaptureDevices();
    if (const auto* dev =
            PickDevice(video_devices, entry.request.requested_video_device_ids)) {
      devices.video_device = *dev;
    }
  }

  const bool no_hardware =
      !devices.audio_device.has_value() && !devices.video_device.has_value();
  if (no_hardware) {
    devices_set.stream_devices.clear();
  }
  std::move(entry.callback)
      .Run(devices_set,
           no_hardware ? blink::mojom::MediaStreamRequestResult::NO_HARDWARE
                       : blink::mojom::MediaStreamRequestResult::OK,
           nullptr);
}

}  // namespace

// static
bool MiruMediaAccessHandler::Check(MiruTab* tab,
                                   const url::Origin& security_origin,
                                   blink::mojom::MediaStreamType type) {
  if (!tab || !tab->web_contents() || !tab->web_contents()->GetBrowserContext()) {
    return false;
  }
  auto* delegate = static_cast<MiruPermissionControllerDelegate*>(
      tab->web_contents()
          ->GetBrowserContext()
          ->GetPermissionControllerDelegate());
  if (!delegate) {
    return false;
  }
  const int kind =
      type == blink::mojom::MediaStreamType::DEVICE_VIDEO_CAPTURE
          ? 0
          : (type == blink::mojom::MediaStreamType::DEVICE_AUDIO_CAPTURE ? 1
                                                                         : -1);
  return kind >= 0 && delegate->QueryRaw(security_origin.GetURL(), kind,
                                         reinterpret_cast<int64_t>(tab)) == 1;
}

// static
void MiruMediaAccessHandler::Request(MiruTab* tab,
                                     const content::MediaStreamRequest& request,
                                     content::MediaResponseCallback callback) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  const bool off_the_record =
      tab && tab->web_contents() && tab->web_contents()->GetBrowserContext() &&
      tab->web_contents()->GetBrowserContext()->IsOffTheRecord();
  int kinds_mask = 0;
  if (request.video_type ==
      blink::mojom::MediaStreamType::DEVICE_VIDEO_CAPTURE) {
    kinds_mask |= (1 << 0);
  }
  if (request.audio_type ==
      blink::mojom::MediaStreamType::DEVICE_AUDIO_CAPTURE) {
    kinds_mask |= (1 << 1);
  }
  if (!tab || !tab->web_contents() || kinds_mask == 0) {
    std::move(callback).Run(
        blink::mojom::StreamDevicesSet(),
        blink::mojom::MediaStreamRequestResult::PERMISSION_DENIED, nullptr);
    return;
  }

  const int64_t request_id = NextMediaRequestId()++;
  const std::string origin = request.url_origin.GetURL().is_valid()
                                 ? request.url_origin.GetURL().spec()
                                 : request.security_origin.spec();
  const GURL top_url = tab->web_contents()->GetLastCommittedURL();
  PendingMedia().emplace(request_id,
                         PendingMediaRequest{tab, request, std::move(callback)});

  RequestMediaAccessBridge(request_id, reinterpret_cast<int64_t>(tab), origin,
                           top_url.is_valid() ? top_url.spec() : std::string(),
                           off_the_record, kinds_mask);
}

// static
void MiruMediaAccessHandler::Resolve(int64_t request_id, bool allowed) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  auto it = PendingMedia().find(request_id);
  if (it == PendingMedia().end()) {
    return;
  }
  PendingMediaRequest entry = std::move(it->second);
  PendingMedia().erase(it);
  CompleteMediaRequest(std::move(entry), allowed);
}

// static
void MiruMediaAccessHandler::CancelForTab(MiruTab* tab) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  std::vector<int64_t> to_remove;
  for (const auto& [id, entry] : PendingMedia()) {
    if (entry.tab == tab) {
      to_remove.push_back(id);
    }
  }
  for (int64_t id : to_remove) {
    auto it = PendingMedia().find(id);
    if (it != PendingMedia().end()) {
      PendingMediaRequest entry = std::move(it->second);
      PendingMedia().erase(it);
      CompleteMediaRequest(std::move(entry), false);
    }
  }
}

}  // namespace miru
