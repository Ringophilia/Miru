// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.prompt.PromptRequest
import org.mozilla.gecko.util.BundleEventListener
import org.mozilla.gecko.util.EventCallback
import org.mozilla.gecko.util.GeckoBundle
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.MiruGeckoSessionBridge

/** A weak, opaque native document identity; combine it with the application's tab/context owner. */
class NativeUploadDocument internal constructor(private val current: () -> Boolean) {
    /** GeckoSession.isOpen has an actual UI-thread assertion; this check must run on Main. */
    @MainThread
    fun isCurrent(): Boolean = isNativePromptMainThread() && current()
}

/** IO-validated files for one real native request. Only its synchronous Main callback may consume them. */
internal class PreparedNativeFiles internal constructor(
    private val requestIdentity: Any,
    files: Array<Uri>,
) {
    private val files = files.copyOf()
    private var consumed = false

    @MainThread
    internal fun belongsTo(identity: Any): Boolean = !consumed && requestIdentity === identity

    internal fun uris(): Array<Uri> = files.copyOf()

    @MainThread
    internal fun consume(identity: Any, selected: Array<Uri>): Array<Uri>? {
        if (!belongsTo(identity)) return null
        consumed = true
        return if (selected.contentEquals(files)) files.copyOf() else null
    }
}

/** Single-use native-record binding of a fully validated folder, retained only in its callback. */
internal class PreparedNativeFolder internal constructor(private val requestIdentity: Any,
    private val folder: PreparedUploadFolder) {
    private var consumed = false
    private val selection = arrayOf(Uri.fromFile(File(folder.rootPath)))

    @MainThread
    fun belongsTo(identity: Any): Boolean = !consumed && requestIdentity === identity
    fun uri(): Uri = selection.single()

    @MainThread
    fun consume(identity: Any, selected: Array<Uri>): PreparedUploadFolder? {
        if(!belongsTo(identity)) return null
        consumed = true
        return folder.takeIf { selected.contentEquals(selection) }
    }
}

/**
 * Upload native delegate adapter for the pinned AC/GeckoView 158.0.2 build.
 *
 * MiruGeckoSessionBridge explicitly uses internal JVM/package ABIs, not official public AC APIs.
 * The matching omni overlay emits top-level Miru:DocumentLocation(sameDocument: Boolean) before
 * forwarding GeckoView:LocationChange. Public NavigationDelegate drops this flag and is untouched.
 * All provider reads and bounded copies belong to the application uploader, before these callbacks.
 */
object NativeFilePrompts {
    private const val STAGING_DIRECTORY = "miru-upload-staging"
    private val installations = mutableListOf<WeakReference<Installation>>()
    private val records = mutableListOf<FileRecord>()
    private val mutableDocumentChanges = MutableStateFlow(0L)
    val documentChanges: StateFlow<Long> = mutableDocumentChanges.asStateFlow()

    /** Install before AC loads this session. Never creates, renders, or moves a view. */
    @MainThread
    fun install(engineSession: EngineSession, context: Context): Boolean {
        if (!isNativePromptMainThread()) return false
        val native = MiruGeckoSessionBridge.nativeSession(engineSession) ?: return false
        installations.removeAll { it.get() == null }
        installations.firstNotNullOfOrNull { it.get()?.takeIf { item -> item.engine.get() === engineSession } }
            ?.let { return it.native.get() === native && it.isInstalled() }
        if (!MiruGeckoSessionBridge.hasExpectedPromptDelegate(native)) return false
        val original = native.promptDelegate ?: return false
        val installation = Installation(engineSession, native, context.applicationContext, original)
        if (!MiruGeckoSessionBridge.registerDocumentListener(native, installation)) return false
        return try {
            native.promptDelegate = installation.promptDelegate
            if (native.promptDelegate !== installation.promptDelegate) {
                MiruGeckoSessionBridge.unregisterDocumentListener(native, installation)
                false
            } else {
                installations += WeakReference(installation)
                true
            }
        } catch (_: Exception) {
            MiruGeckoSessionBridge.unregisterDocumentListener(native, installation)
            if (native.promptDelegate === installation.promptDelegate) native.promptDelegate = original
            false
        }
    }

