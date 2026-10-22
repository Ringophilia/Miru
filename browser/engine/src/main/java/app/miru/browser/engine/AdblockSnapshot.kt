// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

enum class AdblockStatus { STARTING, INSTALLING, READY, DISABLED, FAILED }

data class AdblockSnapshot(
    val status: AdblockStatus = AdblockStatus.STARTING,
    val installed: Boolean = false,
    val enabled: Boolean = true,
    val version: String? = null,
    val privateAllowed: Boolean = false,
    val blockedCount: Int = 0,
    val badgeText: String = "",
    val popupAvailable: Boolean = false,
    val error: String? = null,
    val startupTimedOut: Boolean = false,
    val pageNeedsReload: Boolean = false,
)
