// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Direct origin favicon fetcher with in-memory LRU cache and no cookies or disk cache. */
internal class ChromiumWebsiteIcons : WebsiteIconLoader {
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    override suspend fun load(url: String): Bitmap? {
        val iconUrl = faviconUrl(url) ?: return null
        cache.get(iconUrl)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(iconUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val bytes = response.body?.bytes() ?: return@withContext null
                    if (bytes.size > 1024 * 1024) return@withContext null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { bmp ->
                        cache.put(iconUrl, bmp)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun clearMemory() = cache.evictAll()
}

internal class ChromiumPermissionsController : BrowserPermissionsController {
    override val prompt: StateFlow<SitePermissionPrompt?> = MutableStateFlow<SitePermissionPrompt?>(null).asStateFlow()
    override val runtimeRequest: StateFlow<RuntimePermissionRequest?> = MutableStateFlow<RuntimePermissionRequest?>(null).asStateFlow()
    override val siteState: StateFlow<SitePermissionState?> = MutableStateFlow<SitePermissionState?>(null).asStateFlow()
    override fun attachPermissionHost(host: Any) = Unit
    override fun onHostWindowFocusChanged(host: Any, focused: Boolean) = Unit
    override fun detachPermissionHost(host: Any) = Unit
    override fun answer(token: Long, allow: Boolean, remember: Boolean) = Unit
    override fun dismiss(token: Long) = Unit
    override fun markRuntimeRequestLaunched(token: Long, rationaleBefore: Set<String>): Boolean = false
    override fun onRuntimePermissionsResult(results: Map<String, Boolean>, rationaleAfter: Set<String>): Boolean? = null
    override fun refreshSite() = Unit
    override fun setForSite(token: Long, kind: SitePermissionKind, choice: SitePermissionChoice) = Unit
    override fun resetSite(token: Long) = Unit
    override fun setAutoplayForSite(token: Long, choice: SiteAutoplayChoice) = Unit
}

internal class ChromiumPromptsController : BrowserPromptsController {
    override val prompt: StateFlow<BrowserPromptState?> = MutableStateFlow<BrowserPromptState?>(null).asStateFlow()
    override val blockedPopup: StateFlow<BlockedPopup?> = MutableStateFlow<BlockedPopup?>(null).asStateFlow()
    override val popupSite: StateFlow<PopupSiteState?> = MutableStateFlow<PopupSiteState?>(null).asStateFlow()
    override val clientCertificateResults: SharedFlow<ClientCertificateResult> = MutableSharedFlow<ClientCertificateResult>().asSharedFlow()
    override fun allowPopup(token: Long) = Unit
    override fun dismissPopup(token: Long) = Unit
    override fun setPopupAllowed(site: PopupSiteState, allowed: Boolean) = Unit
    override fun acknowledgeAlert(token: Long, allowMoreDialogs: Boolean) = Unit
    override fun prepareClientCertificate(token: Long): ClientCertificateSelection? = null
    override fun completeClientCertificate(token: Long, alias: String?, unavailable: Boolean) = Unit
    override fun answerConfirm(token: Long, button: BrowserPromptButton, allowMoreDialogs: Boolean) = Unit
    override fun answerText(token: Long, text: String, allowMoreDialogs: Boolean) = Unit
    override fun answerBeforeUnload(token: Long, leave: Boolean) = Unit
    override fun answerRepost(token: Long, resubmit: Boolean) = Unit
    override fun authenticate(token: Long, userName: String, password: String) = Unit
    override fun answerChoice(token: Long, selectedIds: Set<String>) = Unit
    override fun answerDate(token: Long, value: String) = Unit
    override fun clearDate(token: Long) = Unit
    override fun answerColor(token: Long, value: String) = Unit
    override fun answerFolderUpload(token: Long, allow: Boolean) = Unit
    override fun dismiss(token: Long, allowMoreDialogs: Boolean) = Unit
}

internal class ChromiumFileUploadsController : BrowserFileUploadsController {
    override val state: StateFlow<BrowserUploadState> = MutableStateFlow(BrowserUploadState()).asStateFlow()
    override val errors: Flow<BrowserUploadError> = emptyFlow()
    override fun attachHost(activity: Activity, pickerClass: Class<out Activity>) = Unit
    override fun detachHost(activity: Activity) = Unit
    override fun pickerIsPrivate(token: String): Boolean = false
    override fun attachPickerHost(activity: Activity, token: String): Boolean = false
    override fun detachPickerHost(activity: Activity, token: String) = Unit
    override fun cancelPicker(token: String) = Unit
    override fun choose(token: Long, source: BrowserUploadSource) = Unit
    override fun onPermissionsResult(token: String, requestCode: Int, permissions: Array<out String>, results: IntArray): Boolean = false
    override fun onActivityResult(token: String, requestCode: Int, resultCode: Int, data: Intent?): Boolean = false
    override fun captureSources(mimeTypes: List<String>): Set<BrowserUploadSource> = emptySet()
}

internal class ChromiumContextMenusController : BrowserContextMenusController {
    override val state: StateFlow<BrowserContextMenu?> = MutableStateFlow<BrowserContextMenu?>(null).asStateFlow()
    override fun dismiss(token: Long?) = Unit
    override fun beginTouchGesture() = Unit
    override fun isCurrent(request: BrowserContextMenu): Boolean = false
    override fun isOwnerCurrent(request: BrowserContextMenu): Boolean = false
    override fun close() = Unit
}

internal class ChromiumExternalLinksController : BrowserExternalLinksController {
    override val prompt: StateFlow<ExternalLinkPrompt?> = MutableStateFlow<ExternalLinkPrompt?>(null).asStateFlow()
    override fun dismiss(token: Long) = Unit
    override fun open(token: Long, activity: Activity): Boolean = false
}

internal class ChromiumNotificationsController : BrowserNotificationsController {
    override fun configure(activity: Class<*>, receiver: Class<*>, smallIcon: Int) = Unit
    override fun handleIntent(intent: Intent): Boolean = false
    override fun cancelSite(origin: String, isPrivate: Boolean) = Unit
    override fun cancelAll() = Unit
    override fun refresh() = Unit
}
