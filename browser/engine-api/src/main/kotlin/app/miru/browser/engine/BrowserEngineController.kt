// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import app.miru.data.BrowserSecuritySettings
import app.miru.data.BrowsingDataClearRequest
import app.miru.data.BrowsingDataClearResult
import app.miru.data.BrowsingLibrary
import app.miru.media.DetectedMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface BrowserPermissionsController {
    val prompt: StateFlow<SitePermissionPrompt?>
    val runtimeRequest: StateFlow<RuntimePermissionRequest?>
    val siteState: StateFlow<SitePermissionState?>
    fun attachPermissionHost(host: Any)
    fun onHostWindowFocusChanged(host: Any, focused: Boolean)
    fun detachPermissionHost(host: Any)
    fun answer(token: Long, allow: Boolean, remember: Boolean)
    fun dismiss(token: Long)
    fun markRuntimeRequestLaunched(token: Long, rationaleBefore: Set<String>): Boolean
    fun onRuntimePermissionsResult(results: Map<String, Boolean>, rationaleAfter: Set<String>): Boolean?
    fun refreshSite()
    fun setForSite(token: Long, kind: SitePermissionKind, choice: SitePermissionChoice)
    fun resetSite(token: Long)
    fun setAutoplayForSite(token: Long, choice: SiteAutoplayChoice)
}

interface BrowserPromptsController {
    val prompt: StateFlow<BrowserPromptState?>
    val blockedPopup: StateFlow<BlockedPopup?>
    val popupSite: StateFlow<PopupSiteState?>
    val clientCertificateResults: SharedFlow<ClientCertificateResult>
    fun allowPopup(token: Long)
    fun dismissPopup(token: Long)
    fun setPopupAllowed(site: PopupSiteState, allowed: Boolean)
    fun acknowledgeAlert(token: Long, allowMoreDialogs: Boolean = true)
    fun prepareClientCertificate(token: Long): ClientCertificateSelection?
    fun completeClientCertificate(token: Long, alias: String?, unavailable: Boolean = false)
    fun answerConfirm(token: Long, button: BrowserPromptButton, allowMoreDialogs: Boolean = true)
    fun answerText(token: Long, text: String, allowMoreDialogs: Boolean = true)
    fun answerBeforeUnload(token: Long, leave: Boolean)
    fun answerRepost(token: Long, resubmit: Boolean)
    fun authenticate(token: Long, userName: String, password: String)
    fun answerChoice(token: Long, selectedIds: Set<String>)
    fun answerDate(token: Long, value: String)
    fun clearDate(token: Long)
    fun answerColor(token: Long, value: String)
    fun answerFolderUpload(token: Long, allow: Boolean)
    fun dismiss(token: Long, allowMoreDialogs: Boolean = true)
}

interface BrowserFileUploadsController {
    val state: StateFlow<BrowserUploadState>
    val errors: Flow<BrowserUploadError>
    fun attachHost(activity: Activity, pickerClass: Class<out Activity>)
    fun detachHost(activity: Activity)
    fun pickerIsPrivate(token: String): Boolean
    fun attachPickerHost(activity: Activity, token: String): Boolean
    fun detachPickerHost(activity: Activity, token: String)
    fun cancelPicker(token: String)
    fun choose(token: Long, source: BrowserUploadSource = BrowserUploadSource.DOCUMENT)
    fun onPermissionsResult(token: String, requestCode: Int, permissions: Array<out String>, results: IntArray): Boolean
    fun onActivityResult(token: String, requestCode: Int, resultCode: Int, data: Intent?): Boolean
    fun captureSources(mimeTypes: List<String>): Set<BrowserUploadSource>
}

interface BrowserContextMenusController {
    val state: StateFlow<BrowserContextMenu?>
    fun dismiss(token: Long? = null)
    fun beginTouchGesture()
    fun isCurrent(request: BrowserContextMenu): Boolean
    fun isOwnerCurrent(request: BrowserContextMenu): Boolean
    fun close()
}

interface BrowserExternalLinksController {
    val prompt: StateFlow<ExternalLinkPrompt?>
    fun dismiss(token: Long)
    fun open(token: Long, activity: Activity): Boolean
}

