// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_ENGINE_NATIVE_MIRU_CONTENT_BROWSER_CLIENT_H_
#define MIRU_ENGINE_NATIVE_MIRU_CONTENT_BROWSER_CLIENT_H_

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
  void GetAdditionalMappedFilesForChildProcess(
      const base::CommandLine& command_line,
      int child_process_id,
      content::PosixFileDescriptorInfo* mappings) override;
  std::string GetProduct() override;
  std::string GetUserAgent() override;
  std::vector<base::FilePath> GetNetworkContextsParentDirectory() override;
  void OnWebContentsCreated(content::WebContents* web_contents) override;

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

 private:
  raw_ptr<MiruBrowserMainParts, DanglingUntriaged> main_parts_ = nullptr;
  mutable base::Lock adblock_lock_;
  bool adblock_enabled_ = true;
  std::set<std::string> allowed_hosts_;
  std::shared_ptr<MiruAdblockRef> adblock_ref_;
};

}  // namespace miru

#endif  // MIRU_ENGINE_NATIVE_MIRU_CONTENT_BROWSER_CLIENT_H_
