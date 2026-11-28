// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_DOWNLOAD_DELEGATE_H_
#define MIRU_ENGINE_NATIVE_MIRU_DOWNLOAD_DELEGATE_H_

#include <cstdint>
#include <string>

#include "base/memory/raw_ptr.h"
#include "base/memory/weak_ptr.h"
#include "components/download/public/common/download_target_info.h"
#include "content/public/browser/download_manager_delegate.h"

namespace content {
class DownloadManager;
}  // namespace content

namespace miru {

class MiruBrowserContext;

/// Intercepts Chromium download triggers and hands URLs, isolated cookies, and
/// `blob:`/`data:` payloads directly to Miru's native download pipeline.
class MiruDownloadManagerDelegate final : public content::DownloadManagerDelegate {
 public:
  explicit MiruDownloadManagerDelegate(MiruBrowserContext* context);
  MiruDownloadManagerDelegate(const MiruDownloadManagerDelegate&) = delete;
  MiruDownloadManagerDelegate& operator=(const MiruDownloadManagerDelegate&) =
      delete;
  ~MiruDownloadManagerDelegate() override;

  void SetDownloadManager(content::DownloadManager* download_manager);
  void Shutdown() override;

  bool InterceptDownloadIfApplicable(
      const GURL& url,
      const std::string& user_agent,
      const std::string& content_disposition,
      const std::string& mime_type,
      const std::string& request_origin,
      int64_t content_length,
      bool is_transient,
      bool is_content_initiated,
      content::WebContents* web_contents) override;

  bool DetermineDownloadTarget(
      download::DownloadItem* item,
      download::DownloadTargetCallback* callback) override;

 private:
  void DispatchWithOptionalCookies(const std::string& url,
                                   const std::string& file_name,
                                   const std::string& mime_type,
                                   int64_t content_length,
                                   const std::string& page_url,
                                   const std::string& user_agent,
                                   const std::string& temp_path,
                                   bool fetch_cookies);

  raw_ptr<MiruBrowserContext> context_;
  raw_ptr<content::DownloadManager> download_manager_ = nullptr;
  base::WeakPtrFactory<MiruDownloadManagerDelegate> weak_factory_{this};
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_DOWNLOAD_DELEGATE_H_
