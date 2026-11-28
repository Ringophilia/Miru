// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_download_delegate.h"

#include <utility>

#include "base/files/file_path.h"
#include "base/files/file_util.h"
#include "base/functional/bind.h"
#include "base/rand_util.h"
#include "base/strings/string_number_conversions.h"
#include "base/strings/utf_string_conversions.h"
#include "components/download/public/common/download_danger_type.h"
#include "components/download/public/common/download_interrupt_reasons.h"
#include "components/download/public/common/download_item.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/download_manager.h"
#include "content/public/browser/web_contents.h"
#include "miru_engine/native/miru_browser_context.h"
#include "miru_engine/native/miru_content_browser_client.h"
#include "net/base/filename_util.h"
#include "url/gurl.h"

namespace miru {

namespace {

/// Self-deleting observer forwarding completed `blob:` temp files to Kotlin, or
/// deleting incomplete temporary files on cancel/interruption.
class BlobDownloadObserver final : public download::DownloadItem::Observer {
 public:
  BlobDownloadObserver(download::DownloadItem* item,
                       base::FilePath temp_path,
                       bool is_private,
                       std::string url,
                       std::string file_name,
                       std::string mime_type,
                       int64_t content_length,
                       std::string page_url,
                       std::string user_agent)
      : item_(item),
        temp_path_(std::move(temp_path)),
        is_private_(is_private),
        url_(std::move(url)),
        file_name_(std::move(file_name)),
        mime_type_(std::move(mime_type)),
        content_length_(content_length),
        page_url_(std::move(page_url)),
        user_agent_(std::move(user_agent)) {
    item_->AddObserver(this);
  }

  void OnDownloadUpdated(download::DownloadItem* download) override {
    if (download != item_) {
      return;
    }
    switch (download->GetState()) {
      case download::DownloadItem::COMPLETE: {
        const int64_t size = download->GetReceivedBytes() > 0
                                 ? download->GetReceivedBytes()
                                 : content_length_;
        if (auto* client = MiruContentBrowserClient::Get()) {
          client->NotifyDownloadRequest(url_, file_name_, mime_type_, size,
                                        page_url_, user_agent_, std::string(),
                                        is_private_, temp_path_.value());
        } else {
          base::DeleteFile(temp_path_);
        }
        CleanupAndDestroy();
        break;
      }
      case download::DownloadItem::CANCELLED:
      case download::DownloadItem::INTERRUPTED:
        base::DeleteFile(temp_path_);
        CleanupAndDestroy();
        break;
      default:
        break;
    }
  }

  void OnDownloadDestroyed(download::DownloadItem* download) override {
    if (download == item_) {
      base::DeleteFile(temp_path_);
      item_ = nullptr;
      delete this;
    }
  }

 private:
  ~BlobDownloadObserver() override = default;

  void CleanupAndDestroy() {
    if (item_) {
      item_->RemoveObserver(this);
      item_ = nullptr;
    }
    delete this;
  }

  raw_ptr<download::DownloadItem> item_;
  base::FilePath temp_path_;
  bool is_private_;
  std::string url_;
  std::string file_name_;
  std::string mime_type_;
  int64_t content_length_;
  std::string page_url_;
  std::string user_agent_;
};

std::string ResolveSuggestedFileName(const GURL& url,
                                     const std::string& content_disposition,
                                     const std::string& suggested_name,
                                     const std::string& mime_type) {
  return base::UTF16ToUTF8(net::GetSuggestedFilename(
      url, content_disposition, /*referrer_charset=*/"", suggested_name,
      mime_type, /*default_name=*/"download"));
}

}  // namespace

MiruDownloadManagerDelegate::MiruDownloadManagerDelegate(
    MiruBrowserContext* context)
    : context_(context) {}

MiruDownloadManagerDelegate::~MiruDownloadManagerDelegate() {
  if (download_manager_) {
    download_manager_->SetDelegate(nullptr);
    download_manager_ = nullptr;
  }
}

void MiruDownloadManagerDelegate::SetDownloadManager(
    content::DownloadManager* download_manager) {
  download_manager_ = download_manager;
}

void MiruDownloadManagerDelegate::Shutdown() {
  weak_factory_.InvalidateWeakPtrs();
  download_manager_ = nullptr;
}

bool MiruDownloadManagerDelegate::InterceptDownloadIfApplicable(
    const GURL& url,
    const std::string& user_agent,
    const std::string& content_disposition,
    const std::string& mime_type,
    const std::string& request_origin,
    int64_t content_length,
    bool /*is_transient*/,
    bool /*is_content_initiated*/,
    content::WebContents* web_contents) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!url.is_valid()) {
    return false;
  }
  if (url.SchemeIsBlob()) {
    // Let Chromium stream `blob:` payload into a temp file via
    // DetermineDownloadTarget, then hand that temp path to Miru.
    return false;
  }
  if (!url.SchemeIsHTTPOrHTTPS() && !url.SchemeIs(url::kDataScheme)) {
    return false;
  }
  const std::string page_url =
      web_contents && web_contents->GetLastCommittedURL().is_valid()
          ? web_contents->GetLastCommittedURL().spec()
          : request_origin;
  const std::string file_name = ResolveSuggestedFileName(
      url, content_disposition, /*suggested_name=*/"", mime_type);
  DispatchWithOptionalCookies(url.spec(), file_name, mime_type, content_length,
                              page_url, user_agent, /*temp_path=*/"",
                              /*fetch_cookies=*/url.SchemeIsHTTPOrHTTPS());
  return true;
}