    fun isManaged(request: PromptRequest): Boolean = recordFor(request) != null

    fun documentOwner(request: PromptRequest): NativeUploadDocument? = recordFor(request)?.document

    /** Remains readable while the request is retained by its caller, without retaining it globally. */
    fun wasConfirmed(request: PromptRequest): Boolean = recordFor(request)?.confirmed == true

    internal fun confirmsFolder(selection: PromptRequest.Folder, confirmation: PromptRequest.FolderUploadPrompt): Boolean {
        val parent = recordFor(selection) ?: return false
        return recordFor(confirmation)?.folderParent === parent
    }

    @WorkerThread
    internal fun prepareFolder(request: PromptRequest.Folder, folder: PreparedUploadFolder): PreparedNativeFolder? {
        check(!isNativePromptMainThread()) { "Folder paths must be validated off Main" }
        val record = recordFor(request) ?: return null
        val root = File(record.applicationContext.cacheDir, "miru-folder-staging").canonicalFile
        val validated = folder.validateFor(root.toPath())
        return PreparedNativeFolder(record, validated)
    }

    @MainThread
    internal fun selectPreparedFolder(request: PromptRequest.Folder, prepared: PreparedNativeFolder): Boolean {
        check(isNativePromptMainThread())
        val record = recordFor(request) ?: return false
        if(record.ended || !prepared.belongsTo(record)) return false
        record.preparedFolder = prepared
        try { request.onSelected(record.applicationContext, prepared.uri()) }
        finally { record.preparedFolder = null }
        return record.confirmed
    }

    /** All path resolution and filesystem checks finish on IO before native completion is possible. */
    @WorkerThread
    internal fun prepareFiles(request: PromptRequest.File, files: List<File>): PreparedNativeFiles? {
        check(!isNativePromptMainThread()) { "Upload files must be validated off Main" }
        val record = recordFor(request) ?: return null
        if (files.isEmpty() || (!request.isMultipleFilesSelection && files.size != 1)) return null
        val root = File(record.applicationContext.cacheDir, STAGING_DIRECTORY).canonicalFile
        val prefix = root.path + File.separator
        val selected = ArrayList<Uri>(files.size)
        for (input in files) {
            val file = input.canonicalFile
            if (!file.path.startsWith(prefix) || !file.isFile || !file.canRead()) return null
            selected += Uri.fromFile(file)
        }
        return PreparedNativeFiles(record, selected.toTypedArray())
    }

    /** Preserves the shared AC callback identities without allowing arbitrary file URIs to bypass IO. */
    @MainThread
    internal fun selectPrepared(request: PromptRequest.File, prepared: PreparedNativeFiles): Boolean {
        check(isNativePromptMainThread()) { "Native file prompts must be answered on Main" }
        val record = recordFor(request) ?: return false
        if (record.ended) return false
        if (!prepared.belongsTo(record)) {
            request.onDismiss()
            return false
        }
        record.prepared = prepared
        try {
            val selected = prepared.uris()
            if (request.isMultipleFilesSelection) request.onMultipleFilesSelected(record.applicationContext, selected)
            else request.onSingleFileSelected(record.applicationContext, selected.single())
        } finally {
            record.prepared = null
        }
        return record.confirmed
    }

    private fun recordFor(request: PromptRequest): FileRecord? = synchronized(records) {
        records.removeAll { it.request?.get() == null }
        records.firstOrNull { it.request?.get() === request }
    }

    private fun changed() {
        mutableDocumentChanges.value += 1L
    }

    private class FileRecord(val document: NativeUploadDocument, val applicationContext: Context,
        val folderParent: FileRecord? = null) {
        var request: WeakReference<PromptRequest>? = null
        var ended = false // Main only: claims completion before calling native code or observers.
        @Volatile var confirmed = false
        var prepared: PreparedNativeFiles? = null // Only held during the synchronous selection callback.
        var preparedFolder: PreparedNativeFolder? = null
    }

