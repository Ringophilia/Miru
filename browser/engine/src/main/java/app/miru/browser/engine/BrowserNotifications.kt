// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.ext.flow
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate
import java.util.UUID

/** Process-scoped web notifications. PendingIntents contain only opaque, single-use tokens. */
class BrowserNotifications internal constructor(
    private val context: Context,
    scope: CoroutineScope,
    private val store: BrowserStore,
    private val permissions: BrowserPermissions,
    private val openSite: (origin: String, isPrivate: Boolean) -> Unit,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val active = linkedMapOf<String, Entry>()
    // Preserve native show/close ordering across the asynchronous permission read.
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var activity: Class<*>? = null
    private var receiver: Class<*>? = null
    private var icon = 0
    private class Entry(val token: String, val origin: String, val notification: WebNotification)
    internal val delegate = object : WebNotificationDelegate {
        override fun onShowNotification(notification: WebNotification) {
            events.trySend {
                try { show(notification) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notification.dismiss() }
            }
        }
        override fun onCloseNotification(notification: WebNotification) = close(notification)
    }

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.web_notification_channel), NotificationManager.IMPORTANCE_DEFAULT))
        // Native callbacks are process-local. Remove stale Android entries from a previous process.
        manager.activeNotifications.filter { it.tag?.startsWith(PREFIX) == true }.forEach { manager.cancel(it.tag, ID) }
        scope.launch { for (event in events) {
            try { event() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Never log website content or origins. */ }
        } }
        scope.launch {
            store.flow().map { state -> state.tabs.any { it.content.private } }.distinctUntilChanged().collect { privateAlive ->
                if (!privateAlive) cancelWhere { it.notification.privateBrowsing }
            }
        }
    }

    fun configure(activity: Class<*>, receiver: Class<*>, smallIcon: Int) {
        this.activity = activity
        this.receiver = receiver
        icon = smallIcon
    }

    private suspend fun show(notification: WebNotification) {
        val origin = notification.source?.let(::siteOrigin)
        if (origin == null || siteOrigin(notification.origin.substringBefore('^')) != origin ||
            activity == null || receiver == null || !permissions.notificationAllowed(origin, notification.privateBrowsing)) {
            notification.dismiss()
            return
        }
        // Replacing a native tag must not dismiss its new native alert through the old wrapper.
        active.values.filter { it.notification.tag == notification.tag && it.notification.origin == notification.origin }
            .toList().forEach { remove(it, dismissNative = false) }
        if (active.size >= 64) remove(active.values.first())
        val entry = Entry(UUID.randomUUID().toString(), origin, notification)
        val private = notification.privateBrowsing
        val title = if (private) context.getString(R.string.web_notification_private_title) else notification.title.orEmpty().take(512)
        val text = if (private) context.getString(R.string.web_notification_private_text) else notification.text.orEmpty().take(4096)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(icon).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending(entry, ACTION_OPEN)).setDeleteIntent(pending(entry, ACTION_DISMISS))
            .setAutoCancel(true).setLocalOnly(true).setSilent(notification.silent || private)
            .setVisibility(if (private) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
        if (!private) {
            builder.setSubText(origin)
            notification.actions.take(3).forEachIndexed { index, action ->
                builder.addAction(0, action.title.take(128), pending(entry, ACTION_OPEN, index))
            }
        }
        try {
            manager.notify(PREFIX + entry.token, ID, builder.build())
            active[entry.token] = entry
            notification.show()
        } catch (_: SecurityException) { notification.dismiss() }
    }

    private fun close(notification: WebNotification) {
        events.trySend {
            active.values.filter { it.notification.tag == notification.tag && it.notification.origin == notification.origin }
                .toList().forEach { remove(it, dismissNative = false) }
            notification.dismiss()
        }
    }

    /** Call from the Activity for content/actions and the non-exported receiver for delete. */
    fun handleIntent(intent: Intent): Boolean {
        if (intent.action != ACTION_OPEN && intent.action != ACTION_DISMISS) return false
        val token = intent.data?.takeIf { it.scheme == "miru-notification" }?.host ?: return true
        val index = intent.getIntExtra("actionIndex", -1)
        val open = intent.action == ACTION_OPEN
        events.trySend {
            val entry = active[token] ?: return@trySend
            try {
                if (open && permissions.notificationAllowed(entry.origin, entry.notification.privateBrowsing)) {
                    openSite(entry.origin, entry.notification.privateBrowsing)
                    val action = entry.notification.actions.getOrNull(index)
                    if (index == -1) entry.notification.click() else if (action != null) entry.notification.click(action.name)
                }
            } finally { remove(entry) }
        }
        return true
    }

    fun cancelSite(origin: String, isPrivate: Boolean) = cancelWhere { it.origin == origin && it.notification.privateBrowsing == isPrivate }
    fun cancelAll() = cancelWhere { true }
    fun refresh() { if (!enabled(context)) cancelAll() }

    private fun cancelWhere(predicate: (Entry) -> Boolean) {
        active.values.filter(predicate).toList().forEach { remove(it) }
    }

    private fun remove(entry: Entry, dismissNative: Boolean = true) {
        active.remove(entry.token)
        manager.cancel(PREFIX + entry.token, ID)
        if (dismissNative) entry.notification.dismiss()
    }

    private fun pending(entry: Entry, action: String, index: Int = -1): PendingIntent {
        val intent = Intent(context, if (action == ACTION_OPEN) activity!! else receiver!!)
            .setAction(action).setData(Uri.parse("miru-notification://${entry.token}/$index"))
            .putExtra("actionIndex", index)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (action == ACTION_OPEN) PendingIntent.getActivity(context, 0,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        else PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    companion object {
        const val CHANNEL = "site_notifications"
        private const val PREFIX = "site_notification:"
        private const val ID = 1
        private const val ACTION_OPEN = "app.miru.browser.OPEN_SITE_NOTIFICATION"
        private const val ACTION_DISMISS = "app.miru.browser.DISMISS_SITE_NOTIFICATION"
        internal fun enabled(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}
