// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DismissSiteNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as MiruApplication).container.browser.webNotifications.handleIntent(intent)
    }
}
