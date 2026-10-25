// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

enum class SitePermissionKind { CAMERA, MICROPHONE, LOCATION }
enum class SitePermissionChoice { ALLOW, BLOCK, ASK }

/** These projections live only in memory. Never put them in SavedState, logs, or analytics. */
data class SitePermissionPrompt(
    val token: Long,
    val origin: String,
    val topLevelOrigin: String?,
    val isPrivate: Boolean,
    val kinds: Set<SitePermissionKind>,
) {
    override fun toString() = "SitePermissionPrompt(token=$token, private=$isPrivate)"
}

data class RuntimePermissionRequest(
    val token: Long,
    val permissions: List<String>,
    val isPrivate: Boolean,
    val launched: Boolean = false,
)

data class SitePermissionState(
    val token: Long,
    val tabId: String,
    val origin: String,
    val isPrivate: Boolean,
    val choices: Map<SitePermissionKind, SitePermissionChoice>,
    val systemGranted: Map<SitePermissionKind, Boolean>,
    val busy: Boolean = false,
    val error: Boolean = false,
) {
    override fun toString() = "SitePermissionState(token=$token, private=$isPrivate, busy=$busy, error=$error)"
}

internal data class PermissionSite(val origin: String, val isPrivate: Boolean, val contextId: String?) {
    override fun toString() = "PermissionSite(private=$isPrivate)"
}
