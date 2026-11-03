// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.miru.browser.engine.BrowserController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One process-scoped notification, independent of Activity composition and tab selection. */
class PrivateTabsNotifications(private val context: Context, private val browser: BrowserController, scope: CoroutineScope) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.private_notification_channel), NotificationManager.IMPORTANCE_LOW))
        scope.launch {
            browser.state.map { state -> state.tabs.count { it.isPrivate } }.distinctUntilChanged().collect { refresh() }
        }
    }

    fun refresh() {
        if (browser.state.value.tabs.none { it.isPrivate } || !manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            manager.cancel(TAG, ID)
            return
        }
        val close = PendingIntent.getBroadcast(context, 0,
            Intent(context, ClosePrivateTabsReceiver::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = context.getString(R.string.private_close_all)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_private_notification)
            .setContentTitle(title).setContentText(context.getString(R.string.private_notification_detail))
            .setContentIntent(close).addAction(0, title, close)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setLocalOnly(true).setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_STATUS).build()
        try { manager.notify(TAG, ID, notification) }
        catch (_: SecurityException) { /* Permission may be revoked between the check and notify. */ }
    }

    companion object {
        internal const val CHANNEL = "private_tabs"
        internal const val TAG = "private_tabs"
        internal const val ID = 1
        internal const val ACTION_CLOSE = "app.miru.browser.CLOSE_PRIVATE_TABS"
    }
}

/** Explicit immutable PendingIntent only; external apps cannot invoke this receiver. */
class ClosePrivateTabsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PrivateTabsNotifications.ACTION_CLOSE) return
        val container = (context.applicationContext as MiruApplication).container
        container.browser.closePrivateTabsPermanently()
        container.privateTabsNotifications.refresh()
    }
}
