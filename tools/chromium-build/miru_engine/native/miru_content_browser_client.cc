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
#include "content/public/browser/browsing_data_remover.h"
#include "content/public/browser/client_certificate_delegate.h"
#include "content/public/browser/navigation_controller.h"
#include "content/public/browser/navigation_entry.h"
#include "content/public/browser/navigation_handle.h"
#include "content/public/browser/navigation_throttle_registry.h"
#include "content/public/browser/network_service_instance.h"
#include "content/public/browser/posix_file_descriptor_info.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/storage_partition.h"
#include "content/public/browser/web_contents.h"
#include "content/public/common/url_constants.h"
#include "miru_core.h"
#include "miru_engine/native/miru_browser_context.h"
#include "miru_engine/native/miru_browser_main_parts.h"
#include "miru_engine/native/miru_client_cert_bridge.h"
#include "miru_engine/native/miru_descriptors.h"
#include "miru_engine/native/miru_https_only_throttle.h"
#include "miru_engine/native/miru_login_delegate.h"
#include "miru_engine/native/miru_media_drm_storage.h"
#include "miru_engine/native/miru_proxy_config_monitor.h"
#include "miru_engine/native/miru_runtime_bridges.h"
#include "media/mojo/mojom/media_drm_storage.mojom.h"
#include "miru_engine/native/miru_tab.h"
#include "miru_engine/native/miru_url_loader_factory.h"
#include "miru_engine/native/miru_url_loader_throttle.h"
#include "net/base/features.h"
#include "net/base/url_util.h"
#include "net/cookies/canonical_cookie.h"
#include "net/cookies/cookie_options.h"
#include "net/cookies/cookie_partition_key_collection.h"
#include "net/dns/public/dns_over_https_config.h"
#include "net/dns/public/secure_dns_mode.h"
#include "services/network/public/cpp/url_loader_factory_builder.h"
#include "services/network/public/mojom/clear_data_filter.mojom.h"
#include "services/network/public/mojom/cookie_manager.mojom.h"
#include "services/network/public/mojom/host_resolver.mojom.h"
#include "services/network/public/mojom/network_context.mojom.h"
#include "services/network/public/mojom/network_service.mojom.h"
#include "third_party/blink/public/common/web_preferences/web_preferences.h"
#include "third_party/blink/public/mojom/webpreferences/web_preferences.mojom-shared.h"
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

void MiruContentBrowserClient::CreateThrottlesForNavigation(
    content::NavigationThrottleRegistry& registry) {
  if (!https_only_.load(std::memory_order_acquire)) {
    return;
  }
  content::NavigationHandle& handle = registry.GetNavigationHandle();
  if (handle.IsInPrimaryMainFrame() &&
      handle.GetURL().SchemeIs(url::kHttpScheme) &&
      !net::IsLocalhost(handle.GetURL())) {
    registry.AddThrottle(
        std::make_unique<MiruHttpsOnlyNavigationThrottle>(registry));
  }
}

