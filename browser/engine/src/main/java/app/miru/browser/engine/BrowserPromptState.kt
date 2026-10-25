// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

enum class BrowserPromptKind { ALERT, CONFIRM, TEXT, BEFORE_UNLOAD, REPOST, AUTHENTICATION }
enum class BrowserPromptButton { POSITIVE, NEGATIVE, NEUTRAL }
enum class BrowserAuthMethod { HOST, PROXY }
enum class BrowserAuthLevel { NONE, PASSWORD_ENCRYPTED, SECURED }

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
) {
    override fun toString() = "BrowserPromptState(token=$token, kind=$kind, private=$isPrivate)"
}
