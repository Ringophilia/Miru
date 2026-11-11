// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

/** App-session observations of Android permissions that can no longer be requested. */
internal class RuntimePermissionRecovery {
    private val blocked = mutableSetOf<String>()

    fun requiresSettings(
        results: Map<String, Boolean>,
        rationaleBefore: Set<String>,
        rationaleAfter: Set<String>,
    ): Boolean {
        for ((permission, granted) in results) {
            when {
                granted || permission in rationaleAfter -> blocked.remove(permission)
                permission in rationaleBefore -> blocked.add(permission)
            }
        }
        // Android can report a denial when the first dialog is cancelled. A false
        // rationale alone cannot establish that the user permanently refused it.
        return results.any { (permission, granted) -> !granted && permission in blocked }
    }

    fun clearGranted(isGranted: (String) -> Boolean) {
        blocked.removeAll(isGranted)
    }
}
