// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package app.miru.offline

import android.content.Context
import android.os.Looper
import app.miru.common.DownloadConcurrency
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.Requirements
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** Process-wide owner of Media3's index, manager and separate non-evicting offline cache. */
class OfflineCoordinator private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = context.getSharedPreferences("miru_offline_metadata", Context.MODE_PRIVATE)
    private val mutableItems = MutableStateFlow<List<OfflineItem>>(emptyList())
    val items = mutableItems.asStateFlow()
    private val mutableHidden = MutableStateFlow<Set<String>>(emptySet())
    val pendingDeletionIds = mutableHidden.asStateFlow()
    private val deletions = linkedMapOf<String, Pair<Set<String>, Job>>()
    private val credentials = ConcurrentHashMap<String, Map<String, String>>()
    private val errors = ConcurrentHashMap<String, String>()
    // A cancelled ID is never reused or resumed. Old workers can therefore safely remove
    // late cache commits once their data sinks have closed, without touching a new transfer.
    private val permanentlyCancelled = ConcurrentHashMap.newKeySet<String>().apply {
        preferences.all.forEach { (key, value) -> if(key.startsWith("cancelled.") && value == true) add(key.removePrefix("cancelled.")) }
    }
    private var manager: DownloadManager? = null
    private var refreshJob: Job? = null
    private var refreshRequested = false
    private var foregroundRequested = false
    @Volatile var headerResolver: (suspend (OfflineItem) -> Map<String, String>?)? = null

    suspend fun prepare(source: OfflineSource): OfflineSelection = prepareOffline(context, source)

    fun enqueue(selection: OfflineSelection, qualityId: String, title: String = selection.source.title): String {
        val selected = selection.requests[qualityId] ?: throw OfflineException("QUALITY_UNAVAILABLE")
        val quality = selection.qualities.first { it.id == qualityId }
        val source = selection.source
        val id = UUID.randomUUID().toString()
        val headers = OfflineHttp.clean(source.headers)
        val metadata = OfflineMetadata(title.trim().ifBlank { context.getString(R.string.offline_video) }.take(500), source.pageUrl,
            quality.label, source.isPrivate, headers.has("Cookie"), headers.has("Authorization"))
        val request = DownloadRequest.Builder(id, selected.uri).setMimeType(selected.mimeType)
            .setStreamKeys(selected.streamKeys).setData(metadata.encode()).build()
        credentials[id] = headers
        scope.launch {
            val target = ensureManager()
            if(id in permanentlyCancelled) {
                credentials.remove(id); target.addDownload(request, STOP_CANCELLED); refresh(); return@launch
            }
            target.addDownload(request, STOP_PAUSED)
            try {
                DownloadService.sendAddDownload(context, MiruOfflineService::class.java, request, true)
                target.resumeDownloads()
            } catch(_: Exception) {
                error(id, "FOREGROUND_REQUIRED")
                target.setStopReason(id, STOP_FOREGROUND)
            }
            refresh()
        }
        return id
    }

    fun onAppForeground() { foregroundRequested = true; scope.launch {
        val target = ensureManager()
        refresh()
        if(target.currentDownloads.any { it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING }) {
            runCatching { DownloadService.start(context, MiruOfflineService::class.java); target.resumeDownloads() }
        }
    } }

    fun pause(id: String) { if(id in permanentlyCancelled) return; scope.launch { ensureManager().setStopReason(id, STOP_PAUSED); refresh() } }
    fun pauseAll() { scope.launch { ensureManager().currentDownloads.forEach { if(it.state in setOf(Download.STATE_DOWNLOADING, Download.STATE_QUEUED)) manager?.setStopReason(it.request.id, STOP_PAUSED) }; refresh() } }
    fun resumeAll() { items.value.filter { it.status in setOf("paused", "failed", "waiting_network") }.forEach { resume(it.id) } }
    fun resume(id: String) { scope.launch {
        if(id in permanentlyCancelled) { credentials.remove(id); return@launch }
        val target = ensureManager()
        val download = withContext(Dispatchers.IO) { target.downloadIndex.getDownload(id) } ?: return@launch
        if(download.state == Download.STATE_COMPLETED || id in permanentlyCancelled) return@launch
        if(download.stopReason == STOP_CANCELLED) { markCancelled(id); credentials.remove(id); return@launch }
        val request = download.request
        clearError(id)
        try { DownloadService.sendAddDownload(context, MiruOfflineService::class.java, request, true); target.resumeDownloads() }
        catch(_: Exception) { error(id, "FOREGROUND_REQUIRED"); target.setStopReason(id, STOP_FOREGROUND) }
        refresh()
    } }
    /** Supply newly captured page context explicitly when a private authenticated download needs it. */
    fun resume(id: String, headers: Map<String, String>) { if(id in permanentlyCancelled) return; credentials[id] = OfflineHttp.clean(headers); resume(id) }
    fun cancel(id: String) {
        scope.launch {
            if(id in permanentlyCancelled) return@launch
            val target = ensureManager()
            // The UI projection may still say "running" after the native worker has finished.
            // Reject cancellation of an already-complete copy using its authoritative index.
            val download = withContext(Dispatchers.IO) { target.downloadIndex.getDownload(id) }
            if(download?.state == Download.STATE_COMPLETED) return@launch
            // Once accepted, cancellation wins over a completion racing this read. Media3
            // only changes stopReason (without a callback) for a now-terminal index row.
            markCancelled(id); credentials.remove(id)
            target.setStopReason(id, STOP_CANCELLED)
            OfflineNotifications.cancel(context, id)
            scope.launch(Dispatchers.IO) { OfflineStorage.get(context).remove(id) }
            refresh()
        }
    }
    fun remove(id: String) { scope.launch {
        val target = ensureManager()
        target.removeDownload(id)
        runCatching { DownloadService.start(context, MiruOfflineService::class.java) }
        refresh()
    } }
    fun rename(id: String, title: String) {
        if(title.isBlank()) return
        preferences.edit().putString("title.$id", title.trim().take(500)).apply(); refresh()
    }

    /** Delayed deletion belongs to the application, so leaving the download page keeps the deadline. */
    fun stageDelete(ids: Set<String>): String {
        val token = UUID.randomUUID().toString()
        val retained = ids.filter { id -> items.value.any { it.id == id } }.toSet()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(6_000)
            retained.forEach(::remove)
            deletions.remove(token); updateHidden()
        }
        deletions[token] = retained to job; updateHidden(); job.start()
        return token
    }
    fun undoDelete(token: String) { deletions.remove(token)?.second?.cancel(); updateHidden() }
    private fun updateHidden() { mutableHidden.value = deletions.values.flatMap { it.first }.toSet() }

    suspend fun playback(id: String): OfflinePlayback? {
        if(id in permanentlyCancelled) return null
        val target = ensureManager()
        return withContext(Dispatchers.IO) {
            val download = target.downloadIndex.getDownload(id)?.takeIf { it.state == Download.STATE_COMPLETED && id !in permanentlyCancelled } ?: return@withContext null
            val metadata = OfflineMetadata.decode(download.request.data)
            val title = preferences.getString("title.$id", metadata.title).orEmpty()
            OfflinePlayback(download.request.toMediaItem().buildUpon().setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build(), OfflineStorage.get(context).cached(id))
        }
    }

    /** Committed media bytes for one download, excluding every other UUID's partition. */
    suspend fun cachedBytes(id: String): Long = withContext(Dispatchers.IO) {
        val cache = OfflineStorage.get(context).cache
        cache.keys.filter { it.startsWith(OfflineStorage.prefix(id)) }.sumOf { key -> cache.getCachedSpans(key).sumOf { it.length } }
    }

    private suspend fun ensureManager(): DownloadManager = withContext(Dispatchers.Main.immediate) {
        manager ?: withContext(Dispatchers.IO) { OfflineStorage.get(context) }.let(::createManager)
    }

    /** DownloadService requires a synchronous manager accessor on its main thread. */
    internal fun serviceManager(): DownloadManager {
        check(Looper.myLooper() == Looper.getMainLooper())
        return manager ?: createManager(runBlocking(Dispatchers.IO) { OfflineStorage.get(context) })
    }
    private fun createManager(storage: OfflineStorage): DownloadManager {
        manager?.let { return it }
        val index = DefaultDownloadIndex(storage.database, "miru_offline")
        val created = DownloadManager(context, index, DownloaderFactory { request -> ContextDownloader(request, storage) })
        manager = created
        created.maxParallelDownloads = 3
        created.minRetryCount = 2
        created.requirements = Requirements(Requirements.NETWORK)
        created.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) {
                // Never fetch ordinary cookies for a private download after process death.
                downloadManager.currentDownloads.forEach { download ->
                    if(download.request.id in permanentlyCancelled || download.stopReason == STOP_CANCELLED) {
                        markCancelled(download.request.id)
                        if(download.stopReason != STOP_CANCELLED && download.state !in setOf(Download.STATE_REMOVING, Download.STATE_RESTARTING)) downloadManager.setStopReason(download.request.id, STOP_CANCELLED)
                        scope.launch(Dispatchers.IO) { storage.remove(download.request.id) }
                        return@forEach
                    }
                    val metadata = OfflineMetadata.decode(download.request.data)
                    if(metadata.isPrivate && (metadata.requiresCookie || metadata.requiresAuthorization) && !credentials.containsKey(download.request.id) && download.state != Download.STATE_REMOVING) {
                        error(download.request.id, "AUTH_REQUIRED"); downloadManager.setStopReason(download.request.id, STOP_AUTH)
                    }
                }
                if(foregroundRequested && downloadManager.currentDownloads.any { it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING }) {
                    runCatching { DownloadService.start(context, MiruOfflineService::class.java); downloadManager.resumeDownloads() }
                }
                refresh()
            }
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                val id = download.request.id
                if(download.state == Download.STATE_STOPPED && download.stopReason == STOP_CANCELLED) {
                    markCancelled(id)
                }
                if(id in permanentlyCancelled) {
                    // Neither a racing completion nor a native notification action may
                    // revive an accepted cancellation or publish a completion notification.
                    if(download.stopReason != STOP_CANCELLED && download.state !in setOf(Download.STATE_REMOVING, Download.STATE_RESTARTING)) downloadManager.setStopReason(id, STOP_CANCELLED)
                    credentials.remove(id); clearError(id)
                    scope.launch(Dispatchers.IO) { storage.remove(id) }
                    OfflineNotifications.cancel(context, id); refresh(); return
                }
                if(finalException != null) error(id, (finalException as? OfflineException)?.code ?: "NETWORK")
                else if(download.state in setOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING)) clearError(id)
                if(download.state == Download.STATE_COMPLETED) {
                    credentials.remove(id); clearError(id)
                    OfflineNotifications.completed(context, item(download))
                }
                if(download.state != Download.STATE_COMPLETED) OfflineNotifications.stopped(context, item(download))
                refresh()
            }
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                credentials.remove(download.request.id); errors.remove(download.request.id)
                permanentlyCancelled.remove(download.request.id)
                preferences.edit().remove("title.${download.request.id}").remove("error.${download.request.id}").remove("cancelled.${download.request.id}").apply()
                OfflineNotifications.cancel(context, download.request.id); refresh()
            }
            override fun onRequirementsStateChanged(downloadManager: DownloadManager, requirements: Requirements, notMetRequirements: Int) { refresh() }
        })
        // A force-stop can leave a terminal row whose completion raced cancellation.
        // Tombstones also cover those rows, absent from DownloadManager.currentDownloads.
        scope.launch(Dispatchers.IO) { permanentlyCancelled.forEach(storage::remove) }
        scope.launch { while(isActive) { refresh(); delay(1_000) } }
        refresh()
        return created
    }

    private fun refresh() {
        val target = manager ?: return
        if(refreshJob?.isActive == true) { refreshRequested = true; return }
        refreshJob = scope.launch {
            do {
                refreshRequested = false
                val current = target.currentDownloads.associateBy { it.request.id }
                val rows = withContext(Dispatchers.IO) {
                    target.downloadIndex.getDownloads().use { cursor -> buildList { while(cursor.moveToNext()) add(current[cursor.download.request.id] ?: cursor.download) } }
                }
                mutableItems.value = rows.sortedByDescending { it.startTimeMs }.map(::item)
            } while(refreshRequested)
        }
    }

    private fun item(download: Download): OfflineItem {
        val data = OfflineMetadata.decode(download.request.data)
        val failure = errors[download.request.id] ?: preferences.getString("error.${download.request.id}", null)
        val status = if(download.state !in setOf(Download.STATE_REMOVING, Download.STATE_RESTARTING) && download.request.id in permanentlyCancelled) "cancelled" else when(download.state) {
            Download.STATE_COMPLETED -> "completed"
            Download.STATE_FAILED -> "failed"
            Download.STATE_DOWNLOADING -> "running"
            Download.STATE_REMOVING, Download.STATE_RESTARTING -> "removing"
            Download.STATE_STOPPED -> when(download.stopReason) { STOP_CANCELLED -> "cancelled"; STOP_AUTH, STOP_FOREGROUND -> "failed"; else -> "paused" }
            else -> if(manager?.notMetRequirements != 0) "waiting_network" else "queued"
        }
        return OfflineItem(download.request.id, download.request.uri.toString(), preferences.getString("title.${download.request.id}", data.title).orEmpty(),
            download.request.mimeType.orEmpty(), data.pageUrl, data.quality, status, failure,
            download.bytesDownloaded, download.contentLength, download.percentDownloaded, download.startTimeMs,
            download.updateTimeMs.takeIf { status == "completed" }, data.isPrivate)
    }
    private fun markCancelled(id: String) {
        permanentlyCancelled.add(id)
        // Do not infer terminal cancellation from stopReason alone: Media3's "cancel all"
        // command also sets that field on historical completed downloads.
        preferences.edit().putBoolean("cancelled.$id", true).apply()
    }
    private fun error(id: String, code: String) { errors[id] = code; preferences.edit().putString("error.$id", code).apply() }
    private fun clearError(id: String) { errors.remove(id); preferences.edit().remove("error.$id").apply() }

    private inner class ContextDownloader(private val request: DownloadRequest, private val storage: OfflineStorage) : Downloader {
        @Volatile private var delegate: Downloader? = null
        @Volatile private var cancelled = false
        override fun download(progressListener: Downloader.ProgressListener?) {
            var permit: AutoCloseable? = null
            try {
                permit = DownloadConcurrency.transfers.acquire { cancelled || request.id in permanentlyCancelled }
                transfer(progressListener)
            } finally {
                permit?.close()
                // SegmentDownloader has finished and closed its cache sink at this point.
                // This second cleanup catches a final partial fragment committed after the
                // STOP_CANCELLED listener's eager cleanup. Pausing deliberately retains it.
                if(request.id in permanentlyCancelled) storage.remove(request.id)
            }
        }
        private fun transfer(progressListener: Downloader.ProgressListener?) {
            if(cancelled || request.id in permanentlyCancelled) throw InterruptedException()
            val metadata = OfflineMetadata.decode(request.data)
            var headers = credentials[request.id]
            fun resolved(): Map<String, String>? {
                if(metadata.isPrivate) return null
                val row = item(Download(request, Download.STATE_QUEUED, 0, 0, -1, 0, 0))
                return try { runBlocking { withTimeoutOrNull(10_000) { headerResolver?.invoke(row)?.let(OfflineHttp::clean) } } }
                    catch(interrupted: InterruptedException) { throw interrupted }
                    catch(_: Exception) { null }
            }
            fun valid(value: Map<String, String>?) = value != null && (!metadata.requiresCookie || value.has("Cookie")) && (!metadata.requiresAuthorization || value.has("Authorization"))
            if(!valid(headers) && (metadata.requiresCookie || metadata.requiresAuthorization)) headers = resolved()
            if((metadata.requiresCookie || metadata.requiresAuthorization) && !valid(headers)) throw OfflineException("AUTH_REQUIRED")
            headers = headers.orEmpty()
            repeat(2) { attempt ->
                if(cancelled || Thread.currentThread().isInterrupted) throw InterruptedException()
                val factory = CacheDataSource.Factory().setCache(storage.cache)
                    .setCacheKeyFactory { spec -> OfflineStorage.prefix(request.id) + spec.uri.toString() }
                    .setUpstreamDataSourceFactory(OfflineHttp.factory(request.uri.toString(), headers.orEmpty()))
                val downloader = DefaultDownloaderFactory(factory, Executor { it.run() }).createDownloader(request)
                delegate = downloader
                if(cancelled) { downloader.cancel(); throw InterruptedException() }
                try { downloader.download(progressListener); return }
                catch(interrupted: InterruptedException) { throw interrupted }
                catch(error: IOException) {
                    var cause: Throwable? = error
                    var response = 0
                    repeat(8) { if(cause is HttpDataSource.InvalidResponseCodeException) response = (cause as HttpDataSource.InvalidResponseCodeException).responseCode; cause = cause?.cause }
                    if(response in setOf(401, 403)) {
                        credentials.remove(request.id)
                        val fresh = if(attempt == 0) resolved() else null
                        if(valid(fresh)) { headers = fresh; credentials[request.id] = fresh!! } else throw OfflineException("AUTH_REQUIRED")
                    } else throw OfflineException("NETWORK")
                }
            }
            throw OfflineException("AUTH_REQUIRED")
        }
        override fun cancel() { cancelled = true; delegate?.cancel() }
        override fun remove() { storage.remove(request.id) }
    }

    companion object {
        private const val STOP_PAUSED = 1
        private const val STOP_CANCELLED = 2
        private const val STOP_AUTH = 3
        private const val STOP_FOREGROUND = 4
        @Volatile private var instance: OfflineCoordinator? = null
        @Synchronized fun get(context: Context): OfflineCoordinator = instance ?: OfflineCoordinator(context.applicationContext).also { instance = it }
    }
}

private fun Map<String, String>.has(name: String) = entries.any { it.key.equals(name, true) && it.value.isNotBlank() }
