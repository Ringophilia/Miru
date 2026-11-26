// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#include "miru_engine/native/miru_content_browser_client.h"

#include "base/android/apk_assets.h"
#include "base/containers/fixed_flat_set.h"
#include "base/functional/bind.h"
#include "base/memory/weak_ptr.h"
#include "base/posix/global_descriptors.h"
#include "components/embedder_support/user_agent_utils.h"
#include "components/performance_manager/embedder/performance_manager_registry.h"
#include "content/public/browser/browser_task_traits.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/posix_file_descriptor_info.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/storage_partition.h"
#include "content/public/browser/web_contents.h"
#include "content/public/common/url_constants.h"
#include "miru_core.h"
#include "miru_engine/native/miru_browser_context.h"
#include "miru_engine/native/miru_browser_main_parts.h"
#include "miru_engine/native/miru_descriptors.h"
#include "miru_engine/native/miru_tab.h"
#include "miru_engine/native/miru_url_loader_factory.h"
#include "miru_engine/native/miru_url_loader_throttle.h"
#include "net/cookies/canonical_cookie.h"
#include "net/cookies/cookie_options.h"
#include "net/cookies/cookie_partition_key_collection.h"
#include "services/network/public/cpp/url_loader_factory_builder.h"
#include "services/network/public/mojom/cookie_manager.mojom.h"
#include "url/gurl.h"
#include "url/url_constants.h"

