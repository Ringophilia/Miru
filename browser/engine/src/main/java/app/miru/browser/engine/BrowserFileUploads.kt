// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.CancellationSignal
import android.os.Process
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal sealed class BrowserUploadSelection(val token: Long, val isPrivate: Boolean,
    val pending: () -> Boolean, val documentAlive: () -> Boolean, val cancel: () -> Unit)

internal class BrowserFileSelection(
    token: Long,
    val mimeTypes: List<String>,
    val multiple: Boolean,
    isPrivate: Boolean,
    val capture: BrowserFileCapture,
    pending: () -> Boolean,
    documentAlive: () -> Boolean,
    val prepare: (List<File>) -> PreparedNativeFiles?,
    val deliver: (PreparedNativeFiles) -> Boolean,
    cancel: () -> Unit,
) : BrowserUploadSelection(token, isPrivate, pending, documentAlive, cancel) {
    override fun toString() = "BrowserFileSelection(token=$token, private=$isPrivate)"
}

internal class BrowserFolderSelection(token: Long, isPrivate: Boolean, pending: () -> Boolean,
    documentAlive: () -> Boolean, val flowCurrent: () -> Boolean,
    val prepare: (PreparedUploadFolder) -> PreparedNativeFolder?,
    val deliver: (PreparedNativeFolder, String, () -> Unit, (Boolean) -> Unit) -> Boolean,
    cancel: () -> Unit,
) : BrowserUploadSelection(token, isPrivate, pending, documentAlive, cancel)

enum class BrowserUploadPhase { IDLE, PERMISSION, PICKING, PREPARING, CONFIRMING }
enum class BrowserUploadError { UNAVAILABLE, PERMISSION_DENIED, UNREADABLE, TOO_MANY_FILES, BUSY, FOLDER_UNAVAILABLE }
enum class BrowserUploadSource { DOCUMENT, PHOTO, VIDEO, AUDIO, FOLDER }
data class BrowserUploadState(val token: Long? = null, val phase: BrowserUploadPhase = BrowserUploadPhase.IDLE)

