// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

enum class BrowserPromptKind { ALERT, CONFIRM, TEXT, BEFORE_UNLOAD, REPOST, AUTHENTICATION, FILE, FOLDER, FOLDER_CONFIRM }
enum class BrowserPromptButton { POSITIVE, NEGATIVE, NEUTRAL }
enum class BrowserAuthMethod { HOST, PROXY }
enum class BrowserAuthLevel { NONE, PASSWORD_ENCRYPTED, SECURED }
enum class BrowserFileCapture { NONE, ANY, FRONT_CAMERA, BACK_CAMERA }

/** Ephemeral display values only. Never serialize these into saved state, storage, or diagnostics. */
data class BrowserPromptState(
    val token: Long,
    val tabId: String,
    val kind: BrowserPromptKind,
    val origin: String?,
    val topOrigin: String?,
    val isPrivate: Boolean,
    val title: String = "",
    val message: String = "",
    val inputLabel: String = "",
    val inputValue: String = "",
    val hasShownManyDialogs: Boolean = false,
    val positiveButtonTitle: String = "",
    val negativeButtonTitle: String = "",
    val neutralButtonTitle: String = "",
    val userName: String = "",
    val onlyShowPassword: Boolean = false,
    val previousFailed: Boolean = false,
    val isCrossOrigin: Boolean = false,
    val authMethod: BrowserAuthMethod? = null,
    val authLevel: BrowserAuthLevel? = null,
    val fileMimeTypes: List<String> = emptyList(),
    val fileMultiple: Boolean = false,
    val fileCapture: BrowserFileCapture = BrowserFileCapture.NONE,
    val folderName: String = "",
) {
    override fun toString() = "BrowserPromptState(token=$token, kind=$kind, private=$isPrivate)"
}