    private class Installation(
        engineSession: EngineSession,
        nativeSession: GeckoSession,
        private val applicationContext: Context,
        original: GeckoSession.PromptDelegate,
    ) : BundleEventListener {
        val engine = WeakReference(engineSession)
        val native = WeakReference(nativeSession)
        private var documentGeneration = 0L
        private var protocolValid = true
        private var documentLocationSeen = false
        // Native's folder-upload message has no parent ID. Serialize actual root submissions
        // until their genuine second message drains this slot. Cancellation never guesses it away.
        // This tombstone retains no path, source URI, lease or strong request/callback reference.
        private var pendingFolderConfirmation: FileRecord? = null

        val promptDelegate: GeckoSession.PromptDelegate = object : ForwardingGeckoPromptDelegate(original) {
            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                if (prompt.type != GeckoSession.PromptDelegate.FilePrompt.Type.SINGLE &&
                    prompt.type != GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE &&
                    prompt.type != GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER) {
                    return super.onFilePrompt(session, prompt)
                }
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val engineOwner = engine.get()
                if (session !== native.get() || engineOwner == null || !isInstalled() || !documentLocationSeen) {
                    if (!prompt.isComplete) result.complete(prompt.dismiss())
                    return result
                }
                val record = FileRecord(document(), applicationContext)
                val request: PromptRequest = if(prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER) PromptRequest.Folder(
                    onSelected = { _, uri -> finish(record, prompt, result, arrayOf(uri)) },
                    onDismiss = { finish(record, prompt, result, null) },
                ) else PromptRequest.File(
                    mimeTypes = prompt.mimeTypes?.copyOf() ?: emptyArray(),
                    isMultipleFilesSelection = prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE,
                    captureMode = when (prompt.capture) {
                        GeckoSession.PromptDelegate.FilePrompt.Capture.ANY -> PromptRequest.File.FacingMode.ANY
                        GeckoSession.PromptDelegate.FilePrompt.Capture.USER -> PromptRequest.File.FacingMode.FRONT_CAMERA
                        GeckoSession.PromptDelegate.FilePrompt.Capture.ENVIRONMENT -> PromptRequest.File.FacingMode.BACK_CAMERA
                        else -> PromptRequest.File.FacingMode.NONE
                    },
                    onSingleFileSelected = { _, uri ->
                        val selection = if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.SINGLE) arrayOf(uri) else null
                        finish(record, prompt, result, selection)
                    },
                    onMultipleFilesSelected = { _, uris ->
                        val selection = if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) uris else null
                        finish(record, prompt, result, selection)
                    },
                    onDismiss = { finish(record, prompt, result, null) },
                )
                record.request = WeakReference(request)
                synchronized(records) {
                    records.removeAll { it.request?.get() == null }
                    records += record
                }
                prompt.delegate = object : GeckoSession.PromptDelegate.PromptInstanceDelegate {
                    override fun onPromptDismiss(dismissed: GeckoSession.PromptDelegate.BasePrompt) {
                        if (record.ended) return
                        record.ended = true
                        engine.get()?.notifyObservers { onPromptDismissed(request) }
                    }
                }
                // One wrapper and its callback objects are shared by every observer.
                engineOwner.notifyObservers { onPromptRequest(request) }
                return result
            }

            override fun onFolderUploadPrompt(session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FolderUploadPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val owner = engine.get()
                val parent = pendingFolderConfirmation.also { pendingFolderConfirmation = null }
                if(session !== native.get() || owner == null || !isInstalled() || parent == null || !parent.document.isCurrent()) {
                    if(!prompt.isComplete) result.complete(prompt.confirm(AllowOrDeny.DENY))
                    return result
                }
                val record = FileRecord(parent.document, applicationContext, parent)
                fun answer(allow: Boolean) {
                    check(isNativePromptMainThread())
                    if(record.ended || prompt.isComplete) return
                    record.ended = true
                    val accepted = allow && record.document.isCurrent()
                    result.complete(prompt.confirm(if(accepted) AllowOrDeny.ALLOW else AllowOrDeny.DENY))
                    record.confirmed = accepted
                }
                val request = PromptRequest.FolderUploadPrompt(prompt.directoryName.orEmpty(), { answer(true) }, { answer(false) })
                record.request = WeakReference(request)
                synchronized(records) { records.removeAll { it.request?.get() == null }; records += record }
                prompt.delegate = object : GeckoSession.PromptDelegate.PromptInstanceDelegate {
                    override fun onPromptDismiss(dismissed: GeckoSession.PromptDelegate.BasePrompt) {
                        if(record.ended) return
                        record.ended = true
                        engine.get()?.notifyObservers { onPromptDismissed(request) }
                    }
                }
                owner.notifyObservers { onPromptRequest(request) }
                return result
            }
        }

        fun isInstalled(): Boolean = protocolValid && native.get()?.promptDelegate === promptDelegate

        private fun document(): NativeUploadDocument {
            val owner = WeakReference(this)
            val generation = documentGeneration
            return NativeUploadDocument {
                val installation = owner.get()
                installation != null && installation.documentGeneration == generation &&
                    installation.documentLocationSeen && installation.isInstalled() &&
                    installation.native.get()?.isOpen == true
            }
        }

        override fun handleMessage(event: String, message: GeckoBundle, callback: EventCallback?) {
            if (event != MiruGeckoSessionBridge.DOCUMENT_LOCATION_EVENT) return
            val sameDocument = if(message.containsKey("sameDocument")) runCatching { message.getBoolean("sameDocument") }.getOrNull() else null
            if (sameDocument == null) {
                // A mismatched overlay must fail closed, never guess document identity from URLs.
                protocolValid = false
                changed()
            } else {
                documentLocationSeen = true
                if (!sameDocument) {
                    documentGeneration += 1L
                    changed()
                }
            }
            callback?.sendSuccess(null)
        }

        private fun finish(
            record: FileRecord,
            prompt: GeckoSession.PromptDelegate.FilePrompt,
            result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
            selection: Array<Uri>?,
        ) {
            check(isNativePromptMainThread()) { "Native file prompts must be answered on Main" }
            if (record.ended) return
            record.ended = true
            if (prompt.isComplete) return
            try {
                val folderRequest = prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER
                val current = record.document.isCurrent()
                val preparedFolder = if(current && folderRequest) selection?.let { record.preparedFolder?.consume(record, it) } else null
                val prepared = if(current && !folderRequest) selection?.let { record.prepared?.consume(record, it) } else null
                if (prepared == null && preparedFolder == null) {
                    result.complete(prompt.dismiss())
                } else {
                    val response = if(preparedFolder != null) {
                        if(pendingFolderConfirmation != null) {
                            result.complete(prompt.dismiss())
                            return
                        }
                        // Set before either completion can issue the genuine FolderUploadPrompt.
                        // A failed ABI call keeps the same fence: native may already have reentered.
                        pendingFolderConfirmation = record
                        if(preparedFolder.payloads.isEmpty())
                            prompt.confirm(applicationContext, Uri.fromFile(File(preparedFolder.rootPath)))
                        else MiruGeckoSessionBridge.confirmPreparedFolder(prompt, preparedFolder)
                    } else prompt.confirm(applicationContext, checkNotNull(prepared))
                    result.complete(response)
                    record.confirmed = true
                }
            } catch (_: LinkageError) {
                failedCompletion(record, prompt, result)
            } catch (_: Exception) {
                failedCompletion(record, prompt, result)
            }
        }

        private fun failedCompletion(record: FileRecord, prompt: GeckoSession.PromptDelegate.FilePrompt,
            result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>) {
            record.confirmed = false
            try {
                if(!prompt.isComplete) result.complete(prompt.dismiss())
                else result.completeExceptionally(IllegalStateException("File prompt completion failed"))
            } catch(_: LinkageError) {
                // The incompatible ABI cannot be retried; do not expose paths or provider metadata.
            } catch(_: Exception) {
                // The one native completion may already have ended; do not retry or log inputs.
            }
        }

    }
}

private fun isNativePromptMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()