/** Application-lifetime, process-memory-only system result ownership and document file leases. */
class BrowserFileUploads internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prompts: BrowserPrompts,
) {
    companion object {
        // AndroidX ActivityResultRegistry allocates >= 0x10000. These channels never
        // enter its saved-state provider, including results arriving after recreation.
        const val PICKER_REQUEST_CODE = 0x4d41
        const val PERMISSION_REQUEST_CODE = 0x4d42
        const val PICKER_TOKEN_EXTRA = "miru.upload.request"
        const val MAX_FILES = 512
        private const val DISK_RESERVE = 64L * 1024 * 1024
        private const val FOLDER_CONFIRM_APPEAR_TIMEOUT_MS = 30_000L
    }

    private val stagingRoot by lazy { File(context.cacheDir, "miru-upload-staging") }
    private val captureRoot by lazy { File(context.cacheDir, "miru-upload-capture") }
    private val staging by lazy { UploadStagingStore(stagingRoot) }
    private val captures by lazy { UploadStagingStore(captureRoot) }
    private val folderRoot by lazy { File(context.cacheDir, "miru-folder-staging") }
    private val folders by lazy { UploadTreeStagingStore(folderRoot) }
    private val initialized = scope.async(Dispatchers.IO) { staging.clearOrphans(); captures.clearOrphans(); folders.clearOrphans() }
    private var host = WeakReference<Activity>(null)
    private var pickerActivityClass: Class<out Activity>? = null
    private var pickerHost = WeakReference<Activity>(null)
    private var pickerHostToken: String? = null
    private class Operation(val ticket: BrowserUploadSelection, val source: BrowserUploadSource) {
        val channel = UUID.randomUUID().toString()
        var phase = BrowserUploadPhase.PREPARING
        @Volatile var abandoned = false
        var delivering = false
        var job: Job? = null
        @Volatile var signal: CancellationSignal? = null
        @Volatile var captureLease: UploadStagingLease? = null
        var captureFile: File? = null
        var captureUri: Uri? = null
        var systemIntent: Intent? = null
        @Volatile var selectionLease: Closeable? = null
    }
    private class Delivered(val documentAlive: () -> Boolean, val lease: Closeable)
    private class AwaitingFolder(val ticket: BrowserFolderSelection, val lease: Closeable) { var appearanceDeadline: Job? = null }
    private var awaitingFolder: AwaitingFolder? = null
    private class SystemSlot(val operation: Operation?, val phase: BrowserUploadPhase,
        val token: String = checkNotNull(operation).channel, var systemStarted: Boolean = false)
    private val delivered = mutableListOf<Delivered>()
    private var operation: Operation? = null
    // Retained even after owner invalidation. Only the original OS callback drains this slot.
    private var launched: SystemSlot? = null
    private val mutableState = MutableStateFlow(BrowserUploadState())
    val state = mutableState.asStateFlow()
    private val errorChannel = Channel<BrowserUploadError>(Channel.CONFLATED)
    val errors = errorChannel.receiveAsFlow()

    init {
        scope.launch(Dispatchers.Main.immediate) {
            combine(prompts.lifecycleChanges, prompts.prompt, NativeFilePrompts.documentChanges) { _, _, _ -> Unit }
                .collect { reconcile() }
        }
    }

    fun attachHost(activity: Activity, pickerClass: Class<out Activity>) {
        host = WeakReference(activity); pickerActivityClass = pickerClass; reconcile()
    }
    fun detachHost(activity: Activity) {
        if(host.get() === activity) {
            host.clear()
            if(activity.isFinishing) {
                operation?.let(::abandon)
                awaitingFolder?.ticket?.cancel?.invoke()
                publish()
            }
        }
    }

    fun pickerIsPrivate(token: String): Boolean = operation?.takeIf { it.channel == token }?.ticket?.isPrivate ?: true

    /** The receiver Activity owns temporary provider grants; its Intent contains only this token. */
    fun attachPickerHost(activity: Activity, token: String): Boolean {
        val retainedSlot = launched?.takeIf { it.token == token }
        if(retainedSlot?.operation == null && retainedSlot?.systemStarted == true) {
            // The document is gone, but this exact receiver must still accept the old OS result.
            // Finishing it before that result arrives can orphan Android's result URI grant owner.
            pickerHost = WeakReference(activity)
            pickerHostToken = token
            return true
        }
        val op = operation?.takeIf { it.channel == token && !it.abandoned } ?: return false
        pickerHost = WeakReference(activity)
        pickerHostToken = token
        if(!op.ticket.pending()) {
            abandon(op)
            return launched?.let { it.token == token && it.operation == null && it.systemStarted } == true
        }
        val slot = launched?.takeIf { it.token == token }
        if(slot != null && !slot.systemStarted) {
            slot.systemStarted = true
            try {
                if(slot.phase == BrowserUploadPhase.PERMISSION) {
                    val permission = if(op.source == BrowserUploadSource.AUDIO) Manifest.permission.RECORD_AUDIO else Manifest.permission.CAMERA
                    activity.requestPermissions(arrayOf(permission), PERMISSION_REQUEST_CODE)
                } else {
                    val intent = op.systemIntent ?: return false
                    op.systemIntent = null
                    activity.startActivityForResult(intent, PICKER_REQUEST_CODE)
                }
            }
            catch(_: Exception) { launched = null; fail(op, BrowserUploadError.UNAVAILABLE); return false }
        }
        return true
    }

    fun detachPickerHost(activity: Activity, token: String) {
        if(pickerHost.get() === activity) { pickerHost.clear(); pickerHostToken = null }
        if(activity.isFinishing && !activity.isChangingConfigurations) {
            // A finished Activity token cannot deliver into a new receiver.
            if(launched?.token == token) launched = null
            operation?.takeIf { it.channel == token }?.let(::abandon)
            publish()
        }
    }

    fun cancelPicker(token: String) {
        val active = operation?.takeIf { it.channel == token }
        if(active != null) abandon(active)
        else if(launched?.let { it.token == token && it.systemStarted } != true) finishPickerHost(token)
        // A real OS result still in flight must retain its receiver until it returns.
    }

    fun choose(token: Long, source: BrowserUploadSource = BrowserUploadSource.DOCUMENT) {
        reconcile()
        if(launched != null || operation != null || awaitingFolder != null) { errorChannel.trySend(BrowserUploadError.BUSY); return }
        val ticket = prompts.fileSelection(token) ?: prompts.folderSelection(token) ?: return
        val activity = host.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        if(activity == null) { ticket.cancel(); errorChannel.trySend(BrowserUploadError.UNAVAILABLE); return }
        val actualSource = if(ticket is BrowserFolderSelection) BrowserUploadSource.FOLDER else source
        val op = Operation(ticket, actualSource)
        operation = op
        if(ticket is BrowserFileSelection && source == BrowserUploadSource.FOLDER) { fail(op, BrowserUploadError.UNAVAILABLE); return }
        if(actualSource != BrowserUploadSource.FOLDER && source != BrowserUploadSource.DOCUMENT &&
            source !in captureSources((ticket as BrowserFileSelection).mimeTypes)) {
            fail(op, BrowserUploadError.UNAVAILABLE); return
        }
        val permission = when(source) {
            BrowserUploadSource.PHOTO, BrowserUploadSource.VIDEO -> Manifest.permission.CAMERA
            BrowserUploadSource.AUDIO -> Manifest.permission.RECORD_AUDIO
            else -> null
        }
        if(permission != null && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            op.phase = BrowserUploadPhase.PERMISSION
            launched = SystemSlot(op, op.phase)
            publish()
            launchReceiver(op)
        } else launchPicker(op)
    }

    fun onPermissionsResult(token: String, requestCode: Int, permissions: Array<out String>, results: IntArray): Boolean {
        if(requestCode != PERMISSION_REQUEST_CODE) return false
        val slot = launched ?: return true
        if(slot.token != token) return true
        if(slot.phase != BrowserUploadPhase.PERMISSION) return true
        launched = null
        val op = slot.operation ?: run { finishPickerHost(token); publish(); return true }
        if(op.abandoned || !op.ticket.pending()) { abandon(op); return true }
        val expected = if(op.source == BrowserUploadSource.AUDIO) Manifest.permission.RECORD_AUDIO else Manifest.permission.CAMERA
        val index = permissions.indexOf(expected)
        if(index < 0 || results.getOrNull(index) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(expected) != PackageManager.PERMISSION_GRANTED) fail(op, BrowserUploadError.PERMISSION_DENIED)
        else launchPicker(op)
        return true
    }

    fun onActivityResult(token: String, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if(requestCode != PICKER_REQUEST_CODE) return false
        val slot = launched ?: return true // Includes process death: do not resurrect a native request.
        if(slot.token != token) return true
        if(slot.phase != BrowserUploadPhase.PICKING) return true
        launched = null
        val op = slot.operation ?: run { finishPickerHost(token); publish(); return true }
        if(op.abandoned || !op.ticket.pending()) { abandon(op); return true }
        if(resultCode != Activity.RESULT_OK) { cancel(op); return true }
        if(op.source == BrowserUploadSource.FOLDER) {
            val uri = data?.data
            if(uri == null || data.clipData?.itemCount?.let { it > 1 } == true) fail(op, BrowserUploadError.UNREADABLE)
            else prepareFolder(op, uri)
            return true
        }
        val uris = if(op.captureUri != null) listOf(checkNotNull(op.captureUri)) else buildList {
            data?.clipData?.let { clip -> for(index in 0 until clip.itemCount) add(clip.getItemAt(index).uri) }
            data?.data?.let(::add)
        }.distinct()
        if(uris.isEmpty() || (!(op.ticket as BrowserFileSelection).multiple && uris.size != 1)) { fail(op, BrowserUploadError.UNREADABLE); return true }
        if(uris.size > MAX_FILES) { fail(op, BrowserUploadError.TOO_MANY_FILES); return true }
        prepare(op, uris)
        return true
    }

    private fun launchPicker(op: Operation) {
        op.phase = BrowserUploadPhase.PREPARING
        publish()
        op.job = scope.launch(Dispatchers.Main.immediate) {
            try {
                initialized.await()
                if(!current(op)) { abandon(op); return@launch }
                val intent = when(op.source) {
                    BrowserUploadSource.DOCUMENT -> documentIntent(op.ticket as BrowserFileSelection)
                    BrowserUploadSource.FOLDER -> Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    BrowserUploadSource.AUDIO -> Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
                    BrowserUploadSource.PHOTO, BrowserUploadSource.VIDEO -> {
                        val (file, uri) = withContext(Dispatchers.IO) {
                            val lease = captures.createLease()
                            op.captureLease = lease
                            val file = lease.copy(if(op.source == BrowserUploadSource.PHOTO) "capture.jpg" else "capture.mp4") {
                                java.io.ByteArrayInputStream(byteArrayOf())
                            }
                            file to FileProvider.getUriForFile(context, "${context.packageName}.uploads", file)
                        }
                        if(!current(op)) { abandon(op); return@launch }
                        op.captureFile = file
                        op.captureUri = uri
                        Intent(if(op.source == BrowserUploadSource.PHOTO) MediaStore.ACTION_IMAGE_CAPTURE else MediaStore.ACTION_VIDEO_CAPTURE).apply {
                            putExtra(MediaStore.EXTRA_OUTPUT, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            clipData = ClipData.newRawUri("capture", uri)
                            if((op.ticket as BrowserFileSelection).capture == BrowserFileCapture.FRONT_CAMERA) {
                                putExtra("android.intent.extras.CAMERA_FACING", 1)
                                putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                            } else if(op.ticket.capture == BrowserFileCapture.BACK_CAMERA) putExtra("android.intent.extras.CAMERA_FACING", 0)
                        }
                    }
                }
                if(!current(op)) { abandon(op); return@launch }
                op.phase = BrowserUploadPhase.PICKING
                op.systemIntent = intent
                launched = SystemSlot(op, op.phase) // Mark before launch; results have no native request token.
                publish()
                val receiver = pickerHost.get()?.takeIf { pickerHostToken == op.channel && !it.isFinishing && !it.isDestroyed }
                if(receiver != null) attachPickerHost(receiver, op.channel) else launchReceiver(op)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { fail(op, BrowserUploadError.UNREADABLE) }
        }.also { watchCancellation(op, it) }
    }

    private fun launchReceiver(op: Operation) {
        val activity = host.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        val pickerClass = pickerActivityClass
        if(activity == null || pickerClass == null) { launched = null; fail(op, BrowserUploadError.UNAVAILABLE); return }
        try { activity.startActivity(Intent(activity, pickerClass).putExtra(PICKER_TOKEN_EXTRA, op.channel)) }
        catch(_: Exception) { launched = null; fail(op, BrowserUploadError.UNAVAILABLE) }
    }

    private fun prepare(op: Operation, uris: List<Uri>) {
        val ticket = op.ticket as BrowserFileSelection
        op.phase = BrowserUploadPhase.PREPARING
        val captureFile = op.captureFile
        val captureUri = op.captureUri
        publish()
        op.job = scope.launch(Dispatchers.Main.immediate) {
            try {
                initialized.await()
                if(!current(op)) { abandon(op); return@launch }
                val prepared = withContext(Dispatchers.IO) {
                    if(captureFile?.let { it.length() == 0L } == true || uris.any { !readableResultUri(captureUri, it) }) {
                        throw java.io.IOException("Unavailable upload")
                    }
                    val lease = staging.createLease((stagingRoot.usableSpace - DISK_RESERVE).coerceAtLeast(0))
                    op.selectionLease = lease
                    val files = uris.map { uri ->
                        coroutineContext.ensureActive()
                        val signal = CancellationSignal()
                        op.signal = signal
                        try {
                            coroutineContext.ensureActive()
                            if(op.abandoned) throw CancellationException("Upload owner ended")
                            val name = if(uri == captureUri) checkNotNull(captureFile).name else displayName(uri, signal)
                            lease.copy(name) {
                                context.contentResolver.openAssetFileDescriptor(uri, "r", signal)?.createInputStream()
                                    ?: throw java.io.IOException("Unavailable upload")
                            }
                        } finally { if(op.signal === signal) op.signal = null }
                    }
                    ticket.prepare(files) ?: throw java.io.IOException("Upload validation failed")
                }
                if(!current(op) || !op.ticket.documentAlive()) { abandon(op); return@launch }
                // retire() publishes prompt=null synchronously. Do not interpret our own
                // successful native completion as a user cancellation in reconcile().
                op.delivering = true
                val accepted = ticket.deliver(prepared)
                if(accepted && op.ticket.documentAlive()) {
                    delivered += Delivered(op.ticket.documentAlive, checkNotNull(op.selectionLease))
                    op.selectionLease = null
                } else errorChannel.trySend(BrowserUploadError.UNREADABLE)
                finish(op)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { if(!op.abandoned) fail(op, BrowserUploadError.UNREADABLE) }
        }.also { watchCancellation(op, it) }
    }

    private fun prepareFolder(op: Operation, tree: Uri) {
        val ticket = op.ticket as BrowserFolderSelection
        op.phase = BrowserUploadPhase.PREPARING
        publish()
        op.job = scope.launch(Dispatchers.Main.immediate) {
            try {
                initialized.await()
                if(!current(op)) { abandon(op); return@launch }
                val (rootName, prepared) = withContext(Dispatchers.IO) {
                    if(!DocumentsContract.isTreeUri(tree) || !readableResultUri(null, tree))
                        throw java.io.IOException("Unavailable folder selection")
                    val signal = CancellationSignal()
                    op.signal = signal
                    try {
                        coroutineContext.ensureActive()
                        if(op.abandoned) throw CancellationException("Folder owner ended")
                        val lease = folders.createLease((folderRoot.usableSpace - DISK_RESERVE).coerceAtLeast(0), MAX_FILES)
                        op.selectionLease = lease
                        val folder = lease.copy(AndroidUploadTreeSource(context, tree, signal))
                        folder.rootName to (ticket.prepare(folder) ?: throw java.io.IOException("Folder validation failed"))
                    } finally { if(op.signal === signal) op.signal = null }
                }
                if(!current(op) || !ticket.flowCurrent()) { abandon(op); return@launch }
                val waiting = AwaitingFolder(ticket, checkNotNull(op.selectionLease))
                op.selectionLease = null
                awaitingFolder = waiting // The real first callback may immediately issue its confirmation.
                waiting.appearanceDeadline = scope.launch {
                    delay(FOLDER_CONFIRM_APPEAR_TIMEOUT_MS)
                    if(awaitingFolder === waiting) {
                        waiting.ticket.cancel()
                        if(awaitingFolder === waiting) { awaitingFolder = null; dispose(waiting.lease) }
                        errorChannel.trySend(BrowserUploadError.FOLDER_UNAVAILABLE)
                        publish()
                    }
                }
                op.delivering = true
                val accepted = ticket.deliver(prepared, rootName, { waiting.appearanceDeadline?.cancel() }) { allowed ->
                    if(awaitingFolder === waiting) {
                        waiting.appearanceDeadline?.cancel()
                        awaitingFolder = null
                        if(allowed && ticket.documentAlive()) delivered += Delivered(ticket.documentAlive, waiting.lease)
                        else dispose(waiting.lease)
                        publish()
                    }
                }
                if(!accepted) {
                    if(awaitingFolder === waiting) { waiting.appearanceDeadline?.cancel(); awaitingFolder = null; dispose(waiting.lease) }
                    errorChannel.trySend(BrowserUploadError.FOLDER_UNAVAILABLE)
                }
                // Its receiver now releases only this result's temporary tree grant owner.
                // Do not revoke the URI globally: it may also be a user's persisted download folder.
                finish(op)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { if(!op.abandoned) fail(op, BrowserUploadError.UNREADABLE) }
        }.also { watchCancellation(op, it) }
    }

    private fun readableResultUri(captureUri: Uri?, uri: Uri): Boolean {
        if(uri == captureUri) return true
        if(uri.scheme != "content" || uri.authority.isNullOrBlank()) return false
        val provider = context.packageManager.resolveContentProvider(checkNotNull(uri.authority), 0)
        // A hostile picker must not turn our own private providers into file exfiltration capabilities.
        if(provider?.applicationInfo?.uid == Process.myUid()) return false
        return context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    private fun displayName(uri: Uri, signal: CancellationSignal): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)?.use { cursor ->
            if(cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }?.takeIf { it.isNotBlank() } ?: "upload"

    private fun current(op: Operation) = operation === op && !op.abandoned && op.ticket.pending()

    private fun reconcile() {
        operation?.takeIf { !it.delivering && !it.ticket.pending() }?.let(::abandon)
        awaitingFolder?.takeUnless { it.ticket.flowCurrent() }?.let { waiting ->
            waiting.appearanceDeadline?.cancel()
            waiting.ticket.cancel()
            if(awaitingFolder === waiting) { awaitingFolder = null; dispose(waiting.lease) }
        }
        delivered.toList().filterNot { it.documentAlive() }.forEach { value ->
            delivered.remove(value)
            dispose(value.lease)
        }
        publish()
    }

    private fun abandon(op: Operation) {
        op.abandoned = true
        if(operation === op) operation = null
        // Retain only an opaque channel tombstone. Private files, grants, URLs and
        // native request closures must not remain held while an external app stalls.
        launched?.takeIf { it.operation === op }?.let { slot ->
            launched = SystemSlot(null, slot.phase, op.channel, systemStarted = slot.systemStarted)
        }
        val job = op.job.also { op.job = null }
        val signal = op.signal.also { op.signal = null }
        op.ticket.cancel()
        cleanup(op)
        publish()
        // Job cancellation invokes the stream-close handler synchronously, while CancellationSignal
        // may synchronously call a remote provider. Neither may block Main or prevent the other.
        if(job != null) scope.launch(Dispatchers.IO) { job.cancel() }
        if(signal != null) scope.launch(Dispatchers.IO) { runCatching { signal.cancel() } }
    }

    private fun cancel(op: Operation) { if(operation === op) operation = null; op.ticket.cancel(); finish(op) }
    private fun fail(op: Operation, error: BrowserUploadError) {
        errorChannel.trySend(if(op.source == BrowserUploadSource.FOLDER) BrowserUploadError.FOLDER_UNAVAILABLE else error)
        if(operation === op) operation = null
        op.ticket.cancel()
        finish(op)
    }

    private fun finish(op: Operation) {
        if(operation === op) operation = null
        cleanup(op)
        publish()
    }

    private fun cleanup(op: Operation) {
        op.systemIntent = null
        op.captureUri?.let { uri ->
            scope.launch(Dispatchers.IO) {
                runCatching { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            }
        }
        op.captureUri = null
        op.captureFile = null
        op.captureLease?.let(::dispose); op.captureLease = null
        op.selectionLease?.let(::dispose); op.selectionLease = null
        val waitingForOldSystemResult = launched?.let { it.token == op.channel && it.operation == null && it.systemStarted } == true
        if(!waitingForOldSystemResult) finishPickerHost(op.channel)
    }

    private fun finishPickerHost(token: String) {
        if(pickerHostToken == token) pickerHost.get()?.finish()
    }

    private fun dispose(lease: Closeable) { scope.launch(Dispatchers.IO) { runCatching { lease.close() } } }
    private fun watchCancellation(op: Operation, job: Job) {
        job.invokeOnCompletion { cause ->
            if(cause != null) scope.launch(Dispatchers.Main.immediate) {
                if(operation !== op && launched?.operation !== op) cleanup(op)
            }
        }
    }
    private fun publish() {
        val active = operation ?: launched?.operation
        mutableState.value = BrowserUploadState(active?.ticket?.token ?: awaitingFolder?.ticket?.token,
            active?.phase ?: launched?.phase ?: if(awaitingFolder != null) BrowserUploadPhase.CONFIRMING else BrowserUploadPhase.IDLE)
    }

    private fun documentIntent(ticket: BrowserFileSelection): Intent {
        val types = normalizedMimeTypes(ticket.mimeTypes)
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = when {
                types.size == 1 -> types.single()
                types.isNotEmpty() && types.map { it.substringBefore('/') }.distinct().size == 1 -> "${types.first().substringBefore('/')}/*"
                else -> "*/*"
            }
            if(types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, ticket.multiple)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun captureSources(mimeTypes: List<String>): Set<BrowserUploadSource> {
        val types = normalizedMimeTypes(mimeTypes)
        val all = types.isEmpty() || "*/*" in types
        return buildSet {
            if(all || types.any { it in setOf("image/*", "image/jpeg") }) add(BrowserUploadSource.PHOTO)
            if(all || types.any { it in setOf("video/*", "video/mp4", "video/3gpp") }) add(BrowserUploadSource.VIDEO)
            if(all || types.any { it.startsWith("audio/") }) add(BrowserUploadSource.AUDIO)
        }
    }

    private fun normalizedMimeTypes(values: List<String>): List<String> = values.mapNotNull { raw ->
        val value = raw.trim().lowercase(Locale.ROOT)
        if(value == "*/*") value
        else if(value.startsWith('.')) MimeTypeMap.getSingleton().getMimeTypeFromExtension(value.drop(1))
        else value.takeIf { Regex("[a-z0-9!#$&^_.+-]+/([a-z0-9!#$&^_.+-]+|\\*)").matches(it) }
    }.distinct().let { if("*/*" in it) listOf("*/*") else it }
}
