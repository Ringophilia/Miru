// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_CONTENT_BROWSER_CLIENT_H_
#define MIRU_ENGINE_NATIVE_MIRU_CONTENT_BROWSER_CLIENT_H_

#include <atomic>
#include <cstdint>
#include <memory>
#include <optional>
#include <set>
#include <string>
#include <vector>

#include "base/functional/callback.h"
#include "base/memory/raw_ptr.h"
#include "base/memory/raw_ptr_exclusion.h"
#include "base/synchronization/lock.h"
#include "content/public/browser/content_browser_client.h"
#include "content/public/browser/login_delegate.h"
#include "content/public/common/window_container_type.mojom-forward.h"
#include "services/network/public/mojom/web_sandbox_flags.mojom-forward.h"
#include "third_party/blink/public/mojom/window_features/window_features.mojom-forward.h"
#include "ui/base/page_transition_types.h"
#include "ui/base/window_open_disposition.h"

namespace content {
class SiteInstance;
}
namespace blink::web_pref {
struct WebPreferences;
}

struct MiruAdblockHandle;

namespace miru {

class MiruBrowserContext;
class MiruBrowserMainParts;

struct MiruAdblockRef {
  explicit MiruAdblockRef(MiruAdblockHandle* ptr);
  MiruAdblockRef(const MiruAdblockRef&) = delete;
  MiruAdblockRef& operator=(const MiruAdblockRef&) = delete;
  ~MiruAdblockRef();

  // Opaque Rust handle managed by miru_adblock_destroy().
  RAW_PTR_EXCLUSION MiruAdblockHandle* ptr = nullptr;
};

class MiruContentBrowserClient : public content::ContentBrowserClient {
 public:
  static MiruContentBrowserClient* Get();

  MiruContentBrowserClient();
  ~MiruContentBrowserClient() override;

