// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.offline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.UUID

internal enum class OfflineNotificationCommand(val all: Boolean = false) {
    PAUSE, RESUME, CANCEL, PAUSE_ALL(true), RESUME_ALL(true), CANCEL_ALL(true);

    val action: String get() = "app.miru.offline.action.$name"
    companion object {
        const val EXTRA_ID = "miru.offline.id"
        fun fromAction(action: String?) = entries.firstOrNull { it.action == action }
    }
}

/** Explicit immutable notification intents enter the same state machine as the download page. */
class OfflineNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = OfflineNotificationCommand.fromAction(intent.action) ?: return
        val id = intent.getStringExtra(OfflineNotificationCommand.EXTRA_ID)?.takeIf { value ->
            value.length == 36 && runCatching { UUID.fromString(value).toString().equals(value, true) }.getOrDefault(false)
        }
        if(!command.all && id == null) return
        val pending = goAsync()
        try {
            OfflineCoordinator.get(context).notificationAction(command, id).invokeOnCompletion { pending.finish() }
        } catch(_: Exception) { pending.finish() }
    }
}
