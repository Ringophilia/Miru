// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.downloads

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.downloadPreferences by preferencesDataStore("miru_download_settings")

/** Process-wide owner. Credentials and Gecko response bodies exist only in these in-memory maps. */
class DownloadCoordinator private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: DownloadCoordinator? = null
        fun get(context: Context): DownloadCoordinator = instance ?: synchronized(this) { instance ?: DownloadCoordinator(context.applicationContext).also { instance = it } }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = Room.databaseBuilder(context, DownloadDatabase::class.java, "miru-downloads.db").build()
    private val dao = db.downloads()
    private val transfer = DownloadTransfer()
    private val destination = DownloadDestination(context)
    private val credentials = ConcurrentHashMap<String, Map<String,String>>()
    private val bodies = ConcurrentHashMap<String, InputStream>()
    private val active = ConcurrentHashMap<String, Job>()
    private val mutex = Mutex()
    private val settingsStore = context.downloadPreferences
    private val mutableSettings = MutableStateFlow(DownloadPreferences())
    val settings: StateFlow<DownloadPreferences> = mutableSettings.asStateFlow()
    val preferences: StateFlow<DownloadPreferences> get() = settings
    private val mutablePendingDeletionIds=MutableStateFlow<Set<String>>(emptySet())
    val pendingDeletionIds:StateFlow<Set<String>> = mutablePendingDeletionIds.asStateFlow()
    private val deletions=ConcurrentHashMap<String,Pair<Set<String>,Job>>()
    private val speedSamples=mutableMapOf<String,Triple<Long,Long,Long>>()
    val downloads: StateFlow<List<DownloadItem>> = dao.observe().map { rows ->
        val now=System.nanoTime()
        speedSamples.keys.retainAll(rows.map { it.id }.toSet())
        rows.map { row ->
            val old=speedSamples[row.id]
            val speed=if(row.status!="running"||old==null||row.downloadedBytes<old.first)0L else if(now-old.second>=500_000_000)((row.downloadedBytes-old.first)*1_000_000_000/(now-old.second)).coerceAtLeast(0)else old.third
            if(old==null||now-old.second>=500_000_000||row.status!="running")speedSamples[row.id]=Triple(row.downloadedBytes,now,speed)
            row.item().copy(speedBytesPerSec=speed)
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val tempDir = File(context.filesDir, "download-parts").apply { mkdirs() }
    @Volatile private var serviceActive = false
    private val recovered = CompletableDeferred<Unit>()
    // Optional app hook can reacquire Cookie/authorization from Gecko after process death.
    var headerResolver: (suspend (DownloadItem) -> Map<String,String>?)? = null
    var openMedia: ((DownloadItem) -> Boolean)? = null

    init {
        scope.launch {
            settingsStore.data.catch { if(it is IOException) emit(emptyPreferences()) else throw it }.collect { values ->
                mutableSettings.value = DownloadPreferences(values[booleanPreferencesKey("ask")] ?: true, (values[intPreferencesKey("concurrent")] ?: 3).coerceIn(1,5), (values[intPreferencesKey("threads")] ?: 4).coerceIn(1,8), values[stringPreferencesKey("directory")], values[booleanPreferencesKey("deleteFile")] ?: true)
            }
        }
        scope.launch {
            try { dao.recover(); recovered.complete(Unit) }
            catch (error: Exception) { recovered.completeExceptionally(error) }
        }
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { if(serviceActive) pump() }
            override fun onLost(network: Network) { if(!online()) active.keys.forEach { id -> transfer.cancel(id) } }
        })
    }
    fun updatePreferences(transform: (DownloadPreferences) -> DownloadPreferences) {
        val updated = transform(settings.value)
        mutableSettings.value = updated
        scope.launch { settingsStore.edit { values ->
            values[booleanPreferencesKey("ask")] = updated.askBeforeDownload
            values[intPreferencesKey("concurrent")] = updated.maxConcurrent.coerceIn(1,5)
            values[intPreferencesKey("threads")] = updated.threadsPerFile.coerceIn(1,8)
            values[booleanPreferencesKey("deleteFile")] = updated.deleteFileWithRecord
            if(updated.directoryUri == null) values.remove(stringPreferencesKey("directory")) else values[stringPreferencesKey("directory")] = updated.directoryUri
        }; if(serviceActive) pump() }
    }
    fun enqueue(request: DownloadRequest): String = enqueueInternal(request, request.bodyStream)
    /** Restart persisted work while an Activity is visible, when dataSync service starts are allowed. */
    fun onAppForeground() { scope.launch {
        recovered.await()
        if (dao.pending().isNotEmpty()) startService()
    } }
    fun enqueueStream(request: DownloadRequest, stream: InputStream): String = enqueueInternal(request, stream)
    private fun enqueueInternal(request: DownloadRequest, stream: InputStream?): String {
        val id = UUID.randomUUID().toString()
        credentials[id] = request.headers.toMap()
        if(stream != null) bodies[id] = stream
        scope.launch {
            val auth = request.headers.keys.any { it.equals("cookie", true) || it.equals("authorization", true) }
            val item = DownloadEntity(id, request.url, DownloadLogic.fileName(null, request.fileName ?: request.url, request.mimeType), request.mimeType ?: "application/octet-stream", totalBytes = request.contentLength, sourcePage = request.sourcePage, directoryUri = request.directoryUri ?: settings.value.directoryUri, requiresAuth = auth, referrer = request.headers.entries.firstOrNull { it.key.equals("referer", true) }?.value, userNamed=request.fileName!=null,isPrivate=request.isPrivate)
            dao.put(item)
            startService()
        }
        return id
    }
    fun pause(id: String) { scope.launch { dao.status(id,"paused"); transfer.cancel(id); runCatching{bodies.remove(id)?.close()}; active[id]?.cancelAndJoin(); pump() } }
    fun resume(id: String) { scope.launch { dao.status(id,"queued"); startService() } }
    fun cancel(id: String) { scope.launch { dao.status(id,"cancelled"); transfer.cancel(id); runCatching{bodies.remove(id)?.close()}; active[id]?.cancelAndJoin(); credentials.remove(id); File(tempDir,id).delete(); dao.clearSegments(id); pump() } }
    fun pauseAll() { downloads.value.filter { it.status in setOf("queued","running","waiting_network") }.forEach { pause(it.id) } }
    fun resumeAll() { downloads.value.filter { it.status in setOf("paused","failed","waiting_network") }.forEach { resume(it.id) } }
    fun delete(id: String, deleteFile: Boolean = false) { scope.launch {
        dao.status(id,"cancelled"); transfer.cancel(id); runCatching{bodies.remove(id)?.close()}; active[id]?.cancelAndJoin(); credentials.remove(id)
        val item = dao.get(id)
        if(deleteFile && item?.destUri != null) runCatching { destination.delete(item.destUri) }
        dao.delete(id); File(tempDir,id).delete(); pump()
        context.getSystemService(android.app.NotificationManager::class.java).cancel(MiruDownloadService.notificationId(id))
    } }
    fun stageDelete(items:List<DownloadItem>,deleteFile:Boolean=false):String {
        val token=UUID.randomUUID().toString()
        val ids=items.map { it.id }.toSet()
        synchronized(deletions){mutablePendingDeletionIds.value=mutablePendingDeletionIds.value+ids}
        val job=scope.launch(start=CoroutineStart.LAZY){
            delay(6_000)
            ids.forEach { delete(it,deleteFile) }
            // Wait for Room removal before showing the remaining list again.
            downloads.first { rows->rows.none { it.id in ids } }
            synchronized(deletions){mutablePendingDeletionIds.value=mutablePendingDeletionIds.value-ids;deletions.remove(token)}
        }
        deletions[token]=ids to job;job.start();return token
    }
    fun undoDelete(token:String){
        val entry=deletions.remove(token)?:return
        entry.second.cancel()
        synchronized(deletions){mutablePendingDeletionIds.value=mutablePendingDeletionIds.value-entry.first}
    }
    // UI defers delete until its undo window expires; no record recreation or credential persistence.
    fun open(item: DownloadItem) { item.destUri?.let { uri ->
        if((item.mimeType.startsWith("video/") || item.mimeType.startsWith("audio/")) && openMedia?.invoke(item) == true) return
        val mime = if(item.fileName.endsWith(".apk", ignoreCase = true)) "application/vnd.android.package-archive" else item.mimeType
        try {
            if(mime == "application/vnd.android.package-archive" && !context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                android.widget.Toast.makeText(context, R.string.download_install_permission, android.widget.Toast.LENGTH_LONG).show()
            } else context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(context, R.string.download_no_viewer, android.widget.Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            android.widget.Toast.makeText(context, R.string.download_storage_error, android.widget.Toast.LENGTH_LONG).show()
        }
    } }
    fun rename(id:String,name:String) { scope.launch {
        val item=dao.get(id)?:return@launch
        val clean=DownloadLogic.fileName(null,name,item.mimeType)
        try {
            if(item.destUri!=null) {
                val uri=Uri.parse(item.destUri)
                if(item.directoryUri!=null) androidx.documentfile.provider.DocumentFile.fromSingleUri(context,uri)?.renameTo(clean)
                else context.contentResolver.update(uri,android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,clean) },null,null)
            }
            dao.rename(id,clean)
        } catch(_:Exception) { dao.status(id,item.status,"STORAGE") }
    } }
    fun share(item: DownloadItem) { item.destUri?.let { uri ->
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type=item.mimeType; putExtra(Intent.EXTRA_STREAM,Uri.parse(uri)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } }
    fun share(items:List<DownloadItem>) {
        val uris=ArrayList(items.mapNotNull { it.destUri?.let(Uri::parse) })
        if(uris.isEmpty())return
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply { type="*/*";putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    internal fun serviceStarted() { serviceActive = true; scope.launch { recovered.await(); pump() } }
    internal fun serviceStopped() { serviceActive = false; pauseAll() }
    private fun startService() {
        try { ContextCompat.startForegroundService(context,Intent(context,MiruDownloadService::class.java)) }
        catch(error: Exception) { scope.launch { dao.pending().forEach { dao.status(it.id,"paused","FOREGROUND_REQUIRED") } } }
    }
    private fun online(): Boolean = manager.activeNetwork?.let { manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true
    private fun pump() { scope.launch { mutex.withLock {
        if(!serviceActive) return@withLock
        for(item in dao.pending()) {
            if(active.size >= settings.value.maxConcurrent) break
            if(active.containsKey(item.id)) continue
            if(!online()) { dao.status(item.id,"waiting_network"); continue }
            val job = scope.launch(start = CoroutineStart.LAZY) { runDownload(item.id) }
            active[item.id]=job; job.start()
        }
    } } }
    private suspend fun runDownload(id: String) {
        try {
            var item = dao.get(id) ?: return
            dao.status(id,"running")
            val file = File(tempDir,id)
            if(bodies.containsKey(id)) {
                val body = bodies[id] ?: throw IOException("SOURCE_EXPIRED")
                body.use { input -> file.outputStream().use { output ->
                    val buf=ByteArray(64*1024); var done=0L;var last=System.nanoTime()
                    while(true) { currentCoroutineContext().ensureActive(); val n=input.read(buf); if(n<0)break; output.write(buf,0,n); done+=n;if(System.nanoTime()-last>250_000_000){dao.streamProgress(id,done);last=System.nanoTime()} }
                } }
                bodies.remove(id)
                item=item.copy(downloadedBytes=file.length(),totalBytes=file.length())
            } else {
                if(!item.url.startsWith("https://") && !item.url.startsWith("http://")) throw IOException("SOURCE_EXPIRED")
                var headers = credentials[id]
                if(headers == null && !item.isPrivate) headers = headerResolver?.invoke(item.item())?.also { credentials[id]=it }
                if(item.requiresAuth && headers == null) throw IOException("AUTH_REQUIRED")
                val safeHeaders = headers.orEmpty().toMutableMap().apply { item.referrer?.let { putIfAbsent("Referer",it) } }
                val remote = transfer.probe(item.url,safeHeaders,id)
                currentCoroutineContext().ensureActive()
                val changed = DownloadLogic.validatorsChanged(item.etag,item.lastModified,item.totalBytes,remote.etag,remote.modified,remote.size)
                var parts = dao.segments(id)
                if(changed || (!remote.ranges && item.downloadedBytes>0) || !file.exists()) { parts=emptyList(); dao.clearSegments(id); file.delete() }
                // If a partial response has no validator, restart rather than risk joining two different files.
                if(parts.any { it.downloaded>0 } && !DownloadLogic.canResume(item.etag, item.lastModified)) { parts=emptyList(); dao.clearSegments(id); file.delete() }
                item=item.copy(totalBytes=remote.size,etag=remote.etag,lastModified=remote.modified,acceptsRanges=remote.ranges,mimeType=remote.mime?.substringBefore(';') ?: item.mimeType,fileName=if(item.userNamed)item.fileName else DownloadLogic.fileName(remote.disposition,item.url,remote.mime),downloadedBytes=if(parts.isEmpty())0 else parts.sumOf { it.downloaded })
                dao.put(item.copy(status="running",error=null))
                if(parts.isEmpty()) {
                    parts=DownloadLogic.ranges(remote.size,settings.value.threadsPerFile,remote.ranges).mapIndexed { index,r -> DownloadSegment(id,index,r.start,r.end) }
                    dao.putSegments(parts)
                    if(remote.size>0) java.io.RandomAccessFile(file,"rw").use { it.setLength(remote.size) }
                }
                coroutineScope { parts.map { part -> async { try { transfer.segment(id,item.url,safeHeaders,part,file,remote.ranges,item.etag?.takeUnless { it.startsWith("W/") } ?: item.lastModified,totalSize=remote.size) { bytes -> dao.segmentProgress(id,part.index,bytes); dao.sumProgress(id) } } catch(error:Exception){transfer.cancel(id);throw error} } }.awaitAll() }
                item=dao.get(id) ?: return
                if(item.totalBytes < 0) item=item.copy(totalBytes=file.length(),downloadedBytes=file.length())
            }
            currentCoroutineContext().ensureActive()
            val publishingContext = currentCoroutineContext()
            val (uri,name)=destination.publish(file,item) { publishingContext.ensureActive() }
            // Once the public file exists, cancellation must not leave its URI unrecorded.
            withContext(NonCancellable) {
                dao.put(item.copy(fileName=name,destUri=uri,downloadedBytes=file.length(),totalBytes=file.length(),status="completed",error=null,completedAt=System.currentTimeMillis()))
                file.delete(); dao.clearSegments(id); credentials.remove(id)
            }
        } catch(c: CancellationException) { throw c }
        catch(error: Exception) {
            // A retry after reauthentication must reacquire the current Gecko cookie rather than
            // resend a stale credential captured by the original download request.
            if(error is HttpFailure && error.status in setOf(401,403)) credentials.remove(id)
            val current=dao.get(id)
            if(current?.status !in setOf("paused","cancelled")) {
                val waiting = !online() && error is IOException
                val code=when { waiting -> null; error is HttpFailure -> "HTTP_${error.status}"; error is java.io.FileNotFoundException -> "STORAGE"; error.message in setOf("DIRECTORY_REQUIRED","DIRECTORY","AUTH_REQUIRED","SOURCE_EXPIRED","RANGE_CHANGED","INVALID_RANGE","INVALID_LENGTH") -> error.message; else -> "NETWORK" }
                dao.status(id,if(waiting)"waiting_network" else "failed",code)
            }
        } finally { active.remove(id,currentCoroutineContext()[Job]); if(serviceActive) pump() }
    }
}
