// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.downloads

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

class MiruDownloadService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var coordinator:DownloadCoordinator
    private lateinit var notifications:NotificationManager
    private val previousStatus=mutableMapOf<String,String>()
    private val createdAt=System.currentTimeMillis()
    companion object { const val CHANNEL="miru_downloads"; const val GROUP="miru_download_group"; const val FOREGROUND_ID=3000
        internal fun notificationId(id:String)=4000+(id.hashCode() and 0x0fffffff)
    }
    override fun onCreate() {
        super.onCreate(); coordinator=DownloadCoordinator.get(this); notifications=getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,getString(R.string.downloads_title),NotificationManager.IMPORTANCE_LOW))
        val notification=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(getString(R.string.downloads_title)).setContentText(getString(R.string.download_preparing)).setOngoing(true).setGroup(GROUP).setGroupSummary(true).build()
        ServiceCompat.startForeground(this,FOREGROUND_ID,notification,if(Build.VERSION.SDK_INT>=29)ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        coordinator.serviceStarted()
        scope.launch { coordinator.downloads.collect { items ->
            items.forEach { item ->
                if(item.status in setOf("running","queued","waiting_network","paused","completed","failed")) {
                    if(item.status!="completed" || (previousStatus[item.id]!="completed" && (item.completedAt?:0)>=createdAt)) updateNotification(item)
                } else notifications.cancel(notificationId(item.id))
                previousStatus[item.id]=item.status
            }
            if(items.isNotEmpty() && items.none { it.status in setOf("running","queued","waiting_network") }) {
                delay(750)
                if(coordinator.downloads.value.none { it.status in setOf("running","queued","waiting_network") }) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        } }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val id=intent?.getStringExtra("id")
        if(id!=null) when(intent.action) { "pause"->coordinator.pause(id); "resume"->coordinator.resume(id); "cancel"->coordinator.cancel(id) }
        coordinator.serviceStarted(); return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fgsType:Int) { coordinator.pauseAll(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { scope.cancel(); coordinator.serviceStopped(); super.onDestroy() }
    override fun onBind(intent:Intent?):IBinder?=null
    private fun action(id:String,action:String)=PendingIntent.getService(this,(id+action).hashCode(),Intent(this,MiruDownloadService::class.java).setAction(action).putExtra("id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun updateNotification(item:DownloadItem) {
        val active=item.status in setOf("queued","running","waiting_network")
        val builder=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(if(item.status=="completed")android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_download).setContentTitle(item.fileName).setOnlyAlertOnce(true).setGroup(GROUP).setOngoing(active)
        builder.setContentText(downloadStatusLabel(this,item))
        if(active) {
            builder.setProgress(100,if(item.totalBytes>0)(100*item.downloadedBytes/item.totalBytes).toInt() else 0,item.totalBytes<=0)
            builder.addAction(android.R.drawable.ic_media_pause,getString(R.string.download_pause),action(item.id,"pause"))
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel,getString(R.string.download_cancel),action(item.id,"cancel"))
        } else if(item.status in setOf("paused","failed")) builder.addAction(android.R.drawable.ic_media_play,getString(R.string.download_resume),action(item.id,"resume"))
        if(item.status=="completed" && item.destUri!=null) {
            val open=Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.destUri),item.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            builder.setContentIntent(PendingIntent.getActivity(this,notificationId(item.id),open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).setAutoCancel(true)
        }
        runCatching { notifications.notify(notificationId(item.id),builder.build()) }
    }
}

internal fun downloadStatusLabel(context:android.content.Context,item:DownloadItem):String {
    if(item.status == "failed" && item.error?.startsWith("HTTP_") == true) return context.getString(R.string.download_http_error, item.error.removePrefix("HTTP_"))
    return when(item.status) {
    "running" -> {
        val progress="${formatDownloadBytes(item.downloadedBytes)} / ${if(item.totalBytes>=0)formatDownloadBytes(item.totalBytes) else context.getString(R.string.download_unknown_size)}"
        if(item.speedBytesPerSec>0&&item.totalBytes>item.downloadedBytes)context.getString(R.string.download_progress_speed,progress,formatDownloadBytes(item.speedBytesPerSec),(item.totalBytes-item.downloadedBytes)/item.speedBytesPerSec)else progress
    }
    "completed" -> context.getString(R.string.download_completed)
    "paused" -> context.getString(R.string.download_paused)
    "waiting_network" -> context.getString(R.string.download_waiting_network)
    "queued" -> context.getString(R.string.download_queued)
    "cancelled" -> context.getString(R.string.download_cancelled)
    else -> context.getString(when(item.error) { "AUTH_REQUIRED" -> R.string.download_auth_error; "DIRECTORY_REQUIRED","DIRECTORY" -> R.string.download_directory_error; "STORAGE" -> R.string.download_storage_error; "SOURCE_EXPIRED" -> R.string.download_source_error; "FOREGROUND_REQUIRED" -> R.string.download_foreground_error; else -> R.string.download_network_error })
    }
}
internal fun formatDownloadBytes(bytes:Long):String=when { bytes<1024 -> "$bytes B"; bytes<1024*1024 -> String.format(java.util.Locale.getDefault(),"%.1f KB",bytes/1024.0); bytes<1024L*1024*1024 -> String.format(java.util.Locale.getDefault(),"%.1f MB",bytes/(1024.0*1024)); else -> String.format(java.util.Locale.getDefault(),"%.1f GB",bytes/(1024.0*1024*1024)) }
