// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

data class SavedSiteEntry(
    val origin: String,
    val permissions: Map<SitePermissionKind, SitePermissionChoice>,
    val autoplay: SiteAutoplayChoice?,
    val allowPopups: Boolean,
)
