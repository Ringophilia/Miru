// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.net.URI
import java.security.Principal
import javax.security.auth.x500.X500Principal

internal sealed class ChromiumNativePrompt {
    abstract val shouldDismissOnAttempt: Boolean
    open val isUploadPrompt: Boolean get() = false
    abstract fun cancel(tabId: String)

    data class JsDialog(
        val dialogId: Long,
        val kind: BrowserPromptKind,
        val message: String,
        val defaultPrompt: String,
        val hasShownMany: Boolean,
        val resolve: (Long, Boolean, String) -> Unit,
    ) : ChromiumNativePrompt() {
        override val shouldDismissOnAttempt: Boolean = true
        override fun cancel(tabId: String) = resolve(dialogId, false, "")
    }

    data class BeforeUnload(
        val dialogId: Long,
        val isReload: Boolean,
        val resolve: (Long, Boolean, String) -> Unit,
    ) : ChromiumNativePrompt() {
        override val shouldDismissOnAttempt: Boolean = false
        override fun cancel(tabId: String) = resolve(dialogId, false, "")
    }

    data class HttpAuth(
        val authId: Long,
        val url: String,
        val challenger: String,
        val realm: String,
        val scheme: String,
        val isProxy: Boolean,
        val previousFailed: Boolean,
        val resolve: (Long, Boolean, String, String) -> Unit,
    ) : ChromiumNativePrompt() {
        override val shouldDismissOnAttempt: Boolean = false
        override fun cancel(tabId: String) = resolve(authId, false, "", "")
    }

    data class Repost(
        val resolve: (Boolean) -> Unit,
    ) : ChromiumNativePrompt() {
        override val shouldDismissOnAttempt: Boolean = false
        override fun cancel(tabId: String) = resolve(false)
    }

    data class ClientCertificate(
        val requestId: Long,
        val host: String,
        val issuers: Array<Principal>?,
        val cancelNative: (Long) -> Unit,
    ) : ChromiumNativePrompt() {
        override val shouldDismissOnAttempt: Boolean = false
        override fun cancel(tabId: String) = cancelNative(requestId)
    }

    data class FilePicker(
        val requestId: Long,
        val mimeTypes: List<String>,
        val multiple: Boolean,
        val capture: BrowserFileCapture,
        val cancelNative: (Long) -> Unit,
    ) : ChromiumNativePrompt() {
        override val shouldDismissOnAttempt: Boolean = false
        override val isUploadPrompt: Boolean = true
        override fun cancel(tabId: String) = cancelNative(requestId)
    }

    data class FolderPicker(
        val requestId: Long,
        val cancelNative: (Long) -> Unit,
    ) : ChromiumNativePrompt() {
        override val shouldDismissOnAttempt: Boolean = false
        override val isUploadPrompt: Boolean = true
        override fun cancel(tabId: String) = cancelNative(requestId)
    }

    data class FolderConfirm(
        val requestId: Long,
        val folder: PreparedUploadFolder,
        val onResolved: (Boolean) -> Unit,
        val cancelNative: (Long) -> Unit,
    ) : ChromiumNativePrompt() {
        override val shouldDismissOnAttempt: Boolean = false
        override val isUploadPrompt: Boolean = true
        override fun cancel(tabId: String) {
            cancelNative(requestId)
            onResolved(false)
        }
    }

    data class Choice(
        val requestId: Long,
        val multiple: Boolean,
        val choices: List<BrowserChoice>,
        val indexById: Map<String, Int>,
        val callback: (IntArray?) -> Unit,
    ) : ChromiumNativePrompt() {
        override val shouldDismissOnAttempt: Boolean = true
        override fun cancel(tabId: String) = callback(null)
    }

    data class DateTime(
        val requestId: Long,
        val spec: FormDateInput,
        val resolve: (Long, Boolean, String) -> Unit,
    ) : ChromiumNativePrompt() {
        override val shouldDismissOnAttempt: Boolean = true
        override fun cancel(tabId: String) = resolve(requestId, false, "")
    }

    data class Color(
        val requestId: Long,
        val initialColor: String,
        val resolve: (Long, Boolean, Int) -> Unit,
    ) : ChromiumNativePrompt() {
        override val shouldDismissOnAttempt: Boolean = true
        override fun cancel(tabId: String) = resolve(requestId, false, 0)
    }
}

internal fun promptKindFor(native: ChromiumNativePrompt): BrowserPromptKind = when (native) {
    is ChromiumNativePrompt.JsDialog -> native.kind
    is ChromiumNativePrompt.BeforeUnload -> BrowserPromptKind.BEFORE_UNLOAD
    is ChromiumNativePrompt.HttpAuth -> BrowserPromptKind.AUTHENTICATION
    is ChromiumNativePrompt.Repost -> BrowserPromptKind.REPOST
    is ChromiumNativePrompt.ClientCertificate -> BrowserPromptKind.CERTIFICATE
    is ChromiumNativePrompt.FilePicker -> BrowserPromptKind.FILE
    is ChromiumNativePrompt.FolderPicker -> BrowserPromptKind.FOLDER
    is ChromiumNativePrompt.FolderConfirm -> BrowserPromptKind.FOLDER_CONFIRM
    is ChromiumNativePrompt.Choice -> if (native.multiple) BrowserPromptKind.MULTIPLE_CHOICE else BrowserPromptKind.SINGLE_CHOICE
    is ChromiumNativePrompt.DateTime -> BrowserPromptKind.DATE_TIME
    is ChromiumNativePrompt.Color -> BrowserPromptKind.COLOR
}