  std::unique_ptr<content::BrowserMainParts> CreateBrowserMainParts(
      bool is_integration_test) override;
  bool IsHandledURL(const GURL& url) override;
  std::vector<std::unique_ptr<blink::URLLoaderThrottle>>
  CreateURLLoaderThrottles(
      const network::ResourceRequest& request,
      content::BrowserContext* browser_context,
      const base::RepeatingCallback<content::WebContents*()>& wc_getter,
      content::NavigationUIData* navigation_ui_data,
      content::FrameTreeNodeId frame_tree_node_id,
      std::optional<int64_t> navigation_id) override;
  void WillCreateURLLoaderFactory(
      content::BrowserContext* browser_context,
      content::RenderFrameHost* frame,
      int render_process_id,
      URLLoaderFactoryType type,
      const url::Origin& request_initiator,
      const net::IsolationInfo& isolation_info,
      std::optional<int64_t> navigation_id,
      ukm::SourceIdObj ukm_source_id,
      network::URLLoaderFactoryBuilder& factory_builder,
      mojo::PendingRemote<network::mojom::TrustedURLLoaderHeaderClient>*
          header_client,
      bool* bypass_redirect_checks,
      bool* disable_secure_dns,
      network::mojom::URLLoaderFactoryOverridePtr* factory_override,
      scoped_refptr<base::SequencedTaskRunner> navigation_response_task_runner,
      bool is_for_network_service) override;
  void CreateThrottlesForNavigation(
      content::NavigationThrottleRegistry& registry) override;
  void ConfigureNetworkContextParams(
      content::BrowserContext* context,
      bool in_memory,
      const base::FilePath& relative_partition_path,
      network::mojom::NetworkContextParams* network_context_params,
      cert_verifier::mojom::CertVerifierCreationParams*
          cert_verifier_creation_params) override;
  void GetAdditionalMappedFilesForChildProcess(
      const base::CommandLine& command_line,
      int child_process_id,
      content::PosixFileDescriptorInfo* mappings) override;
  std::string GetProduct() override;
  std::string GetUserAgent() override;
  std::vector<base::FilePath> GetNetworkContextsParentDirectory() override;
  void OnWebContentsCreated(content::WebContents* web_contents) override;
  void OverrideWebPreferences(content::WebContents* web_contents,
                              content::SiteInstance& main_frame_site,
                              blink::web_pref::WebPreferences* prefs) override;
  bool OverrideWebPreferencesAfterNavigation(
      content::WebContents* web_contents,
      content::SiteInstance& main_frame_site,
      blink::web_pref::WebPreferences* prefs) override;
  bool CanCreateWindow(content::RenderFrameHost* opener,
                       const GURL& opener_url,
                       const GURL& opener_top_level_frame_url,
                       const url::Origin& source_origin,
                       content::mojom::WindowContainerType container_type,
                       const GURL& target_url,
                       const content::Referrer& referrer,
                       const std::string& frame_name,
                       WindowOpenDisposition disposition,
                       const blink::mojom::WindowFeatures& features,
                       bool user_gesture,
                       bool opener_suppressed,
                       bool* no_javascript_access) override;
  std::unique_ptr<content::LoginDelegate> CreateLoginDelegate(
      const net::AuthChallengeInfo& auth_info,
      content::WebContents* web_contents,
      content::BrowserContext* browser_context,
      const content::GlobalRequestID& request_id,
      bool is_request_for_primary_main_frame_navigation,
      bool is_request_for_navigation,
      const GURL& url,
      scoped_refptr<net::HttpResponseHeaders> response_headers,
      bool first_auth_attempt,
      bool do_not_prompt_for_login,
      content::GuestPageHolder* guest_page_holder,
      content::LoginDelegate::LoginAuthRequiredCallback
          auth_required_callback) override;
  bool HandleExternalProtocol(
      const GURL& url,
      base::RepeatingCallback<content::WebContents*()> web_contents_getter,
      content::FrameTreeNodeId frame_tree_node_id,
      content::NavigationUIData* navigation_data,
      bool is_primary_main_frame,
      bool is_in_fenced_frame_tree,
      network::mojom::WebSandboxFlags sandbox_flags,
      ui::PageTransition page_transition,
      bool has_user_gesture,
      const std::optional<url::Origin>& initiating_origin,
      content::RenderFrameHost* initiator_document,
      const net::IsolationInfo& isolation_info,
      mojo::PendingRemote<network::mojom::URLLoaderFactory>* out_factory)
      override;
  void BindMediaServiceReceiver(content::RenderFrameHost* render_frame_host,
                                mojo::GenericPendingReceiver receiver) override;

  MiruBrowserContext* normal_context();
  MiruBrowserContext* GetOrCreateOffTheRecordContext();
  void DestroyOffTheRecordContext();

  std::shared_ptr<MiruAdblockRef> GetAdblockRef() const;
  void SetAdblockRules(const std::string& rules_utf8);
  bool SetAdblockSnapshot(const uint8_t* data, size_t len);
  void SetAdblockEnabled(bool enabled);
  void SetSiteAdblockAllowed(const std::string& host, bool allowed);
  void GetCookiesForUrl(bool off_the_record,
                        const std::string& url,
                        base::OnceCallback<void(std::string)> callback);
  void ApplySecuritySettings(bool block_third_party_cookies,
                             bool https_only,
                             int secure_dns_mode,
                             const std::string& custom_dns_url);
  void ClearBrowsingData(uint32_t miru_mask,
                         int64_t since_ms,
                         base::OnceCallback<void(uint32_t)> callback);
  void NotifyDownloadRequest(const std::string& url,
                             const std::string& file_name,
                             const std::string& mime_type,
                             int64_t content_length,
                             const std::string& source_page,
                             const std::string& user_agent,
                             const std::string& cookie_header,
                             bool off_the_record,
                             const std::string& temp_path);

 private:
  raw_ptr<MiruBrowserMainParts, DanglingUntriaged> main_parts_ = nullptr;
  std::atomic<bool> block_third_party_cookies_{true};
  std::atomic<bool> https_only_{false};
  mutable base::Lock adblock_lock_;
  bool adblock_enabled_ = true;
  std::set<std::string> allowed_hosts_;
  std::shared_ptr<MiruAdblockRef> adblock_ref_;
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_CONTENT_BROWSER_CLIENT_H_