namespace miru {

namespace {
MiruContentBrowserClient* g_client = nullptr;
constexpr char kMiruProduct[] = "Chrome/159.0.8154.33";
}  // namespace

MiruAdblockRef::MiruAdblockRef(MiruAdblockHandle* ptr) : ptr(ptr) {}

MiruAdblockRef::~MiruAdblockRef() {
  if (ptr) {
    miru_adblock_destroy(ptr);
    ptr = nullptr;
  }
}

// static
MiruContentBrowserClient* MiruContentBrowserClient::Get() {
  return g_client;
}

MiruContentBrowserClient::MiruContentBrowserClient()
    : adblock_ref_(std::make_shared<MiruAdblockRef>(
          miru_adblock_create_from_rules(""))) {
  g_client = this;
}

MiruContentBrowserClient::~MiruContentBrowserClient() {
  {
    base::AutoLock lock(adblock_lock_);
    adblock_ref_.reset();
  }
  if (g_client == this) {
    g_client = nullptr;
  }
}

std::unique_ptr<content::BrowserMainParts>
MiruContentBrowserClient::CreateBrowserMainParts(bool) {
  auto parts = std::make_unique<MiruBrowserMainParts>();
  main_parts_ = parts.get();
  return parts;
}

bool MiruContentBrowserClient::IsHandledURL(const GURL& url) {
  if (!url.is_valid()) {
    return false;
  }
  constexpr auto kHandledSchemes = base::MakeFixedFlatSet<std::string_view>({
      url::kHttpScheme,
      url::kHttpsScheme,
      url::kWsScheme,
      url::kWssScheme,
      url::kBlobScheme,
      url::kFileSystemScheme,
      url::kDataScheme,
      url::kFileScheme,
      url::kAboutScheme,
  });
  return kHandledSchemes.contains(url.scheme());
}

std::vector<std::unique_ptr<blink::URLLoaderThrottle>>
MiruContentBrowserClient::CreateURLLoaderThrottles(
    const network::ResourceRequest& request,
    content::BrowserContext* browser_context,
    const base::RepeatingCallback<content::WebContents*()>& wc_getter,
    content::NavigationUIData*,
    content::FrameTreeNodeId,
    std::optional<int64_t>) {
  std::vector<std::unique_ptr<blink::URLLoaderThrottle>> throttles;
  content::WebContents* wc = wc_getter ? wc_getter.Run() : nullptr;
  MiruTab* tab = wc ? MiruTab::FromWebContents(wc) : nullptr;
  std::string page_url = wc ? wc->GetLastCommittedURL().spec() : std::string();
  base::WeakPtr<MiruTab> weak_tab = tab ? tab->AsWeakPtr() : nullptr;
  throttles.push_back(std::make_unique<MiruUrlLoaderThrottle>(
      std::move(weak_tab), content::GetUIThreadTaskRunner({}),
      std::move(page_url)));
  return throttles;
}

void MiruContentBrowserClient::WillCreateURLLoaderFactory(
    content::BrowserContext* /*browser_context*/,
    content::RenderFrameHost* frame,
    int /*render_process_id*/,
    URLLoaderFactoryType type,
    const url::Origin& request_initiator,
    const net::IsolationInfo& /*isolation_info*/,
    std::optional<int64_t> /*navigation_id*/,
    ukm::SourceIdObj /*ukm_source_id*/,
    network::URLLoaderFactoryBuilder& factory_builder,
    mojo::PendingRemote<network::mojom::TrustedURLLoaderHeaderClient>*
    /*header_client*/,
    bool* /*bypass_redirect_checks*/,
    bool* /*disable_secure_dns*/,
    network::mojom::URLLoaderFactoryOverridePtr* /*factory_override*/,
    scoped_refptr<base::SequencedTaskRunner> /*navigation_response_task_runner*/,
    bool /*is_for_network_service*/) {
  if (type != URLLoaderFactoryType::kDocumentSubResource &&
      type != URLLoaderFactoryType::kWorkerMainResource &&
      type != URLLoaderFactoryType::kWorkerSubResource &&
      type != URLLoaderFactoryType::kServiceWorkerSubResource) {
    return;
  }
  auto [proxy_receiver, target_remote] = factory_builder.Append();
  content::WebContents* wc =
      frame ? content::WebContents::FromRenderFrameHost(frame) : nullptr;
  MiruTab* tab = wc ? MiruTab::FromWebContents(wc) : nullptr;
  std::string page_url =
      wc ? wc->GetLastCommittedURL().spec() : request_initiator.Serialize();
  MiruURLLoaderFactory::Create(
      std::move(proxy_receiver), std::move(target_remote),
      tab ? tab->AsWeakPtr() : nullptr, std::move(page_url));
}

void MiruContentBrowserClient::GetAdditionalMappedFilesForChildProcess(
    const base::CommandLine&,
    int,
    content::PosixFileDescriptorInfo* mappings) {
  mappings->ShareWithRegion(
      kMiruPakDescriptor,
      base::GlobalDescriptors::GetInstance()->Get(kMiruPakDescriptor),
      base::GlobalDescriptors::GetInstance()->GetRegion(kMiruPakDescriptor));
}

std::string MiruContentBrowserClient::GetProduct() {
  return kMiruProduct;
}

std::string MiruContentBrowserClient::GetUserAgent() {
  return embedder_support::BuildUnifiedPlatformUserAgentFromProduct(
      "Chrome/159.0.8154.33 Mobile");
}

std::vector<base::FilePath>
MiruContentBrowserClient::GetNetworkContextsParentDirectory() {
  if (auto* ctx = normal_context()) {
    return {ctx->GetPath()};
  }
  return {};
}

void MiruContentBrowserClient::OnWebContentsCreated(
    content::WebContents* web_contents) {
  if (auto* registry =
          performance_manager::PerformanceManagerRegistry::GetInstance()) {
    registry->MaybeCreatePageNodeForWebContents(web_contents);
  }
}

MiruBrowserContext* MiruContentBrowserClient::normal_context() {
  return main_parts_ ? main_parts_->normal_context() : nullptr;
}

MiruBrowserContext*
MiruContentBrowserClient::GetOrCreateOffTheRecordContext() {
  return main_parts_ ? main_parts_->GetOrCreateOffTheRecordContext() : nullptr;
}

void MiruContentBrowserClient::DestroyOffTheRecordContext() {
  if (main_parts_) {
    main_parts_->DestroyOffTheRecordContext();
  }
}

std::shared_ptr<MiruAdblockRef> MiruContentBrowserClient::GetAdblockRef() const {
  base::AutoLock lock(adblock_lock_);
  return adblock_ref_;
}

void MiruContentBrowserClient::SetAdblockRules(const std::string& rules_utf8) {
  MiruAdblockHandle* raw = miru_adblock_create_from_rules(rules_utf8.c_str());
  auto next = std::make_shared<MiruAdblockRef>(raw);
  base::AutoLock lock(adblock_lock_);
  if (raw) {
    miru_adblock_set_enabled(raw, adblock_enabled_ ? 1 : 0);
    for (const std::string& host : allowed_hosts_) {
      miru_adblock_set_site_allowed(raw, host.c_str(), 1);
    }
  }
  adblock_ref_ = std::move(next);
}

bool MiruContentBrowserClient::SetAdblockSnapshot(const uint8_t* data,
                                                  size_t len) {
  MiruAdblockHandle* raw = miru_adblock_create_from_snapshot(data, len);
  if (!raw) {
    return false;
  }
  auto next = std::make_shared<MiruAdblockRef>(raw);
  base::AutoLock lock(adblock_lock_);
  miru_adblock_set_enabled(raw, adblock_enabled_ ? 1 : 0);
  for (const std::string& host : allowed_hosts_) {
    miru_adblock_set_site_allowed(raw, host.c_str(), 1);
  }
  adblock_ref_ = std::move(next);
  return true;
}

void MiruContentBrowserClient::SetAdblockEnabled(bool enabled) {
  base::AutoLock lock(adblock_lock_);
  adblock_enabled_ = enabled;
  if (adblock_ref_ && adblock_ref_->ptr) {
    miru_adblock_set_enabled(adblock_ref_->ptr, enabled ? 1 : 0);
  }
}

void MiruContentBrowserClient::SetSiteAdblockAllowed(const std::string& host,
                                                     bool allowed) {
  base::AutoLock lock(adblock_lock_);
  if (allowed) {
    allowed_hosts_.insert(host);
  } else {
    allowed_hosts_.erase(host);
  }
  if (adblock_ref_ && adblock_ref_->ptr) {
    miru_adblock_set_site_allowed(adblock_ref_->ptr, host.c_str(),
                                  allowed ? 1 : 0);
  }
}

void MiruContentBrowserClient::GetCookiesForUrl(
    bool off_the_record,
    const std::string& url,
    base::OnceCallback<void(std::string)> callback) {
  MiruBrowserContext* ctx =
      off_the_record ? (main_parts_ ? main_parts_->off_the_record_context() : nullptr)
                     : normal_context();
  const GURL gurl(url);
  if (!ctx || !gurl.is_valid() || !gurl.SchemeIsHTTPOrHTTPS()) {
    std::move(callback).Run(std::string());
    return;
  }
  auto* partition = ctx->GetDefaultStoragePartition();
  auto* cookie_manager =
      partition ? partition->GetCookieManagerForBrowserProcess() : nullptr;
  if (!cookie_manager) {
    std::move(callback).Run(std::string());
    return;
  }
  cookie_manager->GetCookieList(
      gurl, net::CookieOptions::MakeAllInclusive(),
      net::CookiePartitionKeyCollection::ContainsAll(),
      base::BindOnce(
          [](base::OnceCallback<void(std::string)> cb,
             const net::CookieAccessResultList& cookies,
             const net::CookieAccessResultList& /*excluded*/) {
            std::move(cb).Run(net::CanonicalCookie::BuildCookieLine(cookies));
          },
          std::move(callback)));
}

}  // namespace miru