internal fun projectChromiumPrompt(
    token: Long,
    tabId: String,
    topOrigin: String?,
    isPrivate: Boolean,
    certificateChoosing: Boolean,
    native: ChromiumNativePrompt,
): BrowserPromptState {
    val base = BrowserPromptState(
        token = token,
        tabId = tabId,
        kind = promptKindFor(native),
        origin = null,
        topOrigin = topOrigin,
        isPrivate = isPrivate,
    )
    return when (native) {
        is ChromiumNativePrompt.JsDialog -> when (native.kind) {
            BrowserPromptKind.ALERT -> base.copy(
                message = native.message,
                hasShownManyDialogs = native.hasShownMany,
            )
            BrowserPromptKind.CONFIRM -> base.copy(
                message = native.message,
                hasShownManyDialogs = native.hasShownMany,
            )
            BrowserPromptKind.TEXT -> base.copy(
                inputLabel = native.message,
                inputValue = native.defaultPrompt,
                hasShownManyDialogs = native.hasShownMany,
            )
            else -> base
        }
        is ChromiumNativePrompt.BeforeUnload -> base
        is ChromiumNativePrompt.Repost -> base
        is ChromiumNativePrompt.ClientCertificate -> base.copy(
            origin = native.host,
            certificateHost = native.host,
            certificateChoosing = certificateChoosing,
        )
        is ChromiumNativePrompt.FilePicker -> base.copy(
            fileMimeTypes = native.mimeTypes,
            fileMultiple = native.multiple,
            fileCapture = native.capture,
        )
        is ChromiumNativePrompt.FolderPicker -> base
        is ChromiumNativePrompt.FolderConfirm -> base.copy(
            folderName = native.folder.rootName,
        )
        is ChromiumNativePrompt.Choice -> base.copy(choices = native.choices)
        is ChromiumNativePrompt.DateTime -> base.copy(dateInput = native.spec)
        is ChromiumNativePrompt.Color -> base.copy(
            inputValue = normalizedFormColor(native.initialColor) ?: "#000000",
        )
        is ChromiumNativePrompt.HttpAuth -> {
            val authOrigin = siteOrigin(native.url)
            val crossOrigin = authOrigin != null && topOrigin != null && authOrigin != topOrigin
            val isHttps = native.url.startsWith("https://", ignoreCase = true)
            base.copy(
                origin = authOrigin,
                title = native.challenger.ifBlank { runCatching { URI(native.url).host }.getOrNull().orEmpty() },
                message = native.realm.ifBlank { native.scheme },
                onlyShowPassword = false,
                previousFailed = native.previousFailed,
                isCrossOrigin = crossOrigin,
                authMethod = if (native.isProxy) BrowserAuthMethod.PROXY else BrowserAuthMethod.HOST,
                authLevel = if (isHttps) BrowserAuthLevel.SECURED else BrowserAuthLevel.NONE,
            )
        }
    }
}

internal fun decodeIssuers(encodedPrincipals: Array<ByteArray>): Array<Principal>? {
    if (encodedPrincipals.isEmpty()) return null
    val out = ArrayList<Principal>(encodedPrincipals.size)
    for (bytes in encodedPrincipals) {
        if (bytes.isEmpty()) continue
        runCatching { X500Principal(bytes) }.getOrNull()?.let { out += it }
    }
    return out.takeIf { it.isNotEmpty() }?.toTypedArray()
}

internal fun buildSelectChoices(
    labels: Array<String>,
    itemTypes: IntArray,
    multiple: Boolean,
    selectedIndices: IntArray,
): Pair<List<BrowserChoice>, Map<String, Int>> {
    val selectedSet = selectedIndices.toHashSet()
    val choices = ArrayList<BrowserChoice>(labels.size)
    val indexById = LinkedHashMap<String, Int>(labels.size)
    var inGroup = false
    for (i in labels.indices) {
        val type = itemTypes.getOrElse(i) { 2 }
        when (type) {
            0 -> { // POPUP_ITEM_TYPE_GROUP
                inGroup = true
                choices += BrowserChoice(
                    id = "g$i",
                    label = labels[i].trim(),
                    enabled = false,
                    selected = false,
                    group = true,
                    depth = 0,
                )
            }
            else -> {
                val id = "o$i"
                val enabled = type == 2 // POPUP_ITEM_TYPE_ENABLED
                choices += BrowserChoice(
                    id = id,
                    label = labels[i].trim(),
                    enabled = enabled,
                    selected = i in selectedSet,
                    group = false,
                    separator = false,
                    depth = if (inGroup) 1 else 0,
                )
                indexById[id] = i
            }
        }
    }
    if (!multiple && choices.none { !it.group && !it.separator && it.selected }) {
        // Ensure a single-select prompt still tracks selectedIndices cleanly when Blink passes -1.
    }
    return choices to indexById
}