void MiruContentBrowserClient::ConfigureNetworkContextParams(
    content::BrowserContext* context,
    bool in_memory,
    const base::FilePath& relative_partition_path,
    network::mojom::NetworkContextParams* network_context_params,
    cert_verifier::mojom::CertVerifierCreationParams*
        cert_verifier_creation_params) {
  ContentBrowserClient::ConfigureNetworkContextParams(
      context, in_memory, relative_partition_path, network_context_params,
      cert_verifier_creation_params);
  if (network_context_params) {
    if (!network_context_params->cookie_manager_params) {
      network_context_params->cookie_manager_params =
          network::mojom::CookieManagerParams::New();
    }
    network_context_params->cookie_manager_params->block_third_party_cookies =
        block_third_party_cookies_.load(std::memory_order_acquire);
    MiruProxyConfigMonitor::GetInstance()->AddProxyToNetworkContextParams(
        network_context_params);
  }
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

namespace {

blink::mojom::AutoplayPolicy ResolveAutoplayPolicy(
    content::WebContents* web_contents,
    content::SiteInstance& /*main_frame_site*/,
    bool prefer_committed) {
  if (!web_contents || !web_contents->GetBrowserContext()) {
    return blink::mojom::AutoplayPolicy::kDocumentUserActivationRequired;
  }
  content::NavigationEntry* pending =
      web_contents->GetController().GetPendingEntry();
  const GURL pending_url =
      pending && pending->GetURL().is_valid() ? pending->GetURL() : GURL();
  const GURL& committed = web_contents->GetLastCommittedURL();
  const GURL& visible = web_contents->GetVisibleURL();
  const GURL url =
      prefer_committed
          ? (committed.is_valid() ? committed : visible)
          : (pending_url.is_valid()
                 ? pending_url
                 : (visible.is_valid() ? visible : committed));
  const std::string target = url.is_valid() ? url.spec() : std::string();
  const int code = QueryAutoplayPolicyBridge(
      target, web_contents->GetBrowserContext()->IsOffTheRecord());
  if (code == 1) {
    return blink::mojom::AutoplayPolicy::kNoUserGestureRequired;
  }
  if (code == 2) {
    return blink::mojom::AutoplayPolicy::kUserGestureRequired;
  }
  return blink::mojom::AutoplayPolicy::kDocumentUserActivationRequired;
}

}  // namespace

void MiruContentBrowserClient::OverrideWebPreferences(
    content::WebContents* web_contents,
    content::SiteInstance& main_frame_site,
    blink::web_pref::WebPreferences* prefs) {
  if (!prefs) {
    return;
  }
  prefs->autoplay_policy = ResolveAutoplayPolicy(web_contents, main_frame_site,
                                                 /*prefer_committed=*/false);
}

bool MiruContentBrowserClient::OverrideWebPreferencesAfterNavigation(
    content::WebContents* web_contents,
    content::SiteInstance& main_frame_site,
    blink::web_pref::WebPreferences* prefs) {
  if (!prefs) {
    return false;
  }
  const auto next = ResolveAutoplayPolicy(web_contents, main_frame_site,
                                          /*prefer_committed=*/true);
  if (prefs->autoplay_policy == next) {
    return false;
  }
  prefs->autoplay_policy = next;
  return true;
}

bool MiruContentBrowserClient::CanCreateWindow(
    content::RenderFrameHost* opener,
    const GURL& opener_url,
    const GURL& opener_top_level_frame_url,
    const url::Origin& /*source_origin*/,
    content::mojom::WindowContainerType /*container_type*/,
    const GURL& target_url,
    const content::Referrer& /*referrer*/,
    const std::string& /*frame_name*/,
    WindowOpenDisposition /*disposition*/,
    const blink::mojom::WindowFeatures& /*features*/,
    bool user_gesture,
    bool opener_suppressed,
    bool* /*no_javascript_access*/) {
  content::WebContents* wc =
      opener ? content::WebContents::FromRenderFrameHost(opener) : nullptr;
  MiruTab* tab = wc ? MiruTab::FromWebContents(wc) : nullptr;
  if (!tab) {
    return false;
  }
  const std::string effective_opener =
      opener_top_level_frame_url.is_valid()
          ? opener_top_level_frame_url.spec()
          : (wc->GetLastCommittedURL().is_valid()
                 ? wc->GetLastCommittedURL().spec()
                 : opener_url.spec());
  return tab->CheckPopupAllowed(effective_opener, target_url.spec(),
                                user_gesture, opener_suppressed);
}

std::unique_ptr<content::LoginDelegate>
MiruContentBrowserClient::CreateLoginDelegate(
    const net::AuthChallengeInfo& auth_info,
    content::WebContents* web_contents,
    content::BrowserContext* /*browser_context*/,
    const content::GlobalRequestID& /*request_id*/,
    bool /*is_request_for_primary_main_frame_navigation*/,
    bool /*is_request_for_navigation*/,
    const GURL& url,
    scoped_refptr<net::HttpResponseHeaders> /*response_headers*/,
    bool first_auth_attempt,
    bool do_not_prompt_for_login,
    content::GuestPageHolder* /*guest_page_holder*/,
    content::LoginDelegate::LoginAuthRequiredCallback auth_required_callback) {
  if (do_not_prompt_for_login || !web_contents) {
    return nullptr;
  }
  MiruTab* tab = MiruTab::FromWebContents(web_contents);
  if (!tab) {
    return nullptr;
  }
  return std::make_unique<MiruLoginDelegate>(
      tab->AsWeakPtr(), auth_info, url, first_auth_attempt,
      std::move(auth_required_callback));
}

bool MiruContentBrowserClient::HandleExternalProtocol(
    const GURL& url,
    base::RepeatingCallback<content::WebContents*()> web_contents_getter,
    content::FrameTreeNodeId /*frame_tree_node_id*/,
    content::NavigationUIData* /*navigation_data*/,
    bool is_primary_main_frame,
    bool is_in_fenced_frame_tree,
    network::mojom::WebSandboxFlags /*sandbox_flags*/,
    ui::PageTransition /*page_transition*/,
    bool has_user_gesture,
    const std::optional<url::Origin>& /*initiating_origin*/,
    content::RenderFrameHost* /*initiator_document*/,
    const net::IsolationInfo& /*isolation_info*/,
    mojo::PendingRemote<network::mojom::URLLoaderFactory>* /*out_factory*/) {
  if (is_primary_main_frame && !is_in_fenced_frame_tree &&
      web_contents_getter) {
    content::WebContents* wc = web_contents_getter.Run();
    if (MiruTab* tab = wc ? MiruTab::FromWebContents(wc) : nullptr) {
      tab->OnExternalProtocol(url.spec(), has_user_gesture);
    }
  }
  return true;
}

void MiruContentBrowserClient::BindMediaServiceReceiver(
    content::RenderFrameHost* render_frame_host,
    mojo::GenericPendingReceiver receiver) {
  if (auto r = receiver.As<media::mojom::MediaDrmStorage>()) {
    MiruMediaDrmStorage::Create(render_frame_host, std::move(r));
  }
}

base::OnceClosure MiruContentBrowserClient::SelectClientCertificate(
    content::BrowserContext* /*browser_context*/,
    content::ChildProcessId /*process_id*/,
    content::WebContents* web_contents,
    net::SSLCertRequestInfo* cert_request_info,
    net::ClientCertIdentityList /*client_certs*/,
    std::unique_ptr<content::ClientCertificateDelegate> delegate) {
  return MiruClientCertBridge::SelectClientCertificate(
      web_contents, cert_request_info, std::move(delegate));
}

MiruBrowserContext* MiruContentBrowserClient::normal_context() {
  return main_parts_ ? main_parts_->normal_context() : nullptr;
}

MiruBrowserContext*
MiruContentBrowserClient::GetOrCreateOffTheRecordContext() {
  return main_parts_ ? main_parts_->GetOrCreateOffTheRecordContext() : nullptr;
}

void MiruContentBrowserClient::DestroyOffTheRecordContext() {
  MiruMediaDrmStorage::ClearOffTheRecord();
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

void MiruContentBrowserClient::ApplySecuritySettings(
    bool block_third_party_cookies,
    bool https_only,
    int secure_dns_mode,
    const std::string& custom_dns_url) {
  block_third_party_cookies_.store(block_third_party_cookies,
                                   std::memory_order_release);
  https_only_.store(https_only, std::memory_order_release);

  auto apply_cookie_policy = [block_third_party_cookies](MiruBrowserContext* ctx) {
    if (!ctx) {
      return;
    }
    auto* partition = ctx->GetDefaultStoragePartition();
    auto* cookie_manager =
        partition ? partition->GetCookieManagerForBrowserProcess() : nullptr;
    if (cookie_manager) {
      cookie_manager->BlockThirdPartyCookies(block_third_party_cookies);
    }
  };
  apply_cookie_policy(normal_context());
  apply_cookie_policy(main_parts_ ? main_parts_->off_the_record_context()
                                  : nullptr);

  net::SecureDnsMode mode = net::SecureDnsMode::kAutomatic;
  net::DnsOverHttpsConfig doh;
  if (secure_dns_mode == 0) {
    mode = net::SecureDnsMode::kOff;
  } else if (secure_dns_mode == 2) {
    auto parsed = net::DnsOverHttpsConfig::FromString(custom_dns_url);
    if (parsed && !parsed->servers().empty()) {
      mode = net::SecureDnsMode::kSecure;
      doh = *parsed;
    }
  }
  if (auto* network_service = content::GetNetworkService()) {
    const net::InsecureDnsMode insecure_mode =
        net::features::IsDnsPlatformSupported()
            ? net::InsecureDnsMode::kEnabledPlatform
            : net::InsecureDnsMode::kEnabledBuiltIn;
    network_service->ConfigureStubHostResolver(
        insecure_mode,
        /*happy_eyeballs_v3_enabled=*/false, mode, doh,
        /*additional_dns_types_enabled=*/true,
        /*fallback_doh_nameservers=*/{});
  }
}

namespace {

class MiruClearDataObserver final
    : public content::BrowsingDataRemover::Observer {
 public:
  MiruClearDataObserver(content::BrowsingDataRemover* remover,
                        base::OnceCallback<void(uint32_t)> callback)
      : remover_(remover), callback_(std::move(callback)) {
    remover_->AddObserver(this);
  }

  void OnBrowsingDataRemoverDone(uint64_t failed_data_types) override {
    if (remover_) {
      remover_->RemoveObserver(this);
      remover_ = nullptr;
    }
    uint32_t failed_miru = 0;
    if (failed_data_types & content::BrowsingDataRemover::DATA_TYPE_COOKIES) {
      failed_miru |= 1u;
    }
    if (failed_data_types &
        content::BrowsingDataRemover::DATA_TYPE_DOM_STORAGE) {
      failed_miru |= 2u;
    }
    if (failed_data_types & content::BrowsingDataRemover::DATA_TYPE_CACHE) {
      failed_miru |= 4u;
    }
    std::move(callback_).Run(failed_miru);
    delete this;
  }

 private:
  ~MiruClearDataObserver() override = default;

  raw_ptr<content::BrowsingDataRemover> remover_;
  base::OnceCallback<void(uint32_t)> callback_;
};

}  // namespace

void MiruContentBrowserClient::ClearBrowsingData(
    uint32_t miru_mask,
    int64_t since_ms,
    base::OnceCallback<void(uint32_t)> callback) {
  MiruBrowserContext* ctx = normal_context();
  if (!ctx) {
    std::move(callback).Run(miru_mask);
    return;
  }
  const base::Time begin_time =
      since_ms > 0 ? base::Time::FromMillisecondsSinceUnixEpoch(since_ms)
                   : base::Time();
  if (miru_mask & 8u) {
    if (auto* partition = ctx->GetDefaultStoragePartition()) {
      if (auto* net_ctx = partition->GetNetworkContext()) {
        net_ctx->ClearHttpAuthCache(begin_time, base::Time::Max(), nullptr,
                                    base::DoNothing());
      }
    }
  }
  uint64_t remove_mask = 0;
  if (miru_mask & 1u) {
    remove_mask |= content::BrowsingDataRemover::DATA_TYPE_COOKIES;
  }
  if (miru_mask & 2u) {
    remove_mask |= content::BrowsingDataRemover::DATA_TYPE_DOM_STORAGE |
                   content::BrowsingDataRemover::DATA_TYPE_MEDIA_LICENSES |
                   content::BrowsingDataRemover::DATA_TYPE_PRIVACY_SANDBOX;
  }
  if (miru_mask & 4u) {
    remove_mask |= content::BrowsingDataRemover::DATA_TYPE_CACHE;
  }
  if (remove_mask == 0) {
    std::move(callback).Run(0u);
    return;
  }
  content::BrowsingDataRemover* remover = ctx->GetBrowsingDataRemover();
  if (!remover) {
    std::move(callback).Run(miru_mask & 7u);
    return;
  }
  auto* observer = new MiruClearDataObserver(remover, std::move(callback));
  remover->RemoveAndReply(
      begin_time, base::Time::Max(), remove_mask,
      content::BrowsingDataRemover::ORIGIN_TYPE_UNPROTECTED_WEB |
          content::BrowsingDataRemover::ORIGIN_TYPE_PROTECTED_WEB,
      observer);
}

}  // namespace miru
