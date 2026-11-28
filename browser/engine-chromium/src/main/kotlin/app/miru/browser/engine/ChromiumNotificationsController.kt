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
import app.miru.browser.engine.api.R
import org.json.JSONArray
import java.util.UUID

/**
 * Process-scoped Android Web Notification host for Chromium `PlatformNotificationService`.
 * Matches Gecko [BrowserNotifications] privacy guarantees: opaque immutable [PendingIntent] tokens,
 * redacted generic text and `VISIBILITY_SECRET` for private tabs, and cleanup when private tabs close.
 */
internal class ChromiumNotificationsController(
    context: Context,
    private val isNotificationAllowed: (origin: String, isPrivate: Boolean) -> Boolean,
    private val openSite: (origin: String, isPrivate: Boolean) -> Unit,
    private val dispatchNativeClick: (notificationId: String, origin: String, isPrivate: Boolean, isPersistent: Boolean, actionIndex: Int) -> Unit,
    private val dispatchNativeClose: (notificationId: String, origin: String, isPrivate: Boolean, isPersistent: Boolean) -> Unit,
) : BrowserNotificationsController {

    private data class ActionSpec(val action: String, val title: String)

    private class Entry(
        val token: String,
        val nativeId: String,
        val origin: String,
        val isPrivate: Boolean,
        val isPersistent: Boolean,
        val tag: String,
    )

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)
    private val activeByToken = linkedMapOf<String, Entry>()
    private var activityClass: Class<*>? = null
    private var receiverClass: Class<*>? = null
    private var smallIcon: Int = 0

    init {
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL, appContext.getString(R.string.web_notification_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager?.activeNotifications
            ?.filter { it.tag?.startsWith(PREFIX) == true }
            ?.forEach { manager.cancel(it.tag, ID) }
    }

    override fun configure(activity: Class<*>, receiver: Class<*>, smallIcon: Int) {
        activityClass = activity
        receiverClass = receiver
        this.smallIcon = smallIcon
    }

    fun onShowNotification(
        notificationId: String,
        originUrl: String,
        isPrivate: Boolean,
        isPersistent: Boolean,
        title: String,
        body: String,
        tag: String,
        silent: Boolean,
        actionsJson: String,
    ) {
        val origin = siteOrigin(originUrl)
        if (origin == null || activityClass == null || receiverClass == null || !isNotificationAllowed(origin, isPrivate)) {
            return
        }
        val nm = manager ?: return
        if (tag.isNotEmpty()) {
            activeByToken.values
                .filter { it.tag == tag && it.origin == origin && it.isPrivate == isPrivate }
                .toList()
                .forEach { removeEntry(it, dispatchClose = false) }
        }
        if (activeByToken.size >= MAX_ACTIVE) {
            removeEntry(activeByToken.values.first(), dispatchClose = true)
        }
        val entry = Entry(
            token = UUID.randomUUID().toString(),
            nativeId = notificationId,
            origin = origin,
            isPrivate = isPrivate,
            isPersistent = isPersistent,
            tag = tag,
        )
        val shownTitle = if (isPrivate) appContext.getString(R.string.web_notification_private_title) else title.take(512)
        val shownBody = if (isPrivate) appContext.getString(R.string.web_notification_private_text) else body.take(4096)
        val builder = NotificationCompat.Builder(appContext, CHANNEL)
            .setSmallIcon(smallIcon)
            .setContentTitle(shownTitle)
            .setContentText(shownBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(shownBody))
            .setContentIntent(buildPendingIntent(entry, ACTION_OPEN))
            .setDeleteIntent(buildPendingIntent(entry, ACTION_DISMISS))
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setSilent(silent || isPrivate)
            .setVisibility(if (isPrivate) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
        if (!isPrivate) {
            builder.setSubText(origin)
            parseActions(actionsJson).take(3).forEachIndexed { index, action ->
                builder.addAction(0, action.title.take(128), buildPendingIntent(entry, ACTION_OPEN, index))
            }
        }
        try {
            nm.notify(PREFIX + entry.token, ID, builder.build())
            activeByToken[entry.token] = entry
        } catch (_: SecurityException) {
            // Permission revoked mid-flight; do not retain orphan entry.
        }
    }

    fun onNativeCloseNotification(notificationId: String) {
        activeByToken.values
            .filter { it.nativeId == notificationId }
            .toList()
            .forEach { removeEntry(it, dispatchClose = false) }
    }

    override fun handleIntent(intent: Intent): Boolean {
        if (intent.action != ACTION_OPEN && intent.action != ACTION_DISMISS) return false
        val token = intent.data?.takeIf { it.scheme == "miru-notification" }?.host ?: return true
        val index = intent.getIntExtra("actionIndex", -1)
        val isOpen = intent.action == ACTION_OPEN
        val entry = activeByToken[token] ?: return true
        try {
            if (isOpen && isNotificationAllowed(entry.origin, entry.isPrivate)) {
                openSite(entry.origin, entry.isPrivate)
                dispatchNativeClick(entry.nativeId, entry.origin, entry.isPrivate, entry.isPersistent, index)
                removeEntry(entry, dispatchClose = false)
            } else {
                removeEntry(entry, dispatchClose = true)
            }
        } catch (_: Exception) {
            removeEntry(entry, dispatchClose = false)
        }
        return true
    }

    override fun cancelSite(origin: String, isPrivate: Boolean) {
        val normalized = siteOrigin(origin) ?: return
        cancelWhere { it.origin == normalized && it.isPrivate == isPrivate }
    }

    override fun cancelAll() = cancelWhere { true }

    override fun refresh() {
        if (!enabled(appContext)) cancelAll()
    }

    fun onPrivateTabsStateChanged(hasPrivateTabs: Boolean) {
        if (!hasPrivateTabs) cancelWhere { it.isPrivate }
    }

    private fun cancelWhere(predicate: (Entry) -> Boolean) {
        activeByToken.values.filter(predicate).toList().forEach { removeEntry(it, dispatchClose = true) }
    }

    private fun removeEntry(entry: Entry, dispatchClose: Boolean) {
        if (activeByToken.remove(entry.token) == null) return
        manager?.cancel(PREFIX + entry.token, ID)
        if (dispatchClose) {
            dispatchNativeClose(entry.nativeId, entry.origin, entry.isPrivate, entry.isPersistent)
        }
    }

    private fun buildPendingIntent(entry: Entry, action: String, index: Int = -1): PendingIntent {
        val targetClass = if (action == ACTION_OPEN) activityClass!! else receiverClass!!
        val intent = Intent(appContext, targetClass)
            .setAction(action)
            .setData(Uri.parse("miru-notification://${entry.token}/$index"))
            .putExtra("actionIndex", index)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (action == ACTION_OPEN) {
            PendingIntent.getActivity(
                appContext,
                0,
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags,
            )
        } else {
            PendingIntent.getBroadcast(appContext, 0, intent, flags)
        }
    }

    private fun parseActions(actionsJson: String): List<ActionSpec> {
        val array = runCatching { JSONArray(actionsJson) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                add(ActionSpec(obj.optString("action"), obj.optString("title")))
            }
        }
    }

    companion object {
        const val CHANNEL = "site_notifications"
        private const val PREFIX = "site_notification:"
        private const val ID = 1
        private const val MAX_ACTIVE = 64
        private const val ACTION_OPEN = "app.miru.browser.OPEN_SITE_NOTIFICATION"
        private const val ACTION_DISMISS = "app.miru.browser.DISMISS_SITE_NOTIFICATION"

        fun enabled(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            return manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}
