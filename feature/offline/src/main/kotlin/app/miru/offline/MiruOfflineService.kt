// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler

class MiruOfflineService : DownloadService(FOREGROUND_ID, 1_000L, CHANNEL, R.string.offline_title, 0) {
    override fun getDownloadManager(): DownloadManager = OfflineCoordinator.get(this).serviceManager()
    override fun getScheduler(): Scheduler = PlatformScheduler(this, 8_041)
    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val active = downloads.filter { it.state in setOf(Download.STATE_DOWNLOADING, Download.STATE_QUEUED) }
        val progress = active.map { it.percentDownloaded }.filter { it >= 0 }
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.offline_title)).setContentText(getString(if(notMetRequirements != 0) R.string.offline_waiting_network else R.string.offline_downloading, active.size))
            .setOnlyAlertOnce(true).setOngoing(true).setGroup(GROUP).setGroupSummary(true)
            .setContentIntent(OfflineNotifications.launch(this, null))
            .setProgress(100, progress.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 0, progress.isEmpty())
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.offline_pause_all), OfflineNotifications.stopAction(this, null, 1))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.offline_cancel_all), OfflineNotifications.stopAction(this, null, 2)).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { OfflineCoordinator.get(this).pauseAll(); super.onTimeout(startId, fgsType) }
    companion object {
        const val CHANNEL = "miru_offline_downloads"
        const val GROUP = "miru_offline_group"
        const val FOREGROUND_ID = 8_040
    }
}

internal object OfflineNotifications {
    private fun manager(context: Context): NotificationManager = context.getSystemService(NotificationManager::class.java).apply {
        createNotificationChannel(NotificationChannel(MiruOfflineService.CHANNEL, context.getString(R.string.offline_title), NotificationManager.IMPORTANCE_LOW))
    }
    private fun id(id: String) = 10_000 + (id.hashCode() and 0x0fffffff)
    fun launch(context: Context, id: String?): PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if(id != null) intent.putExtra("miru.offline.id", id)
        PendingIntent.getActivity(context, id?.hashCode() ?: 8_040, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun stopAction(context: Context, id: String?, reason: Int): PendingIntent = PendingIntent.getService(context,
        (id.orEmpty() + reason).hashCode(), DownloadService.buildSetStopReasonIntent(context, MiruOfflineService::class.java, id, reason, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun completed(context: Context, item: OfflineItem) {
        val notification = NotificationCompat.Builder(context, MiruOfflineService.CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if(item.isPrivate) context.getString(R.string.offline_video) else item.title)
            .setContentText(context.getString(R.string.offline_complete)).setContentIntent(launch(context, item.id))
            .setAutoCancel(true).setOnlyAlertOnce(true).setGroup(MiruOfflineService.GROUP).build()
        runCatching { manager(context).notify(id(item.id), notification) }
    }
    fun stopped(context: Context, item: OfflineItem) {
        if(item.status !in setOf("paused", "failed")) { cancel(context, item.id); return }
        val notification = NotificationCompat.Builder(context, MiruOfflineService.CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if(item.isPrivate) context.getString(R.string.offline_video) else item.title)
            .setContentText(context.getString(if(item.status == "paused") R.string.offline_paused else if(item.error == "AUTH_REQUIRED") R.string.offline_auth_required else R.string.offline_failed))
            .setOnlyAlertOnce(true).setGroup(MiruOfflineService.GROUP).setContentIntent(launch(context, null))
            .addAction(android.R.drawable.ic_media_play, context.getString(R.string.offline_resume), stopAction(context, item.id, 0))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.offline_cancel), stopAction(context, item.id, 2)).build()
        runCatching { manager(context).notify(id(item.id), notification) }
    }
    fun cancel(context: Context, id: String) { runCatching { manager(context).cancel(id(id)) } }
}
