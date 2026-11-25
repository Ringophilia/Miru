// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.content.Context
import app.miru.browser.engine.SavedSiteEntry
import app.miru.browser.engine.SiteAutoplayChoice
import app.miru.browser.engine.SitePermissionChoice
import app.miru.browser.engine.SitePermissionKind
import app.miru.settings.SiteSettingsItem

internal fun SavedSiteEntry.toSiteSettingsItem(context: Context): SiteSettingsItem {
    val tags = buildList {
        permissions.forEach { (kind, choice) ->
            if (choice != SitePermissionChoice.ASK) {
                val label = context.getString(kindLabel(kind))
                val state = context.getString(
                    if (choice == SitePermissionChoice.ALLOW) R.string.site_permission_allow
                    else R.string.site_permission_block,
                )
                add("$label: $state")
            }
        }
        autoplay?.let { policy ->
            if (policy != SiteAutoplayChoice.BLOCK_AUDIBLE) {
                val title = context.getString(R.string.site_autoplay_title)
                val state = context.getString(
                    if (policy == SiteAutoplayChoice.ALLOW_ALL) R.string.site_autoplay_allow_all
                    else R.string.site_autoplay_block_all,
                )
                add("$title: $state")
            }
        }
        if (allowPopups) {
            add("${context.getString(R.string.popup_site_allow)}: ${context.getString(R.string.site_permission_allow)}")
        }
    }
    return SiteSettingsItem(origin = origin, summaryTags = tags)
}

private fun kindLabel(kind: SitePermissionKind): Int = when (kind) {
    SitePermissionKind.CAMERA -> R.string.site_permission_camera
    SitePermissionKind.MICROPHONE -> R.string.site_permission_microphone
    SitePermissionKind.LOCATION -> R.string.site_permission_location
    SitePermissionKind.NOTIFICATIONS -> R.string.site_permission_notifications
    SitePermissionKind.PROTECTED_MEDIA -> R.string.site_permission_protected_media
}