interface BrowserNotificationsController {
    fun configure(activity: Class<*>, receiver: Class<*>, smallIcon: Int)
    fun handleIntent(intent: Intent): Boolean
    fun cancelSite(origin: String, isPrivate: Boolean)
    fun cancelAll()
    fun refresh()
}

/**
 * Top-level contract implemented by every Miru browser engine backend
 * (frozen GeckoView reference backend today; de-Googled Chromium backend in Phase C1+).
 */
interface BrowserEngineController {
    val websiteIcons: WebsiteIconLoader
    val externalLinks: BrowserExternalLinksController
    val contextMenus: BrowserContextMenusController
    val prompts: BrowserPromptsController
    val uploads: BrowserFileUploadsController
    val permissions: BrowserPermissionsController
    val webNotifications: BrowserNotificationsController
    val media: StateFlow<Map<String, List<DetectedMedia>>>
    val mediaHints: Flow<Pair<String, Boolean>>
    val downloadRequests: Flow<BrowserDownloadRequest>
    val adblock: StateFlow<AdblockSnapshot>
    val adblockPopup: StateFlow<BrowserPopupSession?>
    val state: StateFlow<BrowserSnapshot>

    fun initialize()
    fun newTab(url: String = NEW_TAB_URL, isPrivate: Boolean = false): String
    fun navigate(url: String)
    fun selectTab(id: String)
    fun closeTab(id: String)
    fun closeAllTabs(isPrivate: Boolean = false)
    fun closePrivateTabsPermanently()
    fun undoClose()
    fun goBack(): Boolean
    fun goForward()
    fun exitWebFullScreen(): Boolean
    fun configureWebMedia(holdEnabled: Boolean, holdSpeed: Float)
    fun reload()
    fun stopLoading()
    fun setDesktopMode(enabled: Boolean)
    fun consumeToolbarExpansion()

    // Adblock controls (supports both legacy WebExtension popup and native adblock-rust)
    fun setAdblockEnabled(enabled: Boolean)
    fun retryAdblock()
    fun openAdblockPopup(): Boolean
    fun closeAdblockPopup()
    fun openAdblockDashboard(): Boolean
    fun openAdblockExceptions(): Boolean
    fun openAdblockLogger(): Boolean
    fun setSiteAdblockEnabled(host: String, enabled: Boolean): Boolean = false
    fun startAdblockElementPicker(): Boolean = false

    // Media & context headers (process memory only; never persisted to disk)
    suspend fun prepareMedia(tabId: String, mediaId: String): PreparedMedia?
    suspend fun refreshMediaHeaders(tabId: String, mediaId: String): Map<String, String>?
    fun restoreWebMedia(tabId: String)
    suspend fun contextImagePolicy(request: BrowserContextMenu): ContextImagePolicy?
    suspend fun contextHeaders(request: BrowserContextMenu, url: String, imagePolicy: ContextImagePolicy? = null): Map<String, String>?
    suspend fun headersForMediaUrl(url: String, isPrivate: Boolean): Map<String, String>
    suspend fun downloadHeaders(url: String, sourcePage: String? = null): Map<String, String>?
    fun downloadCurrentPage()

    // Browsing data & saved site settings
    suspend fun clearBrowsingData(request: BrowsingDataClearRequest): BrowsingDataClearResult
    suspend fun listSavedSites(): List<SavedSiteEntry>
    suspend fun resetSavedSite(origin: String)
    suspend fun resetAllSavedSites(): BrowsingDataClearResult

    // Find in page & thumbnails & lifecycle
    fun findAll(query: String)
    fun findNext(forward: Boolean = true)
    fun clearFind()
    fun captureThumbnail()
    fun getThumbnail(id: String): Bitmap?
    fun saveSession()
    fun onTrimMemory(level: Int)

    // Viewport surface host
    fun createSurfaceHost(context: Context): BrowserSurfaceHost
}

interface BrowserEngineFactory {
    fun create(
        context: Context,
        applicationScope: CoroutineScope,
        library: BrowsingLibrary,
        securitySettings: Flow<BrowserSecuritySettings>,
        webZoomPercent: Flow<Int>,
    ): BrowserEngineController
}