bool MiruDownloadManagerDelegate::DetermineDownloadTarget(
    download::DownloadItem* item,
    download::DownloadTargetCallback* callback) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!item || !callback) {
    return false;
  }
  const GURL& url = item->GetURL();
  const std::string page_url =
      item->GetTabUrl().is_valid() ? item->GetTabUrl().spec() : std::string();
  const std::string file_name = ResolveSuggestedFileName(
      url, item->GetContentDisposition(), item->GetSuggestedFilename(),
      item->GetMimeType());

  if (url.SchemeIsHTTPOrHTTPS() || url.SchemeIs(url::kDataScheme)) {
    std::string ua;
    if (auto* client = MiruContentBrowserClient::Get()) {
      ua = client->GetUserAgent();
    }
    DispatchWithOptionalCookies(url.spec(), file_name, item->GetMimeType(),
                                item->GetTotalBytes(), page_url, ua,
                                /*temp_path=*/"",
                                /*fetch_cookies=*/url.SchemeIsHTTPOrHTTPS());
    std::move(*callback).Run(download::DownloadTargetInfo());
    return true;
  }

  if (url.SchemeIsBlob() && context_) {
    base::FilePath dir = context_->GetPath().AppendASCII("blob_tmp");
    if (!base::CreateDirectory(dir)) {
      std::move(*callback).Run(download::DownloadTargetInfo());
      return true;
    }
    base::FilePath temp_path = dir.AppendASCII(
        "blob_" + base::HexEncodeLower(base::RandBytesAsVector(8)) + ".bin");
    std::string ua;
    if (auto* client = MiruContentBrowserClient::Get()) {
      ua = client->GetUserAgent();
    }
    new BlobDownloadObserver(item, temp_path, context_->IsOffTheRecord(),
                             url.spec(), file_name, item->GetMimeType(),
                             item->GetTotalBytes(), page_url, ua);
    download::DownloadTargetInfo info;
    info.target_path = temp_path;
    info.intermediate_path = temp_path;
    info.target_disposition =
        download::DownloadItem::TARGET_DISPOSITION_OVERWRITE;
    info.danger_type = download::DOWNLOAD_DANGER_TYPE_NOT_DANGEROUS;
    info.interrupt_reason = download::DOWNLOAD_INTERRUPT_REASON_NONE;
    info.mime_type = item->GetMimeType();
    std::move(*callback).Run(std::move(info));
    return true;
  }

  std::move(*callback).Run(download::DownloadTargetInfo());
  return true;
}

void MiruDownloadManagerDelegate::DispatchWithOptionalCookies(
    const std::string& url,
    const std::string& file_name,
    const std::string& mime_type,
    int64_t content_length,
    const std::string& page_url,
    const std::string& user_agent,
    const std::string& temp_path,
    bool fetch_cookies) {
  auto* client = MiruContentBrowserClient::Get();
  if (!client) {
    return;
  }
  const bool is_private = context_ && context_->IsOffTheRecord();
  const std::string effective_ua =
      user_agent.empty() ? client->GetUserAgent() : user_agent;
  if (!fetch_cookies) {
    client->NotifyDownloadRequest(url, file_name, mime_type, content_length,
                                  page_url, effective_ua, std::string(),
                                  is_private, temp_path);
    return;
  }
  client->GetCookiesForUrl(
      is_private, url,
      base::BindOnce(
          [](base::WeakPtr<MiruDownloadManagerDelegate> self, std::string u,
             std::string name, std::string mime, int64_t len, std::string page,
             std::string ua, bool priv, std::string tmp, std::string cookies) {
            if (!self) {
              return;
            }
            if (auto* c = MiruContentBrowserClient::Get()) {
              c->NotifyDownloadRequest(u, name, mime, len, page, ua, cookies,
                                       priv, tmp);
            }
          },
          weak_factory_.GetWeakPtr(), url, file_name, mime_type, content_length,
          page_url, effective_ua, is_private, temp_path));
}

}  // namespace miru
